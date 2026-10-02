package com.federicopaglioni.blinko

import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Remote session server, wire-compatible with the iOS app and ios/tools/rslive.py:
 * a TCP listener (port 7777) through which a computer drives the app (record, grab a frame,
 * change settings) and receives live stats, messages and files.
 *
 * Framing, both directions: u32 big-endian length | u8 kind (0 = JSON, 1 = binary) | payload.
 * Binary payloads are always announced by the JSON message that precedes them.
 * Nothing authenticates a client. It listens on the phone's loopback address, which is where
 * `adb forward tcp:7778 tcp:7777` arrives over USB; started with `lan` it listens on every interface
 * and is reachable over Wi-Fi (address shown in Settings).
 */
class RemoteServer {
    /** A command from a client; `reply` sends JSON (+ optional binary) back to that client only; `replyFile` streams a file after the JSON. */
    var onCommand: ((cmd: JSONObject, reply: (JSONObject, ByteArray?) -> Unit, replyFile: (JSONObject, File) -> Unit) -> Unit)? = null
    var onClientsChanged: ((Int) -> Unit)? = null

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile var isRunning = false; private set
    /** How the running server was started: true = every interface, false = loopback only. */
    var lan = false; private set
    val clientCount get() = clients.size

    private inner class Client(val sock: Socket) {
        val out: OutputStream = sock.getOutputStream()
        private val writer = Executors.newSingleThreadExecutor()   // sends are queued in order, never on the caller's thread
        fun send(json: JSONObject, bin: ByteArray?) {
            val data = frame(json, bin)
            try { writer.execute { try { out.write(data); out.flush() } catch (e: IOException) { drop(this) } } } catch (_: Exception) {}
        }
        /** JSON followed by a binary payload streamed from disk in 1 MB chunks (recordings are ~60 MB/s). */
        fun sendFile(json: JSONObject, file: File) {
            val head = frame(json, null)
            try { writer.execute {
                try {
                    out.write(head)
                    val len = file.length()
                    out.write(ByteBuffer.allocate(5).putInt(len.toInt()).put(1).array())
                    FileInputStream(file).use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
                    out.flush()
                } catch (e: IOException) { drop(this) }
            } } catch (_: Exception) {}
        }
        fun close() { writer.shutdown(); try { sock.close() } catch (_: IOException) {} }
    }

    fun start(lan: Boolean) {
        if (isRunning) return
        val s: ServerSocket
        try {
            // A client can change settings and pull or delete recordings without a password, so the default is the
            // loopback address: only this phone (adbd, for `adb forward`) can connect. It used to bind every interface
            // for anyone on the network. 127.0.0.1 by number: InetAddress.getLoopbackAddress() is ::1 on Android, and
            // adb forwards to the IPv4 one first.
            s = ServerSocket(); s.reuseAddress = true
            s.bind(if (lan) InetSocketAddress(PORT) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT))
            server = s; this.lan = lan; isRunning = true
        } catch (e: IOException) { Diag.warn("[remote] listener failed: $e"); return }
        Diag.log("[remote] listening on ${if (lan) "every interface" else "localhost"}, port $PORT")
        Thread({
            while (isRunning && !s.isClosed) {     // its own socket: after a restart `server` is the next listener's
                val sock = try { s.accept() } catch (e: IOException) { break }
                // a client that resets right after connecting makes these throw: that ends this client, not the accept thread
                val c = try { sock.tcpNoDelay = true; Client(sock) } catch (e: IOException) { try { sock.close() } catch (_: IOException) {}; continue }
                clients.add(c); onClientsChanged?.invoke(clients.size)
                Diag.log("[remote] client connected (${clients.size})")
                Thread({ serve(c) }, "blinko.remote.client").apply { isDaemon = true; start() }
            }
        }, "blinko.remote").apply { isDaemon = true; start() }
    }

    fun stop() {
        isRunning = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        for (c in clients) c.close()
        clients.clear(); onClientsChanged?.invoke(0)
    }

    /** Send to every connected client. */
    fun broadcast(json: JSONObject, bin: ByteArray? = null) { for (c in clients) c.send(json, bin) }

    private fun serve(c: Client) {
        try {
            val din = DataInputStream(c.sock.getInputStream())
            while (isRunning) {
                val len = din.readInt(); val kind = din.readUnsignedByte()
                if (len == 0) continue
                // Longer than any command (or negative): the client is dropped. The payload was once skipped without
                // being read, so the loop went on taking its bytes for headers, and up to 64 MB were allocated on a
                // client's word.
                if (len < 0 || len > MAX_COMMAND) { Diag.warn("[remote] frame of $len bytes refused, closing the client"); break }
                val payload = ByteArray(len); din.readFully(payload)
                if (kind != 0) continue
                val obj = try { JSONObject(String(payload)) } catch (e: Exception) { continue }
                val h = onCommand
                if (h == null) c.send(JSONObject().put("type", "error").put("msg", "no handler"), null)
                else h(obj, { json, bin -> c.send(json, bin) }, { json, f -> c.sendFile(json, f) })
            }
        } catch (_: IOException) {} finally { drop(c) }
    }

    private fun drop(c: Client) {
        if (clients.remove(c)) { c.close(); onClientsChanged?.invoke(clients.size); Diag.log("[remote] client left (${clients.size})") }
    }

    companion object {
        const val PORT = 7777
        const val MAX_COMMAND = 1 shl 20     // bytes of one incoming frame: commands are small JSON objects

        fun frame(json: JSONObject, bin: ByteArray?): ByteArray {
            val j = json.toString().toByteArray()
            val b = ByteBuffer.allocate(5 + j.size + (bin?.let { 5 + it.size } ?: 0))
            b.putInt(j.size); b.put(0); b.put(j)
            if (bin != null) { b.putInt(bin.size); b.put(1); b.put(bin) }
            return b.array()
        }

        /** First IPv4 address of a wlan/eth interface, for the Settings screen. */
        fun localIPv4(): String? {
            try {
                val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
                val pref = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
                for (ni in pref) for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            } catch (_: Exception) {}
            return null
        }
    }
}
