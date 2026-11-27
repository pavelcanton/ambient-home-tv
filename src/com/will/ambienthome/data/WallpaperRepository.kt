package com.will.ambienthome.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import com.will.ambienthome.R
import com.will.ambienthome.model.NightConfig
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.WallpaperSource
import java.io.File

/**
 * Загрузка и полное фоновое запекание обоев (1920x1080 + затемнение + ночной янтарный оттенок + скримы)
 * строго вне UI-потока. Ноль GPU-шейдеров в простое и ночном кино-режиме.
 */
object WallpaperRepository {

    private const val TAG = "AmbientHome"

    data class BakedWallpaper(
        val bitmap: Bitmap,
        val resolvedIndex: Int,
        val playlistSize: Int,
        val isFallback: Boolean = false,
        val caption: String = ""
    )

    private val BUILTIN_RES = intArrayOf(
        R.drawable.w1, R.drawable.w2, R.drawable.w3, R.drawable.w4, R.drawable.w5
    )

    private val CUSTOM_DIRS = arrayOf(
        "/sdcard/Pictures/Wallpapers",
        "/sdcard/Pictures",
        "/sdcard/Download/Wallpapers"
    )

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

    private class GradientSpec(
        val linearColors: IntArray,
        val linearStops: FloatArray,
        val radialX: Float,
        val radialY: Float,
        val radialRadius: Float,
        val radialColor: Int
    )

    private val GRADIENT_PRESETS = arrayOf(
        GradientSpec(
            linearColors = intArrayOf(0xFF07111F.toInt(), 0xFF0E2136.toInt(), 0xFF09131D.toInt()),
            linearStops = floatArrayOf(0f, 0.48f, 1f),
            radialX = 0.72f, radialY = 0.24f, radialRadius = 0.55f,
            radialColor = 0x363A9D8F
        ),
        GradientSpec(
            linearColors = intArrayOf(0xFF0B0E16.toInt(), 0xFF131B2A.toInt(), 0xFF090C12.toInt()),
            linearStops = floatArrayOf(0f, 0.5f, 1f),
            radialX = 0.30f, radialY = 0.32f, radialRadius = 0.50f,
            radialColor = 0x305865F2
        ),
        GradientSpec(
            linearColors = intArrayOf(0xFF141114.toInt(), 0xFF1E1920.toInt(), 0xFF0C0B0E.toInt()),
            linearStops = floatArrayOf(0f, 0.5f, 1f),
            radialX = 0.65f, radialY = 0.30f, radialRadius = 0.52f,
            radialColor = 0x28D4895E
        )
    )

    private sealed class WallpaperItem {
        data class Builtin(val resId: Int) : WallpaperItem()
        data class CustomFile(val file: File, val caption: String = "") : WallpaperItem()
        data class Gradient(val preset: Int) : WallpaperItem()
    }

    private data class ResolvedPlaylist(
        val items: List<WallpaperItem>,
        val isFallback: Boolean
    )

    fun requiredMediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasMediaPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(requiredMediaPermission()) == PackageManager.PERMISSION_GRANTED

    fun listCustomFiles(): List<File> {
        val found = ArrayList<File>()
        forEachCustomImageFile { file, _, _ -> found.add(file) }
        found.sortBy { it.name.lowercase() }
        return found
    }

    fun customFilesSignature(): String {
        var count = 0
        var maxMod = 0L
        var modXor = 0L
        var totalBytes = 0L
        var pathXor = 0
        forEachCustomImageFile { file, len, path ->
            count++
            val mod = file.lastModified()
            if (mod > maxMod) maxMod = mod
            modXor = modXor xor mod
            totalBytes += len
            pathXor = pathXor xor path.hashCode()
        }
        return if (count == 0) "0:0:0:0:0" else "$count:$maxMod:$modXor:$totalBytes:$pathXor"
    }

