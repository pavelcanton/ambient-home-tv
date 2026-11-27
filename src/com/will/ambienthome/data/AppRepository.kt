package com.will.ambienthome.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.will.ambienthome.model.AppEntry
import com.will.ambienthome.model.AppOrdering
import com.will.ambienthome.model.AppsLayoutState
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.WolTarget
import java.util.concurrent.ConcurrentHashMap

/**
 * Загрузка списка приложений и внеэкранное «запекание» унифицированных плиток.
 * Включает виртуальную плитку Wake-on-LAN «Включить ПК (WOL)», когда задан MAC-адрес.
 */
object AppRepository {

    private const val TILE_PX = 192
    private const val CORNER_RATIO = 0.23f
    private const val LIGHT_CACHE_TTL_MS = 30_000L

    private val DEFAULT_PRIORITY = listOf(
        "org.smarttube.stable",
        "ru.kinopoisk.tv",
        "com.vk.tv",
        "com.lazycatsoftware.lmd",
        "ru.rt.video.app.mobile",
        "org.courville.nova",
        "com.lonelycatgames.Xplore",
        "com.yandex.browser.tv",
        "com.android.chrome",
        "com.rockchip.wfd",
        "com.android.tv.settings",
        CustomShortcuts.WOL_PACKAGE
    )

    private data class BakedTile(val bitmap: Bitmap, val accentColor: Int)
    private data class LightAppCache(val entries: List<AppEntry>, val loadedElapsedMs: Long)

    private val tileCache = ConcurrentHashMap<String, BakedTile>()

    @Volatile
    private var lightAppCache: LightAppCache? = null

