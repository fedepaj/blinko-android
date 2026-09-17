package com.federicopaglioni.rslog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Minimal RSLog viewer: back camera, manual exposure at the sensor minimum, focus at
 * infinity (near LED defocused), YUV 1080p at the highest frame rate, luma plane -> C core.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var preview: TextureView
    private lateinit var statsView: TextView
    private lateinit var lastView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var profileView: ProfileView
    private lateinit var markers: MarkerView

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("rslog.camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val stats = FloatArray(14)
    private var reqBuilder: CaptureRequest.Builder? = null
    private var isoNow = 0; private var isoMin = 0; private var isoMax = 0; private var lastIsoT = 0L
    private val profile = FloatArray(320)
    private val packets = FloatArray(96 * 4)
    private var frames = 0; private var lastFpsT = 0L; private var fps = 0
    private var pktCount = 0; private var lastPktT = 0L; private var pps = 0f
    private var lastUi = 0L
    private var cameraInfo = ""
    @Volatile private var axis = 0   // 0 rows, 1 columns (tap the profile to toggle)
    private var useRgba = false
    private val trackBuf = FloatArray(8 * 4)
    private val pktBuf = IntArray(1)
    @Volatile private var lastTracks = FloatArray(0)
    private var lastTrackCount = 0
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = findViewById(R.id.preview); statsView = findViewById(R.id.stats)
        lastView = findViewById(R.id.last); logView = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll); profileView = findViewById(R.id.profile); markers = findViewById(R.id.markers)
        lastView.text = "Point the camera at the LED, 1–3 cm away"
        profileView.setOnClickListener { axis = 1 - axis; RsCore.reset(); lastView.text = "scan axis: " + if (axis == 0) "rows" else "columns" }
        profileView.setOnLongClickListener { RsCore.reset(); logView.text = ""; lastView.text = "reset"; true }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else lastView.text = "Camera permission denied"
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startWhenReady()
    }

    override fun onPause() {
        super.onPause()
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
    }

    private fun startWhenReady() {
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun openCamera() {
        if (camera != null) return
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: mgr.cameraIdList.first()
        val ch = mgr.getCameraCharacteristics(id)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // Prefer RGBA_8888 (full-resolution colour for the RGB channels); YUV_420_888 otherwise.
        useRgba = map.isOutputSupportedFor(PixelFormat.RGBA_8888) && (map.getOutputSizes(PixelFormat.RGBA_8888)?.any { it.width == 1920 && it.height == 1080 } == true)
        val format = if (useRgba) PixelFormat.RGBA_8888 else ImageFormat.YUV_420_888
        val sizes = map.getOutputSizes(format)
        val size = sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }!!
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
        val fpsRange = fpsRanges.filter { it.lower == it.upper && it.upper <= 120 }.maxByOrNull { it.upper } ?: fpsRanges.maxByOrNull { it.upper }!!
        val minExp = expRange?.lower ?: 100_000L
        val iso = isoRange?.let { (it.lower * 2).coerceAtMost(it.upper) } ?: 100
        isoNow = iso; isoMin = isoRange?.lower ?: iso; isoMax = isoRange?.upper ?: iso
        cameraInfo = "cam $id ${size.width}x${size.height} ${if (useRgba) "RGBA" else "YUV"} fps ${fpsRange.upper} manual=$manual minExp=${minExp / 1000.0}us iso=$iso"

        reader = ImageReader.newInstance(size.width, size.height, format, 4).also { r ->
            r.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.let { img -> try { process(img) } finally { img.close() } } }, handler)
        }
        preview.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        val previewSurface = Surface(preview.surfaceTexture)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                camera = dev
                val outputs = listOf(OutputConfiguration(previewSurface), OutputConfiguration(reader!!.surface))
                val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface); addTarget(reader!!.surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                                if (manual) {
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, minExp)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                    set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fpsRange.upper)
                                } else {
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0)
                                }
                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)   // infinity: a LED 1-3 cm away is a big blob
                            }
                            reqBuilder = req
                            s.setRepeatingRequest(req.build(), null, handler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { lastView.text = "Camera session failed" } }
                    })
                dev.createCaptureSession(cfg)
            }
            override fun onDisconnected(dev: CameraDevice) { dev.close(); camera = null }
            override fun onError(dev: CameraDevice, error: Int) { dev.close(); camera = null; runOnUiThread { lastView.text = "Camera error $error" } }
        }, handler)
    }

    private fun process(img: android.media.Image) {
        val t = (System.nanoTime() / 1e9).toFloat()
        val n = if (useRgba) {
            val p = img.planes[0]
            // multi-source first (every light gets its own receiver); single-ROI path when no light is segmented
            val tc = RsCore.processFrameRgbaMulti(p.buffer, p.rowStride, p.pixelStride, img.width, img.height, t, trackBuf, pktBuf)
            if (tc > 0) {
                lastTrackCount = tc; lastTracks = trackBuf.copyOf(tc * 8)
                RsCore.processFrameRgba(p.buffer, p.rowStride, p.pixelStride, img.width, img.height, axis, t, stats, profile, null)   // stats/profile for the UI
                pktBuf[0]
            } else {
                lastTrackCount = 0
                RsCore.processFrameRgba(p.buffer, p.rowStride, p.pixelStride, img.width, img.height, axis, t, stats, profile, packets)
            }
        } else {
            val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
            RsCore.processFrame(py.buffer, py.rowStride, py.pixelStride, pu.buffer, pu.rowStride, pu.pixelStride, pv.buffer, pv.rowStride, pv.pixelStride,
                img.width, img.height, axis, t, stats, profile, packets)
        }
        val now = System.currentTimeMillis()
        frames++; pktCount += n
        if (now - lastFpsT >= 1000) { fps = frames; frames = 0; pps = pktCount * 1000f / (now - lastFpsT).coerceAtLeast(1); pktCount = 0; lastFpsT = now }
        val messages = ArrayList<String>()
        while (true) { val m = RsCore.pollMessage() ?: break; messages.add(m) }
        if (messages.isNotEmpty() || now - lastUi > 100 || n > 0) {
            lastUi = now
            val prof = profile.copyOf(); val pk = packets.copyOf(); val st = stats.copyOf()
            runOnUiThread {
                profileView.profile = prof; profileView.packets = pk; profileView.packetCount = n; profileView.invalidate()
                statsView.text = String.format(Locale.US, "%s\nfps %d  pkt/s %.1f  rows/chip %.1f  contrast %.0f  syncs %.0f  crcfail %.0f  roi %.0f-%.0f  msgs %.0f  axis %s\nmode %s  pilots %.0f  cond %.2f  peak %.0f  iso %d",
                    cameraInfo, fps, pps, st[3], st[2], st[0], st[1], st[4], st[5], st[7], if (axis == 0) "rows" else "cols",
                    if (st[8] > 0.5f) "RGB" else "luma", st[9], st[10], st[12], isoNow)
                markers.tracks = lastTracks; markers.count = lastTrackCount; markers.invalidate()
                for (m in messages) {
                    val parts = m.split("|")
                    val level = parts[1].toIntOrNull() ?: 7
                    val src = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    val line = "${fmt.format(Date())} [${RsCore.levelNames[level.coerceIn(0, 7)]}]" + (if (src > 0) " src#$src" else "") + " slot${parts[0]} ${parts[2]}"
                    lastView.text = line
                    logView.append(line + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    vib.vibrate(VibrationEffect.createOneShot(if (level == 6 || level == 4) 300 else 40, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
    }
}