    fun loadAndBakeStep(
        ctx: Context,
        source: WallpaperSource,
        currentIndex: Int,
        advance: Boolean,
        forceReloadIfSingle: Boolean = false,
        previousWasFallback: Boolean = false,
        dimPercent: Int,
        targetW: Int,
        targetH: Int,
        nightActive: Boolean = false,
        nightConfig: NightConfig = NightConfig()
    ): BakedWallpaper? {
        val resolved = resolvePlaylist(ctx, source)
        val playlist = resolved.items
        if (playlist.isEmpty()) return null
        val exitingFallback = previousWasFallback && !resolved.isFallback
        if (advance && playlist.size < 2 && !forceReloadIfSingle && !exitingFallback) return null

        val nextIdx = WallpaperSource.nextIndex(currentIndex, playlist.size, advance)
        val chosen = playlist[nextIdx]
        val effDim = NightSchedule.effectiveDimPercent(dimPercent, nightActive, nightConfig)
        val warmTint = NightSchedule.warmTintColor(nightActive)
        val (bmp, decodeFellBack) = bakeItem(ctx, chosen, effDim, warmTint, nightActive, targetW, targetH)
        val caption = if (!decodeFellBack && chosen is WallpaperItem.CustomFile) chosen.caption else ""
        return BakedWallpaper(
            bitmap = bmp,
            resolvedIndex = nextIdx,
            playlistSize = playlist.size,
            isFallback = resolved.isFallback || decodeFellBack,
            caption = caption
        )
    }

    private fun forEachCustomImageFile(visit: (File, Long, String) -> Unit) {
        val seen = HashSet<String>()
        for (dirPath in CUSTOM_DIRS) {
            val children = try {
                File(dirPath).listFiles()
            } catch (t: Throwable) {
                Log.w(TAG, "listFiles failed for $dirPath", t)
                null
            } ?: continue
            for (f in children) {
                if (!f.isFile || !f.canRead()) continue
                val len = f.length()
                if (f.extension.lowercase() !in IMAGE_EXTS || len !in 10_000L..35_000_000L) continue
                val path = f.absolutePath
                if (seen.add(path)) visit(f, len, path)
            }
        }
    }

    private fun resolvePlaylist(ctx: Context, source: WallpaperSource): ResolvedPlaylist {
        return when (source) {
            WallpaperSource.GRADIENT -> ResolvedPlaylist(
                items = listOf(WallpaperItem.Gradient(0), WallpaperItem.Gradient(1), WallpaperItem.Gradient(2)),
                isFallback = false
            )
            WallpaperSource.CUSTOM -> {
                val custom = listCustomFiles().map { WallpaperItem.CustomFile(it) }
                if (custom.isNotEmpty()) ResolvedPlaylist(custom, isFallback = false)
                else ResolvedPlaylist(BUILTIN_RES.map { WallpaperItem.Builtin(it) }, isFallback = true)
            }
            WallpaperSource.ALL -> {
                val list = ArrayList<WallpaperItem>()
                list.addAll(listCustomFiles().map { WallpaperItem.CustomFile(it) })
                list.addAll(BUILTIN_RES.map { WallpaperItem.Builtin(it) })
                ResolvedPlaylist(list, isFallback = false)
            }
            WallpaperSource.ONLINE_DAILY -> {
                val daily = OnlineWallpaperProvider.listCached(ctx)
                    .map { WallpaperItem.CustomFile(it.file, it.caption) }
                if (daily.isNotEmpty()) ResolvedPlaylist(daily, isFallback = false)
                else ResolvedPlaylist(BUILTIN_RES.map { WallpaperItem.Builtin(it) }, isFallback = true)
            }
            WallpaperSource.BUILTIN -> ResolvedPlaylist(
                items = BUILTIN_RES.map { WallpaperItem.Builtin(it) },
                isFallback = false
            )
        }
    }

