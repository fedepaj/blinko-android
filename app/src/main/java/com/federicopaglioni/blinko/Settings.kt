package com.federicopaglioni.blinko

import android.content.Context
import android.util.Log

/** Logcat tag used by every component (adb logcat -s Blinko). */
object Diag {
    const val TAG = "Blinko"
    fun log(msg: String) { Log.i(TAG, msg) }
    fun warn(msg: String) { Log.w(TAG, msg) }
}

/** User settings, persisted in SharedPreferences. Mirrors the iOS Settings struct and the remote `set` keys. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("blinko", Context.MODE_PRIVATE)

    /** A stored number, or the default when what is stored is not a finite one. The UI and the JSON of the remote
     *  session throw on NaN, at every start once it is in the file: nothing writes one any more, but a file that has it must still load. */
    private fun float(key: String, def: Float): Float = prefs.getFloat(key, def).let { if (it.isFinite()) it else def }

    var camera = prefs.getString("camera", "") ?: ""          // camera id, "" = first back camera
    var fps = prefs.getInt("fps", 0)                          // 0 = fastest available
    var resolution = prefs.getString("resolution", "1080p") ?: "1080p"   // 1080p | 4K (longer readout: more signal time per frame) | RAW; what was asked for: CameraInfo.mode is what the camera delivers
    var exposure = float("exposure", 0f).toDouble()           // 0 = shortest, 1 = 1/250 s (log scale)
    var iso = float("iso", 0f).toDouble()                     // 0..1 of the sensitivity range
    var lensPosition = float("lensPosition", 1f)              // 1 = infinity (LED 1-3 cm away is a big blob), 0 = nearest
    var zoom = float("zoom", 1f).toDouble()
    var axis = prefs.getInt("axis", 0)                        // 0 rows, 1 columns
    var minContrast = float("minContrast", 6f)
    var multiSource = prefs.getBoolean("multiSource", true)
    var remoteEnabled = prefs.getBoolean("remoteEnabled", true)
    var remoteLan = prefs.getBoolean("remoteLan", false)      // false: the remote session listens on localhost only (USB, adb forward); true: on every interface
    var recordingEnabled = prefs.getBoolean("recordingEnabled", false)
    var labMode = prefs.getBoolean("labMode", false)
    var strobeHz = float("strobeHz", 2000f).toDouble().let { if (it > 0) it else 2000.0 }
    /** Row time (µs) of the image the receiver sees, from the strobe calibration, one per capture mode
     *  (CameraInfo.mode: the row time changes with the readout mode, and the mode is what the camera
     *  delivers, which can differ from the resolution asked for). The defaults are the S21 FE's:
     *  1080p 5.44, 4K 3.22; RAW 4000x3000 has 2.65 µs sensor rows and is processed at one row per 4,
     *  10.6 µs per processed row. */
    private val rowUsDefaults = mapOf("1080p" to 5.44, "4K" to 3.22, "RAW" to 10.6)
    fun rowUs(mode: String): Double {
        val def = (rowUsDefaults[mode] ?: 5.4).toFloat()
        return float("rowUs_$mode", def).let { if (it > 0f) it else def }.toDouble()
    }
    fun setRowUs(mode: String, v: Double) { prefs.edit().putFloat("rowUs_$mode", v.toFloat()).apply() }
    var note = prefs.getString("note", "") ?: ""

    fun save() {
        prefs.edit().putString("camera", camera).putString("resolution", resolution).putInt("fps", fps).putFloat("exposure", exposure.toFloat()).putFloat("iso", iso.toFloat())
            .putFloat("lensPosition", lensPosition).putFloat("zoom", zoom.toFloat()).putInt("axis", axis).putFloat("minContrast", minContrast)
            .putBoolean("multiSource", multiSource).putBoolean("remoteEnabled", remoteEnabled).putBoolean("remoteLan", remoteLan).putBoolean("recordingEnabled", recordingEnabled)
            .putBoolean("labMode", labMode).putFloat("strobeHz", strobeHz.toFloat()).putString("note", note).apply()
    }

    val axisName get() = if (axis == 0) "Rows" else "Columns"
}
