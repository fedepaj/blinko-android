package com.federicopaglioni.blinko

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Everything the UI and the remote `stats` need from the last frames. */
class Snapshot {
    var fps = 0; var packetsPerSec = 0f
    var stats = FloatArray(15)
    var profile = FloatArray(0)
    var packets = FloatArray(0); var packetCount = 0
    var tracks = FloatArray(0); var trackCount = 0
    var totalPackets = 0; var totalMessages = 0
    var lastPacketAge = 999.0
    var width = 0; var height = 0
    val modeName get() = if (trackCount > 0) (if ((0 until trackCount).any { tracks[it * 8 + 4] >= 2f }) "direct" else if ((0 until trackCount).any { tracks[it * 8 + 4] == 1f }) "RGB" else "mono")
                         else when (stats[RsCore.ST_MODE].toInt()) { 2 -> "direct"; 1 -> "RGB"; else -> "mono" }
}

/** Strobe calibration: band period on the scan axis -> sensor row time. */
class LabResult(val axis: Int, val periodRows: Float, val strength: Float, val rowTimeUs: Double, val readoutMs: Double, val count: Int)

/**
 * Frame -> packed BGRA (columns /4) -> receiver (multi-source or single ROI) -> packets -> messages.
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
    private var lastTrackCount = 0; private var lastTracks = FloatArray(0)
    val snapshot = Snapshot()

    fun reset() { RsCore.reset(); totalPackets = 0; totalMessages = 0; lastTracks = FloatArray(0); lastTrackCount = 0 }

    /** RAW_SENSOR Bayer parameters of the current camera (set by the session). */
    @Volatile var rawCfa = 1; @Volatile var rawBlack = 64; @Volatile var rawWhite = 1023

    fun process(img: Image, tNs: Long) {
        if (paused) return
        val raw = img.format == android.graphics.ImageFormat.RAW_SENSOR
        if (raw && labMode) { processRaw(img, tNs); return }            // strobe calibration on the full-resolution mosaic
        // RAW frames become a half-resolution BGRA image (one pixel per 2x2 Bayer block) and take the
        // same path as YUV: segmentation keeps the lights apart, the multi-source receiver tracks them
        val w = if (raw) img.width / 2 else img.width; val h = if (raw) img.height / 2 else img.height
        val step = maxOf(1, w / 480)                 // 1080p -> /4, 4K -> /8, RAW 2000 blocks -> /4: always ~480 columns
        val ow = w / step
        val need = ow * h * 4
        if (bgra == null || bgra!!.capacity() < need) { bgra = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()); bw = ow; bh = h }
        val buf = bgra!!
        val outW = if (raw) {
            val p = img.planes[0]; RsCore.convertRawToBgra(p.buffer, p.rowStride, img.width, img.height, rawCfa, rawBlack, rawWhite, step, buf)
        } else if (img.format == android.graphics.PixelFormat.RGBA_8888) {
            val p = img.planes[0]; RsCore.convertRgbaToBgra(p.buffer, p.rowStride, p.pixelStride, w, h, step, buf)
        } else {
            val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
            RsCore.convertYuvToBgra(py.buffer, py.rowStride, py.pixelStride, pu.buffer, pu.rowStride, pu.pixelStride, pv.buffer, pv.rowStride, pv.pixelStride, w, h, step, buf)
        }
        if (outW <= 0) return
        bw = outW; bh = h
        val tsAbs = tNs / 1e9
        if (t0 < 0) t0 = tNs
        val t = ((tNs - t0) / 1e9).toFloat()        // small numbers: float keeps ms precision for pilot timing

        frameRequest?.let { fr -> frameRequest = null; val copy = ByteArray(need); val d = buf.duplicate(); d.position(0); d.get(copy, 0, need); fr(copy, bw, bh, tsAbs) }
        if (recorder.isRecording) recorder.append(buf, need, tsAbs)

        var n: Int
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
                lastTrackCount = tc; lastTracks = trackBuf.copyOf(tc * 8)
                RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, null)   // stats/profile for the UI
                n = pktBuf[0]
            } else {
                lastTrackCount = 0; lastTracks = FloatArray(0)
                n = RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, packets)
            }
        } else {
            lastTrackCount = 0; lastTracks = FloatArray(0)
            n = RsCore.processFrameBgra(buf, bw, bh, axis, t, stats, profile, packets)
        }
        val now = System.currentTimeMillis()
        frames++; pktCount += n; totalPackets += n
        if (n > 0) lastPacketMs = now
        if (now - lastFpsT >= 1000) { fps = frames; frames = 0; pps = pktCount * 1000f / max(1L, now - lastFpsT); pktCount = 0; lastFpsT = now }
        var got = false
        while (true) {
            val m = RsCore.pollMessage() ?: break
            val parts = m.split("|")
            totalMessages++; got = true
            onMessage?.invoke(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 7, parts.getOrElse(2) { "" }, parts.getOrNull(3)?.toIntOrNull() ?: 0)
        }
        if (got || n > 0 || now - lastUi > 100) { lastUi = now; publish(n, now, w, h) }
    }

    /** RAW path: profiles straight from the mosaic, single receiver (no segmentation / markers). */
    private fun processRaw(img: Image, tNs: Long) {
        val w = img.width; val h = img.height; val p = img.planes[0]
        if (t0 < 0) t0 = tNs
        val t = ((tNs - t0) / 1e9).toFloat()
        lastTrackCount = 0; lastTracks = FloatArray(0)
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
            if (now - lastUi > 150) { lastUi = now; onLab?.invoke(LabResult(axis, period, strength, rowTime * 1e6, rowTime * count * 1e3, count)); publish(0, now, w, h) }
            return
        }
        frames++; pktCount += n; totalPackets += n
        if (n > 0) lastPacketMs = now
        if (now - lastFpsT >= 1000) { fps = frames; frames = 0; pps = pktCount * 1000f / max(1L, now - lastFpsT); pktCount = 0; lastFpsT = now }
        var got = false
        while (true) {
            val m = RsCore.pollMessage() ?: break
            val parts = m.split("|"); totalMessages++; got = true
            onMessage?.invoke(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 7, parts.getOrElse(2) { "" }, parts.getOrNull(3)?.toIntOrNull() ?: 0)
        }
        if (got || n > 0 || now - lastUi > 100) { lastUi = now; publish(n, now, w, h) }
    }

    private fun publish(n: Int, now: Long, w: Int, h: Int) {
        val s = snapshot
        s.fps = fps; s.packetsPerSec = pps
        System.arraycopy(stats, 0, s.stats, 0, 15)
        s.profile = downsample(profile, stats[RsCore.ST_COUNT].toInt(), 320)
        s.packets = packets.copyOf(); s.packetCount = n
        s.tracks = lastTracks; s.trackCount = lastTrackCount
        s.totalPackets = totalPackets; s.totalMessages = totalMessages
        s.lastPacketAge = if (lastPacketMs > 0) (now - lastPacketMs) / 1000.0 else 999.0
        s.width = w; s.height = h
        onSnapshot?.invoke(s)
    }

    companion object {
        const val STEP = 4          // column step at 1080p (see process: 4K uses 8); profiles are column averages, nothing is lost
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
