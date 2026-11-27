package com.will.ambienthome.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.TopAction
import com.will.ambienthome.model.WeatherIcon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Глубокий предзапекатель статичных стеклянных панелей и кнопок топ-бара для бюджетных TV GPU.
 * Убирает дорогие SDF roundRect/arc-шейдеры из onDraw() и гарантирует 0 аллокаций в draw().
 *
 * ВАЖНО: НИКОГДА не вызывает Bitmap.recycle() на HWUI-битмапах (исключает SIGSEGV на API 26+).
 */
internal class HomeSurfaceCache(
    private val tfMedium: Typeface
) {
    private companion object {
        const val MAX_TITLE_PILLS = 16
        val SLIDER_OFFSETS = floatArrayOf(-0.34f, 0f, 0.34f)
        val SLIDER_KNOBS = floatArrayOf(-0.22f, 0.25f, -0.10f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val tmpRect = RectF()
    private val tmpFm = Paint.FontMetrics()

    private var dockBgBmp: Bitmap? = null
    private var dockBgW: Int = 0
    private var dockBgH: Int = 0
    private var gridBgBmp: Bitmap? = null
    private var gridBgW: Int = 0
    private var gridBgH: Int = 0
    private var allAppsIdleBmp: Bitmap? = null
    private var allAppsFocusBmp: Bitmap? = null

    private var topIconSize: Int = 0
    private val topIconBitmaps = arrayOfNulls<Bitmap>(8)

    private var wifiPillBmp: Bitmap? = null
    private var wifiPillStatusRef: SystemStatus? = null
    private var wifiPillVisual: SystemStatus.WifiPillVisualKey? = null
    private var wifiPillFocused: Boolean = false
    private var wifiPillW: Int = 0
    private var wifiPillH: Int = 0

    private var weatherPillBmp: Bitmap? = null
    private var weatherPillKey: String = ""
    private var sleepPillBmp: Bitmap? = null
    private var sleepPillKey: String = ""

    private val titlePills = object : LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_TITLE_PILLS
    }
    private var titlePillH: Int = 0
    private var titlePillU: Float = 0f

    fun panel(isDock: Boolean, wPx: Int, hPx: Int, u: Float): Bitmap {
        val w = wPx.coerceAtLeast(1)
        val h = hPx.coerceAtLeast(1)
        return if (isDock) {
            if (gridBgBmp != null) {
                gridBgBmp = null; gridBgW = 0; gridBgH = 0
            }
            val existing = dockBgBmp
            if (existing != null && !existing.isRecycled && dockBgW == w && dockBgH == h) existing
            else bakePanel(w, h, 38f * u, 0xBA0C1018.toInt(), 0x30FFFFFF, 1.6f * u).also {
                dockBgBmp = it; dockBgW = w; dockBgH = h
            }
        } else {
            val existing = gridBgBmp
            if (existing != null && !existing.isRecycled && gridBgW == w && gridBgH == h) existing
            else bakePanel(w, h, 30f * u, 0xD40A0E16.toInt(), 0x2CFFFFFF, 1.5f * u).also {
                gridBgBmp = it; gridBgW = w; gridBgH = h
            }
        }
    }

    fun allAppsTile(focused: Boolean): Bitmap {
        val existing = if (focused) allAppsFocusBmp else allAppsIdleBmp
        if (existing != null && !existing.isRecycled) return existing
        return bakeAllAppsTile(focused).also {
            if (focused) allAppsFocusBmp = it else allAppsIdleBmp = it
        }
    }

    fun topButton(
        action: TopAction,
        status: SystemStatus,
        wifiLabel: String,
        extraLabel: String,
        weatherIcon: WeatherIcon,
        wPx: Int,
        hPx: Int,
        focused: Boolean,
        u: Float
    ): Bitmap {
        val w = wPx.coerceAtLeast(1)
        val h = hPx.coerceAtLeast(1)
        return when (action) {
            TopAction.WIFI -> ensureWifiPill(status, wifiLabel, w, h, focused, u)
            TopAction.WEATHER -> ensureWeatherPill(weatherIcon, extraLabel, w, h, focused, u)
            TopAction.SLEEP_TIMER -> ensureSleepPill(extraLabel, w, h, focused, u)
            TopAction.BLUETOOTH -> ensureTopIcon(0, focused, h, u)
            TopAction.QUICK_CONTROLS -> ensureTopIcon(1, focused, h, u)
            TopAction.ANDROID_SETTINGS -> ensureTopIcon(2, focused, h, u)
            TopAction.LAUNCHER_SETTINGS -> ensureTopIcon(3, focused, h, u)
        }
    }

    fun titlePill(title: String, hPx: Int, u: Float): Bitmap {
        val h = hPx.coerceAtLeast(1)
        if (titlePillH != h || titlePillU != u) {
            titlePills.clear()
            titlePillH = h
            titlePillU = u
        }
        val existing = titlePills[title]
        if (existing != null && !existing.isRecycled) return existing
        return bakeTitlePill(title, h, u).also { titlePills[title] = it }
    }

    fun evictPanels(keepDock: Boolean) {
        gridBgBmp = null; gridBgW = 0; gridBgH = 0
        if (!keepDock) {
            dockBgBmp = null; dockBgW = 0; dockBgH = 0
        }
    }

    fun release() {
        evictPanels(keepDock = false)
        allAppsIdleBmp = null
        allAppsFocusBmp = null
        topIconBitmaps.fill(null)
        topIconSize = 0
        wifiPillBmp = null; wifiPillStatusRef = null; wifiPillVisual = null; wifiPillW = 0; wifiPillH = 0
        weatherPillBmp = null; weatherPillKey = ""
        sleepPillBmp = null; sleepPillKey = ""
        titlePills.clear(); titlePillH = 0; titlePillU = 0f
    }

    private fun ensureTopIcon(kind: Int, focused: Boolean, sizePx: Int, u: Float): Bitmap {
        if (topIconSize != sizePx) {
            topIconSize = sizePx
            topIconBitmaps.fill(null)
        }
        val slot = kind * 2 + (if (focused) 1 else 0)
        val existing = topIconBitmaps[slot]
        if (existing != null && !existing.isRecycled) return existing
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val f = sizePx.toFloat()
        val inset = if (focused) 1.6f * u else 0.8f * u
        drawTopButtonFrame(bc, inset, inset, f - inset, f - inset, focused, u)
        when (kind) {
            0 -> drawHeadphonesIcon(bc, f / 2f, f / 2f, f * 0.25f, focused, u)
            1 -> drawQuickIcon(bc, f / 2f, f / 2f, f * 0.22f, focused, u)
            2 -> drawGearIcon(bc, f / 2f, f / 2f, f * 0.25f, focused, u)
            else -> drawSlidersIcon(bc, f / 2f, f / 2f, f * 0.44f, focused, u)
        }
        bmp.prepareToDraw()
        topIconBitmaps[slot] = bmp
        return bmp
    }

    private fun ensureWifiPill(
        status: SystemStatus,
        label: String,
        wPx: Int,
        hPx: Int,
        focused: Boolean,
        u: Float
    ): Bitmap {
        val existing = wifiPillBmp
        if (existing != null && !existing.isRecycled && wifiPillW == wPx && wifiPillH == hPx && wifiPillFocused == focused) {
            if (wifiPillStatusRef === status) return existing
            val key = status.visualKey()
            if (wifiPillVisual == key) {
                wifiPillStatusRef = status
                return existing
            }
            wifiPillVisual = key
        } else {
            wifiPillVisual = status.visualKey()
        }
        val bmp = bakeWifiPill(status, label, wPx, hPx, focused, u)
        wifiPillBmp = bmp; wifiPillStatusRef = status; wifiPillW = wPx; wifiPillH = hPx; wifiPillFocused = focused
        return bmp
    }

    private fun ensureWeatherPill(
        icon: WeatherIcon,
        tempLabel: String,
        wPx: Int,
        hPx: Int,
        focused: Boolean,
        u: Float
    ): Bitmap {
        val key = "${icon.code}:$tempLabel:$wPx:$hPx:$focused"
        val existing = weatherPillBmp
        if (existing != null && !existing.isRecycled && weatherPillKey == key) return existing
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val inset = if (focused) 1.6f * u else 0.8f * u
        drawTopButtonFrame(bc, inset, inset, wPx - inset, hPx - inset, focused, u)
        drawWeatherIcon(bc, 28f * u, hPx / 2f, 11f * u, icon, u)
        drawPillText(bc, tempLabel, 48f * u, hPx / 2f, focused, u)
        bmp.prepareToDraw()
        weatherPillBmp = bmp
        weatherPillKey = key
        return bmp
    }

    private fun ensureSleepPill(badge: String, wPx: Int, hPx: Int, focused: Boolean, u: Float): Bitmap {
        val key = "$badge:$wPx:$hPx:$focused"
        val existing = sleepPillBmp
        if (existing != null && !existing.isRecycled && sleepPillKey == key) return existing
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val inset = if (focused) 1.6f * u else 0.8f * u
        drawTopButtonFrame(bc, inset, inset, wPx - inset, hPx - inset, focused, u)
        drawMoonIcon(bc, 26f * u, hPx / 2f, 9f * u, u)
        drawPillText(bc, badge, 44f * u, hPx / 2f, focused, u)
        bmp.prepareToDraw()
        sleepPillBmp = bmp
        sleepPillKey = key
        return bmp
    }

    private fun bakeWifiPill(status: SystemStatus, label: String, wPx: Int, hPx: Int, focused: Boolean, u: Float): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val inset = if (focused) 1.6f * u else 0.8f * u
        drawTopButtonFrame(bc, inset, inset, wPx - inset, hPx - inset, focused, u)
        val dotColor = when {
            status.ethernetConnected -> 0xFF60A5FA.toInt()
            status.wifiConnected && status.signalBars >= 3 -> 0xFF5CE0A0.toInt()
            status.wifiConnected -> 0xFFFBBF24.toInt()
            else -> 0xFFF87171.toInt()
        }
        val cy = hPx / 2f
        fillPaint.color = dotColor
        bc.drawCircle(22f * u, cy, 5f * u, fillPaint)
        if (status.ethernetConnected) {
            drawEthernetIcon(bc, 46f * u, cy, 12f * u, u)
        } else {
            drawWifiIcon(bc, 46f * u, cy + 7f * u, 16f * u, status.signalBars, status.isOnline, u)
        }
        drawPillText(bc, label, 68f * u, cy, focused, u)
        bmp.prepareToDraw()
        return bmp
    }

    private fun drawPillText(c: Canvas, label: String, x: Float, cy: Float, focused: Boolean, u: Float) {
        textPaint.typeface = tfMedium
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 21f * u
        textPaint.color = if (focused) 0xFFFFFFFF.toInt() else 0xE6FFFFFF.toInt()
        textPaint.getFontMetrics(tmpFm)
        c.drawText(label, x, cy - (tmpFm.ascent + tmpFm.descent) / 2f, textPaint)
    }

    private fun bakeTitlePill(title: String, h: Int, u: Float): Bitmap {
        textPaint.typeface = tfMedium
        textPaint.textSize = 25f * u
        textPaint.textAlign = Paint.Align.CENTER
        val w = (textPaint.measureText(title) + 48f * u).roundToInt().coerceAtLeast(h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp)
        val inset = 0.8f * u
        tmpRect.set(inset, inset, w - inset, h - inset)
        fillPaint.color = 0xB80A0E15.toInt()
        bc.drawRoundRect(tmpRect, h / 2f, h / 2f, fillPaint)
        strokePaint.strokeWidth = 1.2f * u
        strokePaint.color = 0x2EFFFFFF
        bc.drawRoundRect(tmpRect, h / 2f, h / 2f, strokePaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.getFontMetrics(tmpFm)
        bc.drawText(title, w / 2f, h / 2f - (tmpFm.ascent + tmpFm.descent) / 2f, textPaint)
        bmp.prepareToDraw()
        return bmp
    }

    private fun bakePanel(w: Int, h: Int, radius: Float, fillColor: Int, strokeColor: Int, strokeW: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = strokeW * 0.5f
        tmpRect.set(inset, inset, w - inset, h - inset)
        fillPaint.color = fillColor
        strokePaint.strokeWidth = strokeW
        strokePaint.color = strokeColor
        canvas.drawRoundRect(tmpRect, radius, radius, fillPaint)
        canvas.drawRoundRect(tmpRect, radius, radius, strokePaint)
        bmp.prepareToDraw()
        return bmp
    }

    private fun bakeAllAppsTile(focused: Boolean): Bitmap {
        val sz = 192
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fsz = sz.toFloat()
        tmpRect.set(1.5f, 1.5f, fsz - 1.5f, fsz - 1.5f)
        fillPaint.color = if (focused) 0xFF233042.toInt() else 0xFF17202C.toInt()
        strokePaint.strokeWidth = 2.2f
        strokePaint.color = 0x38FFFFFF
        canvas.drawRoundRect(tmpRect, fsz * 0.23f, fsz * 0.23f, fillPaint)
        canvas.drawRoundRect(tmpRect, fsz * 0.23f, fsz * 0.23f, strokePaint)
        val cell = fsz * 0.145f
        val gap = fsz * 0.115f
        val sx = (fsz - (cell * 3f + gap * 2f)) / 2f + cell / 2f
        fillPaint.color = if (focused) 0xFFFFFFFF.toInt() else 0xD8FFFFFF.toInt()
        for (r in 0..2) {
            for (col in 0..2) {
                canvas.drawCircle(sx + col * (cell + gap), sx + r * (cell + gap), cell / 2f, fillPaint)
            }
        }
        bmp.prepareToDraw()
        return bmp
    }

    private fun drawTopButtonFrame(c: Canvas, l: Float, t: Float, r: Float, b: Float, focused: Boolean, u: Float) {
        tmpRect.set(l, t, r, b)
        val rad = 18f * u
        fillPaint.color = if (focused) 0xD91E2A3A.toInt() else 0x990D1219.toInt()
        c.drawRoundRect(tmpRect, rad, rad, fillPaint)
        strokePaint.strokeWidth = if (focused) 3f * u else 1.3f * u
        strokePaint.color = if (focused) 0xFFFFFFFF.toInt() else 0x33FFFFFF
        c.drawRoundRect(tmpRect, rad, rad, strokePaint)
    }

    private fun drawHeadphonesIcon(c: Canvas, cx: Float, cy: Float, r: Float, focused: Boolean, u: Float) {
        strokePaint.strokeWidth = 2.6f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = if (focused) 0xFFFFFFFF.toInt() else 0xFF7FD8A0.toInt()
        tmpRect.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f)
        c.drawArc(tmpRect, 180f, 180f, false, strokePaint)
        fillPaint.color = strokePaint.color
        tmpRect.set(cx - r - 2f * u, cy, cx - r + 4.5f * u, cy + r * 0.9f)
        c.drawRoundRect(tmpRect, 2.5f * u, 2.5f * u, fillPaint)
        tmpRect.set(cx + r - 4.5f * u, cy, cx + r + 2f * u, cy + r * 0.9f)
        c.drawRoundRect(tmpRect, 2.5f * u, 2.5f * u, fillPaint)
    }

    private fun drawQuickIcon(c: Canvas, cx: Float, cy: Float, r: Float, focused: Boolean, u: Float) {
        fillPaint.color = if (focused) 0xFFFFFFFF.toInt() else 0xE0FFFFFF.toInt()
        val gap = r * 0.58f
        val box = r * 0.44f
        val offsets = floatArrayOf(-gap, gap)
        for (ox in offsets) {
            for (oy in offsets) {
                tmpRect.set(cx + ox - box, cy + oy - box, cx + ox + box, cy + oy + box)
                c.drawRoundRect(tmpRect, 3f * u, 3f * u, fillPaint)
            }
        }
    }

    private fun drawMoonIcon(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        strokePaint.strokeWidth = 2.6f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = 0xFFFBBF24.toInt()
        tmpRect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(tmpRect, 40f, 250f, false, strokePaint)
        tmpRect.set(cx - r * 0.45f, cy - r * 0.75f, cx + r * 0.85f, cy + r * 0.55f)
        c.drawArc(tmpRect, 90f, 160f, false, strokePaint)
    }

    private fun drawWeatherIcon(c: Canvas, cx: Float, cy: Float, r: Float, icon: WeatherIcon, u: Float) {
        fillPaint.color = when (icon) {
            WeatherIcon.CLEAR -> 0xFFFBBF24.toInt()
            WeatherIcon.PARTLY_CLOUDY -> 0xFFFCD34D.toInt()
            WeatherIcon.RAIN, WeatherIcon.STORM -> 0xFF60A5FA.toInt()
            WeatherIcon.SNOW -> 0xFFE0F2FE.toInt()
            else -> 0xFFD1D5DB.toInt()
        }
        if (icon == WeatherIcon.CLEAR) {
            c.drawCircle(cx, cy, r * 0.62f, fillPaint)
            return
        }
        if (icon == WeatherIcon.PARTLY_CLOUDY) {
            c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.42f, fillPaint)
            fillPaint.color = 0xFFE5E7EB.toInt()
        }
        c.drawCircle(cx - r * 0.25f, cy + r * 0.1f, r * 0.42f, fillPaint)
        c.drawCircle(cx + r * 0.25f, cy + r * 0.15f, r * 0.36f, fillPaint)
        c.drawCircle(cx, cy - r * 0.08f, r * 0.44f, fillPaint)
        if (icon == WeatherIcon.RAIN || icon == WeatherIcon.STORM) {
            strokePaint.strokeWidth = 2f * u
            strokePaint.color = 0xFF60A5FA.toInt()
            c.drawLine(cx - 3f * u, cy + r * 0.6f, cx - 5f * u, cy + r * 0.95f, strokePaint)
            c.drawLine(cx + 3f * u, cy + r * 0.6f, cx + 1f * u, cy + r * 0.95f, strokePaint)
        }
    }

    private fun drawEthernetIcon(c: Canvas, cx: Float, cy: Float, size: Float, u: Float) {
        strokePaint.strokeWidth = 2.2f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = 0xFFFFFFFF.toInt()
        val half = size * 0.72f
        val box = size * 0.24f
        tmpRect.set(cx - box, cy - half, cx + box, cy - half + box * 1.8f)
        c.drawRoundRect(tmpRect, 2f * u, 2f * u, strokePaint)
        c.drawLine(cx, cy - half + box * 1.8f, cx, cy, strokePaint)
        c.drawLine(cx - half * 0.75f, cy, cx + half * 0.75f, cy, strokePaint)
        c.drawLine(cx - half * 0.75f, cy, cx - half * 0.75f, cy + half * 0.45f, strokePaint)
        c.drawLine(cx + half * 0.75f, cy, cx + half * 0.75f, cy + half * 0.45f, strokePaint)
        tmpRect.set(cx - half * 0.75f - box, cy + half * 0.45f, cx - half * 0.75f + box, cy + half * 0.45f + box * 1.8f)
        c.drawRoundRect(tmpRect, 2f * u, 2f * u, strokePaint)
        tmpRect.set(cx + half * 0.75f - box, cy + half * 0.45f, cx + half * 0.75f + box, cy + half * 0.45f + box * 1.8f)
        c.drawRoundRect(tmpRect, 2f * u, 2f * u, strokePaint)
    }

    private fun drawWifiIcon(c: Canvas, cx: Float, bottomY: Float, radius: Float, bars: Int, online: Boolean, u: Float) {
        val activeCol = if (online) 0xFFFFFFFF.toInt() else 0xFFF87171.toInt()
        val dimCol = 0x44FFFFFF
        fillPaint.color = if (online && bars >= 1) activeCol else dimCol
        c.drawCircle(cx, bottomY, 2.5f * u, fillPaint)
        strokePaint.strokeWidth = 2.4f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        for (arc in 1..3) {
            val r = radius * (0.40f + arc * 0.24f)
            tmpRect.set(cx - r, bottomY - r, cx + r, bottomY + r)
            strokePaint.color = if (online && bars >= arc + 1) activeCol else dimCol
            c.drawArc(tmpRect, 225f, 90f, false, strokePaint)
        }
    }

    private fun drawGearIcon(c: Canvas, cx: Float, cy: Float, r: Float, focused: Boolean, u: Float) {
        strokePaint.strokeWidth = 2.8f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = if (focused) 0xFFFFFFFF.toInt() else 0xD8FFFFFF.toInt()
        c.drawCircle(cx, cy, r * 0.56f, strokePaint)
        val inner = r * 0.80f
        val outer = r * 1.18f
        for (i in 0 until 8) {
            val ang = i * (Math.PI / 4.0)
            c.drawLine(
                cx + cos(ang).toFloat() * inner,
                cy + sin(ang).toFloat() * inner,
                cx + cos(ang).toFloat() * outer,
                cy + sin(ang).toFloat() * outer,
                strokePaint
            )
        }
    }

    private fun drawSlidersIcon(c: Canvas, cx: Float, cy: Float, size: Float, focused: Boolean, u: Float) {
        val col = if (focused) 0xFFFFFFFF.toInt() else 0xD8FFFFFF.toInt()
        val half = size / 2f
        strokePaint.strokeWidth = 2.6f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = col
        fillPaint.color = col
        for (i in 0..2) {
            val y = cy + SLIDER_OFFSETS[i] * size
            c.drawLine(cx - half, y, cx + half, y, strokePaint)
            c.drawCircle(cx + SLIDER_KNOBS[i] * size, y, 4.2f * u, fillPaint)
        }
    }
}
