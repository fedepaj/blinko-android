package com.federicopaglioni.blinko

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Everything the UI and the remote `stats` need from the last frames. Immutable: the camera thread
 * publishes a new one per update and the main thread works on the one it was handed, so its fields
 * belong to the same frame. (One object updated in place let the main thread read a new track count
 * with the previous track array.) The arrays are the snapshot's own copies and are never written again.
 */
class Snapshot(
    val fps: Int = 0, val packetsPerSec: Float = 0f,
    val stats: FloatArray = FloatArray(15),
    val profile: FloatArray = FloatArray(0),
    val packets: FloatArray = FloatArray(0), val packetCount: Int = 0,
    val tracks: FloatArray = FloatArray(0),          // (id, x, y, radius, mode, packets, messages, group) per track
    val totalPackets: Int = 0, val totalMessages: Int = 0,
    val lastPacketAge: Double = 999.0,
    val width: Int = 0, val height: Int = 0,
) {
    val trackCount get() = tracks.size / 8
    val modeName get() = if (trackCount > 0) (if ((0 until trackCount).any { tracks[it * 8 + 4] >= 2f }) "direct" else if ((0 until trackCount).any { tracks[it * 8 + 4] == 1f }) "RGB" else "mono")
                         else when (stats[RsCore.ST_MODE].toInt()) { 2 -> "direct"; 1 -> "RGB"; else -> "mono" }
}

/**
 * Strobe calibration: band period on the scan axis -> row time of the rows it was measured on.
 * `rowBin` is how many of those rows make one row of the image the receiver decodes: 1, except in
 * RAW, where the calibration reads the sensor rows and the receiver a reduced image.
 */
class LabResult(val axis: Int, val periodRows: Float, val strength: Float, val rowTimeUs: Double, val readoutMs: Double, val count: Int, val rowBin: Int = 1)

/**
 * Frame -> packed BGRA (about 480 columns) -> receiver (multi-source or single ROI) -> packets -> messages.
 * Runs on the camera thread. The BGRA buffer is also what the recorder stores and the remote
 * `frame` command returns, so a frame is converted once.
 */
class Pipeline(val recorder: Recorder) {
    var axis = 0
    var multiSource = true
    var labMode = false
    var strobeHz = 2000.0
    @Volatile var paused = false          // replay running: live frames are skipped
    var onSnapshot: ((Snapshot) -> Unit)? = null
    var onMessage: ((slot: Int, level: Int, text: String, source: Int) -> Unit)? = null
    var onLab: ((LabResult) -> Unit)? = null
    /** One-shot: the next converted frame (BGRA copy, w, h, time) is handed here, then cleared. */
    @Volatile var frameRequest: ((ByteArray, Int, Int, Double) -> Unit)? = null

    private var bgra: ByteBuffer? = null
    private var bw = 0; private var bh = 0
    private val stats = FloatArray(15)
    private var tConvMs = 0.0; private var tDecMs = 0.0   // per-second timing, logged
    private val profile = FloatArray(4096)
    private val packets = FloatArray(96 * 4)
    private val trackBuf = FloatArray(8 * 8)
    private val pktBuf = IntArray(1)
    private var t0 = -1L
    private var frames = 0; private var lastFpsT = 0L; private var fps = 0
    private var pktCount = 0; private var pps = 0f
    private var lastUi = 0L
    private var lastPacketMs = 0L
    private var totalPackets = 0; private var totalMessages = 0
    @Volatile private var lastTracks = FloatArray(0)   // replaced whole, never written in place: a snapshot keeps the one it was built with
    /** The last published snapshot. */
    @Volatile var snapshot = Snapshot(); private set
    /** Held while a live frame is processed (see awaitIdle). */
    private val frameLock = Any()

    fun reset() { RsCore.reset(); totalPackets = 0; totalMessages = 0; lastTracks = FloatArray(0) }

    /** RAW_SENSOR Bayer parameters of the current camera (set by the session). */
    @Volatile var rawCfa = 1; @Volatile var rawBlack = 64; @Volatile var rawWhite = 1023

    fun process(img: Image, tNs: Long) {
        if (paused) return
        synchronized(frameLock) { if (!paused) processFrame(img, tNs) }
    }

    /**
     * Returns when no live frame is being processed. Called with `paused` already set, so none starts
     * afterwards: the replay thread waits here before it takes the receiver, otherwise the frame in
     * flight would decode into the replay's receiver state and deliver the replay's first messages as live ones.
     */
    fun awaitIdle() { synchronized(frameLock) { } }

