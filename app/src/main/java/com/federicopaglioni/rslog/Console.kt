package com.federicopaglioni.rslog

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A decoded message: slot, level, text, logical source (group id) and, when announced, board id. */
data class LogMsg(val time: Long, val slot: Int, val level: Int, val text: String, val source: Int) {
    val levelName get() = RsCore.levelNames[level.coerceIn(0, 7)]
}

/** Message history (newest first, persisted to files/history.json), board ids and the source filter. */
class Console(private val ctx: Context) {
    val messages = ArrayList<LogMsg>()
    val boardIds = HashMap<Int, String>()
    var filter = 0                         // 0 = every source
    private val file = File(ctx.filesDir, "history.json")
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private val saver = Runnable { if (dirty) { dirty = false; save() } }
    var onChanged: (() -> Unit)? = null

    init { load() }

    fun add(m: LogMsg) {
        messages.add(0, m)
        if (messages.size > MAX) messages.removeAt(messages.size - 1)
        val i = m.text.indexOf("id=")
        if (m.source > 0 && i >= 0 && m.text.length >= i + 7) {
            val hex = m.text.substring(i + 3, i + 7)
            if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) boardIds[m.source] = hex
        }
        markDirty(); onChanged?.invoke()
    }

    fun remove(m: LogMsg) { messages.remove(m); markDirty(); onChanged?.invoke() }
    fun clear() { messages.clear(); boardIds.clear(); markDirty(); onChanged?.invoke() }

    fun filtered(): List<LogMsg> = if (filter == 0) messages.toList() else messages.filter { it.source == filter }

    /** Sources seen, with board id and message count: (id, label). */
    fun sources(): List<Pair<Int, String>> {
        val counts = HashMap<Int, Int>()
        for (m in messages) if (m.source > 0) counts[m.source] = (counts[m.source] ?: 0) + 1
        return counts.keys.sorted().map { id -> id to "#$id" + (boardIds[id]?.let { " · board $it" } ?: "") + "  (${counts[id]} msgs)" }
    }

    fun label(id: Int) = if (id == 0) "All sources" else "#$id" + (boardIds[id]?.let { " · $it" } ?: "")

    private fun markDirty() { dirty = true; handler.removeCallbacks(saver); handler.postDelayed(saver, 1000) }

    private fun save() {
        val arr = JSONArray()
        for (m in messages) arr.put(JSONObject().put("t", m.time).put("slot", m.slot).put("level", m.level).put("text", m.text).put("src", m.source))
        val root = JSONObject().put("messages", arr).put("boards", JSONObject(boardIds.mapKeys { it.key.toString() }))
        Thread { try { file.writeText(root.toString()) } catch (_: Exception) {} }.start()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("messages")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(LogMsg(o.getLong("t"), o.getInt("slot"), o.getInt("level"), o.getString("text"), o.optInt("src", 0)))
            }
            val b = root.optJSONObject("boards")
            if (b != null) for (k in b.keys()) boardIds[k.toInt()] = b.getString(k)
        } catch (_: Exception) {}
    }

    companion object { const val MAX = 2000 }
}
