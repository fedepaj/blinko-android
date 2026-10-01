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

    var camera = prefs.getString("camera", "") ?: ""          // camera id, "" = first back camera
    var fps = prefs.getInt("fps", 0)                          // 0 = fastest available
    var resolution = prefs.getString("resolution", "1080p") ?: "1080p"   // 1080p | 4K (longer readout: more signal time per frame)
    var exposure = prefs.getFloat("exposure", 0f).toDouble()  // 0 = shortest, 1 = 1/250 s (log scale)
    var iso = prefs.getFloat("iso", 0f).toDouble()            // 0..1 of the sensitivity range
    var lensPosition = prefs.getFloat("lensPosition", 1f)     // 1 = infinity (LED 1-3 cm away is a big blob), 0 = nearest
    var zoom = prefs.getFloat("zoom", 1f).toDouble()
    var axis = prefs.getInt("axis", 0)                        // 0 rows, 1 columns
    var minContrast = prefs.getFloat("minContrast", 6f)
    var multiSource = prefs.getBoolean("multiSource", true)
    var remoteEnabled = prefs.getBoolean("remoteEnabled", true)
    var recordingEnabled = prefs.getBoolean("recordingEnabled", false)
    var labMode = prefs.getBoolean("labMode", false)
    var strobeHz = prefs.getFloat("strobeHz", 2000f).toDouble()
    /** Sensor row time per resolution (µs), from the strobe calibration; the defaults are the S21 FE's
     *  (1080p 5.44, 4K 3.22, RAW 4000x3000 ~2.65 as the decoded packets' clock shows). The row time
     *  changes with the readout mode, so each resolution keeps its own. `rowUs` is the current one. */
    private val rowUsDefaults = mapOf("1080p" to 5.44, "4K" to 3.22, "RAW" to 2.65)
    var rowUs: Double
        get() = prefs.getFloat("rowUs_$resolution", (rowUsDefaults[resolution] ?: 5.4).toFloat()).toDouble()
        set(v) { prefs.edit().putFloat("rowUs_$resolution", v.toFloat()).apply() }
    var note = prefs.getString("note", "") ?: ""

    fun save() {
        prefs.edit().putString("camera", camera).putString("resolution", resolution).putInt("fps", fps).putFloat("exposure", exposure.toFloat()).putFloat("iso", iso.toFloat())
            .putFloat("lensPosition", lensPosition).putFloat("zoom", zoom.toFloat()).putInt("axis", axis).putFloat("minContrast", minContrast)
            .putBoolean("multiSource", multiSource).putBoolean("remoteEnabled", remoteEnabled).putBoolean("recordingEnabled", recordingEnabled)
            .putBoolean("labMode", labMode).putFloat("strobeHz", strobeHz.toFloat()).putString("note", note).apply()
    }

    val axisName get() = if (axis == 0) "Rows" else "Columns"
}
