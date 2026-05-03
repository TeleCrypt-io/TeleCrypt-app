package de.connect2x.tammy.telecryptModules.call.widgetBridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Локальный HTTP+WebSocket сервер, который Chrome загружает как «host page»
 * для Element Call в widget‑режиме.
 *
 * URLs:
 *   GET /widget-host.html?widgetId=...&...   — отдаёт HTML с подставленными плейсхолдерами
 *   GET /widget-bus                          — апгрейд в WebSocket; шина между EC и Kotlin
 *
 * Сервер биндится на 127.0.0.1 на свободный порт (port=0).
 *
 * Жизненный цикл — на один звонок: создаётся в [CallCoordinatorImpl.startCall/joinCall],
 * закрывается при завершении звонка (или вместе с приложением).
 */
class WidgetBridgeServer(
    private val widgetId: String,
    private val elementCallUrl: String,
    /** Создаётся при первом подключении WS, чтобы знать roomId/userId/deviceId до открытия. */
    private val handlerFactory: () -> WidgetApiHandler,
    /** Колбэк, чтобы наружу можно было отдать активную сессию (для forward sync events). */
    private val onConnected: (WidgetSession) -> Unit = {},
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { r ->
            Thread(r, "widget-bridge").apply { isDaemon = true }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: WidgetSession? = null

    val port: Int get() = server.address.port
    val hostHtmlUrl: String get() = "http://127.0.0.1:$port/widget-host.html"
    val wsUrl: String get() = "ws://127.0.0.1:$port/widget-bus"

    fun start() {
        server.createContext("/widget-host.html") { ex -> handleHostHtml(ex) }
        server.createContext("/widget-bus")        { ex -> handleWsUpgrade(ex) }
        server.start()
        println("[WidgetBridge] started on port=$port (host=$hostHtmlUrl, ws=$wsUrl)")
    }

    /**
     * Передать в widget событие, прилетевшее из Matrix sync.
     * Безопасно вызывать до connect — просто игнорируется.
     */
    fun forwardSyncEvent(rawEvent: kotlinx.serialization.json.JsonObject) {
        val s = session ?: return
        runCatching { s.sendText(s.handler.forwardSyncEvent(rawEvent)) }
            .onFailure { println("[WidgetBridge] forwardSyncEvent failed: ${it.message}") }
    }

    fun forwardToDeviceEvent(rawEvent: kotlinx.serialization.json.JsonObject) {
        val s = session ?: return
        runCatching { s.sendText(s.handler.forwardToDeviceEvent(rawEvent)) }
            .onFailure { println("[WidgetBridge] forwardToDeviceEvent failed: ${it.message}") }
    }

    override fun close() {
        runCatching { session?.close() }
        runCatching { server.stop(0) }
        scope.cancel()
        println("[WidgetBridge] stopped")
    }

    // ---------------- HTTP: widget-host.html ----------------

    private fun handleHostHtml(ex: HttpExchange) {
        try {
            val template = loadHostHtmlTemplate()
            val rendered = template
                .replace("__ELEMENT_CALL_URL__", jsEscape(elementCallUrl))
                .replace("__WIDGET_ID__",        jsEscape(widgetId))
                .replace("__BRIDGE_WS_URL__",    jsEscape(wsUrl))
            val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.responseHeaders.add("Cache-Control", "no-store")
            // Permissions-Policy, чтобы getUserMedia точно работал в iframe.
            ex.responseHeaders.add(
                "Permissions-Policy",
                "camera=*, microphone=*, autoplay=*, display-capture=*, fullscreen=*"
            )
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (t: Throwable) {
            println("[WidgetBridge] host-html error: ${t.message}")
            val msg = ("error: " + t.message).toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(500, msg.size.toLong())
            ex.responseBody.use { it.write(msg) }
        }
    }

    private fun loadHostHtmlTemplate(): String {
        val cl = WidgetBridgeServer::class.java.classLoader
        val stream = cl.getResourceAsStream("widget-host.html")
            ?: error("widget-host.html resource not found in desktopMain/resources")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    // ---------------- HTTP → WebSocket upgrade ----------------

    /**
     * `com.sun.net.httpserver.HttpServer` не поддерживает WebSocket нативно,
     * но даёт сырой Socket через рефлексию. Мы обходимся без рефлексии, используя
     * уже отправленные заголовки Connection: Upgrade — а сам апгрейд делаем на
     * отдельном TCP-сервере.
     *
     * Простейшее решение: отдаём 426 от HttpServer, а WebSocket поднимаем на
     * том же порту через отдельный поток приёма? — к сожалению, порт занят
     * HttpServer'ом. Поэтому идём другим путём: WebSocket-апгрейд делаем
     * прямо здесь, выдёргивая транспорт через рефлексию HttpExchange.
     *
     * Это работает в Sun JDK / OpenJDK — поле "impl.tx" / "exchange.tx" хранит
     * `sun.net.httpserver.ExchangeImpl` → имеет ссылку на Socket.
     */
    private fun handleWsUpgrade(ex: HttpExchange) {
        val key = ex.requestHeaders.getFirst("Sec-WebSocket-Key")
        val upgrade = ex.requestHeaders.getFirst("Upgrade")?.lowercase()
        if (key.isNullOrBlank() || upgrade != "websocket") {
            val msg = "WebSocket upgrade required".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(400, msg.size.toLong())
            ex.responseBody.use { it.write(msg) }
            return
        }

        val socket = extractSocket(ex)
        if (socket == null) {
            val msg = "cannot extract socket".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(500, msg.size.toLong())
            ex.responseBody.use { it.write(msg) }
            return
        }

        // Готовим accept-key.
        val accept = computeAcceptKey(key)
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }

        try {
            // ВАЖНО: HttpServer уже мог что-то отправить в OutputStream — нужно
            // обойти его. Поэтому пишем в сырой Socket напрямую.
            val rawOut = socket.getOutputStream()
            rawOut.write(response.toByteArray(StandardCharsets.US_ASCII))
            rawOut.flush()

            val handler = handlerFactory()
            val newSession = WidgetSession(socket, handler, scope)
            session = newSession
            onConnected(newSession)
            newSession.startReadLoop()
            println("[WidgetBridge] WS connected widgetId=${handler.widgetId}")
        } catch (t: Throwable) {
            println("[WidgetBridge] WS upgrade failed: ${t.message}")
            runCatching { socket.close() }
        }
    }

    /**
     * Достаём `java.net.Socket` из HttpExchange через рефлексию (sun.net.httpserver.ExchangeImpl).
     */
    private fun extractSocket(ex: HttpExchange): Socket? {
        return runCatching {
            val implField = ex.javaClass.getDeclaredField("impl").apply { isAccessible = true }
            val impl = implField.get(ex)
            val connField = impl.javaClass.getDeclaredField("connection").apply { isAccessible = true }
            val connection = connField.get(impl)
            val socketField = connection.javaClass.superclass?.getDeclaredField("chan")
                ?: connection.javaClass.getDeclaredField("chan")
            socketField.isAccessible = true
            val chan = socketField.get(connection)
            // chan — SocketChannel, у него socket()
            val socketMethod = chan.javaClass.getMethod("socket")
            socketMethod.invoke(chan) as Socket
        }.onFailure { println("[WidgetBridge] reflection-extract socket failed: $it") }
            .getOrNull()
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
            // Server-to-client фреймы НЕ маскируются.
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
                        if (frame.opcode == 0x8) {
                            // close
                            break
                        }
                        if (frame.opcode == 0x1 || frame.opcode == 0x0) {
                            val text = String(frame.payload, StandardCharsets.UTF_8)
                            val replies = runCatching { handler.handleMessage(text) }
                                .getOrElse {
                                    println("[WidgetBridge] handler error: ${it.message}")
                                    emptyList()
                                }
                            for (reply in replies) {
                                runCatching { sendText(reply) }
                            }
                        }
                        // ping(0x9), pong(0xA) и прочее — игнорируем
                    }
                } catch (t: Throwable) {
                    println("[WidgetBridge] read loop ended: ${t.message}")
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
            // 1MB сап на фрейм
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
