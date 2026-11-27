package com.will.ambienthome.data

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Лёгкий HTTP/1.1 сервер на чистом Kotlin (ServerSocket) для LAN Web-пульта Ambient Home 3.0.2.
 * 0.0% CPU в простое (блокирующий accept()), ограниченный пул daemon-потоков (4 + очередь 32 -> 503),
 * общие дедлайны заголовков/тела (защита от Slowloris), Expect: 100-continue и строгие заголовки безопасности.
 */
class LanWebRemoteServer(
    private val port: Int,
    private val router: LanWebRemoteRouter,
    private val socketTimeoutMs: Int = SOCKET_TIMEOUT_MS,
    private val headerDeadlineMs: Long = HEADER_DEADLINE_MS,
    private val bodyDeadlineMs: Long = BODY_DEADLINE_MS,
    private val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    private val maxQueue: Int = DEFAULT_MAX_QUEUE
) {

    private companion object {
        const val TAG = "AmbientHome"
        const val SOCKET_TIMEOUT_MS = 6000
        const val HEADER_DEADLINE_MS = 10_000L
        const val BODY_DEADLINE_MS = 30_000L
        const val DEFAULT_MAX_WORKERS = 4
        const val DEFAULT_MAX_QUEUE = 32
        const val WORKER_IDLE_TIMEOUT_SEC = 30L
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADER_LINES = 64
        const val CSP_HEADER =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }

    @Volatile
    private var running: Boolean = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool = newPool()

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            if (workerPool.isShutdown) workerPool = newPool()
            running = true
            acceptThread = Thread({ acceptLoop(ss) }, "ambient-web-accept").apply {
                isDaemon = true
                start()
            }
            safeLogInfo("LanWebRemoteServer started on :${ss.localPort}")
            true
        } catch (t: Throwable) {
            safeLogWarn("LanWebRemoteServer start failed on :$port", t)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread = null
        workerPool.shutdownNow()
    }

    fun isRunning(): Boolean = running

    internal fun boundPort(): Int = serverSocket?.localPort ?: port

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                if (running) safeLogWarn("ServerSocket accept error", t)
                break
            }
            try {
                workerPool.execute { handleClient(client) }
            } catch (_: Throwable) {
                rejectBusyClient(client)
            }
        }
    }

    private fun rejectBusyClient(client: Socket) {
        try {
            if (running) {
                client.soTimeout = 1000
                writeHttpResponse(
                    client.getOutputStream(),
                    LanWebRemoteRouter.HttpResponse(
                        503,
                        "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"service_unavailable\"}".toByteArray(Charsets.UTF_8)
                    )
                )
                try {
                    client.shutdownOutput()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = socketTimeoutMs
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = socket.getOutputStream()
            val response = when (val parsed = readHttpRequest(socket, input, output)) {
                ParseOutcome.Eof -> return
                is ParseOutcome.Ok -> router.routePreAuthenticated(parsed.request)
                is ParseOutcome.Error -> LanWebRemoteRouter.HttpResponse(
                    parsed.status,
                    "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"${parsed.code}\"}".toByteArray(Charsets.UTF_8)
                )
            }
            writeHttpResponse(output, response)
            try {
                socket.shutdownOutput()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private sealed class ParseOutcome {
        data object Eof : ParseOutcome()
        data class Ok(val request: LanWebRemoteRouter.HttpRequest) : ParseOutcome()
        data class Error(val status: Int, val code: String) : ParseOutcome()
    }

    private sealed class HeaderBlock {
        data object Eof : HeaderBlock()
        data object TooLarge : HeaderBlock()
        data object Timeout : HeaderBlock()
        data object Incomplete : HeaderBlock()
        data class Text(val value: String) : HeaderBlock()
    }

    private sealed class BodyRead {
        data object Incomplete : BodyRead()
        data object Timeout : BodyRead()
        data class Bytes(val value: ByteArray) : BodyRead()
    }

    private fun readHttpRequest(socket: Socket, input: InputStream, output: OutputStream): ParseOutcome {
        val headerText = when (val hb = readHeaderBlock(socket, input)) {
            HeaderBlock.Eof -> return ParseOutcome.Eof
            HeaderBlock.TooLarge -> return ParseOutcome.Error(431, "header_too_large")
            HeaderBlock.Timeout -> return ParseOutcome.Error(408, "request_timeout")
            HeaderBlock.Incomplete -> return ParseOutcome.Error(400, "incomplete_headers")
            is HeaderBlock.Text -> hb.value
        }
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return ParseOutcome.Error(400, "empty_request")
        if (lines.size > MAX_HEADER_LINES) return ParseOutcome.Error(431, "too_many_headers")
        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return ParseOutcome.Error(400, "bad_request_line")
        val method = reqParts[0].trim().uppercase()
        val (path, query) = splitTarget(reqParts[1].trim())
        val headers = parseHeaders(lines) ?: return ParseOutcome.Error(400, "invalid_headers")
        when (router.checkAuthForPath(path, headers)) {
            429 -> return ParseOutcome.Error(429, "rate_limited")
            401 -> return ParseOutcome.Error(401, "unauthorized")
        }
        return readBodyAndBuild(socket, input, output, method, path, query, headers)
    }

    private fun parseHeaders(lines: List<String>): LinkedHashMap<String, String>? {
        val headers = LinkedHashMap<String, String>()
        var clCount = 0
        for (i in 1 until lines.size) {
            val ln = lines[i]
            val idx = ln.indexOf(':')
            if (idx > 0) {
                val k = ln.substring(0, idx).trim()
                val v = ln.substring(idx + 1).trim()
                if (k.equals("Transfer-Encoding", ignoreCase = true)) return null
                if (k.equals("Content-Length", ignoreCase = true)) clCount++
                headers[k] = v
            }
        }
        return if (clCount > 1) null else headers
    }

    private fun readBodyAndBuild(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ): ParseOutcome {
        val contentLength = readContentLength(headers) ?: return ParseOutcome.Error(400, "bad_content_length")
        val maxBody = if (path == "/api/wallpaper") {
            LanWebRemoteRouter.MAX_UPLOAD_BYTES
        } else {
            LanWebRemoteRouter.MAX_JSON_BYTES
        }
        if (contentLength > maxBody) return ParseOutcome.Error(413, "payload_too_large")
        if (contentLength > 0 && expectsContinue(headers)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        val body = if (contentLength > 0) {
            when (val res = readExactWithDeadline(socket, input, contentLength)) {
                BodyRead.Timeout -> return ParseOutcome.Error(408, "request_timeout")
                BodyRead.Incomplete -> return ParseOutcome.Error(400, "incomplete_body")
                is BodyRead.Bytes -> res.value
            }
        } else ByteArray(0)
        return ParseOutcome.Ok(LanWebRemoteRouter.HttpRequest(method, path, query, headers, body))
    }

    private fun readHeaderBlock(socket: Socket, input: InputStream): HeaderBlock {
        val out = ByteArrayOutputStream(1024)
        val deadlineNs = System.nanoTime() + headerDeadlineMs * 1_000_000L
        var state = 0
        while (out.size() < MAX_HEADER_BYTES) {
            if (input.available() == 0 && !applyRemainingTimeout(socket, deadlineNs)) return HeaderBlock.Timeout
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return HeaderBlock.Timeout
            }
            if (b < 0) return if (out.size() == 0) HeaderBlock.Eof else HeaderBlock.Incomplete
            out.write(b)
            state = nextCrlfState(state, b)
            if (state == 4) return HeaderBlock.Text(out.toByteArray().toString(Charsets.ISO_8859_1))
        }
        return HeaderBlock.TooLarge
    }

    private fun nextCrlfState(state: Int, b: Int): Int = when {
        state == 0 && b == '\r'.code -> 1
        state == 1 && b == '\n'.code -> 2
        state == 2 && b == '\r'.code -> 3
        state == 3 && b == '\n'.code -> 4
        b == '\r'.code -> 1
        else -> 0
    }

    private fun readExactWithDeadline(socket: Socket, input: InputStream, size: Int): BodyRead {
        val buf = ByteArray(size)
        val deadlineNs = System.nanoTime() + bodyDeadlineMs * 1_000_000L
        var off = 0
        while (off < size) {
            if (input.available() == 0 && !applyRemainingTimeout(socket, deadlineNs)) return BodyRead.Timeout
            val n = try {
                input.read(buf, off, size - off)
            } catch (_: SocketTimeoutException) {
                return BodyRead.Timeout
            }
            if (n <= 0) return BodyRead.Incomplete
            off += n
        }
        return BodyRead.Bytes(buf)
    }

    private fun applyRemainingTimeout(socket: Socket, deadlineNs: Long): Boolean {
        val remMs = (deadlineNs - System.nanoTime()) / 1_000_000L
        if (remMs <= 0L) return false
        socket.soTimeout = remMs.coerceIn(1L, socketTimeoutMs.toLong()).toInt()
        return true
    }

    private fun expectsContinue(headers: Map<String, String>): Boolean {
        for ((k, v) in headers) {
            if (k.equals("Expect", ignoreCase = true) && v.equals("100-continue", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun readContentLength(headers: Map<String, String>): Int? {
        for ((k, v) in headers) {
            if (k.equals("Content-Length", ignoreCase = true)) {
                val n = v.toIntOrNull() ?: return null
                return if (n < 0) null else n
            }
        }
        return 0
    }

    private fun splitTarget(raw: String): Pair<String, Map<String, String>> {
        val qIdx = raw.indexOf('?')
        if (qIdx < 0) return raw to emptyMap()
        val path = raw.substring(0, qIdx)
        val qs = raw.substring(qIdx + 1)
        val params = LinkedHashMap<String, String>()
        for (pair in qs.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decodeQuery(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = decodeQuery(if (eq >= 0) pair.substring(eq + 1) else "")
            if (k.isNotEmpty()) params[k] = v
        }
        return path to params
    }

    private fun decodeQuery(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun writeHttpResponse(output: OutputStream, res: LanWebRemoteRouter.HttpResponse) {
        val reason = when (res.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${res.status} $reason\r\n" +
            "Content-Type: ${res.contentType}\r\n" +
            "Content-Length: ${res.body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: $CSP_HEADER\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(res.body)
        output.flush()
    }

    private fun safeLogInfo(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun safeLogWarn(msg: String, t: Throwable) {
        try {
            Log.w(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    private fun newPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        maxWorkers.coerceAtLeast(1),
        maxWorkers.coerceAtLeast(1),
        WORKER_IDLE_TIMEOUT_SEC,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(maxQueue.coerceAtLeast(1)),
        ThreadFactory { r -> Thread(r, "ambient-web-worker").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
}
