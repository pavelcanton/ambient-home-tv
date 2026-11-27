package com.will.ambienthome.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.AmbientOverlayData
import com.will.ambienthome.model.NightSchedule
import kotlin.math.cos
import kotlin.math.sin

/**
 * Рендерер Ambient-часов и ненавязчивых оверлеев поверх HWC WallpaperSurfaceView (выделен для Deslop).
 * Поддерживает 4 стиля часов (Тонкие / Кино / Минимал / Аналоговые), перескок по 4 углам раз в 15 мин
 * с сохранением мягкого минутного дрейфа, ночное приглушение яркости, погоду, таймер сна,
 * подпись «Фото дня» и строку «Сейчас играет».
 */
internal class AmbientClockRenderer {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tfThin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun draw(
        canvas: Canvas,
        timeText: String,
        dateText: String,
        tickCount: Int,
        overlay: AmbientOverlayData
    ) {
        if (timeText.isEmpty()) return
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        val u = (ch / 1080f).coerceAtLeast(0.5f)
        val corner = (tickCount.coerceAtLeast(0) / 15) % 4
        val driftX = (sin(tickCount * 0.45) * 24.0).toFloat() * u
        val driftY = (cos(tickCount * 0.35) * 18.0).toFloat() * u
        val alpha = if (overlay.blackout) 92 else NightSchedule.clockLuminanceAlpha(overlay.nightActive)
        val rightAligned = corner == 2 || corner == 3
        val topAnchored = corner == 1 || corner == 2

        val anchorX = (if (rightAligned) cw - 96f * u else 96f * u) + driftX
        val anchorY = (if (topAnchored) 224f * u else ch - 160f * u) + driftY

        val subY = when (overlay.clockStyle) {
            AmbientClockStyle.THIN -> drawThinStyle(canvas, timeText, dateText, anchorX, anchorY, rightAligned, alpha, u)
            AmbientClockStyle.CINEMA -> drawCinemaStyle(canvas, timeText, dateText, anchorX, anchorY, rightAligned, alpha, overlay.nightActive, u)
            AmbientClockStyle.MINIMAL -> drawMinimalStyle(canvas, timeText, dateText, anchorX, anchorY, rightAligned, alpha, u)
            AmbientClockStyle.ANALOG -> drawAnalogStyle(canvas, timeText, dateText, anchorX, anchorY, rightAligned, alpha, u)
        }
        drawAmbientMeta(canvas, overlay, anchorX, subY, rightAligned, alpha, u)
        if (!overlay.blackout && overlay.caption.isNotBlank()) {
            drawPhotoCaption(canvas, overlay.caption, cw, ch, corner, alpha, u)
        }
    }

    private fun drawThinStyle(
        c: Canvas,
        time: String,
        date: String,
        x: Float,
        y: Float,
        right: Boolean,
        alpha: Int,
        u: Float
    ): Float {
        textPaint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        textPaint.typeface = tfThin
        textPaint.textSize = 152f * u
        textPaint.letterSpacing = -0.02f
        drawShadowed(c, time, x, y, argb(alpha, 0xFFFFFF), u)

        textPaint.typeface = tfLight
        textPaint.textSize = 34f * u
        textPaint.letterSpacing = 0.04f
        val dx = if (right) -4f * u else 6f * u
        drawShadowed(c, date, x + dx, y + 50f * u, argb((alpha * 86) / 100, 0xFFFFFF), u)
        textPaint.letterSpacing = 0f
        return y + 92f * u
    }

    private fun drawCinemaStyle(
        c: Canvas,
        time: String,
        date: String,
        x: Float,
        y: Float,
        right: Boolean,
        alpha: Int,
        night: Boolean,
        u: Float
    ): Float {
        textPaint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        textPaint.typeface = tfMedium
        textPaint.textSize = 144f * u
        textPaint.letterSpacing = 0.02f
        val mainRgb = if (night) 0xFFE3B8 else 0xFFF5E6
        drawShadowed(c, time, x, y, argb(alpha, mainRgb), u)

        val lineW = 140f * u
        val lineLeft = if (right) x - lineW else x + 4f * u
        fillPaint.color = argb((alpha * 65) / 100, 0xE8B46A)
        c.drawRect(lineLeft, y + 18f * u, lineLeft + lineW, y + 21f * u, fillPaint)

        textPaint.typeface = tfLight
        textPaint.textSize = 30f * u
        textPaint.letterSpacing = 0.08f
        drawShadowed(c, date.uppercase(), x, y + 58f * u, argb((alpha * 82) / 100, 0xFFFFFF), u)
        textPaint.letterSpacing = 0f
        return y + 98f * u
    }

    private fun drawMinimalStyle(
        c: Canvas,
        time: String,
        date: String,
        x: Float,
        y: Float,
        right: Boolean,
        alpha: Int,
        u: Float
    ): Float {
        textPaint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        textPaint.typeface = tfLight
        textPaint.textSize = 98f * u
        textPaint.letterSpacing = 0.01f
        drawShadowed(c, time, x, y, argb(alpha, 0xFFFFFF), u)

        textPaint.textSize = 26f * u
        textPaint.letterSpacing = 0.03f
        drawShadowed(c, date, x, y + 40f * u, argb((alpha * 78) / 100, 0xFFFFFF), u)
        textPaint.letterSpacing = 0f
        return y + 78f * u
    }

