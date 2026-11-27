package com.will.ambienthome.data

import com.will.ambienthome.model.MiniJson
import java.io.File
import java.io.FileInputStream

/**
 * Чистый маршрутизатор и шлюз безопасности LAN Web-пульта Ambient Home 3.0.0.
 * 100% JVM-тестируемый (0 Android-вызовов):
 * - Constant-time проверка 6-значного PIN в заголовке X-Auth-Token для всех /api/ маршрутов (кроме /api/ping);
 * - Жёсткие лимиты размера (64 КБ JSON, 10 МБ обои), валидация имени файла и magic bytes JPEG/PNG.
 */
class LanWebRemoteRouter(
    private val backend: Backend,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0)
    )

    data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray
    )

    interface Backend {
        fun currentPin(): String
        fun htmlShell(): ByteArray
        fun statusJson(): Map<String, Any?>
        fun dispatchKeyOrText(keyCode: Int?, text: String?): Pair<Boolean, String>
        fun dispatchApp(action: String, pkg: String): Pair<Boolean, String>
        fun dispatchAction(action: String, params: Map<String, Any?>): Pair<Boolean, String>
        fun saveWallpaper(safeFileName: String, bytes: ByteArray): Pair<Boolean, String>
    }

    private var consecutiveAuthFailures: Int = 0
    private var authLockoutUntilMs: Long = 0L

    @Synchronized
    fun checkAuthForPath(path: String, headers: Map<String, String>): Int {
        if (!path.startsWith("/api/") || path == "/api/ping") return 0
        val now = clockMs()
        if (authLockoutUntilMs > 0L) {
            if (now < authLockoutUntilMs) return 429
            authLockoutUntilMs = 0L
            consecutiveAuthFailures = 0
        }
        if (isAuthorizedHeaders(headers)) {
            consecutiveAuthFailures = 0
            return 0
        }
        consecutiveAuthFailures++
        if (consecutiveAuthFailures >= MAX_AUTH_FAILURES) {
            authLockoutUntilMs = now + AUTH_LOCKOUT_MS
        }
        return 401
    }

    fun route(req: HttpRequest): HttpResponse {
        val method = req.method.uppercase()
        val path = req.path
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            return HttpResponse(200, CT_HTML, backend.htmlShell())
        }
        if (method == "GET" && path == "/api/ping") {
            return jsonResponse(200, mapOf("ok" to true, "app" to "AmbientHome", "version" to "3.0.1"))
        }
        if (!path.startsWith("/api/")) {
            return jsonResponse(404, mapOf("ok" to false, "error" to "not_found"))
        }
        when (checkAuthForPath(path, req.headers)) {
            429 -> return jsonResponse(429, mapOf("ok" to false, "error" to "rate_limited"))
            401 -> return jsonResponse(401, mapOf("ok" to false, "error" to "unauthorized"))
        }
        return routeAuthorizedApi(method, path, req)
    }

    internal fun routePreAuthenticated(req: HttpRequest): HttpResponse {
        val method = req.method.uppercase()
        val path = req.path
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            return HttpResponse(200, CT_HTML, backend.htmlShell())
        }
        if (method == "GET" && path == "/api/ping") {
            return jsonResponse(200, mapOf("ok" to true, "app" to "AmbientHome", "version" to "3.0.1"))
        }
        if (!path.startsWith("/api/")) {
            return jsonResponse(404, mapOf("ok" to false, "error" to "not_found"))
        }
        return routeAuthorizedApi(method, path, req)
    }

    private fun routeAuthorizedApi(method: String, path: String, req: HttpRequest): HttpResponse {
        return when {
            method == "GET" && path == "/api/status" -> jsonResponse(200, backend.statusJson())
            method == "POST" && path == "/api/key" -> handleKey(req)
            method == "POST" && path == "/api/text" -> handleText(req)
            method == "POST" && path == "/api/launch" -> handleApp(req, "launch")
            method == "POST" && path == "/api/forcestop" -> handleApp(req, "forcestop")
            method == "POST" && path == "/api/action" -> handleAction(req)
            method == "POST" && path == "/api/config" -> handleConfig(req)
            method == "POST" && path == "/api/wallpaper" -> handleWallpaper(req)
            else -> jsonResponse(404, mapOf("ok" to false, "error" to "not_found"))
        }
    }

    private fun handleKey(req: HttpRequest): HttpResponse {
        val obj = parseJsonBody(req) ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_json"))
        val code = MiniJson.asInt(obj["code"])
        if (code == null || code !in 1..300) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_keycode"))
        }
        return resultResponse(backend.dispatchKeyOrText(keyCode = code, text = null))
    }

    private fun handleText(req: HttpRequest): HttpResponse {
        val obj = parseJsonBody(req) ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_json"))
        val text = obj["text"] as? String
        if (text.isNullOrEmpty() || text.length > MAX_TEXT_CHARS) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_text"))
        }
        return resultResponse(backend.dispatchKeyOrText(keyCode = null, text = text))
    }

    private fun handleApp(req: HttpRequest, mode: String): HttpResponse {
        val obj = parseJsonBody(req) ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_json"))
        val pkg = (obj["package"] as? String)?.trim().orEmpty()
        if (!isValidPackageName(pkg)) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_package"))
        }
        return resultResponse(backend.dispatchApp(mode, pkg))
    }

    private fun handleAction(req: HttpRequest): HttpResponse {
        val obj = parseJsonBody(req) ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_json"))
        val action = (obj["action"] as? String)?.trim()?.lowercase().orEmpty()
        if (action.isEmpty() || action.length > 48) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_action"))
        }
        return resultResponse(backend.dispatchAction(action, obj))
    }

    private fun handleConfig(req: HttpRequest): HttpResponse {
        val obj = parseJsonBody(req) ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_json"))
        return resultResponse(backend.dispatchAction("config", obj))
    }

    private fun handleWallpaper(req: HttpRequest): HttpResponse {
        if (req.body.isEmpty()) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "empty_upload"))
        }
        if (req.body.size > MAX_UPLOAD_BYTES) {
            return jsonResponse(413, mapOf("ok" to false, "error" to "payload_too_large"))
        }
        val rawName = headerIgnoreCase(req.headers, "X-Filename") ?: req.query["name"].orEmpty()
        val safeName = sanitizeWallpaperFilename(rawName)
            ?: return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_filename"))
        if (!hasValidImageMagic(req.body, safeName)) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "invalid_image_magic"))
        }
        return resultResponse(backend.saveWallpaper(safeName, req.body))
    }

    private fun isAuthorizedHeaders(headers: Map<String, String>): Boolean {
        val expected = backend.currentPin().trim()
        if (expected.length != 6) return false
        val provided = headerIgnoreCase(headers, "X-Auth-Token")?.trim().orEmpty()
        return constantTimeEquals(expected, provided)
    }

    private fun parseJsonBody(req: HttpRequest): Map<String, Any?>? {
        if (req.body.size > MAX_JSON_BYTES) return null
        val text = req.body.toString(Charsets.UTF_8)
        return MiniJson.parseObject(text)
    }

    private fun resultResponse(res: Pair<Boolean, String>): HttpResponse {
        val code = if (res.first) 200 else 400
        return jsonResponse(code, mapOf("ok" to res.first, "message" to res.second))
    }

    private fun jsonResponse(status: Int, payload: Map<String, Any?>): HttpResponse =
        HttpResponse(status, CT_JSON, MiniJson.stringify(payload).toByteArray(Charsets.UTF_8))

    private fun headerIgnoreCase(headers: Map<String, String>, name: String): String? {
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true)) return v
        }
        return null
    }

    companion object {
        const val MAX_JSON_BYTES = 64 * 1024
        const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
        const val MAX_AUTH_FAILURES = 5
        const val AUTH_LOCKOUT_MS = 15_000L
        private const val MAX_TEXT_CHARS = 2000
        private const val CT_JSON = "application/json; charset=utf-8"
        private const val CT_HTML = "text/html; charset=utf-8"
        private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._]{3,128}$")
        private val SAFE_FILENAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}\\.(jpg|jpeg|png)$")
        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        internal fun isValidPackageName(pkg: String): Boolean =
            SAFE_PKG_REGEX.matches(pkg) &&
                pkg.contains('.') &&
                !pkg.startsWith('.') &&
                !pkg.endsWith('.') &&
                !pkg.contains("..")

        /** Сравнение строк за постоянное время (защита от timing-атак на PIN). */
        fun constantTimeEquals(expected: String, actual: String): Boolean {
            val a = expected.toByteArray(Charsets.UTF_8)
            val b = actual.toByteArray(Charsets.UTF_8)
            var diff = a.size xor b.size
            val len = maxOf(a.size, b.size)
            for (i in 0 until len) {
                val x = if (i < a.size) a[i].toInt() else 0
                val y = if (i < b.size) b[i].toInt() else 0
                diff = diff or (x xor y)
            }
            return diff == 0 && a.isNotEmpty()
        }

        /** Строгая санитизация имени файла обоев: запрещает .., /, \, пробелы и лишние расширения. */
        fun sanitizeWallpaperFilename(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 70) return null
            if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) {
                return null
            }
            val lower = trimmed.lowercase()
            if (!SAFE_FILENAME_REGEX.matches(lower)) return null
            val dot = trimmed.lastIndexOf('.')
            val stem = trimmed.substring(0, dot)
            val ext = trimmed.substring(dot + 1).lowercase()
            return "$stem.$ext"
        }

        /** Проверка сигнатурных байтов JPEG (FF D8 FF) или PNG (89 50 4E 47 0D 0A 1A 0A). */
        fun hasValidImageMagic(bytes: ByteArray, safeFileName: String): Boolean {
            val ext = safeFileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> bytes.size >= 3 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
                "png" -> {
                    if (bytes.size < PNG_MAGIC.size) return false
                    for (i in PNG_MAGIC.indices) {
                        if (bytes[i] != PNG_MAGIC[i]) return false
                    }
                    true
                }
                "webp" -> bytes.size >= 12 &&
                    bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
                else -> false
            }
        }

        internal fun hasValidImageFileHeader(
            file: File,
            minBytes: Long = 64L,
            maxBytes: Long = 35_000_000L
        ): Boolean {
            if (!file.isFile || !file.canRead()) return false
            val len = file.length()
            if (len !in minBytes..maxBytes) return false
            val head = ByteArray(12)
            val read = try {
                FileInputStream(file).use { stream ->
                    var off = 0
                    while (off < head.size) {
                        val n = stream.read(head, off, head.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    off
                }
            } catch (_: Throwable) {
                return false
            }
            return hasValidImageMagic(head.copyOf(read), file.name)
        }
    }
}
