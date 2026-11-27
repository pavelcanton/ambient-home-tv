package com.will.ambienthome.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.will.ambienthome.model.DailyWallpaperMeta
import com.will.ambienthome.model.MiniJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Поставщик «Фото дня» (Bing RU HPImageArchive + резервный Wikimedia Commons POTD).
 * Проверяет обновления не чаще 1 раза в 12 часов (и с backoff 15 мин при оффлайне) строго пока HOME активен;
 * кэш ограничен максимум 3 JPEG в cacheDir/wp_daily/ с оффлайн-фолбэком на встроенные обои.
 */
object OnlineWallpaperProvider {

    private const val TAG = "AmbientHome"
    private const val MAX_CACHED_IMAGES = 3
    internal const val REFRESH_INTERVAL_MS = 12L * 3600L * 1000L
    internal const val FAILURE_BACKOFF_MS = 15L * 60L * 1000L
    internal const val MAX_HTTP_JSON_BYTES = 262_144
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    private const val BING_API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=7&mkt=ru-RU"
    private const val WIKI_API =
        "https://commons.wikimedia.org/w/api.php?action=query&generator=images&titles=Template:Potd&gimlimit=4&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=1920&format=json"

    data class CachedWallpaper(
        val file: File,
        val caption: String
    )

    private val refreshLock = Any()

    @Volatile
    private var lastSuccessElapsedMs: Long = -1L

    @Volatile
    private var lastFailureElapsedMs: Long = -1L

    internal var elapsedClockMs: () -> Long = { safeElapsedMs() }

