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
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Лёгкий HTTP/1.1 сервер на чистом Kotlin (ServerSocket) для LAN Web-пульта Ambient Home 3.0.0.
 * 0.0% CPU в простое (блокирующий accept()), пул из 4 daemon-потоков, жёсткие лимиты заголовков/тела.
 */
class LanWebRemoteServer(
    private val port: Int,
    private val router: LanWebRemoteRouter
) {

    private companion object {
        const val TAG = "AmbientHome"
        const val SOCKET_TIMEOUT_MS = 6000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADER_LINES = 64
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
            Log.i(TAG, "LanWebRemoteServer started on :$port")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "LanWebRemoteServer start failed on :$port", t)
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

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "ServerSocket accept error", t)
                break
            }
            try {
                workerPool.execute { handleClient(client) }
            } catch (_: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = socket.getOutputStream()
            val parsed = readHttpRequest(input)
            val response = when (parsed) {
                is ParseOutcome.Ok -> router.route(parsed.request)
                is ParseOutcome.Error -> LanWebRemoteRouter.HttpResponse(
                    parsed.status,
                    "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"${parsed.code}\"}".toByteArray(Charsets.UTF_8)
                )
            }
            writeHttpResponse(output, response)
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private sealed class ParseOutcome {
        data class Ok(val request: LanWebRemoteRouter.HttpRequest) : ParseOutcome()
        data class Error(val status: Int, val code: String) : ParseOutcome()
    }

    private fun readHttpRequest(input: InputStream): ParseOutcome {
        val headerText = readHeaderBlock(input) ?: return ParseOutcome.Error(431, "header_too_large")
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return ParseOutcome.Error(400, "empty_request")
        if (lines.size > MAX_HEADER_LINES) return ParseOutcome.Error(431, "too_many_headers")
        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return ParseOutcome.Error(400, "bad_request_line")
        val method = reqParts[0].trim().uppercase()
        val (path, query) = splitTarget(reqParts[1].trim())

        val headers = LinkedHashMap<String, String>()
        var clCount = 0
        for (i in 1 until lines.size) {
            val ln = lines[i]
            val idx = ln.indexOf(':')
            if (idx > 0) {
                val k = ln.substring(0, idx).trim()
                val v = ln.substring(idx + 1).trim()
                if (k.equals("Transfer-Encoding", ignoreCase = true)) return ParseOutcome.Error(400, "chunked_unsupported")
                if (k.equals("Content-Length", ignoreCase = true)) clCount++
                headers[k] = v
            }
        }
        if (clCount > 1) return ParseOutcome.Error(400, "duplicate_content_length")
        when (router.checkAuthForPath(path, headers)) {
            429 -> return ParseOutcome.Error(429, "rate_limited")
            401 -> return ParseOutcome.Error(401, "unauthorized")
        }
        val contentLength = readContentLength(headers) ?: return ParseOutcome.Error(400, "bad_content_length")
        val maxBody = if (path == "/api/wallpaper") {
            LanWebRemoteRouter.MAX_UPLOAD_BYTES
        } else {
            LanWebRemoteRouter.MAX_JSON_BYTES
        }
        if (contentLength > maxBody) return ParseOutcome.Error(413, "payload_too_large")
        val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
        if (body == null) return ParseOutcome.Error(400, "incomplete_body")
        return ParseOutcome.Ok(LanWebRemoteRouter.HttpRequest(method, path, query, headers, body))
    }

    private fun readHeaderBlock(input: InputStream): String? {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray().toString(Charsets.ISO_8859_1)
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
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

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(buf, off, size - off)
            if (n <= 0) return null
            off += n
        }
        return buf
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
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${res.status} $reason\r\n" +
            "Content-Type: ${res.contentType}\r\n" +
            "Content-Length: ${res.body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(res.body)
        output.flush()
    }

    private fun newPool() = Executors.newFixedThreadPool(4, ThreadFactory { r ->
        Thread(r, "ambient-web-worker").apply { isDaemon = true }
    })
}
