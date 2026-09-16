package com.federicopaglioni.rslog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Row-brightness profile (time axis) with decoded packet spans. */
class ProfileView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var profile = FloatArray(0)
    var packets = FloatArray(0)   // (start, end, slot, channel) normalized
    var packetCount = 0
    private val line = Paint().apply { color = Color.YELLOW; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val bg = Paint().apply { color = Color.argb(150, 20, 20, 20) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        for (i in 0 until packetCount) {
            val s = packets[i * 4]; val e = packets[i * 4 + 1]; val slot = packets[i * 4 + 2].toInt(); val ch = packets[i * 4 + 3].toInt()
            fill.color = if (slot == 7) Color.argb(90, 255, 60, 60) else when (ch) { 1 -> Color.argb(80, 60, 255, 60); 2 -> Color.argb(80, 80, 120, 255); else -> Color.argb(80, 255, 170, 60) }
            c.drawRect(s * w, 0f, e * w, h, fill)
        }
        if (profile.size > 1) {
            val p = Path()
            for (i in profile.indices) {
                val x = i * w / (profile.size - 1); val y = h - profile[i] / 255f * h
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, line)
        }
    }
}
