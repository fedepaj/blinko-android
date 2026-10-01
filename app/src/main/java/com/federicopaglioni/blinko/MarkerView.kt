package com.federicopaglioni.blinko

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Rings and labels at every tracked light, like the iOS preview: "#group·id mode Np" and the
 *  light's last message. Track coordinates are normalized to the sensor buffer (landscape); the
 *  portrait preview shows it rotated by 90 degrees inside `content` (aspect-fit rectangle). */
class MarkerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var tracks = FloatArray(0)   // (id, x, y, radius, mode, packets, messages, group) * count
    var count = 0
    var texts: Map<Int, String> = emptyMap()
    var content = RectF()        // where the camera frame is drawn inside this view; empty = whole view
    private val F = 8
    private val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val link = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    private val text = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true }
    private val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    private fun rect(): RectF = if (content.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else content
    // sensor (x right, y down in landscape) -> portrait view: view x = 1 - y, view y = x
    private fun vx(r: RectF, i: Int) = r.left + (1f - tracks[i * F + 2]) * r.width()
    private fun vy(r: RectF, i: Int) = r.top + tracks[i * F + 1] * r.height()

    override fun onDraw(c: Canvas) {
        val r = rect()
        for (i in 0 until count) {
            val id = tracks[i * F].toInt(); val group = tracks[i * F + 7].toInt()
            if (group == id) continue
            for (j in 0 until count) if (tracks[j * F].toInt() == group) {
                link.color = color(group); c.drawLine(vx(r, i), vy(r, i), vx(r, j), vy(r, j), link)
            }
        }
        for (i in 0 until count) {
            val id = tracks[i * F].toInt(); val rad = tracks[i * F + 3]
            val mode = tracks[i * F + 4].toInt(); val pk = tracks[i * F + 5].toInt(); val group = tracks[i * F + 7].toInt()
            val x = vx(r, i); val y = vy(r, i); val vr = maxOf(rad * r.height(), 24f)
            ring.color = color(group); c.drawCircle(x, y, vr, ring)
            val lines = ArrayList<String>()
            lines.add("#$group${if (group != id) "·$id" else ""} ${when (mode) { 1 -> "RGB"; 2 -> "direct"; else -> "mono" }} ${pk}p")
            texts[group]?.let { lines.add(if (it.length > 28) it.substring(0, 28) + "…" else it) }
            val tw = lines.maxOf { text.measureText(it) }
            val top = y + vr + 6
            c.drawRect(x - tw / 2 - 8, top, x + tw / 2 + 8, top + 30f * lines.size + 6, bg)
            text.color = ring.color
            for ((k, l) in lines.withIndex()) c.drawText(l, x - tw / 2, top + 26 + 30f * k, text)
        }
    }

    companion object {
        val palette = intArrayOf(Color.rgb(255, 160, 40), Color.rgb(80, 220, 80), Color.rgb(60, 200, 255), Color.rgb(255, 100, 180))
        fun color(source: Int) = palette[(maxOf(source, 1) - 1) % palette.size]
    }
}
