package com.federicopaglioni.rslog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rings and labels at every tracked light. Track coordinates are normalized to the sensor
 *  buffer (landscape); the portrait preview shows it rotated by 90 degrees. */
class MarkerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var tracks = FloatArray(0)   // (id, x, y, radius, mode, packets, messages) * count
    var count = 0
    private val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val text = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true }
    private val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val palette = intArrayOf(Color.rgb(255, 160, 40), Color.rgb(80, 220, 80), Color.rgb(60, 200, 255), Color.rgb(255, 100, 180))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0 until count) {
            val id = tracks[i * 7].toInt(); val x = tracks[i * 7 + 1]; val y = tracks[i * 7 + 2]; val r = tracks[i * 7 + 3]
            val mode = tracks[i * 7 + 4].toInt(); val pk = tracks[i * 7 + 5].toInt()
            // sensor (x right, y down in landscape) -> portrait view: view x = 1 - y, view y = x
            val vx = (1f - y) * w; val vy = x * h; val vr = r * h
            ring.color = palette[(maxOf(id, 1) - 1) % palette.size]
            c.drawCircle(vx, vy, maxOf(vr, 24f), ring)
            val label = "#$id ${when (mode) { 1 -> "RGB"; 2 -> "direct"; else -> "mono" }} ${pk}p"
            val tw = text.measureText(label)
            c.drawRect(vx - tw / 2 - 6, vy + vr + 6, vx + tw / 2 + 6, vy + vr + 40, bg)
            text.color = ring.color
            c.drawText(label, vx - tw / 2, vy + vr + 34, text)
        }
    }
}