    private fun bakeItem(
        ctx: Context,
        item: WallpaperItem,
        dimPercent: Int,
        warmTintArgb: Int,
        nightActive: Boolean,
        targetW: Int,
        targetH: Int
    ): Pair<Bitmap, Boolean> {
        val w = targetW.coerceAtLeast(640)
        val h = targetH.coerceAtLeast(360)
        val bmpConfig = if (item is WallpaperItem.Gradient) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val out = Bitmap.createBitmap(w, h, bmpConfig)
        try {
            val canvas = Canvas(out)
            val fellBack = drawWallpaperBase(ctx, canvas, item, w, h)
            applyDimAndNightTint(canvas, item, fellBack, dimPercent, warmTintArgb, nightActive)
            drawWallpaperScrims(canvas, w, h)
            out.setHasAlpha(false)
            return out to fellBack
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }

    private fun applyDimAndNightTint(
        canvas: Canvas,
        item: WallpaperItem,
        fellBack: Boolean,
        dimPercent: Int,
        warmTintArgb: Int,
        nightActive: Boolean
    ) {
        if (item is WallpaperItem.Gradient) {
            if (nightActive) {
                canvas.drawColor(Color.argb(90, 8, 5, 2))
                if (warmTintArgb != 0) canvas.drawColor(warmTintArgb)
            }
            return
        }
        val dim = dimPercent.coerceIn(0, 82)
        if (dim > 0 && !fellBack) {
            canvas.drawColor(Color.argb(dim * 255 / 100, 0, 0, 0))
        }
        if (warmTintArgb != 0) {
            canvas.drawColor(warmTintArgb)
        }
    }

    private fun drawWallpaperBase(ctx: Context, canvas: Canvas, item: WallpaperItem, w: Int, h: Int): Boolean {
        return when (item) {
            is WallpaperItem.Gradient -> {
                drawGradientPreset(canvas, w, h, item.preset)
                false
            }
            is WallpaperItem.Builtin -> drawDecodedOrFallback(canvas, decodeResourceScaled(ctx, item.resId, w, h), w, h)
            is WallpaperItem.CustomFile -> drawDecodedOrFallback(canvas, decodeFileScaled(item.file, w, h), w, h)
        }
    }

    private fun drawDecodedOrFallback(canvas: Canvas, raw: Bitmap?, w: Int, h: Int): Boolean {
        if (raw != null) {
            try {
                drawCenterCrop(canvas, raw, w, h)
            } finally {
                raw.recycle()
            }
            return false
        }
        drawGradientPreset(canvas, w, h, 0)
        return true
    }

    private fun drawWallpaperScrims(canvas: Canvas, w: Int, h: Int) {
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.30f,
            intArrayOf(0x9E000000.toInt(), 0x33000000, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.30f, scrimPaint)

        scrimPaint.shader = LinearGradient(
            0f, h * 0.48f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x7A000000, 0xDC000000.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.48f, w.toFloat(), h.toFloat(), scrimPaint)
    }

    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, w: Int, h: Int) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = maxOf(w / bw, h / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
    }

    private fun drawGradientPreset(canvas: Canvas, w: Int, h: Int, preset: Int) {
        val spec = GRADIENT_PRESETS[preset.coerceAtLeast(0) % GRADIENT_PRESETS.size]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val fw = w.toFloat()
        val fh = h.toFloat()
        paint.shader = LinearGradient(0f, 0f, fw, fh, spec.linearColors, spec.linearStops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fw, fh, paint)
        paint.shader = RadialGradient(
            fw * spec.radialX, fh * spec.radialY, fw * spec.radialRadius,
            intArrayOf(spec.radialColor, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, fw, fh, paint)
    }

    private fun decodeResourceScaled(ctx: Context, resId: Int, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(ctx.resources, resId, probe)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = WallpaperSource.computeSampleSize(probe.outWidth, probe.outHeight, reqW, reqH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeResource(ctx.resources, resId, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "decodeResourceScaled failed for resId=$resId", t)
            null
        }
    }

    private fun decodeFileScaled(file: File, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = WallpaperSource.computeSampleSize(probe.outWidth, probe.outHeight, reqW, reqH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "decodeFileScaled failed for ${file.name}", t)
            null
        }
    }
}
