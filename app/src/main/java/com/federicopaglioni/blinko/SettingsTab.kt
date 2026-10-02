package com.federicopaglioni.blinko

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Settings tab, same sections as iOS: Camera, Decoder, Debug, Tips. Rebuilt when the camera changes. */
class SettingsTab(private val ctx: Context, private val box: LinearLayout, private val session: Session, private val onChanged: (reopen: Boolean) -> Unit) {
    private val s = session.settings
    private var exposureLabel: TextView? = null
    private var isoLabel: TextView? = null
    private var focusLabel: TextView? = null
    private var zoomLabel: TextView? = null
    private var remoteNote: TextView? = null
    private var statsNote: TextView? = null
    private var builtFor = ""

    /** Rebuild if the camera changed; cheap otherwise (labels only). */
    fun refresh() {
        val c = session.camera.info
        val key = "${c.id}/${c.frameRates}/${c.manual}/${c.width}"
        if (key != builtFor) { builtFor = key; build() }
        updateLabels()
    }

    private fun build() {
        box.removeAllViews()
        val f = Form(ctx, box); val c = session.camera.info
        f.header("Camera")
        val cams = session.camera.cameras()
        f.label("Camera")
        box.addView(f.spinner(cams.map { it.second }, cams.indexOfFirst { it.first == c.id }.coerceAtLeast(0)) { i -> val id = cams[i].first; if (id != s.camera) { s.camera = id; onChanged(true) } })
        f.rowView("Resolution", f.spinner(c.resolutions, c.resolutions.indexOf(s.resolution).coerceAtLeast(0)) { i -> if (c.resolutions[i] != s.resolution) { s.resolution = c.resolutions[i]; onChanged(true) } })
        f.rowView("Frame rate", f.spinner(c.frameRates.map { "$it fps" }, c.frameRates.indexOf(c.fps).coerceAtLeast(0)) { i -> s.fps = c.frameRates[i]; onChanged(false) })
        exposureLabel = f.label("")
        f.seek(1000, (s.exposure * 1000).roundToInt()) { v -> s.exposure = v / 1000.0; updateLabels(); onChanged(false) }
        isoLabel = f.label("")
        f.seek(1000, (s.iso * 1000).roundToInt()) { v -> s.iso = v / 1000.0; updateLabels(); onChanged(false) }
        if (c.lensSupported) {
            focusLabel = f.label("")
            f.seek(100, (s.lensPosition * 100).roundToInt()) { v -> s.lensPosition = v / 100f; updateLabels(); onChanged(false) }
        }
        if (c.maxZoom > 1.01) {
            zoomLabel = f.label("")
            f.seek(100, (((s.zoom - 1) / (c.maxZoom - 1)) * 100).roundToInt()) { v -> s.zoom = 1 + v / 100.0 * (c.maxZoom - 1); updateLabels(); onChanged(false) }
        }
        f.note(String.format(Locale.US, "%dx%d %s · exposure %.1f–%.0f µs · ISO %d–%d%s", c.width, c.height, c.format, c.minExposureUs, c.maxExposureUs, c.minIso, c.maxIso,
            if (!c.manual) " · no manual sensor control on this camera" else ""))

        f.header("Decoder")
        f.rowView("Scan axis", f.spinner(listOf("Rows", "Columns"), s.axis) { i -> s.axis = i; onChanged(false); session.pipeline.reset() })
        f.label("Min contrast ${s.minContrast.toInt()}").also { l -> f.seek(38, (s.minContrast - 2).roundToInt()) { v -> s.minContrast = (v + 2).toFloat(); l.text = "Min contrast ${s.minContrast.toInt()}"; onChanged(false) } }
        f.switch("Multi-source (track every light separately)", s.multiSource) { v -> s.multiSource = v; onChanged(false) }
        statsNote = f.note("")

        f.header("Debug")
        f.switch("Remote session (TCP port ${RemoteServer.PORT})", s.remoteEnabled) { v -> s.remoteEnabled = v; onChanged(false); updateLabels() }
        f.switch("Allow Wi-Fi (LAN) connections", s.remoteLan) { v -> s.remoteLan = v; onChanged(false); updateLabels() }
        remoteNote = f.note("")
        f.switch("Recording mode (Record button, .rsrec in app files)", s.recordingEnabled) { v -> s.recordingEnabled = v; onChanged(false) }

        f.header("Tips")
        f.note("Hold the phone 1–3 cm from the board so the defocused LED fills the frame. Keep exposure at the shortest setting: it should stay below T, the shortest run of the signal (60 µs on the demo boards). Tap the profile to switch the scan axis, long-press it to reset the receiver. Lab measures the sensor row time with the board's strobe.")
        f.note("Blinko · ${session.settingsJson().optString("device")}")
    }

    fun updateLabels() {
        val c = session.camera.info
        val minE = c.minExposureUs; val maxE = maxOf(c.maxExposureUs, minE * 1.001)
        val us = minE * (maxE / minE).pow(s.exposure)
        exposureLabel?.text = String.format(Locale.US, "Exposure  %.1f µs  (sensor min %.1f)", us, minE)
        isoLabel?.text = "ISO  ${(c.minIso + s.iso * (c.maxIso - c.minIso)).toInt()}"
        focusLabel?.text = String.format(Locale.US, "Focus  %.2f  (0 nearest, 1 infinity)", s.lensPosition)
        zoomLabel?.text = String.format(Locale.US, "Zoom  ×%.1f", s.zoom)
        remoteNote?.text = if (!s.remoteEnabled) "Off"
            else (if (s.remoteLan) "Wi-Fi: ${session.remoteAddress()} (anyone on the network can connect)" else "This phone only") + "  ·  USB: adb forward tcp:7778 tcp:7777  ·  clients: ${session.remoteClients}"
        val st = session.pipeline.snapshot
        statsNote?.text = "Stats: ${st.totalPackets} packets, ${st.totalMessages} messages, syncs/frame ${st.stats[RsCore.ST_SYNCS].toInt()}, crc fail ${st.stats[RsCore.ST_CRC_FAIL].toInt()}"
    }
}
