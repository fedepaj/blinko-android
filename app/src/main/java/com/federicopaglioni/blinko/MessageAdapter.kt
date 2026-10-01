package com.federicopaglioni.blinko

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Console rows like iOS: "LEVEL  src #1 e844  replay  slot 6          12:34:56" and the text, newest first. */
class MessageAdapter(private val console: Console) : RecyclerView.Adapter<MessageAdapter.Row>() {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    var items: List<LogMsg> = console.filtered()

    class Row(val root: LinearLayout, val level: TextView, val src: TextView, val tag: TextView, val slot: TextView, val time: TextView, val body: TextView) : RecyclerView.ViewHolder(root)

    fun refresh() { items = console.filtered(); notifyDataSetChanged() }

    private fun caption(ctx: android.content.Context, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = 11f; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 14, 0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val level = caption(ctx, Color.WHITE, true); val src = caption(ctx, Color.LTGRAY, true); val tag = caption(ctx, Color.rgb(190, 120, 255))
        val slot = caption(ctx, Color.GRAY); val time = caption(ctx, Color.GRAY).apply { gravity = Gravity.END; setPadding(0, 0, 0, 0) }
        head.addView(level); head.addView(src); head.addView(tag); head.addView(slot)
        head.addView(time, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val body = TextView(ctx).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE); setPadding(0, 4, 0, 0) }
        root.addView(head); root.addView(body)
        val divider = android.view.View(ctx).apply { setBackgroundColor(Color.rgb(34, 34, 34)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = (8 * d).toInt() } }
        root.addView(divider)
        return Row(root, level, src, tag, slot, time, body)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Row, position: Int) {
        val m = items[position]
        h.level.text = m.levelName; h.level.setTextColor(levelColor(m.level))
        val replay = m.text.startsWith("replay: ")
        h.src.text = if (m.source > 0) "src #${m.source}" + (console.boardIds[m.source]?.let { " $it" } ?: "") else ""
        h.src.setTextColor(MarkerView.color(m.source)); h.src.visibility = if (m.source > 0) android.view.View.VISIBLE else android.view.View.GONE
        h.tag.text = "replay"; h.tag.visibility = if (replay) android.view.View.VISIBLE else android.view.View.GONE
        h.slot.text = "slot ${m.slot}"
        h.time.text = fmt.format(Date(m.time))
        h.body.text = if (replay) m.text.removePrefix("replay: ") else m.text
    }

    companion object {
        fun levelColor(level: Int) = when (level) { 0 -> Color.GRAY; 1 -> Color.rgb(80, 220, 80); 2 -> Color.YELLOW; 3 -> Color.rgb(255, 160, 40); 4, 6 -> Color.rgb(255, 70, 70); 5 -> Color.CYAN; else -> Color.WHITE }
    }
}