    private fun drawAnalogStyle(
        c: Canvas,
        time: String,
        date: String,
        x: Float,
        y: Float,
        right: Boolean,
        alpha: Int,
        u: Float
    ): Float {
        val radius = 84f * u
        val cx = if (right) x - radius else x + radius
        val cy = y - 34f * u
        drawAnalogDial(c, cx, cy, radius, time, alpha, u)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = tfLight
        textPaint.textSize = 28f * u
        textPaint.letterSpacing = 0.03f
        drawShadowed(c, date, cx, cy + radius + 42f * u, argb((alpha * 84) / 100, 0xFFFFFF), u)
        textPaint.letterSpacing = 0f
        return cy + radius + 82f * u
    }

    private fun drawAnalogDial(
        c: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        time: String,
        alpha: Int,
        u: Float
    ) {
        fillPaint.color = argb((alpha * 42) / 100, 0x0A0E15)
        c.drawCircle(cx, cy, r, fillPaint)
        strokePaint.strokeWidth = 2.6f * u
        strokePaint.color = argb((alpha * 75) / 100, 0xFFFFFF)
        c.drawCircle(cx, cy, r, strokePaint)

        strokePaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            val ang = Math.toRadians(i * 30.0 - 90.0)
            val major = i % 3 == 0
            val rIn = r * (if (major) 0.78f else 0.86f)
            val rOut = r * 0.93f
            strokePaint.strokeWidth = (if (major) 3.2f else 1.8f) * u
            strokePaint.color = argb((alpha * (if (major) 88 else 50)) / 100, 0xFFFFFF)
            c.drawLine(
                cx + (cos(ang) * rIn).toFloat(),
                cy + (sin(ang) * rIn).toFloat(),
                cx + (cos(ang) * rOut).toFloat(),
                cy + (sin(ang) * rOut).toFloat(),
                strokePaint
            )
        }
        val parts = time.split(':')
        val hour = (parts.getOrNull(0)?.toIntOrNull() ?: 0) % 12
        val min = (parts.getOrNull(1)?.toIntOrNull() ?: 0) % 60
        drawHand(c, cx, cy, (hour + min / 60.0) * 30.0 - 90.0, r * 0.50f, 5.2f * u, argb(alpha, 0xFFFFFF))
        drawHand(c, cx, cy, min * 6.0 - 90.0, r * 0.72f, 3.2f * u, argb((alpha * 90) / 100, 0x7FD8A0))
        fillPaint.color = argb(alpha, 0xFFFFFF)
        c.drawCircle(cx, cy, 4.5f * u, fillPaint)
    }

    private fun drawHand(c: Canvas, cx: Float, cy: Float, deg: Double, len: Float, width: Float, color: Int) {
        val rad = Math.toRadians(deg)
        strokePaint.strokeWidth = width
        strokePaint.color = color
        c.drawLine(cx, cy, cx + (cos(rad) * len).toFloat(), cy + (sin(rad) * len).toFloat(), strokePaint)
    }

    private fun drawAmbientMeta(
        c: Canvas,
        overlay: AmbientOverlayData,
        x: Float,
        startY: Float,
        right: Boolean,
        alpha: Int,
        u: Float
    ) {
        val parts = ArrayList<String>(2)
        if (overlay.weatherSummary.isNotBlank() && !overlay.blackout) parts.add(overlay.weatherSummary)
        if (overlay.sleepBadge.isNotBlank()) parts.add("🌙 ${overlay.sleepBadge}")
        var curY = startY
        textPaint.typeface = tfLight
        textPaint.textAlign = if (overlay.clockStyle == AmbientClockStyle.ANALOG) {
            Paint.Align.CENTER
        } else if (right) {
            Paint.Align.RIGHT
        } else {
            Paint.Align.LEFT
        }
        val textX = if (overlay.clockStyle == AmbientClockStyle.ANALOG) {
            if (right) x - 84f * u else x + 84f * u
        } else {
            x
        }
        if (parts.isNotEmpty()) {
            textPaint.textSize = 24f * u
            drawShadowed(c, parts.joinToString("   ·   "), textX, curY, argb((alpha * 80) / 100, 0xDCE8FF), u)
            curY += 34f * u
        }
        if (overlay.nowPlayingLine.isNotBlank() && !overlay.blackout) {
            textPaint.textSize = 22f * u
            drawShadowed(c, "♫  ${overlay.nowPlayingLine}", textX, curY, argb((alpha * 78) / 100, 0x7FD8A0), u)
        }
    }

    private fun drawPhotoCaption(
        c: Canvas,
        caption: String,
        cw: Float,
        ch: Float,
        clockCorner: Int,
        alpha: Int,
        u: Float
    ) {
        val placeRight = clockCorner != 3
        textPaint.typeface = tfLight
        textPaint.textSize = 20f * u
        textPaint.textAlign = if (placeRight) Paint.Align.RIGHT else Paint.Align.LEFT
        val cx = if (placeRight) cw - 68f * u else 68f * u
        drawShadowed(c, caption, cx, ch - 44f * u, argb((alpha * 62) / 100, 0xFFFFFF), u)
    }

    private fun drawShadowed(c: Canvas, text: String, x: Float, y: Float, color: Int, u: Float) {
        textPaint.color = 0x8A000000.toInt()
        c.drawText(text, x, y + 2.2f * u, textPaint)
        textPaint.color = color
        c.drawText(text, x, y, textPaint)
    }

    private fun argb(alpha: Int, rgb: Int): Int =
        ((alpha.coerceIn(0, 255)) shl 24) or (rgb and 0x00FFFFFF)
}