    fun listCached(ctx: Context): List<CachedWallpaper> {
        val dir = cacheDir(ctx)
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "jpg" && f.length() > 8_000L }
            ?: return emptyList()
        val captions = readCaptions(dir)
        val sorted = files.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        return sorted.take(MAX_CACHED_IMAGES).map { f ->
            CachedWallpaper(f, captions[f.name].orEmpty())
        }
    }

    fun cacheSignature(ctx: Context): String {
        val items = listCached(ctx)
        if (items.isEmpty()) return "daily:0"
        return "daily:" + items.joinToString("|") { "${it.file.name}:${it.file.length()}" }
    }

    fun refreshIfDue(ctx: Context, force: Boolean = false): Boolean = synchronized(refreshLock) {
        val now = elapsedClockMs()
        val hasCache = listCached(ctx).isNotEmpty()
        if (!shouldAttemptRefresh(now, hasCache, force)) {
            return@synchronized false
        }
        val metas = fetchDailyMetas()
        if (metas.isEmpty()) {
            recordAttemptResult(now, success = false)
            return@synchronized false
        }
        val dir = cacheDir(ctx)
        val captions = readCaptions(dir).toMutableMap()
        val downloaded = downloadMissing(dir, metas, captions)
        pruneOldCache(dir, captions)
        writeCaptions(dir, captions)
        val succeeded = downloaded || listCached(ctx).isNotEmpty()
        recordAttemptResult(now, success = succeeded)
        downloaded
    }

    fun parseBingJson(json: String): List<DailyWallpaperMeta> {
        val root = MiniJson.parseObject(json) ?: return emptyList()
        val images = root["images"] as? List<*> ?: return emptyList()
        val out = ArrayList<DailyWallpaperMeta>(images.size)
        for (raw in images) {
            val obj = raw as? Map<*, *> ?: continue
            val relUrl = (obj["url"] as? String)?.trim().orEmpty()
            if (relUrl.isEmpty()) continue
            val fullUrl = when {
                relUrl.startsWith("https://") -> relUrl
                relUrl.startsWith("/") -> "https://www.bing.com$relUrl"
                else -> continue
            }
            val dateKey = (obj["startdate"] as? String)?.trim().orEmpty().ifEmpty { "img${out.size}" }
            val title = (obj["title"] as? String)?.trim().orEmpty()
            val copyright = (obj["copyright"] as? String)?.substringBefore(" (")?.trim().orEmpty()
            val caption = when {
                title.isNotEmpty() && copyright.isNotEmpty() && !copyright.contains(title, ignoreCase = true) ->
                    "$title · $copyright"
                copyright.isNotEmpty() -> copyright
                else -> title
            }
            out.add(DailyWallpaperMeta(fullUrl, caption.take(120), dateKey))
        }
        return out
    }

    fun parseWikimediaJson(json: String): List<DailyWallpaperMeta> {
        val root = MiniJson.parseObject(json) ?: return emptyList()
        val query = root["query"] as? Map<*, *> ?: return emptyList()
        val pages = query["pages"] as? Map<*, *> ?: return emptyList()
        val out = ArrayList<DailyWallpaperMeta>()
        for ((id, rawPage) in pages) {
            val page = rawPage as? Map<*, *> ?: continue
            val infos = page["imageinfo"] as? List<*> ?: continue
            val info = infos.firstOrNull() as? Map<*, *> ?: continue
            val url = ((info["thumburl"] as? String) ?: (info["url"] as? String))?.trim().orEmpty()
            if (!url.startsWith("https://")) continue
            if (!url.lowercase().contains(".jpg") && !url.lowercase().contains(".jpeg")) continue
            val title = (page["title"] as? String)?.removePrefix("File:")?.substringBeforeLast('.')?.trim().orEmpty()
            out.add(DailyWallpaperMeta(url, title.take(120), "wiki_$id"))
        }
        return out
    }

    internal fun shouldAttemptRefresh(nowElapsedMs: Long, hasCache: Boolean, force: Boolean): Boolean {
        if (force) return true
        val okAt = lastSuccessElapsedMs
        if (hasCache && okAt >= 0L && (nowElapsedMs - okAt) in 0L until REFRESH_INTERVAL_MS) {
            return false
        }
        val failAt = lastFailureElapsedMs
        if (failAt >= 0L && (nowElapsedMs - failAt) in 0L until FAILURE_BACKOFF_MS) {
            return false
        }
        return true
    }

    internal fun recordAttemptResult(nowElapsedMs: Long, success: Boolean) {
        if (success) {
            lastSuccessElapsedMs = nowElapsedMs
            lastFailureElapsedMs = -1L
        } else {
            lastFailureElapsedMs = nowElapsedMs
        }
    }

    internal fun resetRefreshThrottleForTest() {
        lastSuccessElapsedMs = -1L
        lastFailureElapsedMs = -1L
    }

    internal fun readBoundedUtf8(input: InputStream, maxBytes: Int = MAX_HTTP_JSON_BYTES): String? {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buf = ByteArray(8192)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    internal fun streamWithMagicCheck(input: InputStream, tmp: File): Boolean {
        val buf = ByteArray(16384)
        val magic = ByteArray(3)
        var magicLen = 0
        var headerChecked = false
        var total = 0
        FileOutputStream(tmp).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (!headerChecked) {
                    val take = minOf(3 - magicLen, n)
                    System.arraycopy(buf, 0, magic, magicLen, take)
                    magicLen += take
                    if (magicLen == 3) {
                        if (magic[0] != 0xFF.toByte() || magic[1] != 0xD8.toByte() || magic[2] != 0xFF.toByte()) {
                            return false
                        }
                        headerChecked = true
                    }
                }
                total += n
                if (total > MAX_IMAGE_BYTES) return false
                out.write(buf, 0, n)
            }
        }
        return headerChecked && total > 8_000
    }

    internal fun pruneOldCache(dir: File, captions: MutableMap<String, String>) {
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "jpg" } ?: return
        val sorted = files.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        for (i in MAX_CACHED_IMAGES until sorted.size) {
            captions.remove(sorted[i].name)
            sorted[i].delete()
        }
    }

    private fun downloadMissing(
        dir: File,
        metas: List<DailyWallpaperMeta>,
        captions: MutableMap<String, String>
    ): Boolean {
        var downloaded = false
        for (meta in metas.take(MAX_CACHED_IMAGES)) {
            val safeName = "daily_${sanitizeDateKey(meta.dateKey)}.jpg"
            val target = File(dir, safeName)
            if (!target.exists() || target.length() < 8_000L) {
                if (downloadJpeg(meta.url, target)) downloaded = true
            }
            if (target.exists() && meta.caption.isNotBlank()) {
                captions[safeName] = meta.caption
            }
        }
        return downloaded
    }

    private fun fetchDailyMetas(): List<DailyWallpaperMeta> {
        val bingBody = httpGetString(BING_API)
        if (bingBody != null) {
            val parsed = parseBingJson(bingBody)
            if (parsed.isNotEmpty()) return parsed
        }
        val wikiBody = httpGetString(WIKI_API)
        if (wikiBody != null) {
            return parseWikimediaJson(wikiBody)
        }
        return emptyList()
    }

    private fun httpGetString(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AmbientHome/3.0.1 (AndroidTV)")
            }
            if (conn.responseCode != 200) return null
            readBoundedUtf8(conn.inputStream, MAX_HTTP_JSON_BYTES)
        } catch (t: Throwable) {
            Log.w(TAG, "OnlineWallpaper GET failed: $urlStr", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadJpeg(urlStr: String, target: File): Boolean {
        var conn: HttpURLConnection? = null
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AmbientHome/3.0.1 (AndroidTV)")
            }
            if (conn.responseCode != 200) return false
            val ok = streamWithMagicCheck(conn.inputStream, tmp)
            if (ok && tmp.renameTo(target)) true else {
                tmp.delete()
                false
            }
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "OnlineWallpaper download failed", t)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCaptions(dir: File): Map<String, String> {
        val f = File(dir, "captions.json")
        if (!f.isFile) return emptyMap()
        val map = try {
            MiniJson.parseObject(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in map) {
            if (v is String && v.isNotBlank()) out[k] = v
        }
        return out
    }

    private fun writeCaptions(dir: File, captions: Map<String, String>) {
        try {
            File(dir, "captions.json").writeText(MiniJson.stringify(captions), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "writeCaptions failed", t)
        }
    }

    private fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "wp_daily").apply { mkdirs() }

    private fun sanitizeDateKey(raw: String): String =
        raw.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '_' }
            .take(24).ifEmpty { "0" }

    internal fun safeElapsedMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L
    }
}
