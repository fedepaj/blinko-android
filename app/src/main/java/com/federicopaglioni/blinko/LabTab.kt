package com.federicopaglioni.blinko

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** Lab tab like iOS: strobe calibration, replay a recording, live profile, measurement, camera. */
class LabTab(private val ctx: Context, private val box: LinearLayout, private val session: Session, private val onChanged: () -> Unit) {
    private val s = session.settings
    private lateinit var profile: ProfileView
    private var replayBox: LinearLayout? = null
    private var replayProgress: TextView? = null
    private var measureBox: LinearLayout? = null
    private var camBox: LinearLayout? = null
    private var built = false

    fun build() {
        if (built) return
        built = true
        box.removeAllViews()
        val f = Form(ctx, box)
        f.header("Strobe calibration")
        f.switch("Strobe calibration mode", s.labMode) { v -> s.labMode = v; onChanged() }
        f.rowView("Strobe frequency (Hz)", f.edit(s.strobeHz.toInt().toString(), Form.NUMBER) { t -> t.toDoubleOrNull()?.let { s.strobeHz = it; onChanged() } })
        f.note("Flash the strobe_calib sketch (or send `strobe 2000` to the demo), enable this mode and point the camera at the LED.")

        f.header("Replay a recording")
        replayBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }; box.addView(replayBox)
        replayProgress = f.note("")
        f.note("Runs the recording through the multi-source receiver; messages appear in the console tagged 'replay'. Recordings come from Recording mode (Settings › Debug) or the remote session with --keep.")

        f.header("Live profile")
        profile = ProfileView(ctx, null).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (90 * ctx.resources.displayMetrics.density).toInt()); setBackgroundResource(R.drawable.panel) }
        box.addView(profile)

        measureBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }; box.addView(measureBox)
        camBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }; box.addView(camBox)
        refreshRecordings(); refreshMeasurement()
    }

    fun refreshRecordings() {
        val b = replayBox ?: return
        b.removeAllViews()
        val f = Form(ctx, b)
        val files = session.recorder.files()
        if (files.isEmpty()) f.note("No recordings on this phone.")
        for (file in files) {
            b.addView(TextView(ctx).apply { text = "${file.name}  ·  ${file.length() / 1_000_000} MB"; textSize = 12f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.MONOSPACE
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, 8, 0, 0) })
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            line.addView(f.button(if (session.replay.running) "…" else "Run") { if (!session.replay.running) session.startReplay(file) })
            line.addView(f.button("Share") { share(file) })
            line.addView(f.button("Delete", Color.rgb(255, 112, 112)) { file.delete(); refreshRecordings() })
            b.addView(line); f.divider()
        }
        if (session.replay.running) b.addView(f.button("Cancel replay", Color.rgb(255, 112, 112)) { session.replay.cancel() })
    }

    fun setProgress(t: String) { replayProgress?.text = t }

    fun refreshMeasurement() {
        val m = measureBox ?: return
        m.removeAllViews()
        val f = Form(ctx, m)
        val r = session.lab
        if (r != null) {
            f.header("Measurement (${s.axisName} axis, ${r.count} samples)")
            f.row("Band period", if (r.periodRows > 0) String.format(Locale.US, "%.2f rows", r.periodRows) else "no bands found")
            f.row("Peak strength", String.format(Locale.US, "%.2f", r.strength))
            f.row("Row time", if (r.rowTimeUs > 0) String.format(Locale.US, "%.2f µs", r.rowTimeUs) else "-")
            f.row("Frame readout", if (r.readoutMs > 0) String.format(Locale.US, "%.2f ms", r.readoutMs) else "-")
            if (r.rowTimeUs > 0) {
                f.row("Min chip (4 rows)", String.format(Locale.US, "%.0f µs", 4 * r.rowTimeUs))
                f.row("Packet height @30µs", String.format(Locale.US, "%.0f rows", 67 * 30 / r.rowTimeUs))
            }
        }
        val c = camBox ?: return
        c.removeAllViews()
        val g = Form(ctx, c); val cam = session.camera.info; val st = session.pipeline.snapshot.stats
        g.header("Camera")
        g.row("Device", cam.name)
        g.row("Format", "${cam.width}×${cam.height} ${cam.format} @ ${cam.fps}")
        g.row("Exposure", String.format(Locale.US, "%.1f µs (min %.1f)", cam.exposureUs, cam.minExposureUs))
        g.row("ISO", "${cam.iso} (${cam.minIso}–${cam.maxIso})")
        g.row("Lens", String.format(Locale.US, "%.2f", cam.lensPosition))
        g.row("ROI", "${st[RsCore.ST_ROI0].toInt()}–${st[RsCore.ST_ROI1].toInt()} / ${st[RsCore.ST_COUNT].toInt()}")
    }

    fun setProfile(p: FloatArray) { if (built) { profile.profile = p; profile.packets = FloatArray(0); profile.packetCount = 0; profile.invalidate() } }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, f.name))
        } catch (e: Exception) { Diag.warn("[share] $e") }
    }
}