    private fun processFrame(img: Image, tNs: Long) {
        val raw = img.format == android.graphics.ImageFormat.RAW_SENSOR
        if (raw && labMode) { processRaw(img, tNs); return }            // strobe calibration on the full-resolution mosaic
        // RAW frames become a reduced BGRA image (one pixel from a 2x2 Bayer block per RAW_ROW_BIN sensor rows,
        // nominally 4x4) and take the same path as YUV: segmentation keeps the lights apart, the multi-source receiver tracks them
        val w = if (raw) img.width / 2 else img.width; val h = if (raw) img.height / RAW_ROW_BIN else img.height
        val step = maxOf(1, w / 480)                 // 1080p -> /4, 4K -> /8, RAW 2000 blocks -> /4: always ~480 columns
        val ow = w / step
        val need = ow * h * 4
        if (bgra == null || bgra!!.capacity() < need) { bgra = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()); bw = ow; bh = h }
        val buf = bgra!!
        val tConv0 = System.nanoTime()
        val outW = if (raw) {
            val p = img.planes[0]; RsCore.convertRawToBgra(p.buffer, p.rowStride, img.width, img.height, rawCfa, rawBlack, rawWhite, step, RAW_ROW_BIN, buf)
        } else if (img.format == android.graphics.PixelFormat.RGBA_8888) {
            val p = img.planes[0]; RsCore.convertRgbaToBgra(p.buffer, p.rowStride, p.pixelStride, w, h, step, buf)
        } else {
            val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
            RsCore.convertYuvToBgra(py.buffer, py.rowStride, py.pixelStride, pu.buffer, pu.rowStride, pu.pixelStride, pv.buffer, pv.rowStride, pv.pixelStride, w, h, step, buf)
        }
        if (outW <= 0) return
        bw = outW; bh = h
        tConvMs += (System.nanoTime() - tConv0) / 1e6
        val tsAbs = tNs / 1e9
        if (t0 < 0) t0 = tNs
        val t = ((tNs - t0) / 1e9).toFloat()        // small numbers: float keeps ms precision for pilot timing

        frameRequest?.let { fr -> frameRequest = null; val copy = ByteArray(need); val d = buf.duplicate(); d.position(0); d.get(copy, 0, need); fr(copy, bw, bh, tsAbs) }
        if (recorder.isRecording) recorder.append(buf, need, tsAbs)

