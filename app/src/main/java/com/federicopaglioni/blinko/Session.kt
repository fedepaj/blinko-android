package com.federicopaglioni.blinko

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's model: camera + pipeline + console + recorder + remote session + replay, and the
 * remote command handler (same commands and JSON as the iOS SessionModel, see ios/tools/rslive.py).
 * Callbacks to the UI are delivered on the main thread.
 */
class Session(private val ctx: Context) {
    val settings = Settings(ctx)
    val camera = CameraController(ctx)
    val recorder = Recorder(ctx)
    val pipeline = Pipeline(recorder)
    val console = Console(ctx)
    val motion = MotionMonitor(ctx)
    val remote = RemoteServer()
    val replay = ReplayEngine()
    private val main = Handler(Looper.getMainLooper())

    var onSnapshot: ((Snapshot) -> Unit)? = null
    var onMessage: ((LogMsg) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onLab: ((LabResult) -> Unit)? = null
    var onRemoteClients: ((Int) -> Unit)? = null
    var onCameraInfo: ((CameraInfo) -> Unit)? = null

    var isRecording = false; private set
    var lastRecording = ""; private set
    var remoteClients = 0; private set
    var lab: LabResult? = null; private set
    var replayProgress = ""; private set

    private class PendingRecord(val reply: (JSONObject, ByteArray?) -> Unit, val replyFile: (JSONObject, File) -> Unit, val send: Boolean, val keep: Boolean)
    private var pendingRecord: PendingRecord? = null
    private val statsTick = object : Runnable {
        override fun run() { if (remoteClients > 0) remote.broadcast(statsJson()); main.postDelayed(this, 200) }
    }

    init {
        pipeline.onSnapshot = { s -> main.post { onSnapshot?.invoke(s) } }
        pipeline.onMessage = { slot, level, text, source -> main.post { deliver(slot, level, text, source, false) } }
        pipeline.onLab = { r -> main.post { lab = r; if (r.rowTimeUs > 2 && r.rowTimeUs < 30 && r.strength > 0.3f) { settings.rowUs = r.rowTimeUs; settings.save() }; onLab?.invoke(r) } }
        recorder.latestMotion = { motion.latest() }
        recorder.onFinished = { summary -> main.post { isRecording = false; lastRecording = summary; Diag.log("[recorder] done: $summary"); onStatus?.invoke(summary); finishRemoteRecording() } }
        camera.onInfo = { i -> main.post { pipeline.rawCfa = i.cfa; pipeline.rawBlack = i.blackLevel; pipeline.rawWhite = i.whiteLevel
            val e = if (i.actualExposureUs > 0) i.actualExposureUs else i.exposureUs
            val rowUs = settings.rowUs   /* the HAL's rolling-shutter skew is not trustworthy here (28 ms reported, 8.5 ms measured): the strobe calibration rules */
            RsCore.setExposureRows(if (e > 0) (e / rowUs).toFloat() else 0f); onCameraInfo?.invoke(i) } }
        camera.onError = { e -> main.post { Diag.warn("[camera] $e"); onStatus?.invoke(e) } }
        camera.onFrame = { img, t -> pipeline.process(img, t) }
        remote.onCommand = { cmd, reply, replyFile -> main.post { handleRemote(cmd, reply, replyFile) } }
        remote.onClientsChanged = { n -> main.post { remoteClients = n; onRemoteClients?.invoke(n) } }
        replay.onMessage = { slot, level, text, source -> main.post { deliver(slot, level, text, source, true) } }
        replay.onProgress = { p -> main.post { replayProgress = p; onStatus?.invoke(p) } }
        replay.onDone = { summary -> main.post {
            replayProgress = summary; pipeline.paused = false; onStatus?.invoke(summary)
            remote.broadcast(JSONObject().put("type", "replay").put("state", "done").put("summary", summary))
        } }
    }

    fun start(preview: SurfaceTexture?) {
        applyPipelineSettings()
        camera.open(settings, preview)
        motion.start()
        if (settings.remoteEnabled) startRemote()
    }

    fun stop() {
        camera.close(); motion.stop()
        if (isRecording) recorder.finish()
    }

    /** Settings changed: push them to the pipeline and the camera (`reopen` when the camera id changed). */
    fun applySettings(reopen: Boolean = false, preview: SurfaceTexture? = null) {
        settings.save()
        applyPipelineSettings()
        if (reopen) camera.open(settings, preview) else camera.apply(settings)
        if (settings.remoteEnabled && !remote.isRunning) startRemote()
        if (!settings.remoteEnabled && remote.isRunning) stopRemote()
    }

    private fun applyPipelineSettings() {
        pipeline.axis = settings.axis; pipeline.multiSource = settings.multiSource
        pipeline.labMode = settings.labMode; pipeline.strobeHz = settings.strobeHz
        RsCore.setMinContrast(settings.minContrast)
    }

    private fun startRemote() { remote.start(); main.removeCallbacks(statsTick); main.post(statsTick); Diag.log("[remote] server on ${remoteAddress()}") }
    private fun stopRemote() { main.removeCallbacks(statsTick); remote.stop() }
    fun remoteAddress() = (RemoteServer.localIPv4() ?: "no Wi-Fi") + ":${RemoteServer.PORT}"

    private fun deliver(slot: Int, level: Int, text: String, source: Int, fromReplay: Boolean) {
        val m = LogMsg(System.currentTimeMillis(), slot, level, if (fromReplay) "replay: $text" else text, source)
        Diag.log("[blinko] message src $source slot $slot level $level: $text")
        console.add(m)
        if (remoteClients > 0) remote.broadcast(JSONObject().put("type", "message").put("t", m.time / 1000.0).put("slot", slot).put("level", level)
            .put("level_name", m.levelName).put("text", text).put("source", source))
        onMessage?.invoke(m)
    }

    fun clearMessages() { console.clear(); pipeline.reset() }

    // ---- recording

    fun startRecording(seconds: Double): Boolean {
        if (isRecording) return false
        val c = camera.info
        val step = Pipeline.stepFor(c.width)
        val header = JSONObject().put("width", c.width / step).put("height", c.height).put("columnStep", step).put("pixelFormat", "BGRA")
            .put("fps", c.fps).put("exposureUs", c.exposureUs).put("iso", c.iso).put("lensPosition", c.lensPosition)
            .put("camera", c.name).put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("axis", settings.axisName)
            .put("startedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
            .put("note", settings.note)
        val f = recorder.start(seconds, header) ?: return false
        isRecording = true; lastRecording = "recording ${f.name}…"; onStatus?.invoke(lastRecording)
        return true
    }

    private fun finishRemoteRecording() {
        val p = pendingRecord ?: return
        pendingRecord = null
        val f = recorder.file ?: run { p.reply(JSONObject().put("type", "error").put("msg", "no recording file"), null); return }
        p.reply(JSONObject().put("type", "recording").put("state", "done").put("file", f.name).put("summary", lastRecording), null)
        if (!p.send) return
        sendFile(f, p.replyFile, !p.keep)
    }

    /** Stream a recording to a client; the file is deleted once the writer is done with it (`deleteAfter`). */
    private fun sendFile(f: File, replyFile: (JSONObject, File) -> Unit, deleteAfter: Boolean) {
        if (!deleteAfter) { replyFile(JSONObject().put("type", "file").put("name", f.name).put("size", f.length()), f); return }
        // the writer streams from disk asynchronously: hand it the file and delete when the size has been sent
        val tmp = File(f.path + ".sending"); f.renameTo(tmp)
        replyFile(JSONObject().put("type", "file").put("name", f.name).put("size", tmp.length()), tmp)
        main.postDelayed(object : Runnable { override fun run() { if (remoteClients == 0 || tmp.length() == 0L) tmp.delete() else main.postDelayed(this, 60_000) } }, 60_000)
    }

    fun startReplay(f: File) {
        if (replay.running) return
        pipeline.paused = true
        replay.start(f)
    }

    // ---- remote session

    fun statsJson(): JSONObject {
        val s = pipeline.snapshot; val st = s.stats; val c = camera.info
        val tr = JSONArray()
        for (i in 0 until s.trackCount) {
            val o = i * 8; val id = s.tracks[o].toInt(); val group = s.tracks[o + 7].toInt()
            tr.put(JSONObject().put("id", id).put("group", group).put("board", console.boardIds[group] ?: "").put("x", s.tracks[o + 1]).put("y", s.tracks[o + 2])
                .put("radius", s.tracks[o + 3]).put("mode", when (s.tracks[o + 4].toInt()) { 2 -> "direct"; 1 -> "RGB"; else -> "mono" })
                .put("packets", s.tracks[o + 5].toInt()).put("messages", s.tracks[o + 6].toInt()).put("pilots", 0))
        }
        return JSONObject().put("type", "stats").put("t", System.currentTimeMillis() / 1000.0).put("fps", s.fps).put("pkt_per_s", s.packetsPerSec)
            .put("rows_per_chip", st[RsCore.ST_RPC]).put("contrast", st[RsCore.ST_CONTRAST]).put("syncs", st[RsCore.ST_SYNCS].toInt()).put("crc_fail", st[RsCore.ST_CRC_FAIL].toInt())
            .put("packets", s.totalPackets).put("messages", s.totalMessages).put("roi", JSONArray().put(st[RsCore.ST_ROI0].toInt()).put(st[RsCore.ST_ROI1].toInt()))
            .put("mode", s.modeName).put("pilots", st[RsCore.ST_PILOTS].toInt()).put("cond", st[RsCore.ST_COND]).put("peak", st[RsCore.ST_PEAK].toInt()).put("sat", st[RsCore.ST_SAT])
            .put("last_packet_age", s.lastPacketAge).put("exposure_us", c.exposureUs).put("exposure_actual_us", c.actualExposureUs).put("readout_ms", c.readoutMs).put("iso", c.iso).put("cam_fps", c.fps).put("width", c.width).put("height", c.height)
            .put("still", motion.isStill).put("motion", motion.level).put("recording", isRecording).put("tracks", tr).put("thermal", "nominal")
            .apply { lab?.let { l -> put("lab", JSONObject().put("period_rows", l.periodRows).put("strength", l.strength).put("row_time_us", l.rowTimeUs).put("readout_ms", l.readoutMs).put("count", l.count)) } }
    }

    fun settingsJson(): JSONObject {
        val s = settings; val c = camera.info
        return JSONObject().put("camera", c.id).put("resolution", s.resolution).put("resolutions", JSONArray(c.resolutions)).put("fps", c.fps).put("exposure", s.exposure).put("iso", s.iso).put("lensPosition", s.lensPosition)
            .put("zoom", s.zoom).put("axis", s.axisName).put("minContrast", s.minContrast).put("multiSource", s.multiSource)
            .put("remoteEnabled", s.remoteEnabled).put("labMode", s.labMode).put("strobeHz", s.strobeHz).put("rowUs", s.rowUs).put("note", s.note)
            .put("camera_name", c.name).put("cameras", JSONArray(camera.cameras().map { "${it.first}: ${it.second}" })).put("frame_rates", JSONArray(c.frameRates))
            .put("min_exposure_us", c.minExposureUs).put("exposure_us", c.exposureUs).put("iso_range", JSONArray().put(c.minIso).put(c.maxIso)).put("max_zoom", c.maxZoom)
            .put("format", c.format).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /** Camera by remote name: an id, or Wide (back, longest focal) / Ultra (back, shortest focal) / Front. */
    private fun cameraIdFor(name: String): String? {
        val cams = camera.cameras()
        if (cams.any { it.first == name }) return name
        val back = cams.filter { it.second.startsWith("Back") }
        fun focal(l: String) = Regex("([0-9.]+)mm").find(l)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        return when (name.lowercase()) {
            "wide" -> back.maxByOrNull { focal(it.second) }?.first
            "ultra" -> back.minByOrNull { focal(it.second) }?.first
            "front" -> cams.firstOrNull { it.second.startsWith("Front") }?.first
            else -> null
        }
    }

    private fun handleRemote(cmd: JSONObject, reply: (JSONObject, ByteArray?) -> Unit, replyFile: (JSONObject, File) -> Unit) {
        val err = { msg: String -> reply(JSONObject().put("type", "error").put("msg", msg), null) }
        when (val name = cmd.optString("cmd")) {
            "get" -> reply(JSONObject().put("type", "settings").put("settings", settingsJson()), null)
            "stats" -> reply(statsJson(), null)
            "messages" -> {
                val list = JSONArray()
                for (m in console.messages.reversed()) list.put(JSONObject().put("t", m.time / 1000.0).put("slot", m.slot).put("level", m.level).put("level_name", m.levelName).put("text", m.text).put("source", m.source))
                reply(JSONObject().put("type", "messages").put("messages", list), null)
            }
            "reset" -> { clearMessages(); reply(JSONObject().put("type", "ok").put("cmd", name), null) }
            "files" -> {
                val list = JSONArray()
                for (f in recorder.files()) list.put(JSONObject().put("name", f.name).put("size", f.length()))
                reply(JSONObject().put("type", "files").put("files", list), null)
            }
            "pull" -> {
                val n = cmd.optString("name")
                val f = File(recorder.directory, n)
                if (n.isEmpty() || n.contains("/") || !f.exists()) { err("no such recording"); return }
                sendFile(f, replyFile, false)
            }
            "delete" -> {
                val names = cmd.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(cmd.optString("name"))
                var removed = 0
                for (n in names) if (n.isNotEmpty() && !n.contains("/")) { val f = File(recorder.directory, n); if (f.exists() && f.delete()) removed++ }
                reply(JSONObject().put("type", "ok").put("cmd", name).put("removed", removed), null)
            }
            "set" -> {
                val key = cmd.optString("key"); if (key.isEmpty()) { err("set needs key/value"); return }
                val v = cmd.opt("value")
                val d = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: 0.0
                val b = (v as? Boolean) ?: (d != 0.0)
                var reopen = false
                val s = settings
                when (key) {
                    "fps" -> s.fps = d.toInt()
                    "resolution" -> { val r = (v as? String ?: "").uppercase().let { if (it.startsWith("4")) "4K" else if (it.startsWith("RAW")) "RAW" else "1080p" }; if (r != s.resolution) { s.resolution = r; reopen = true } }
                    "exposure" -> s.exposure = d
                    "exposure_us" -> s.exposure = CameraController.exposureFraction(camera.info, d)
                    "iso" -> s.iso = d
                    "lensPosition" -> s.lensPosition = d.toFloat()
                    "zoom" -> s.zoom = d
                    "minContrast" -> s.minContrast = d.toFloat()
                    "multiSource" -> s.multiSource = b
                    "labMode" -> s.labMode = b
                    "strobeHz" -> s.strobeHz = d
                    "rowUs" -> s.rowUs = d
                    "axis" -> s.axis = if ((v as? String ?: "").lowercase().startsWith("col")) 1 else 0
                    "camera" -> { val id = cameraIdFor(v as? String ?: ""); if (id == null) { err("unknown camera $v"); return }; if (id != s.camera) { s.camera = id; reopen = true } }
                    "note" -> s.note = v as? String ?: ""
                    "recordingEnabled" -> s.recordingEnabled = b
                    else -> { err("unknown key $key"); return }
                }
                applySettings(reopen, previewTexture?.invoke())
                onSettingsChanged?.invoke()
                if (reopen) main.postDelayed({ reply(JSONObject().put("type", "settings").put("settings", settingsJson()), null) }, 1500)
                else reply(JSONObject().put("type", "settings").put("settings", settingsJson()), null)
            }
            "replay" -> {
                val n = cmd.optString("name")
                val f = File(recorder.directory, n)
                if (n.isEmpty() || n.contains("/") || !f.exists()) { err("no such recording"); return }
                if (replay.running) { err("replay already running"); return }
                startReplay(f); reply(JSONObject().put("type", "replay").put("state", "started").put("name", n), null)
            }
            "frame" -> {
                pipeline.frameRequest = { bytes, w, h, t ->
                    if (w == 3) reply(JSONObject().put("type", "frame").put("w", w).put("h", h).put("step", 0).put("format", "PROFILES").put("t", t), bytes) else
                    reply(JSONObject().put("type", "frame").put("w", w).put("h", h).put("step", Pipeline.stepFor(camera.info.width)).put("format", "BGRA").put("t", t), bytes)
                }
                main.postDelayed({ if (pipeline.frameRequest != null) { pipeline.frameRequest = null; err("no frame") } }, 3000)
            }
            "record" -> {
                if (isRecording) { err("already recording"); return }
                val seconds = cmd.optDouble("seconds", 2.0)
                if (cmd.has("note")) settings.note = cmd.optString("note")
                pendingRecord = PendingRecord(reply, replyFile, cmd.optBoolean("send", true), cmd.optBoolean("keep", true))
                if (!startRecording(seconds)) { pendingRecord = null; err("cannot start recording"); return }
                reply(JSONObject().put("type", "recording").put("state", "started").put("seconds", seconds).put("note", settings.note), null)
            }
            else -> err("unknown cmd $name")
        }
    }

    /** Latest message text per logical source, for the preview markers. */
    fun lastTextPerSource(): Map<Int, String> { val m = HashMap<Int, String>(); for (x in console.messages) if (x.source > 0 && !m.containsKey(x.source)) m[x.source] = x.text; return m }
    /** Most recent FAULT/FATAL message, shown on top of the console. */
    fun faultMessage(): LogMsg? = console.messages.firstOrNull { it.level == 6 || it.level == 4 }
    fun exportText(): String {
        val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return console.messages.reversed().joinToString("\n") { "${f.format(Date(it.time))} [${it.levelName}] src${it.source} slot${it.slot} ${it.text}" }
    }

    /** Provided by the activity: the preview texture, needed when a remote `set camera` reopens the camera. */
    var previewTexture: (() -> SurfaceTexture?)? = null
    /** Settings changed remotely: the UI refreshes its controls. */
    var onSettingsChanged: (() -> Unit)? = null
}
