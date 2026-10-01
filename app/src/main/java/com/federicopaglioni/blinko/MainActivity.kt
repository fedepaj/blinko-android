package com.federicopaglioni.blinko

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Locale

/**
 * Four tabs like the iOS app: Live (preview with markers, stats capsule, profile, last message),
 * Console (messages, fault banner, source filter, share, clear), Lab (strobe calibration, replay,
 * measurement, camera) and Settings. All state lives in Session; this class only binds views.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var session: Session
    // live
    private lateinit var preview: TextureView
    private lateinit var markers: MarkerView
    private lateinit var statsBar: LinearLayout
    private lateinit var holdStill: TextView
    private lateinit var cameraError: TextView
    private lateinit var profileView: ProfileView
    private lateinit var recordRow: LinearLayout
    private lateinit var recBtn: Button
    private lateinit var noteField: EditText
    private lateinit var lastRecording: TextView
    private lateinit var lastView: TextView
    private val statValues = ArrayList<TextView>()
    private val statKeys = listOf("fps", "pkt/s", "rows/chip", "contrast", "mode", "peak", "pilots", "msgs")
    // console
    private lateinit var consoleView: RecyclerView
    private lateinit var consoleToolbar: MaterialToolbar
    private lateinit var consoleHeader: TextView
    private lateinit var faultBox: LinearLayout
    private lateinit var faultText: TextView
    private lateinit var adapter: MessageAdapter
    // lab, settings
    private lateinit var lab: LabTab
    private lateinit var settingsTab: SettingsTab
    private var started = false
    private var bufW = 1920; private var bufH = 1080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSustainedPerformanceMode(true)   // steadier CPU clocks for the per-frame decode (the governor otherwise idles the big cores under this bursty load)
        // edge-to-edge: keep the tabs' content below the status bar (the bottom bar handles its own inset)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { v, insets -> v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0); insets }
        session = Session(this)
        bindLive(); bindConsole()
        lab = LabTab(this, findViewById(R.id.labBox), session) { session.applySettings() }
        settingsTab = SettingsTab(this, findViewById(R.id.settingsBox), session) { reopen -> session.applySettings(reopen, preview.surfaceTexture); updateRecordRow() }

        val tabs = mapOf(R.id.tab_live to R.id.live, R.id.tab_console to R.id.consoleTab, R.id.tab_lab to R.id.lab, R.id.tab_settings to R.id.settings)
        findViewById<BottomNavigationView>(R.id.nav).setOnItemSelectedListener { item ->
            for ((menuId, viewId) in tabs) findViewById<View>(viewId).visibility = if (menuId == item.itemId) View.VISIBLE else View.GONE
            when (item.itemId) { R.id.tab_lab -> { lab.build(); lab.refreshRecordings(); lab.refreshMeasurement() }; R.id.tab_settings -> settingsTab.refresh() }
            true
        }

        session.onSnapshot = { s -> render(s) }
        session.onMessage = { m ->
            lastView.text = "${m.levelName}  ${m.text}"; lastView.setTextColor(MessageAdapter.levelColor(m.level))
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(if (m.level == 6 || m.level == 4) 300 else 40, VibrationEffect.DEFAULT_AMPLITUDE))
            updateFault()
        }
        session.onStatus = { t -> lastRecording.text = t; lastRecording.visibility = View.VISIBLE; updateRecordRow(); lab.setProgress(t); lab.refreshRecordings() }
        session.onLab = { lab.refreshMeasurement() }
        session.onCameraInfo = { c -> bufW = c.width; bufH = c.height; fitPreview(); settingsTab.refresh(); cameraError.visibility = View.GONE }
        session.onRemoteClients = { settingsTab.updateLabels() }
        session.previewTexture = { preview.surfaceTexture }
        session.onSettingsChanged = { settingsTab.refresh(); updateRecordRow() }
        session.camera.onError = { e -> runOnUiThread { cameraError.text = e; cameraError.visibility = View.VISIBLE } }
        session.console.onChanged = { adapter.refresh(); updateConsoleChrome() }
        updateRecordRow(); updateConsoleChrome()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    // ---- live tab

    private fun bindLive() {
        preview = findViewById(R.id.preview); markers = findViewById(R.id.markers); statsBar = findViewById(R.id.statsBar)
        holdStill = findViewById(R.id.holdStill); cameraError = findViewById(R.id.cameraError); profileView = findViewById(R.id.profile)
        recordRow = findViewById(R.id.recordRow); recBtn = findViewById(R.id.recBtn); noteField = findViewById(R.id.noteField)
        lastRecording = findViewById(R.id.lastRecording); lastView = findViewById(R.id.last)
        val d = resources.displayMetrics.density
        for (k in statKeys) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0) }
            val v = TextView(this).apply { text = "-"; textSize = 11f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) }
            val l = TextView(this).apply { text = k; textSize = 8f; setTextColor(Color.rgb(158, 158, 158)) }
            col.addView(v); col.addView(l); statsBar.addView(col); statValues.add(v)
        }
        lastView.text = "Point the camera at the LED, 1–3 cm away"
        profileView.setOnClickListener { session.settings.axis = 1 - session.settings.axis; session.applySettings(); session.pipeline.reset(); status("scan axis: " + session.settings.axisName.lowercase()) }
        profileView.setOnLongClickListener { session.pipeline.reset(); status("receiver reset"); true }
        recBtn.setOnClickListener { session.settings.note = noteField.text.toString(); session.settings.save(); if (!session.isRecording) session.startRecording(2.0) else session.recorder.finish() }
        noteField.setText(session.settings.note)
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
    }

    /** The TextureView shows the rotated sensor frame stretched to its bounds: scale it to aspect-fit and tell the markers where it is. */
    private fun fitPreview() {
        val vw = preview.width.toFloat(); val vh = preview.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val cw = bufH.toFloat(); val ch = bufW.toFloat()          // portrait: the frame is displayed rotated by 90°
        val s = minOf(vw / cw, vh / ch)
        val m = Matrix(); m.setScale(cw * s / vw, ch * s / vh, vw / 2, vh / 2)
        preview.setTransform(m)
        markers.content = RectF((vw - cw * s) / 2, (vh - ch * s) / 2, (vw + cw * s) / 2, (vh + ch * s) / 2)
        markers.invalidate()
    }

    private fun status(t: String) { lastRecording.text = t; lastRecording.visibility = View.VISIBLE }

    private fun updateRecordRow() {
        recordRow.visibility = if (session.settings.recordingEnabled) View.VISIBLE else View.GONE
        recBtn.text = if (session.isRecording) "⏺ REC" else "⏺ Record 2 s"
        recBtn.setTextColor(if (session.isRecording) Color.WHITE else Color.rgb(255, 82, 82))
    }

    private fun render(s: Snapshot) {
        val st = s.stats
        val vals = listOf("%d".format(s.fps), "%.1f".format(Locale.US, s.packetsPerSec), if (st[RsCore.ST_RPC] > 0) "%.1f".format(Locale.US, st[RsCore.ST_RPC]) else "-",
            "%.0f".format(st[RsCore.ST_CONTRAST]), s.modeName, "%.0f".format(st[RsCore.ST_PEAK]), "%.0f".format(st[RsCore.ST_PILOTS]), "${s.totalMessages}")
        for ((i, v) in vals.withIndex()) statValues[i].text = v
        profileView.profile = s.profile; profileView.packets = s.packets; profileView.packetCount = s.packetCount; profileView.invalidate()
        markers.tracks = s.tracks; markers.count = s.trackCount; markers.texts = session.lastTextPerSource(); markers.invalidate()
        holdStill.visibility = if (session.motion.isStill) View.GONE else View.VISIBLE
        if (session.console.messages.isEmpty()) {
            lastView.text = if (s.lastPacketAge < 3) "Receiving packets…" else "Point the camera at the LED, 1–3 cm away"; lastView.setTextColor(Color.rgb(158, 158, 158))
        }
        if (findViewById<View>(R.id.lab).visibility == View.VISIBLE) lab.setProfile(s.profile)
    }

    // ---- console tab

    private fun bindConsole() {
        consoleView = findViewById(R.id.console); consoleToolbar = findViewById(R.id.consoleToolbar)
        consoleHeader = findViewById(R.id.consoleHeader); faultBox = findViewById(R.id.faultBox); faultText = findViewById(R.id.faultText)
        val console = session.console
        adapter = MessageAdapter(console)
        consoleView.layoutManager = LinearLayoutManager(this)
        consoleView.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { console.remove(adapter.items[vh.bindingAdapterPosition]) }
        }).attachToRecyclerView(consoleView)
        consoleToolbar.setNavigationOnClickListener {
            AlertDialog.Builder(this).setMessage("Delete all ${console.messages.size} messages?")
                .setPositiveButton("Delete all") { _, _ -> session.clearMessages() }.setNegativeButton("Cancel", null).show()
        }
        consoleToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter -> {
                    val src = console.sources()
                    val labels = arrayOf("All sources") + src.map { it.second }.toTypedArray()
                    val ids = intArrayOf(0) + src.map { it.first }.toIntArray()
                    AlertDialog.Builder(this).setTitle("Source")
                        .setSingleChoiceItems(labels, ids.indexOf(console.filter).coerceAtLeast(0)) { d, which -> console.filter = ids[which]; console.onChanged?.invoke(); d.dismiss() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
                R.id.action_share -> {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText()), "Blinko messages")); true
                }
                else -> false
            }
        }
    }

    private fun updateConsoleChrome() {
        val console = session.console
        consoleToolbar.title = if (console.filter == 0) "Blinko Console" else "Source #${console.filter}"
        consoleToolbar.menu.findItem(R.id.action_filter)?.title = console.label(console.filter).replace("All sources", "All")
        consoleHeader.text = "MESSAGES (${console.filtered().size})"
        updateFault()
    }

    private fun updateFault() {
        val f = session.faultMessage()
        faultBox.visibility = if (f != null) View.VISIBLE else View.GONE
        faultText.text = f?.text ?: ""
    }

    // ---- lifecycle

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else { cameraError.text = "Camera permission denied"; cameraError.visibility = View.VISIBLE }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startWhenReady()
    }

    override fun onPause() {
        super.onPause()
        if (started) { session.stop(); started = false }
    }

    private fun startWhenReady() {
        if (started) return
        if (preview.isAvailable) start() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { start() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { fitPreview() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun start() {
        if (started) return
        started = true
        session.start(preview.surfaceTexture)
    }
}
