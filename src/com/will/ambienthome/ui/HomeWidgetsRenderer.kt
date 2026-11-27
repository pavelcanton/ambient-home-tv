package com.will.ambienthome.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.HomeNavigation
import com.will.ambienthome.model.NowPlayingState

/**
 * Рендерер компактных виджетов на HOME:
 * 1) Матовая пилюля «Сейчас играет» (Now Playing + Open / Play-Pause / Next, 100% D-pad + air-mouse);
 * 2) Отменяемый баннер обратного отсчёта (15с Таймер сна / 3с Автозапуск после ребута).
 * Гарантирует 0 аллокаций в draw().
 */
internal class HomeWidgetsRenderer(
    private val tfLight: Typeface,
    private val tfMedium: Typeface
) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private val mediaCardRect = RectF()
    private val openBtnRect = RectF()
    private val playBtnRect = RectF()
    private val nextBtnRect = RectF()
    private val bannerRect = RectF()

    private var cachedTitle: String = ""
    private var cachedSubtitleLine: String = ""
    private var lastTitleRaw: String = ""
    private var lastSubRaw: String = ""
    private var lastU: Float = 0f
    private var lastMaxW: Float = 0f

    fun draw(canvas: Canvas, frame: HomeRenderFrame, u: Float) {
        val np = frame.nowPlaying
        if (np != null && frame.nav.mode == HomeMode.DOCK) {
            drawNowPlayingCard(canvas, np, frame.nav, frame.chromeAlpha, u)
        }
        val banner = frame.countdownBanner
        if (banner.isNotEmpty()) {
            drawCountdownBanner(canvas, banner, frame.width, frame.chromeAlpha, u)
        }
    }

    fun hitTestMedia(x: Float, y: Float, frame: HomeRenderFrame): HomeRenderer.HitTarget? {
        if (frame.nowPlaying == null || frame.nav.mode != HomeMode.DOCK) return null
        if (!mediaCardRect.contains(x, y)) return null
        return when {
            nextBtnRect.contains(x, y) -> HomeRenderer.HitTarget.MediaControl(HomeNavigation.MEDIA_BTN_NEXT)
            playBtnRect.contains(x, y) -> HomeRenderer.HitTarget.MediaControl(HomeNavigation.MEDIA_BTN_PLAY_PAUSE)
            else -> HomeRenderer.HitTarget.MediaControl(HomeNavigation.MEDIA_BTN_OPEN)
        }
    }

    private fun drawNowPlayingCard(
        c: Canvas,
        np: NowPlayingState,
        nav: HomeNavigation.NavState,
        alpha: Int,
        u: Float
    ) {
        val left = 76f * u
        val top = 224f * u
        val cardW = 500f * u
        val cardH = 76f * u
        mediaCardRect.set(left, top, left + cardW, top + cardH)

        val btnSz = 46f * u
        val btnTop = top + (cardH - btnSz) / 2f
        val nextLeft = mediaCardRect.right - 16f * u - btnSz
        val playLeft = nextLeft - 10f * u - btnSz
        nextBtnRect.set(nextLeft, btnTop, nextLeft + btnSz, btnTop + btnSz)
        playBtnRect.set(playLeft, btnTop, playLeft + btnSz, btnTop + btnSz)
        openBtnRect.set(left, top, playLeft - 8f * u, top + cardH)

        val inMedia = nav.row == HomeNavigation.ROW_MEDIA
        fillPaint.color = scaleAlpha(0xBA0C111A.toInt(), alpha)
        c.drawRoundRect(mediaCardRect, 22f * u, 22f * u, fillPaint)
        strokePaint.strokeWidth = if (inMedia && nav.mediaFocus == HomeNavigation.MEDIA_BTN_OPEN) 3f * u else 1.3f * u
        strokePaint.color = scaleAlpha(
            if (inMedia && nav.mediaFocus == HomeNavigation.MEDIA_BTN_OPEN) 0xFFFFFFFF.toInt() else 0x30FFFFFF,
            alpha
        )
        c.drawRoundRect(mediaCardRect, 22f * u, 22f * u, strokePaint)

        drawNowPlayingText(c, np, left + 22f * u, top, openBtnRect.width() - 32f * u, alpha, u)
        drawMediaButton(c, playBtnRect, if (np.isPlaying) "❚❚" else "▶", inMedia && nav.mediaFocus == HomeNavigation.MEDIA_BTN_PLAY_PAUSE, alpha, u)
        drawMediaButton(c, nextBtnRect, "⏭", inMedia && nav.mediaFocus == HomeNavigation.MEDIA_BTN_NEXT, alpha, u)
    }

    private fun drawNowPlayingText(
        c: Canvas,
        np: NowPlayingState,
        x: Float,
        top: Float,
        maxW: Float,
        alpha: Int,
        u: Float
    ) {
        if (lastTitleRaw != np.displayTitle || lastSubRaw != np.displaySubtitle || lastU != u || lastMaxW != maxW) {
            textPaint.typeface = tfMedium
            textPaint.textSize = 21f * u
            cachedTitle = ellipsize(np.displayTitle, textPaint, maxW)
            textPaint.typeface = tfLight
            textPaint.textSize = 16f * u
            cachedSubtitleLine = ellipsize("♫  ${np.displaySubtitle}", textPaint, maxW)
            lastTitleRaw = np.displayTitle
            lastSubRaw = np.displaySubtitle
            lastU = u
            lastMaxW = maxW
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = tfMedium
        textPaint.textSize = 21f * u
        textPaint.color = scaleAlpha(0xFFFFFFFF.toInt(), alpha)
        c.drawText(cachedTitle, x, top + 33f * u, textPaint)

        textPaint.typeface = tfLight
        textPaint.textSize = 16f * u
        textPaint.color = scaleAlpha(0xB87FD8A0.toInt(), alpha)
        c.drawText(cachedSubtitleLine, x, top + 58f * u, textPaint)
    }

    private fun drawMediaButton(c: Canvas, rect: RectF, glyph: String, focused: Boolean, alpha: Int, u: Float) {
        fillPaint.color = scaleAlpha(if (focused) 0xE6223044.toInt() else 0x80151D29.toInt(), alpha)
        c.drawRoundRect(rect, 14f * u, 14f * u, fillPaint)
        strokePaint.strokeWidth = if (focused) 2.8f * u else 1.2f * u
        strokePaint.color = scaleAlpha(if (focused) 0xFFFFFFFF.toInt() else 0x33FFFFFF, alpha)
        c.drawRoundRect(rect, 14f * u, 14f * u, strokePaint)
        textPaint.typeface = tfMedium
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 18f * u
        textPaint.color = scaleAlpha(0xFFFFFFFF.toInt(), alpha)
        c.drawText(glyph, rect.centerX(), rect.centerY() + 6f * u, textPaint)
    }

    private fun drawCountdownBanner(c: Canvas, text: String, viewW: Int, alpha: Int, u: Float) {
        textPaint.typeface = tfMedium
        textPaint.textSize = 22f * u
        textPaint.textAlign = Paint.Align.CENTER
        val tw = textPaint.measureText(text)
        val bw = (tw + 56f * u).coerceIn(360f * u, viewW - 140f * u)
        val bh = 54f * u
        val cx = viewW / 2f
        val top = 148f * u
        bannerRect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        fillPaint.color = scaleAlpha(0xE81B1210.toInt(), alpha)
        c.drawRoundRect(bannerRect, 18f * u, 18f * u, fillPaint)
        strokePaint.strokeWidth = 2f * u
        strokePaint.color = scaleAlpha(0xCCFBBF24.toInt(), alpha)
        c.drawRoundRect(bannerRect, 18f * u, 18f * u, strokePaint)
        textPaint.color = scaleAlpha(0xFFFFFFFF.toInt(), alpha)
        c.drawText(text, cx, bannerRect.centerY() + 7f * u, textPaint)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val avail = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        var count = paint.breakText(text, true, avail, null).coerceIn(0, text.length)
        if (count > 0 && count < text.length && Character.isHighSurrogate(text[count - 1])) {
            count--
        }
        return if (count <= 0) ellipsis else text.substring(0, count) + ellipsis
    }

    private fun scaleAlpha(argb: Int, masterAlpha: Int): Int {
        val outA = (((argb ushr 24) and 0xFF) * masterAlpha) / 255
        return (outA shl 24) or (argb and 0x00FFFFFF)
    }
}
