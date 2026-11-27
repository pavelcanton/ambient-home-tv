package com.will.ambienthome.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.will.ambienthome.model.HomeNavigation
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.TopAction
import com.will.ambienthome.model.WeatherIcon
import com.will.ambienthome.model.WeatherSnapshot
import kotlin.math.roundToInt

/**
 * Рендерер верхней панели главного экрана (выделен из HomeRenderer для Deslop).
 * Рисует часы (с ночным приглушением), динамические бейджи (Погода, BT 🎧, Таймер сна 🌙),
 * пилюлю Wi-Fi/LAN, кнопки Быстрых действий и Настроек, плюс всплывающую подсказку фокуса.
 */
internal class TopBarRenderer(
    private val surfaceCache: HomeSurfaceCache,
    private val tfLight: Typeface,
    private val tfMedium: Typeface
) {

    private val panelBmpPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val actionRects = Array(TopAction.entries.size) { RectF() }
    private val visibleList = ArrayList<TopAction>(TopAction.entries.size)

    private var cachedWifiLabel: String = "Нет сети"
    private var cachedWifiLabelW: Float = 0f
    private var cachedWeatherLabel: String = ""
    private var cachedWeatherLabelW: Float = 0f
    private var cachedSleepLabel: String = ""
    private var cachedSleepLabelW: Float = 0f

    fun syncLabels(
        u: Float,
        status: SystemStatus,
        weather: WeatherSnapshot?,
        sleepBadge: String
    ) {
        textPaint.typeface = tfMedium
        textPaint.textSize = 21f * u
        cachedWifiLabel = ellipsize(status.shortLabel, textPaint, 185f * u)
        cachedWifiLabelW = textPaint.measureText(cachedWifiLabel)
        cachedWeatherLabel = weather?.pillTempText.orEmpty()
        cachedWeatherLabelW = if (cachedWeatherLabel.isNotEmpty()) textPaint.measureText(cachedWeatherLabel) else 0f
        cachedSleepLabel = sleepBadge
        cachedSleepLabelW = if (cachedSleepLabel.isNotEmpty()) textPaint.measureText(cachedSleepLabel) else 0f
    }

    fun syncVisibleActions(hasWeather: Boolean, btConnected: Boolean, sleepActive: Boolean): Int {
        visibleList.clear()
        if (hasWeather) visibleList.add(TopAction.WEATHER)
        if (btConnected) visibleList.add(TopAction.BLUETOOTH)
        if (sleepActive) visibleList.add(TopAction.SLEEP_TIMER)
        visibleList.add(TopAction.WIFI)
        visibleList.add(TopAction.QUICK_CONTROLS)
        visibleList.add(TopAction.ANDROID_SETTINGS)
        visibleList.add(TopAction.LAUNCHER_SETTINGS)
        return visibleList.size
    }

    fun actionAt(index: Int): TopAction =
        visibleList.getOrElse(index) { TopAction.LAUNCHER_SETTINGS }

    fun draw(canvas: Canvas, frame: HomeRenderFrame, u: Float) {
        if (frame.showClock) {
            drawTopClock(canvas, frame.timeText, frame.dateText, frame.chromeAlpha, frame.nightActive, u)
        }
        drawActionButtons(canvas, frame, u)
        drawTooltip(canvas, frame.width, frame.nav, frame.chromeAlpha, u)
    }

    fun hitTest(x: Float, y: Float): HomeRenderer.HitTarget? {
        for (i in visibleList.indices) {
            val action = visibleList[i]
            if (actionRects[action.ordinal].contains(x, y)) {
                return HomeRenderer.HitTarget.TopButton(i, action)
            }
        }
        return null
    }

    private fun drawTopClock(
        c: Canvas,
        timeText: String,
        dateText: String,
        alpha: Int,
        nightActive: Boolean,
        u: Float
    ) {
        val lum = NightSchedule.clockLuminanceAlpha(nightActive)
        val effAlpha = (alpha * lum) / 255
        textPaint.typeface = tfLight
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 108f * u
        textPaint.letterSpacing = -0.015f
        drawLegibleText(c, timeText, 76f * u, 148f * u, 0xFFFFFFFF.toInt(), effAlpha, u)

        textPaint.textSize = 28f * u
        textPaint.letterSpacing = 0.03f
        drawLegibleText(c, dateText, 81f * u, 194f * u, 0xE6FFFFFF.toInt(), effAlpha, u)
        textPaint.letterSpacing = 0f
    }

    private fun drawActionButtons(c: Canvas, frame: HomeRenderFrame, u: Float) {
        val btnH = 56f * u
        val btnPx = btnH.roundToInt().coerceAtLeast(1)
        val gap = 12f * u
        val top = 54f * u
        var cursorRight = frame.width - 68f * u
        panelBmpPaint.alpha = frame.chromeAlpha

        val weatherIcon = frame.weather?.icon ?: WeatherIcon.CLOUDY
        for (i in visibleList.lastIndex downTo 0) {
            val action = visibleList[i]
            val btnW = buttonWidth(action, btnH, u)
            val left = cursorRight - btnW
            actionRects[action.ordinal].set(left, top, cursorRight, top + btnH)
            val focused = frame.nav.row == HomeNavigation.ROW_TOP && frame.nav.topFocus == i
            val extra = when (action) {
                TopAction.WEATHER -> cachedWeatherLabel
                TopAction.SLEEP_TIMER -> cachedSleepLabel
                else -> ""
            }
            val bmp = surfaceCache.topButton(
                action = action,
                status = frame.status,
                wifiLabel = cachedWifiLabel,
                extraLabel = extra,
                weatherIcon = weatherIcon,
                wPx = btnW.roundToInt(),
                hPx = btnPx,
                focused = focused,
                u = u
            )
            c.drawBitmap(bmp, left, top, panelBmpPaint)
            cursorRight = left - gap
        }
        panelBmpPaint.alpha = 255
    }

    private fun buttonWidth(action: TopAction, btnH: Float, u: Float): Float = when (action) {
        TopAction.WIFI -> (cachedWifiLabelW + 84f * u).coerceIn(136f * u, 260f * u)
        TopAction.WEATHER -> (cachedWeatherLabelW + 64f * u).coerceIn(96f * u, 160f * u)
        TopAction.SLEEP_TIMER -> (cachedSleepLabelW + 60f * u).coerceIn(96f * u, 150f * u)
        else -> btnH
    }

    private fun drawTooltip(c: Canvas, w: Int, nav: HomeNavigation.NavState, alpha: Int, u: Float) {
        if (nav.row != HomeNavigation.ROW_TOP || visibleList.isEmpty()) return
        val action = visibleList.getOrElse(nav.topFocus) { TopAction.LAUNCHER_SETTINGS }
        val anchorRect = actionRects[action.ordinal]
        textPaint.typeface = tfLight
        textPaint.textSize = 18f * u
        textPaint.textAlign = Paint.Align.CENTER
        val halfTipW = textPaint.measureText(action.tooltip) / 2f
        val minCx = 68f * u + halfTipW
        val maxCx = w - 68f * u - halfTipW
        val tipCx = if (minCx <= maxCx) anchorRect.centerX().coerceIn(minCx, maxCx) else w / 2f
        drawLegibleText(c, action.tooltip, tipCx, anchorRect.bottom + 28f * u, 0xE6FFFFFF.toInt(), alpha, u)
    }

    private fun drawLegibleText(c: Canvas, text: String, x: Float, y: Float, color: Int, alpha: Int, u: Float) {
        textPaint.color = scaleAlpha(0x8A000000.toInt(), alpha)
        c.drawText(text, x, y + 2.2f * u, textPaint)
        textPaint.color = scaleAlpha(color, alpha)
        c.drawText(text, x, y, textPaint)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val avail = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        var count = paint.breakText(text, true, avail, null).coerceIn(0, text.length)
        if (count > 0 && count < text.length && Character.isHighSurrogate(text[count - 1])) count--
        if (count == 0) count = if (text.length >= 2 && Character.isSurrogatePair(text[0], text[1])) 2 else 1
        return text.substring(0, count) + ellipsis
    }

    private fun scaleAlpha(argb: Int, masterAlpha: Int): Int {
        val outA = (((argb ushr 24) and 0xFF) * masterAlpha) / 255
        return (outA shl 24) or (argb and 0x00FFFFFF)
    }
}
