package com.federicopaglioni.blinko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the camera is doing right now, for the UI, the recorder header and the remote `get`/`stats`. */
data class CameraInfo(
    var id: String = "", var name: String = "-", var width: Int = 0, var height: Int = 0, var format: String = "YUV",
    var fps: Int = 0, var frameRates: List<Int> = emptyList(),
    var minExposureUs: Double = 0.0, var maxExposureUs: Double = 0.0, var exposureUs: Double = 0.0, var resolutions: List<String> = listOf("1080p"),
    var minIso: Int = 0, var maxIso: Int = 0, var iso: Int = 0,
    var lensPosition: Float = 1f, var lensSupported: Boolean = false, var maxZoom: Double = 1.0, var manual: Boolean = false,
    var cfa: Int = 1, var blackLevel: Int = 64, var whiteLevel: Int = 1023,
    var actualExposureUs: Double = 0.0, var readoutMs: Double = 0.0,   /* from the capture results: real exposure and rolling-shutter skew */
) {
    /** The capture mode the camera really delivers, "1080p" | "4K" | "RAW": the resolution asked for in the settings
     *  falls back to 1080p on a camera without it. The recorder header and the row time go by this one. */
    val mode get() = if (format == "RAW") "RAW" else if (width == 3840 && height == 2160) "4K" else "1080p"
}

/**
 * Owns the Camera2 device: YUV_420_888 (or RGBA_8888 when offered) 1080p at the fastest fixed frame rate
 * of a regular session, manual exposure/ISO/focus when the device allows it, no stabilization or
 * noise reduction. Frames are delivered on the camera thread through `onFrame`.
 *
 * Constrained high-speed sessions (120/240 fps) are not used: they refuse manual exposure and only
 * feed preview/encoder surfaces, so the CPU could not read the frames anyway.
 */
