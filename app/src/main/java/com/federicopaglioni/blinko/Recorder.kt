package com.federicopaglioni.blinko

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Records camera frames to the .rsrec container read by core/tools/rsrec.py and the iOS app:
 *   "RSREC001" | u32 headerLen | header JSON
 *   per frame: "FRME" | f64 timestamp | f32 gyro[3] | f32 accel[3] | u32 rawSize | u32 compSize | u8 codec(0 raw) | bytes
 * Frames are packed BGRA with every `columnStep`-th column kept (rows stay full), exactly the
 * buffer the pipeline already converts for the receiver, so appending is one memcpy into a pooled
 * buffer; the file write happens on a worker thread.
 */
class Recorder(ctx: Context) {
    val directory: File = File(ctx.filesDir, "recordings").apply { mkdirs() }
    @Volatile var isRecording = false; private set
    var file: File? = null; private set
    private var out: FileOutputStream? = null
    private var deadline = 0L
    private var frames = 0; private var dropped = 0; private var bytesWritten = 0L
    private val pool = ArrayList<ByteArray>()
    private val free = ArrayDeque<Int>()
    private val queue = LinkedBlockingQueue<Job>()
    private val lock = Any()
    var latestMotion: () -> FloatArray = { FloatArray(6) }   // gyro xyz, accel xyz
    var onFinished: ((String) -> Unit)? = null

    private class Job(val slot: Int, val raw: Int, val ts: Double, val motion: FloatArray)
    private val stopJob = Job(-1, 0, 0.0, FloatArray(6))

    init {
        Thread({
            while (true) {
                val j = queue.take()
                if (j === stopJob) { try { out?.close() } catch (_: Exception) {}; out = null; onFinished?.invoke(summary); continue }
                write(j)
                synchronized(lock) { free.addLast(j.slot) }
            }
        }, "blinko.recorder").apply { isDaemon = true; start() }
    }

    fun start(seconds: Double, header: JSONObject): File? {
        if (isRecording) return null
        val name = "rec-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".rsrec"
        val f = File(directory, name)
        return try {
            val o = FileOutputStream(f)
            val json = header.toString().toByteArray()
            val head = ByteBuffer.allocate(12 + json.size).order(ByteOrder.LITTLE_ENDIAN)
            head.put("RSREC001".toByteArray()); head.putInt(json.size); head.put(json)
            o.write(head.array())
            out = o; file = f; frames = 0; dropped = 0; bytesWritten = head.capacity().toLong()
            deadline = System.currentTimeMillis() + (seconds * 1000).toLong()
            isRecording = true
            Diag.log("[recorder] started $name for ${seconds}s")
            f
        } catch (e: Exception) { Diag.warn("[recorder] cannot create $name: $e"); null }
    }

    /** Copy one packed BGRA frame (w*h*4 bytes at bgra[0..)) into the queue. Returns false when the recording just ended. */
    fun append(bgra: ByteBuffer, raw: Int, timestamp: Double): Boolean {
        if (!isRecording) return false
        if (System.currentTimeMillis() >= deadline) { finish(); return false }
        val slot: Int
        synchronized(lock) {
            if (pool.isEmpty() || pool[0].size < raw) {
                pool.clear(); free.clear()
                for (i in 0 until POOL) { pool.add(ByteArray(raw)); free.addLast(i) }
            }
            if (free.isEmpty()) { dropped++; return true }
            slot = free.removeLast()
        }
        val src = bgra.duplicate(); src.position(0); src.limit(raw)
        src.get(pool[slot], 0, raw)
        queue.offer(Job(slot, raw, timestamp, latestMotion()))
        return true
    }

    private fun write(j: Job) {
        val o = out ?: return
        val hdr = ByteBuffer.allocate(4 + 8 + 24 + 4 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("FRME".toByteArray()); hdr.putDouble(j.ts)
        for (k in 0 until 6) hdr.putFloat(j.motion.getOrElse(k) { 0f })
        hdr.putInt(j.raw); hdr.putInt(j.raw); hdr.put(0)
        try { o.write(hdr.array()); o.write(pool[j.slot], 0, j.raw); frames++; bytesWritten += hdr.capacity() + j.raw } catch (e: Exception) { Diag.warn("[recorder] write failed: $e") }
    }

    fun finish() {
        if (!isRecording) return
        isRecording = false
        queue.offer(stopJob)
    }

    val summary: String get() {
        val f = file ?: return ""
        return "${f.name}: $frames frames, ${bytesWritten / 1_000_000} MB" + if (dropped > 0) ", $dropped dropped" else ""
    }

    fun files(): List<File> = (directory.listFiles { f -> f.name.endsWith(".rsrec") } ?: emptyArray()).sortedBy { it.name }

    companion object { const val POOL = 24 }
}
