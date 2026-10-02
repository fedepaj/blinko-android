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
        // What is stored is the row time of the image the receiver decodes: the measured one times rowBin. In RAW the
        // calibration runs on sensor rows (2.65 µs on the S21 FE) and the receiver on one row per 4 of them (10.6 µs);
        // storing the measured figure there gave the receiver an exposure four times too long in rows.
        pipeline.onLab = { r -> main.post { lab = r; if (r.rowTimeUs > 2 && r.rowTimeUs < 30 && r.strength > 0.3f) { settings.setRowUs(camera.info.mode, r.rowTimeUs * r.rowBin); settings.save() }; onLab?.invoke(r) } }
        recorder.latestMotion = { motion.latest() }
        recorder.onFinished = { summary -> main.post { isRecording = false; lastRecording = summary; Diag.log("[recorder] done: $summary"); onStatus?.invoke(summary); finishRemoteRecording() } }
        camera.onInfo = { i -> main.post { pipeline.rawCfa = i.cfa; pipeline.rawBlack = i.blackLevel; pipeline.rawWhite = i.whiteLevel
            val e = if (i.actualExposureUs > 0) i.actualExposureUs else i.exposureUs
            val rowUs = settings.rowUs(i.mode)   /* the HAL's rolling-shutter skew is not trustworthy here (28 ms reported, 8.5 ms measured): the strobe calibration rules */
            RsCore.setExposureRows(if (e > 0) (e / rowUs).toFloat() else 0f); RsCore.setRowTime((rowUs * 1e-6).toFloat()); onCameraInfo?.invoke(i) } }
        camera.onError = { e -> main.post { Diag.warn("[camera] $e"); onStatus?.invoke(e) } }
        camera.onFrame = { img, t -> pipeline.process(img, t) }
        remote.onCommand = { cmd, reply, replyFile -> main.post { handleRemote(cmd, reply, replyFile) } }
        remote.onClientsChanged = { n -> main.post { remoteClients = n; onRemoteClients?.invoke(n) } }
        replay.onMessage = { slot, level, text, source -> main.post { deliver(slot, level, text, source, true) } }
        replay.onProgress = { p -> main.post { replayProgress = p; onStatus?.invoke(p) } }
        replay.onDone = { summary -> main.post {
            // `running` goes false on the replay thread before this runs, so another replay can have been started in
            // between (startReplay, also on this thread): then the pipeline stays paused, and that replay's own end resumes it
            if (!replay.running) pipeline.paused = false
            replayProgress = summary; onStatus?.invoke(summary)
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

    /**
     * The activity is destroyed: let go of everything this session holds, i.e. the camera and its thread, the recorder
     * thread, a running replay, the stats tick and the server port. The session cannot be used afterwards. (A recreated
     * activity makes a new Session; without this the old one lived on next to it, still bound to port 7777.)
     */
    fun close() {
        stop()
        replay.cancel()
        stopRemote()
        main.removeCallbacksAndMessages(null)
        onSnapshot = null; onMessage = null; onStatus = null; onLab = null; onRemoteClients = null; onCameraInfo = null
        onSettingsChanged = null; previewTexture = null
        camera.release(); recorder.close()
    }

    /** Settings changed: push them to the pipeline and the camera (`reopen` when the camera id changed). */
    fun applySettings(reopen: Boolean = false, preview: SurfaceTexture? = null) {
        settings.save()
        applyPipelineSettings()
        if (reopen) camera.open(settings, preview) else camera.apply(settings)
        if (remote.isRunning && (!settings.remoteEnabled || remote.lan != settings.remoteLan)) stopRemote()   // off, or listening on the wrong interfaces
        if (settings.remoteEnabled && !remote.isRunning) startRemote()
    }

    private fun applyPipelineSettings() {
        pipeline.axis = settings.axis; pipeline.multiSource = settings.multiSource
        pipeline.labMode = settings.labMode; pipeline.strobeHz = settings.strobeHz
        RsCore.setMinContrast(settings.minContrast)
    }

    private fun startRemote() { remote.start(settings.remoteLan); main.removeCallbacks(statsTick); main.post(statsTick); if (settings.remoteLan) Diag.log("[remote] server on ${remoteAddress()}") }
    private fun stopRemote() { main.removeCallbacks(statsTick); remote.stop() }
    /** Where a computer on the same network reaches the server (when LAN connections are allowed). */
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
        // by the format the camera delivers, not the resolution asked for (a camera without RAW runs 1080p): RAW frames
        // are recorded as the reduced BGRA image the receiver sees
        val raw = c.format == "RAW"
        val pw = if (raw) c.width / 2 else c.width; val ph = if (raw) c.height / Pipeline.RAW_ROW_BIN else c.height
        val step = Pipeline.stepFor(pw)
        val header = JSONObject().put("width", pw / step).put("height", ph).put("columnStep", step).put("pixelFormat", "BGRA")
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
        replay.start(f) { pipeline.awaitIdle() }      // the frame the camera thread is in the middle of ends before the replay takes the receiver
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
            .put("mode", s.modeName).put("pilots", st[RsCore.ST_PILOTS].toInt()).put("cond", st[RsCore.ST_COND]).put("peak", st[RsCore.ST_PEAK].toInt()).put("sat", st[RsCore.ST_SAT]).put("stitched", st[RsCore.ST_STITCHED].toInt())
            .put("last_packet_age", s.lastPacketAge).put("exposure_us", c.exposureUs).put("exposure_actual_us", c.actualExposureUs).put("readout_ms", c.readoutMs).put("iso", c.iso).put("cam_fps", c.fps).put("width", c.width).put("height", c.height)
            .put("still", motion.isStill).put("motion", motion.level).put("recording", isRecording).put("tracks", tr).put("thermal", "nominal")
            .apply { lab?.let { l -> put("lab", JSONObject().put("period_rows", l.periodRows).put("strength", l.strength).put("row_time_us", l.rowTimeUs).put("readout_ms", l.readoutMs).put("count", l.count)) } }
    }

    fun settingsJson(): JSONObject {
        val s = settings; val c = camera.info
        return JSONObject().put("camera", c.id).put("resolution", s.resolution).put("resolutions", JSONArray(c.resolutions)).put("fps", c.fps).put("exposure", s.exposure).put("iso", s.iso).put("lensPosition", s.lensPosition)
            .put("zoom", s.zoom).put("axis", s.axisName).put("minContrast", s.minContrast).put("multiSource", s.multiSource)
            .put("remoteEnabled", s.remoteEnabled).put("remoteLan", s.remoteLan).put("labMode", s.labMode).put("strobeHz", s.strobeHz).put("rowUs", s.rowUs(c.mode)).put("note", s.note)
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

    /** The recording a remote client names, or null: only a plain name of a regular file directly
     *  inside the recordings directory. A name such as ".." passed the old test (no "/" and it
     *  exists) and reached the directory above, where `delete` and `pull` then worked on it. */
    private fun recordingNamed(name: String): File? {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        val f = File(recorder.directory, name)
        return if (f.isFile && f.canonicalFile.parentFile == recorder.directory.canonicalFile) f else null
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
                val f = recordingNamed(n)
                if (f == null) { err("no such recording"); return }
                sendFile(f, replyFile, false)
            }
            "delete" -> {
                val names = cmd.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(cmd.optString("name"))
                var removed = 0
                for (n in names) if (recordingNamed(n)?.delete() == true) removed++
                reply(JSONObject().put("type", "ok").put("cmd", name).put("removed", removed), null)
            }
            "set" -> {
                val key = cmd.optString("key"); if (key.isEmpty()) { err("set needs key/value"); return }
                val v = cmd.opt("value")
                // What is accepted here is saved and read back at every start, so it is checked first: a number must be
                // finite (org.json parses the literals NaN and Infinity, and "NaN".toDouble() is one too) and inside the
                // setting's range. A NaN stored this way made settingsJson and the Settings tab throw at every launch.
                val d = ((v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull())?.takeIf { it.isFinite() }
                val b = (v as? Boolean) ?: d?.let { it != 0.0 } ?: (v as? String)?.lowercase()?.toBooleanStrictOrNull()
                fun num(lo: Double, hi: Double): Double? = d?.takeIf { it in lo..hi } ?: run { err("$key needs a number in $lo..$hi"); null }
                fun bool(): Boolean? = b ?: run { err("$key needs true or false"); null }
                var reopen = false
                val s = settings; val c = camera.info
                when (key) {
                    "fps" -> {
                        val f = (num(0.0, 240.0) ?: return).toInt()            // 0 = fastest
                        if (f != 0 && c.frameRates.isNotEmpty() && f !in c.frameRates) { err("fps $f is not one of ${c.frameRates}"); return }
                        s.fps = f
                    }
                    "resolution" -> {
                        val r = (v as? String ?: "").uppercase().let { if (it.startsWith("4")) "4K" else if (it.startsWith("RAW")) "RAW" else if (it.startsWith("1080")) "1080p" else it }
                        if (r !in c.resolutions) { err("resolution $v is not one of ${c.resolutions} on this camera"); return }
                        if (r != s.resolution) { s.resolution = r; reopen = true }
                    }
                    "exposure" -> s.exposure = num(0.0, 1.0) ?: return
                    "exposure_us" -> s.exposure = CameraController.exposureFraction(c, num(1.0, 1e6) ?: return)   // any duration: the sensor's range clamps it
                    "iso" -> s.iso = num(0.0, 1.0) ?: return
                    "lensPosition" -> s.lensPosition = (num(0.0, 1.0) ?: return).toFloat()
                    "zoom" -> s.zoom = num(1.0, maxOf(1.0, c.maxZoom)) ?: return
                    "minContrast" -> s.minContrast = (num(0.0, 255.0) ?: return).toFloat()
                    "multiSource" -> s.multiSource = bool() ?: return
                    "labMode" -> s.labMode = bool() ?: return
                    "strobeHz" -> s.strobeHz = num(1.0, 1e6) ?: return
                    "rowUs" -> s.setRowUs(c.mode, num(0.1, 1000.0) ?: return)
                    "axis" -> s.axis = if ((v as? String ?: "").lowercase().startsWith("col")) 1 else 0
                    "camera" -> { val id = cameraIdFor(v as? String ?: ""); if (id == null) { err("unknown camera $v"); return }; if (id != s.camera) { s.camera = id; reopen = true } }
                    "note" -> s.note = v as? String ?: ""
                    "recordingEnabled" -> s.recordingEnabled = bool() ?: return
                    else -> { err("unknown key $key"); return }
                }
                applySettings(reopen, previewTexture?.invoke())
                onSettingsChanged?.invoke()
                if (reopen) main.postDelayed({ reply(JSONObject().put("type", "settings").put("settings", settingsJson()), null) }, 1500)
                else reply(JSONObject().put("type", "settings").put("settings", settingsJson()), null)
            }
            "replay" -> {
                val n = cmd.optString("name")
                val f = recordingNamed(n)
                if (f == null) { err("no such recording"); return }
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
                val asked = cmd.optDouble("seconds", 2.0)
                if (asked.isNaN()) { err("record needs seconds as a number"); return }
                val seconds = asked.coerceIn(0.1, 30.0)                       // a recording is ~60 MB/s on the phone's storage
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