class CameraController(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("blinko.camera", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }   // frames are decoded on this thread
    val handler = Handler(thread.looper)
    /** The capture session's state callbacks run on the camera thread like everything else here. One executor for
     *  every open: a new single-thread executor was made per open and never shut down. */
    private val sessionExecutor = Executor { handler.post(it) }
    /** Camera thread only. Every open and every close moves it on; the callbacks of an open carry the value it started
     *  with and do nothing once it is no longer the current one. Without it a device that finished opening after a quick
     *  pause found the reader gone, and the previous device's late onDisconnected cleared the next one. */
    private var generation = 0
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null
    private var chars: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var minFrameDurationNs = 0L
    var info = CameraInfo(); private set
    var onFrame: ((Image, Long) -> Unit)? = null      // image, sensor timestamp (ns)
    var onInfo: ((CameraInfo) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** (id, label) of every camera, back cameras first, e.g. "Back 0 · 5.4mm · min 58µs · manual". */
    fun cameras(): List<Pair<String, String>> {
        val out = ArrayList<Triple<Int, String, String>>()
        for (id in mgr.cameraIdList) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val minExp = (c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower ?: 0L) / 1000.0
            val manual = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val side = when (facing) { CameraCharacteristics.LENS_FACING_BACK -> "Back"; CameraCharacteristics.LENS_FACING_FRONT -> "Front"; else -> "Ext" }
            val label = String.format(Locale.US, "%s %s · %.1fmm · min %.0fµs%s", side, id, focal, minExp, if (manual) " · manual" else "")
            out.add(Triple(if (facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1, id, label))
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.toIntOrNull() ?: 999 })).map { it.second to it.third }
    }

    fun defaultCameraId(): String = cameras().firstOrNull()?.first ?: mgr.cameraIdList.first()

    fun isOpen() = camera != null

    /** Open `settings.camera` (or the default back camera) and start streaming to the reader and the preview texture. */
    @SuppressLint("MissingPermission")
    fun open(settings: Settings, preview: SurfaceTexture?) {
        handler.post { openSync(settings, preview) }
    }

    private fun openSync(s: Settings, preview: SurfaceTexture?) {
        closeSync()
        val gen = generation
        // One try around the whole setup: a camera list that cannot be read, a camera without a stream map or without
        // the size asked for used to throw on the camera thread; all of it is reported through onError.
        try {
            val ids = mgr.cameraIdList
            val id = if (s.camera.isNotEmpty() && ids.contains(s.camera)) s.camera else defaultCameraId()
            val ch = mgr.getCameraCharacteristics(id); chars = ch
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val raw = s.resolution == "RAW" && rawSizes != null && rawSizes.isNotEmpty()
            val rgba = !raw && map.isOutputSupportedFor(PixelFormat.RGBA_8888) && (map.getOutputSizes(PixelFormat.RGBA_8888)?.any { it.width == 1920 && it.height == 1080 } == true)
            val format = if (raw) ImageFormat.RAW_SENSOR else if (rgba) PixelFormat.RGBA_8888 else ImageFormat.YUV_420_888
            val sizes = map.getOutputSizes(format)
            val want = if (s.resolution == "4K") Pair(3840, 2160) else Pair(1920, 1080)
            val size: Size = if (raw) sizes.maxByOrNull { it.width * it.height }!! else sizes.firstOrNull { it.width == want.first && it.height == want.second }
                ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }!!
            minFrameDurationNs = try { map.getOutputMinFrameDuration(format, size) } catch (e: Exception) { 0L }
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 30_000_000L)
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 100)
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
            val streamMax = if (minFrameDurationNs > 0) (1e9 / minFrameDurationNs).toInt() else 30
            val rates = (ranges.map { it.upper } + streamMax).filter { it <= 240 }.distinct().sorted()
            val fps = if (s.fps > 0 && rates.contains(s.fps)) s.fps else rates.last()
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val maxZoom = (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).toDouble().coerceAtMost(4.0)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            info = CameraInfo(id = id, name = String.format(Locale.US, "cam %s %.1fmm", id, focal), width = size.width, height = size.height,
                format = if (raw) "RAW" else if (rgba) "RGBA" else "YUV", fps = fps, frameRates = rates,
                resolutions = listOf("1080p") + (if (map.getOutputSizes(ImageFormat.YUV_420_888)?.any { it.width == 3840 && it.height == 2160 } == true) listOf("4K") else emptyList())
                            + (if (rawSizes != null && rawSizes.isNotEmpty()) listOf("RAW") else emptyList()),
                cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 1,
                blackLevel = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) ?: 64,
                whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023,
                minExposureUs = expRange.lower / 1000.0, maxExposureUs = min(expRange.upper / 1000.0, 4000.0),
                minIso = isoRange.lower, maxIso = isoRange.upper, lensSupported = minFocus > 0f, maxZoom = maxZoom, manual = manual)

            val rd = ImageReader.newInstance(size.width, size.height, format, if (raw) 3 else 4)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try { onFrame?.invoke(img, img.timestamp) } finally { img.close() }
            }, handler)
            preview?.setDefaultBufferSize(size.width, size.height)
            previewSurface = preview?.let { Surface(it) }
            Diag.log("[camera] opening $id ${size.width}x${size.height} ${info.format} fps $fps manual=$manual minExp=${info.minExposureUs}us")

            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    if (gen != generation) { dev.close(); return }      // closed or reopened while it was opening: nobody else holds this device
                    camera = dev
                    try {
                        val outputs = ArrayList<OutputConfiguration>()
                        previewSurface?.let { outputs.add(OutputConfiguration(it)) }
                        outputs.add(OutputConfiguration(rd.surface))
                        dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, sessionExecutor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(sess: CameraCaptureSession) {
                                    if (gen != generation) return
                                    session = sess
                                    try {
                                        request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            previewSurface?.let { addTarget(it) }
                                            addTarget(rd.surface)
                                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                                            set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                        }
                                        applySync(s); repeat()
                                    } catch (e: Exception) { sessionFailed(e) }
                                }
                                override fun onConfigureFailed(sess: CameraCaptureSession) { if (gen == generation) onError?.invoke("Camera session failed") }
                            }))
                    } catch (e: Exception) { sessionFailed(e) }           // e.g. the preview surface was released meanwhile
                }
                override fun onDisconnected(dev: CameraDevice) { dev.close(); if (gen == generation) camera = null }
                override fun onError(dev: CameraDevice, error: Int) { dev.close(); if (gen == generation) { camera = null; onError?.invoke("Camera error $error") } }
            }, handler)
        } catch (e: Exception) {   // e.g. CameraAccessException: opened from the background, or in use by another app
            Diag.warn("[camera] open failed: $e"); closeSync(); onError?.invoke("Camera unavailable: ${e.message?.take(60)}")
        }
    }

    private fun sessionFailed(e: Exception) { Diag.warn("[camera] session failed: $e"); onError?.invoke("Camera session failed: ${e.message?.take(60)}") }

    /** Push exposure / ISO / focus / zoom / frame rate from `settings` into the running request. */
    fun apply(settings: Settings) { handler.post { applySync(settings); repeat() } }

    private fun applySync(s: Settings) {
        val req = request ?: return
        val ch = chars ?: return
        val i = info
        val rates = i.frameRates
        val fps = if (s.fps > 0 && rates.contains(s.fps)) s.fps else (rates.lastOrNull() ?: 30)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(fps, fps))
        val range = ranges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower } ?: ranges.maxByOrNull { it.upper }!!
        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        val frameNs = max(1_000_000_000L / fps, minFrameDurationNs)
        // exposure: log scale between the sensor minimum and 1/250 s; ISO linear in the sensor range
        val minE = i.minExposureUs; val maxE = max(i.maxExposureUs, minE * 1.001)
        val expUs = minE * (maxE / minE).pow(s.exposure.coerceIn(0.0, 1.0))
        val iso = (i.minIso + s.iso.coerceIn(0.0, 1.0) * (i.maxIso - i.minIso)).toInt()
        if (i.manual) {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (expUs * 1000).toLong().coerceAtMost(frameNs))
            req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            req.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0)
        }
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocus > 0f) req.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - s.lensPosition.coerceIn(0f, 1f)) * minFocus)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (active != null) {
            val z = s.zoom.coerceIn(1.0, i.maxZoom)
            val w = (active.width() / z).toInt(); val h = (active.height() / z).toInt()
            val x = active.left + (active.width() - w) / 2; val y = active.top + (active.height() - h) / 2
            req.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + w, y + h))
        }
        info = i.copy(fps = fps, exposureUs = if (i.manual) expUs else 0.0, iso = if (i.manual) iso else 0, lensPosition = s.lensPosition)
        onInfo?.invoke(info)
    }

    private var resultCount = 0
    private val resultCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            if (++resultCount % 30 != 1) return                       // once a second is plenty
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME); val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
            val e = (exp ?: 0L) / 1000.0; val r = (skew ?: 0L) / 1e6
            if (e != info.actualExposureUs || r != info.readoutMs) { info = info.copy(actualExposureUs = e, readoutMs = r); onInfo?.invoke(info) }
        }
    }

    private fun repeat() {
        val sess = session ?: return; val req = request ?: return
        try { sess.setRepeatingRequest(req.build(), resultCb, handler) } catch (e: Exception) { Diag.warn("[camera] repeating request failed: $e") }
    }

    fun close() { handler.post { closeSync() } }

    /** Close the camera and end the camera thread: the controller cannot be used afterwards. */
    fun release() {
        handler.post { closeSync() }
        // the thread stays a little longer: a device that was still opening reports to it, and that callback is what closes the device
        handler.postDelayed({ thread.quitSafely() }, 2000)
    }

    private fun closeSync() {
        generation++
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        request = null
    }

    companion object {
        /** Exposure fraction (0..1 log scale) for a given duration in microseconds, the inverse of applySync. */
        fun exposureFraction(info: CameraInfo, us: Double): Double {
            val minE = info.minExposureUs; val maxE = max(info.maxExposureUs, minE * 1.001)
            if (minE <= 0) return 0.0
            return (ln(us.coerceAtLeast(minE) / minE) / ln(maxE / minE)).coerceIn(0.0, 1.0)
        }
    }
}