    fun loadAll(ctx: Context, wolTarget: WolTarget = WolTarget()): List<AppEntry> {
        val self = ctx.packageName
        val pm = ctx.packageManager
        val byPackage = LinkedHashMap<String, AppEntry>()

        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPackage, self, readUpdateTime = true)
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPackage, self, readUpdateTime = true)

        val rawEntries = byPackage.values.toList()
        lightAppCache = LightAppCache(rawEntries, OnlineWallpaperProvider.safeElapsedMs())
        val activeKeys = HashSet<String>(rawEntries.size * 2 + 4)
        val entries = ArrayList<AppEntry>(rawEntries.size + 1)
        for (entry in rawEntries) {
            activeKeys.add(entry.cacheKey)
            val baked = tileCache[entry.cacheKey] ?: try {
                bakeTile(pm, entry).also { tileCache[entry.cacheKey] = it }
            } catch (t: Throwable) {
                Log.w("AmbientHome", "bakeTile failed for ${entry.packageName}", t)
                null
            }
            entries.add(
                entry.copy(
                    tileBitmap = baked?.bitmap,
                    accentColor = baked?.accentColor ?: AppEntry.DEFAULT_ACCENT
                )
            )
        }
        if (wolTarget.isConfigured) {
            val wolEntry = buildWolEntry(wolTarget)
            activeKeys.add(wolEntry.cacheKey)
            entries.add(wolEntry)
        }
        val it = tileCache.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in activeKeys) it.remove()
        }
        return entries
    }

    internal fun loadLightweight(ctx: Context, wolTarget: WolTarget = WolTarget()): List<AppEntry> {
        val now = OnlineWallpaperProvider.safeElapsedMs()
        val cached = lightAppCache
        val base = if (cached != null && (now - cached.loadedElapsedMs) in 0L until LIGHT_CACHE_TTL_MS) {
            cached.entries
        } else {
            val self = ctx.packageName
            val pm = ctx.packageManager
            val byPackage = LinkedHashMap<String, AppEntry>()
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPackage, self, readUpdateTime = false)
            collect(pm, Intent.CATEGORY_LAUNCHER, false, byPackage, self, readUpdateTime = false)
            byPackage.values.toList().also { lightAppCache = LightAppCache(it, now) }
        }
        if (!wolTarget.isConfigured) return base
        val out = ArrayList<AppEntry>(base.size + 1)
        out.addAll(base)
        out.add(
            AppEntry(
                packageName = CustomShortcuts.WOL_PACKAGE,
                className = CustomShortcuts.WOL_CLASS,
                label = wolTarget.name.ifBlank { WolTarget.DEFAULT_NAME },
                isTvApp = true,
                lastUpdateTime = 1L,
                isVirtual = true
            )
        )
        return out
    }

    fun orderVisible(all: List<AppEntry>, layout: AppsLayoutState): List<AppEntry> {
        val byPkg = HashMap<String, AppEntry>(all.size * 2)
        val pairs = ArrayList<Pair<String, String>>(all.size)
        for (e in all) {
            if (byPkg.putIfAbsent(e.packageName, e) == null) {
                pairs.add(e.packageName to e.label)
            }
        }
        val orderedPkgs = AppOrdering.orderPackageNames(
            installed = pairs,
            hidden = layout.hiddenPackages,
            pinned = layout.pinnedPackages,
            dockOrder = layout.dockOrder,
            defaultPriority = DEFAULT_PRIORITY
        )
        return orderedPkgs.mapNotNull { byPkg[it] }
    }

    fun launch(ctx: Context, entry: AppEntry): Boolean {
        if (entry.isVirtual) return false
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val primary = Intent(Intent.ACTION_MAIN).apply {
            addCategory(if (entry.isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            component = entry.component
            addFlags(flags)
        }
        if (tryStart(ctx, primary)) return true

        val alt = Intent(Intent.ACTION_MAIN).apply {
            addCategory(if (entry.isTvApp) Intent.CATEGORY_LAUNCHER else Intent.CATEGORY_LEANBACK_LAUNCHER)
            component = entry.component
            addFlags(flags)
        }
        if (tryStart(ctx, alt)) return true

        val fallback = ctx.packageManager.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: ctx.packageManager.getLaunchIntentForPackage(entry.packageName)
        return fallback != null && tryStart(ctx, fallback.addFlags(flags))
    }

    fun openAppInfo(ctx: Context, packageName: String) {
        val pkgUri = Uri.fromParts("package", packageName, null)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val primary = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = pkgUri
            addFlags(flags)
        }
        if (tryStart(ctx, primary)) return

        val explicit = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            component = ComponentName("com.android.tv.settings", "com.android.tv.settings.device.apps.AppManagementActivity")
            data = pkgUri
            addFlags(flags)
        }
        tryStart(ctx, explicit)
    }

    fun openAndroidSettings(ctx: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TASK
        if (tryStart(ctx, Intent(Settings.ACTION_SETTINGS).addFlags(flags))) return

        val explicit = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
            addFlags(flags)
        }
        if (tryStart(ctx, explicit)) return

        val fallback = ctx.packageManager.getLeanbackLaunchIntentForPackage("com.android.tv.settings")
            ?: ctx.packageManager.getLaunchIntentForPackage("com.android.tv.settings")
        if (fallback != null) tryStart(ctx, fallback.addFlags(flags))
    }

    fun openWifiSettings(ctx: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TASK
        if (tryStart(ctx, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(flags))) return

        val explicit = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            component = ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")
            addFlags(flags)
        }
        if (tryStart(ctx, explicit)) return

        openAndroidSettings(ctx)
    }

    fun resolveLabels(ctx: Context, packages: List<String>): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return packages.map { pkg ->
            val label = if (CustomShortcuts.isWolShortcut(pkg)) {
                WolTarget.DEFAULT_NAME
            } else {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
                } catch (_: Throwable) {
                    pkg
                }
            }
            pkg to label
        }
    }

    private fun buildWolEntry(wol: WolTarget): AppEntry {
        val key = "${CustomShortcuts.WOL_PACKAGE}:1"
        val baked = tileCache[key] ?: bakeWolTile().also { tileCache[key] = it }
        return AppEntry(
            packageName = CustomShortcuts.WOL_PACKAGE,
            className = CustomShortcuts.WOL_CLASS,
            label = wol.name.ifBlank { WolTarget.DEFAULT_NAME },
            isTvApp = true,
            lastUpdateTime = 1L,
            tileBitmap = baked.bitmap,
            accentColor = baked.accentColor,
            isVirtual = true
        )
    }

    private fun bakeWolTile(): BakedTile {
        val content = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(content)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, TILE_PX.toFloat(), TILE_PX.toFloat(),
                0xFF16324A.toInt(), 0xFF0E1B2B.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, TILE_PX.toFloat(), TILE_PX.toFloat(), paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 12f
            paint.color = 0xFF7FD8A0.toInt()
            val cx = TILE_PX / 2f
            val cy = TILE_PX / 2f + 4f
            val r = 44f
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -52f, 284f, false, paint)
            c.drawLine(cx, cy - r - 12f, cx, cy - 6f, paint)
            val masked = applyRoundedGlassMask(content)
            return BakedTile(masked, 0xFF7FD8A0.toInt())
        } finally {
            content.recycle()
        }
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w("AmbientHome", "Failed to start $intent", t)
            false
        }
    }

    private fun collect(
        pm: PackageManager,
        category: String,
        isTv: Boolean,
        out: LinkedHashMap<String, AppEntry>,
        self: String,
        readUpdateTime: Boolean
    ) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val list = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (t: Throwable) {
            Log.w("AmbientHome", "queryIntentActivities failed for $category", t)
            return
        }
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (!ai.enabled || pkg == self || out.containsKey(pkg)) continue

            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            } catch (_: Throwable) {
                pkg
            }
            val updated = if (readUpdateTime) {
                try {
                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                } catch (_: Throwable) {
                    0L
                }
            } else 0L
            out[pkg] = AppEntry(
                packageName = pkg,
                className = ai.name,
                label = label,
                isTvApp = isTv,
                lastUpdateTime = updated
            )
        }
    }

    private fun bakeTile(pm: PackageManager, entry: AppEntry): BakedTile {
        val rawBmp = renderRawIcon(pm, entry)
        val (content, accent) = try {
            val col = extractAccentColor(rawBmp)
            composeTileContent(rawBmp, col, isFullBleed(rawBmp)) to col
        } finally {
            rawBmp.recycle()
        }
        val masked = try {
            applyRoundedGlassMask(content)
        } finally {
            content.recycle()
        }
        return BakedTile(masked, accent)
    }

    private fun renderRawIcon(pm: PackageManager, entry: AppEntry): Bitmap {
        val rawDrawable: Drawable = try {
            pm.getActivityIcon(entry.component)
        } catch (_: Throwable) {
            try {
                pm.getApplicationIcon(entry.packageName)
            } catch (_: Throwable) {
                pm.defaultActivityIcon
            }
        }
        val drawable = rawDrawable.mutate()
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val left: Int
        val top: Int
        val drawW: Int
        val drawH: Int
        if (iw > 0 && ih > 0 && iw != ih) {
            val scale = minOf(TILE_PX.toFloat() / iw, TILE_PX.toFloat() / ih)
            drawW = (iw * scale).toInt().coerceIn(1, TILE_PX)
            drawH = (ih * scale).toInt().coerceIn(1, TILE_PX)
            left = (TILE_PX - drawW) / 2
            top = (TILE_PX - drawH) / 2
        } else {
            left = 0; top = 0; drawW = TILE_PX; drawH = TILE_PX
        }
        val rawBmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(rawBmp)
            drawable.setBounds(left, top, left + drawW, top + drawH)
            drawable.draw(c)
            return rawBmp
        } catch (t: Throwable) {
            rawBmp.recycle()
            throw t
        }
    }

    private fun composeTileContent(rawBmp: Bitmap, accent: Int, fullBleed: Boolean): Bitmap {
        val content = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(content)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (fullBleed) {
                c.drawBitmap(rawBmp, 0f, 0f, paint)
            } else {
                val topColor = blendColors(0xFF1A222E.toInt(), accent, 0.22f)
                val botColor = blendColors(0xFF0E131B.toInt(), accent, 0.10f)
                paint.shader = LinearGradient(0f, 0f, 0f, TILE_PX.toFloat(), topColor, botColor, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, TILE_PX.toFloat(), TILE_PX.toFloat(), paint)
                paint.shader = null
                val pad = TILE_PX * 0.14f
                c.drawBitmap(rawBmp, null, RectF(pad, pad, TILE_PX - pad, TILE_PX - pad), paint)
            }
            return content
        } catch (t: Throwable) {
            content.recycle()
            throw t
        }
    }

    private fun applyRoundedGlassMask(content: Bitmap): Bitmap {
        val masked = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(masked)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = TILE_PX * CORNER_RATIO
            c.drawRoundRect(RectF(0f, 0f, TILE_PX.toFloat(), TILE_PX.toFloat()), r, r, maskPaint)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(content, 0f, 0f, maskPaint)

            val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x24FFFFFF
            }
            c.drawRoundRect(RectF(1f, 1f, TILE_PX - 1f, TILE_PX - 1f), r - 1f, r - 1f, glossPaint)
            masked.prepareToDraw()
            return masked
        } catch (t: Throwable) {
            masked.recycle()
            throw t
        }
    }

    private fun isFullBleed(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val inset = (w * 0.08f).toInt().coerceAtLeast(2)
        val probes = intArrayOf(
            bmp.getPixel(inset, inset), bmp.getPixel(w - 1 - inset, inset),
            bmp.getPixel(inset, h - 1 - inset), bmp.getPixel(w - 1 - inset, h - 1 - inset),
            bmp.getPixel(w / 2, inset), bmp.getPixel(w / 2, h - 1 - inset),
            bmp.getPixel(inset, h / 2), bmp.getPixel(w - 1 - inset, h / 2)
        )
        var opaqueCount = 0
        for (px in probes) if (Color.alpha(px) >= 220) opaqueCount++
        return opaqueCount >= 7
    }

    private fun extractAccentColor(bmp: Bitmap): Int {
        val step = (bmp.width / 12).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var bestScore = -1f
        var bestColor = AppEntry.DEFAULT_ACCENT
        var y = step
        while (y < bmp.height - step) {
            var x = step
            while (x < bmp.width - step) {
                val px = bmp.getPixel(x, y)
                if (Color.alpha(px) >= 200) {
                    Color.colorToHSV(px, hsv)
                    if (hsv[1] > 0.25f && hsv[2] > 0.25f) {
                        val score = hsv[1] * 1.4f + hsv[2] * 0.6f
                        if (score > bestScore) {
                            bestScore = score
                            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
                            hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
                            bestColor = Color.HSVToColor(255, hsv)
                        }
                    }
                }
                x += step
            }
            y += step
        }
        return bestColor
    }

    private fun blendColors(base: Int, tint: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(base) * inv + Color.red(tint) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(tint) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(tint) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
