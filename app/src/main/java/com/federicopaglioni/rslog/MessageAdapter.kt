package com.federicopaglioni.rslog

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

/** Console rows: "[LEVEL] src #1 · e844   slot 6   12:34:56" and the text, newest first. */
class MessageAdapter(private val console: Console) : RecyclerView.Adapter<MessageAdapter.Row>() {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    var items: List<LogMsg> = console.filtered()

    class Row(val root: LinearLayout, val head: TextView, val body: TextView) : RecyclerView.ViewHolder(root)

    fun refresh() { items = console.filtered(); notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(8, 6, 8, 6); setBackgroundColor(Color.BLACK)
        }
        val head = TextView(ctx).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.LTGRAY); gravity = Gravity.START }
        val body = TextView(ctx).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE) }
        root.addView(head); root.addView(body)
        return Row(root, head, body)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Row, position: Int) {
        val m = items[position]
        val color = when (m.level) { 0 -> Color.GRAY; 1 -> Color.rgb(80, 220, 80); 2 -> Color.YELLOW; 3 -> Color.rgb(255, 160, 40); 4, 6 -> Color.rgb(255, 70, 70); 5 -> Color.CYAN; else -> Color.WHITE }
        val src = if (m.source > 0) "  src #${m.source}" + (console.boardIds[m.source]?.let { " · $it" } ?: "") else ""
        h.head.text = "[${m.levelName}]$src   slot ${m.slot}   ${fmt.format(Date(m.time))}"
        h.head.setTextColor(color)
        h.body.text = m.text
    }
}
