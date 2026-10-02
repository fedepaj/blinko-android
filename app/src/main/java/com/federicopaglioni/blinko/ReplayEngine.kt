package com.federicopaglioni.blinko

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a .rsrec recording through the multi-source receiver, as core/tools/replay.py --multi does.
 * Only raw frames (codec 0) are supported: the iOS app writes raw by default; LZ4 ones are replayed on the Mac.
 * The live pipeline is paused meanwhile because the receiver state is shared: the receiver is reset
 * before the first frame and again on every way out.
 */
class ReplayEngine {
    @Volatile var running = false; private set
    @Volatile private var cancelled = false
    var onMessage: ((slot: Int, level: Int, text: String, source: Int) -> Unit)? = null
    var onProgress: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null

    fun cancel() { cancelled = true }

    /** `prepare` runs on the replay thread before the receiver is touched: the owner waits there for the live frame in flight. */
    fun start(file: File, prepare: () -> Unit = {}) {
        if (running) return
        running = true; cancelled = false
        Thread({
            // The final reset is in `finally`: the early ways out (a compressed recording, a frame size that does not
            // match, an I/O error) used to skip it and hand the live pipeline a receiver holding the replay's state.
            val summary = try { prepare(); RsCore.reset(); run(file) } catch (e: Exception) { "replay failed: $e" } finally { RsCore.reset() }
            finish(summary)
        }, "blinko.replay").start()
    }

    private fun finish(summary: String) { running = false; Diag.log("[replay] $summary"); onDone?.invoke(summary) }

    /** Returns the summary line. */
    private fun run(file: File): String =
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(8); raf.readFully(magic)
            if (String(magic) != "RSREC001") return@use "not an rsrec file"
            val hl = readLE(raf, 4).int
            val hb = ByteArray(hl); raf.readFully(hb)
            val header = JSONObject(String(hb))
            val w = header.getInt("width"); val h = header.getInt("height")
            var frames = 0; var packets = 0; var messages = 0; var t0 = 0.0; var ts = 0.0
            var buf: ByteBuffer? = null
            val tracks = FloatArray(8 * 8); val pk = IntArray(1)
            val tag = ByteArray(4)
            while (!cancelled && raf.filePointer + 45 <= raf.length()) {
                raf.readFully(tag)
                if (String(tag) != "FRME") break
                val hdr = readLE(raf, 8 + 24 + 8 + 1)
                ts = hdr.double; hdr.position(hdr.position() + 24)
                val raw = hdr.int; val comp = hdr.int; val codec = hdr.get().toInt()
                if (codec != 0) return@use "compressed recording (codec $codec): replay it on the computer"
                if (raw != w * h * 4) return@use "frame size mismatch"
                if (buf == null || buf.capacity() < raw) buf = ByteBuffer.allocateDirect(raw)
                val b = buf!!; b.clear()
                val chunk = ByteArray(raw); raf.readFully(chunk); b.put(chunk); b.flip()
                if (frames == 0) t0 = ts
                val tc = RsCore.processFrameBgraMulti(b, w, h, (ts - t0).toFloat(), tracks, pk)
                if (tc > 0) packets += pk[0]
                while (true) {
                    val m = RsCore.pollMessage() ?: break
                    messages++
                    onMessage?.invoke(m.slot, m.level, m.text, m.source)
                }
                frames++
                if (frames % 30 == 0) onProgress?.invoke("replay ${file.name}: $frames frames, $packets packets, $messages messages")
                if (comp != raw) raf.seek(raf.filePointer + (comp - raw))
            }
            val dur = ts - t0
            "${file.name}: $frames frames (${"%.1f".format(dur)} s), $packets packets, $messages messages" + if (cancelled) " (cancelled)" else ""
        }

    private fun readLE(raf: RandomAccessFile, n: Int): ByteBuffer {
        val a = ByteArray(n); raf.readFully(a)
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    }
}
