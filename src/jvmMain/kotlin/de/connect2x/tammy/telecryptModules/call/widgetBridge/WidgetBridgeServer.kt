package de.connect2x.tammy.telecryptModules.call.widgetBridge

import de.connect2x.tammy.telecryptModules.call.callLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Локальный HTTP+WebSocket сервер на сыром ServerSocket. Не используем
 * com.sun.net.httpserver.HttpServer, чтобы не зависеть от рефлексии в
 * sun.net.httpserver.* (которая ломается на JDK 17+ из-за модулей).
 *
 * URLs:
 *   GET /widget-host.html  — отдаёт HTML с подставленными плейсхолдерами
 *   GET /widget-bus        — апгрейд в WebSocket
 */
class WidgetBridgeServer(
    private val widgetId: String,
    @Volatile private var elementCallUrl: String,
    private val handlerFactory: () -> WidgetApiHandler,
    private val onConnected: (WidgetSession) -> Unit = {},
    // Receives raw WebRTC stats envelopes ({"type":"telecrypt-webrtc-stats",...})
    // forwarded by the host page; routed out-of-band from the Widget API.
    private val onWebRtcStats: (String) -> Unit = {},
) : AutoCloseable {

    fun setElementCallUrl(url: String) {
        elementCallUrl = url
    }

    private val serverSocket: ServerSocket =
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "widget-bridge").apply { isDaemon = true }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: WidgetSession? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var running: Boolean = false

    val port: Int get() = serverSocket.localPort
    val hostHtmlUrl: String get() = "http://127.0.0.1:$port/widget-host.html"
    val wsUrl: String get() = "ws://127.0.0.1:$port/widget-bus"

    fun start() {
        running = true
        acceptThread = Thread({
            while (running && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (t: Throwable) {
                    if (running) callLog("[WidgetBridge] accept error: ${t.message}")
                    break
                }
                executor.execute { handleConnection(client) }
            }
        }, "widget-bridge-accept").apply {
            isDaemon = true
            start()
        }
        callLog("[WidgetBridge] started on port=$port (host=$hostHtmlUrl, ws=$wsUrl)")
    }

    fun forwardSyncEvent(rawEvent: kotlinx.serialization.json.JsonObject) {
        val s = session ?: return
        runCatching { s.sendText(s.handler.forwardSyncEvent(rawEvent)) }
            .onFailure { callLog("[WidgetBridge] forwardSyncEvent failed: ${it.message}") }
    }

    fun forwardToDeviceEvent(rawEvent: kotlinx.serialization.json.JsonObject) {
        val s = session ?: return
        runCatching { s.sendText(s.handler.forwardToDeviceEvent(rawEvent)) }
            .onFailure { callLog("[WidgetBridge] forwardToDeviceEvent failed: ${it.message}") }
    }

    override fun close() {
        running = false
        // Flush any pending MSC4140 delayed events (the EC "dead man's switch"
        // disconnect/leave state events). EC counts on the homeserver firing
        // them automatically — we have to commit them ourselves on teardown,
        // otherwise the user's membership state stays as a ghost forever.
        val s = session
        if (s != null) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val committed = s.handler.flushPendingDelayedEvents()
                    if (committed > 0) {
                        callLog("[WidgetBridge] close: flushed $committed pending delayed event(s) before teardown")
                    }
                }
            }.onFailure { callLog("[WidgetBridge] close: flushPendingDelayedEvents failed: ${it.message}") }
        }
        runCatching { session?.close() }
        runCatching { serverSocket.close() }
        runCatching { executor.shutdownNow() }
        scope.cancel()
        callLog("[WidgetBridge] stopped")
    }

    // ---------------- Connection dispatch ----------------

    private fun handleConnection(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 0
            val input = client.getInputStream()
            val out = client.getOutputStream()

            // Читаем request line + headers (только до \r\n\r\n).
            val (requestLine, headers) = readHttpHead(input) ?: run {
                runCatching { client.close() }
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) { runCatching { client.close() }; return }
            val method = parts[0]
            val path = parts[1]

            when {
                method == "GET" && (path == "/widget-host.html" || path.startsWith("/widget-host.html?")) -> {
                    handleHostHtml(out)
                    runCatching { client.close() }
                }
                method == "GET" && (path == "/widget-bus" || path.startsWith("/widget-bus?")) -> {
                    handleWsUpgrade(client, headers, out)
                    // НЕ закрываем сокет: WidgetSession владеет им.
                }
                else -> {
                    write404(out)
                    runCatching { client.close() }
                }
            }
        } catch (t: Throwable) {
            callLog("[WidgetBridge] connection error: ${t.message}")
            runCatching { client.close() }
        }
    }

    private fun readHttpHead(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = StringBuilder()
        var prev = -1
        var prevPrev = -1
        var prevPrevPrev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buf.append(b.toChar())
            if (prevPrevPrev == '\r'.code && prevPrev == '\n'.code && prev == '\r'.code && b == '\n'.code) break
            prevPrevPrev = prevPrev
            prevPrev = prev
            prev = b
            if (buf.length > 64 * 1024) return null
        }
        val raw = buf.toString()
        val lines = raw.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0]
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }
        return requestLine to headers
    }

    private fun write404(out: OutputStream) {
        val body = "Not Found".toByteArray(StandardCharsets.UTF_8)
        val resp = buildString {
            append("HTTP/1.1 404 Not Found\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(resp.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    // ---------------- HTTP: widget-host.html ----------------

    private fun handleHostHtml(out: OutputStream) {
        try {
            val template = loadHostHtmlTemplate()
            val rendered = template
                .replace("__ELEMENT_CALL_URL__", jsEscape(elementCallUrl))
                .replace("__WIDGET_ID__", jsEscape(widgetId))
                .replace("__BRIDGE_WS_URL__", jsEscape(wsUrl))
            val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
            val resp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Permissions-Policy: camera=*, microphone=*, autoplay=*, display-capture=*, fullscreen=*\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(resp.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        } catch (t: Throwable) {
            callLog("[WidgetBridge] host-html error: ${t.message}")
            val msg = ("error: " + t.message).toByteArray(StandardCharsets.UTF_8)
            val resp = buildString {
                append("HTTP/1.1 500 Internal Server Error\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${msg.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            runCatching {
                out.write(resp.toByteArray(StandardCharsets.US_ASCII))
                out.write(msg)
                out.flush()
            }
        }
    }

    private fun loadHostHtmlTemplate(): String {
        val cl = WidgetBridgeServer::class.java.classLoader
        val stream = cl.getResourceAsStream("widget-host.html")
            ?: error("widget-host.html resource not found in desktopMain/resources")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    // ---------------- WebSocket upgrade ----------------

    private fun handleWsUpgrade(socket: Socket, headers: Map<String, String>, out: OutputStream) {
        val key = headers["sec-websocket-key"]
        val upgrade = headers["upgrade"]?.lowercase()
        if (key.isNullOrBlank() || upgrade != "websocket") {
            val msg = "WebSocket upgrade required".toByteArray(StandardCharsets.UTF_8)
            val resp = buildString {
                append("HTTP/1.1 400 Bad Request\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${msg.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            runCatching {
                out.write(resp.toByteArray(StandardCharsets.US_ASCII))
                out.write(msg)
                out.flush()
                socket.close()
            }
            return
        }

        val accept = computeAcceptKey(key)
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }

        try {
            out.write(response.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val handler = handlerFactory()
            val newSession = WidgetSession(socket, handler, scope)
            session = newSession
            onConnected(newSession)
            newSession.startReadLoop()
            callLog("[WidgetBridge] WS connected widgetId=${handler.widgetId}")
        } catch (t: Throwable) {
            callLog("[WidgetBridge] WS upgrade failed: ${t.message}")
            runCatching { socket.close() }
        }
    }

    private fun computeAcceptKey(clientKey: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest((clientKey + magic).toByteArray(StandardCharsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
    }

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    // ---------------- Session (WebSocket frames) ----------------

    inner class WidgetSession internal constructor(
        private val socket: Socket,
        val handler: WidgetApiHandler,
        private val parentScope: CoroutineScope,
    ) {
        private val out: OutputStream = socket.getOutputStream()
        private val input = socket.getInputStream()
        private var readJob: Job? = null

        @Synchronized
        fun sendText(text: String) {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val frame = ArrayList<Byte>(payload.size + 14)
            frame.add(0x81.toByte()) // FIN + text
            when {
                payload.size < 126 -> frame.add(payload.size.toByte())
                payload.size < 65536 -> {
                    frame.add(126.toByte())
                    frame.add(((payload.size shr 8) and 0xFF).toByte())
                    frame.add((payload.size and 0xFF).toByte())
                }
                else -> {
                    frame.add(127.toByte())
                    for (i in 7 downTo 0) {
                        frame.add(((payload.size.toLong() shr (8 * i)) and 0xFF).toByte())
                    }
                }
            }
            out.write(frame.toByteArray())
            out.write(payload)
            out.flush()
        }

        fun startReadLoop() {
            readJob = parentScope.launch {
                try {
                    while (!socket.isClosed) {
                        val frame = readFrame() ?: break
                        if (frame.opcode == 0x8) break
                        if (frame.opcode == 0x1 || frame.opcode == 0x0) {
                            val text = String(frame.payload, StandardCharsets.UTF_8)
                            if (text.contains("telecrypt-webrtc-stats")) {
                                runCatching { onWebRtcStats(text) }
                                continue
                            }
                            val replies = runCatching { handler.handleMessage(text) }
                                .getOrElse {
                                    callLog("[WidgetBridge] handler error: ${it.message}")
                                    emptyList()
                                }
                            for (reply in replies) {
                                runCatching { sendText(reply) }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    callLog("[WidgetBridge] read loop ended: ${t.message}")
                } finally {
                    close()
                }
            }
        }

        private fun readFrame(): Frame? {
            val header = ByteArray(2)
            if (!readFully(header)) return null
            val opcode = header[0].toInt() and 0x0F
            val masked = (header[1].toInt() and 0x80) != 0
            var len = (header[1].toInt() and 0x7F).toLong()
            when (len.toInt()) {
                126 -> {
                    val ext = ByteArray(2); if (!readFully(ext)) return null
                    len = ((ext[0].toInt() and 0xFF).toLong() shl 8) or (ext[1].toInt() and 0xFF).toLong()
                }
                127 -> {
                    val ext = ByteArray(8); if (!readFully(ext)) return null
                    len = 0L
                    for (i in 0..7) len = (len shl 8) or (ext[i].toInt() and 0xFF).toLong()
                }
            }
            val maskKey = if (masked) {
                val mk = ByteArray(4); if (!readFully(mk)) return null; mk
            } else null
            val safeLen = minOf(len, 1024L * 1024L).toInt()
            val payload = ByteArray(safeLen)
            if (!readFully(payload)) return null
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            return Frame(opcode, payload)
        }

        private fun readFully(buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n == -1) return false
                off += n
            }
            return true
        }

        fun close() {
            runCatching { readJob?.cancel() }
            runCatching { socket.close() }
            if (session === this) session = null
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)
}