        var n: Int
        val tDec0 = System.nanoTime()
        if (labMode) {
            // strobe calibration: full-resolution luma profile, no decoding side effects worth keeping
            RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, null)
            val count = stats[RsCore.ST_COUNT].toInt()
            val (period, strength) = period(profile, count)
            val rowTime = if (period > 0) 1.0 / (strobeHz * period) else 0.0
            val now = System.currentTimeMillis()
            if (now - lastUi > 150) {
                lastUi = now
                onLab?.invoke(LabResult(axis, period, strength, rowTime * 1e6, rowTime * count * 1e3, count))
                publish(0, now, w, h)
            }
            return
        }
        if (multiSource) {
            val tc = RsCore.processFrameBgraMulti(buf, bw, bh, t, trackBuf, pktBuf)
            if (tc > 0) {
                lastTracks = trackBuf.copyOf(minOf(tc * 8, trackBuf.size))
                // stats/profile for the UI, one frame in three. Only the profile is computed: the tracks' receivers own the
                // decoding (a single-ROI decode of the same frame here assembled every message a second time, without a source)
                if (frames % 3 == 0) RsCore.profileBgra(buf, bw, bh, axis, stats, profile)
                n = pktBuf[0]
            } else {
                lastTracks = FloatArray(0)
                n = RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, packets)
            }
        } else {
            lastTracks = FloatArray(0)
            n = RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, packets)
        }
        tDecMs += (System.nanoTime() - tDec0) / 1e6
        val now = System.currentTimeMillis()
        frames++; pktCount += n; totalPackets += n
        if (n > 0) lastPacketMs = now
        if (now - lastFpsT >= 1000) {
            Diag.log("[timing] ${frames} frames: convert ${"%.1f".format(tConvMs / frames)} ms, decode ${"%.1f".format(tDecMs / frames)} ms per frame (${bw}x${bh}, multi=$multiSource)")
            tConvMs = 0.0; tDecMs = 0.0
            fps = frames; frames = 0; pps = pktCount * 1000f / max(1L, now - lastFpsT); pktCount = 0; lastFpsT = now
        }
        val got = deliverMessages()
        if (got || n > 0 || now - lastUi > 100) { lastUi = now; publish(n, now, w, h) }
    }

    /** Hand every complete message to `onMessage`; true when there was at least one. */
    private fun deliverMessages(): Boolean {
        var got = false
        while (true) {
            val m = RsCore.pollMessage() ?: break
            totalMessages++; got = true
            onMessage?.invoke(m.slot, m.level, m.text, m.source)
        }
        return got
    }

    /** RAW path: profiles straight from the mosaic, single receiver (no segmentation / markers). */
    private fun processRaw(img: Image, tNs: Long) {
        val w = img.width; val h = img.height; val p = img.planes[0]
        if (t0 < 0) t0 = tNs
        val t = ((tNs - t0) / 1e9).toFloat()
        lastTracks = FloatArray(0)
        val n = RsCore.processFrameRaw(p.buffer, p.rowStride, w, h, rawCfa, rawBlack, rawWhite, t, stats, profile, if (labMode) null else packets)
        frameRequest?.let { fr ->
            frameRequest = null
            val buf = FloatArray(3 * h); val m = RsCore.lastProfiles(buf)
            val bytes = java.nio.ByteBuffer.allocate(4 * 3 * m).order(java.nio.ByteOrder.LITTLE_ENDIAN); for (i in 0 until 3 * m) bytes.putFloat(buf[i])
            fr(bytes.array(), 3, m, tNs / 1e9)              // "w" = 3 profiles, "h" = rows: the session labels it PROFILES
        }
        val now = System.currentTimeMillis()
        if (labMode) {
            val count = stats[RsCore.ST_COUNT].toInt()
            val (period, strength) = period(profile, count)
            val rowTime = if (period > 0) 1.0 / (strobeHz * period) else 0.0
            // measured on sensor rows; the receiver decodes RAW frames from an image with one row per RAW_ROW_BIN of them
            if (now - lastUi > 150) { lastUi = now; onLab?.invoke(LabResult(axis, period, strength, rowTime * 1e6, rowTime * count * 1e3, count, RAW_ROW_BIN)); publish(0, now, w, h) }
            return
        }
        frames++; pktCount += n; totalPackets += n
        if (n > 0) lastPacketMs = now
        if (now - lastFpsT >= 1000) { fps = frames; frames = 0; pps = pktCount * 1000f / max(1L, now - lastFpsT); pktCount = 0; lastFpsT = now }
        val got = deliverMessages()
        if (got || n > 0 || now - lastUi > 100) { lastUi = now; publish(n, now, w, h) }
    }

    private fun publish(n: Int, now: Long, w: Int, h: Int) {
        val s = Snapshot(fps, pps, stats.copyOf(), downsample(profile, stats[RsCore.ST_COUNT].toInt(), 320), packets.copyOf(), n,
            lastTracks, totalPackets, totalMessages, if (lastPacketMs > 0) (now - lastPacketMs) / 1000.0 else 999.0, w, h)
        snapshot = s
        onSnapshot?.invoke(s)
    }

    companion object {
        const val STEP = 4          // column step at 1080p (see process: 4K uses 8); profiles are column averages, nothing is lost
        /** Sensor rows per row of the BGRA image a RAW frame is reduced to (RsCore.convertRawToBgra): the one place that
         *  sets it. The recorder header and the RAW row time (strobe calibration x this) follow from it. */
        const val RAW_ROW_BIN = 4
        fun stepFor(width: Int) = maxOf(1, width / 480)

        fun downsample(p: FloatArray, n: Int, m: Int): FloatArray {
            if (n <= 0) return FloatArray(0)
            if (n <= m) return p.copyOf(n)
            val o = FloatArray(m)
            for (i in 0 until m) {
                val a = i * n / m; val b = max(a + 1, (i + 1) * n / m)
                var mx = -1f
                for (j in a until b) if (p[j] > mx) mx = p[j]
                o[i] = mx
            }
            return o
        }

        /** Dominant period (in samples) of a profile via normalized autocorrelation: (period, peak strength). */
        fun period(p: FloatArray, n: Int): Pair<Float, Float> {
            if (n <= 32) return 0f to 0f
            val w = max(8, n / 8)
            val x = FloatArray(n); var acc = 0f
            for (i in 0 until n) { acc += p[i]; if (i >= w) acc -= p[i - w]; x[i] = p[i] - acc / minOf(i + 1, w) }
            var e = 0f; for (i in 0 until n) e += x[i] * x[i]
            if (e <= 1e-3f) return 0f to 0f
            val maxLag = n / 3
            val r = FloatArray(maxLag + 1)
            for (lag in 1..maxLag) { var s = 0f; for (i in 0 until n - lag) s += x[i] * x[i + lag]; r[lag] = s / e }
            var lag = 1
            while (lag < maxLag && r[lag] > 0) lag++
            var best = 0f; var bestLag = 0
            while (lag < maxLag - 1) {
                if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0.05f) { best = r[lag]; bestLag = lag; break }
                lag++
            }
            if (bestLag <= 1) return 0f to 0f
            val y0 = r[bestLag - 1]; val y1 = r[bestLag]; val y2 = r[bestLag + 1]
            val denom = y0 - 2 * y1 + y2
            val delta = if (denom != 0f) 0.5f * (y0 - y2) / denom else 0f
            return (bestLag + delta) to best
        }
    }
}
