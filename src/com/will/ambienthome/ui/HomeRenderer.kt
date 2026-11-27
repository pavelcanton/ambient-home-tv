package com.will.ambienthome.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.will.ambienthome.model.AppEntry
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.HomeNavigation
import com.will.ambienthome.model.NowPlayingState
import com.will.ambienthome.model.SleepTimerState
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.TopAction
import com.will.ambienthome.model.WeatherSnapshot
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Переиспользуемый снимок кадра главного экрана.
 * Аллоцируется один раз в HomeCanvasView и обновляется in-place перед HomeRenderer.draw()
 * (гарантирует ноль GC-аллокаций на кадр при 60 fps на бюджетных Android TV GPU).
 */
internal class HomeRenderFrame {
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var nav: HomeNavigation.NavState = HomeNavigation.NavState()
        private set
    var apps: List<AppEntry> = emptyList()
        private set
    var status: SystemStatus = SystemStatus()
        private set
    var showClock: Boolean = true
        private set
    var timeText: String = ""
        private set
    var dateText: String = ""
        private set
    var dockScroll: Float = 0f
        private set
    var focusScale: Float = 1f
        private set
    var chromeAlpha: Int = 255
        private set
    var showHint: Boolean = false
        private set
    var nightActive: Boolean = false
        private set
    var btConnected: Boolean = false
        private set
    var weather: WeatherSnapshot? = null
        private set
    var sleepTimer: SleepTimerState = SleepTimerState()
        private set
    var nowPlaying: NowPlayingState? = null
        private set
    var countdownBanner: String = ""
        private set

    internal fun updateCore(
        width: Int, height: Int, nav: HomeNavigation.NavState, apps: List<AppEntry>,
        status: SystemStatus, showClock: Boolean, timeText: String, dateText: String,
        dockScroll: Float, focusScale: Float, chromeAlpha: Int, showHint: Boolean
    ) {
        this.width = width; this.height = height; this.nav = nav; this.apps = apps
        this.status = status; this.showClock = showClock; this.timeText = timeText; this.dateText = dateText
        this.dockScroll = dockScroll; this.focusScale = focusScale; this.chromeAlpha = chromeAlpha; this.showHint = showHint
    }

    internal fun updateWidgets(
        nightActive: Boolean,
        btConnected: Boolean,
        weather: WeatherSnapshot?,
        sleepTimer: SleepTimerState,
        nowPlaying: NowPlayingState?,
        countdownBanner: String
    ) {
        this.nightActive = nightActive
        this.btConnected = btConnected
        this.weather = weather
        this.sleepTimer = sleepTimer
        this.nowPlaying = nowPlaying
        this.countdownBanner = countdownBanner
    }
}

/**
 * Движок вёрстки, отрисовки и hit-testing главного экрана Ambient Home 3.0.0.
 * Топ-бар делегирован в TopBarRenderer, виджеты («Сейчас играет», баннеры) — в HomeWidgetsRenderer.
 */
internal class HomeRenderer {

    sealed class HitTarget {
        data class TopButton(val index: Int, val action: TopAction) : HitTarget()
        data class MediaControl(val button: Int) : HitTarget()
        data class DockChevron(val dir: Int) : HitTarget()
        data class AppTile(val index: Int) : HitTarget()
        data class GridPageDot(val page: Int) : HitTarget()
        object None : HitTarget()
    }

    private class DockMetrics {
        val rect = RectF()
        var totalItems: Int = 1
        var maxVisible: Int = 7
        var halfSpan: Float = 3f
        var iconSize: Float = 128f
        var halfIcon: Float = 64f
        var step: Float = 162f
        var tileTop: Float = 0f

        fun canScrollLeft(scroll: Float): Boolean = totalItems > maxVisible && scroll > halfSpan + 0.15f
        fun canScrollRight(scroll: Float): Boolean = totalItems > maxVisible && scroll < (totalItems - 1) - halfSpan - 0.15f
    }

    private class GridMetrics {
        val panelRect = RectF()
        var padX: Float = 48f
        var gridLeft: Float = 0f
        var gridRight: Float = 0f
        var gridTop: Float = 0f
        var gridBottom: Float = 0f
        var cellW: Float = 200f
        var cellH: Float = 200f
        var iconSize: Float = 108f
        var dotY: Float = 0f
        var dotGap: Float = 20f

        fun dotsStartX(pages: Int): Float = panelRect.centerX() - (pages - 1) * dotGap / 2f
    }

    private val panelBmpPaint = Paint()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val surfaceCache = HomeSurfaceCache(tfMedium)
    private val topBar = TopBarRenderer(surfaceCache, tfLight, tfMedium)
    private val widgets = HomeWidgetsRenderer(tfLight, tfMedium)

    private val tmpRect = RectF()
    private val pillRect = RectF()
    private val dock = DockMetrics()
    private val grid = GridMetrics()

    private var u: Float = 1f
    private var viewportH: Int = 1080
    private var cachedDockTitles: Array<String> = emptyArray()
    private var cachedGridHeader: String = "Все приложения  ·  0"
    private var cachedGridLabels: Array<String> = emptyArray()

    fun syncViewport(
        w: Int,
        h: Int,
        status: SystemStatus,
        weather: WeatherSnapshot?,
        btConnected: Boolean,
        sleepTimer: SleepTimerState,
        apps: List<AppEntry>,
        pinned: Set<String>
    ): Int {
        viewportH = h.coerceAtLeast(1)
        u = (viewportH / 1080f).coerceAtLeast(0.5f)
        val topCount = syncTopMeta(status, weather, btConnected, sleepTimer)
        syncApps(apps, pinned, w)
        return topCount
    }

    fun syncTopMeta(
        status: SystemStatus,
        weather: WeatherSnapshot?,
        btConnected: Boolean,
        sleepTimer: SleepTimerState
    ): Int {
        topBar.syncLabels(u, status, weather, sleepTimer.badgeText)
        return topBar.syncVisibleActions(weather != null, btConnected, sleepTimer.active)
    }

    fun syncApps(apps: List<AppEntry>, pinned: Set<String>, viewWidth: Int) {
        val count = apps.size
        cachedGridHeader = "Все приложения  ·  $count"
        val maxDockTitleW = (viewWidth - 260f * u).coerceAtLeast(240f * u)
        textPaint.typeface = tfMedium
        textPaint.textSize = 25f * u
        cachedDockTitles = Array(count) { i ->
            val e = apps[i]
            val raw = if (e.packageName in pinned) "★  ${e.label}" else e.label
            ellipsize(raw, textPaint, maxDockTitleW)
        }
        updateGridMetrics(viewWidth, viewportH)
        val maxLabelW = (grid.cellW.coerceAtLeast(120f * u) - 20f * u).coerceAtLeast(60f * u)
        textPaint.typeface = tfMedium
        textPaint.textSize = 21f * u
        cachedGridLabels = Array(count) { i ->
            val e = apps[i]
            val raw = if (e.packageName in pinned) "★ ${e.label}" else e.label
            ellipsize(raw, textPaint, maxLabelW)
        }
    }

    fun topActionAt(index: Int): TopAction = topBar.actionAt(index)

    fun maxDockVisible(viewWidth: Int): Int {
        val avail = viewWidth - 2f * 64f * u - 2f * 58f * u
        val step = (128f + 34f) * u
        return (avail / step).toInt().coerceIn(5, 9)
    }

    fun draw(canvas: Canvas, frame: HomeRenderFrame) {
        viewportH = frame.height.coerceAtLeast(1)
        topBar.draw(canvas, frame, u)
        if (frame.nav.mode == HomeMode.DOCK) {
            drawDock(canvas, frame)
        } else {
            drawGrid(canvas, frame)
        }
        widgets.draw(canvas, frame, u)
    }

    fun hitTest(x: Float, y: Float, frame: HomeRenderFrame): HitTarget {
        topBar.hitTest(x, y)?.let { return it }
        widgets.hitTestMedia(x, y, frame)?.let { return it }
        if (frame.nav.mode == HomeMode.DOCK) {
            updateDockMetrics(frame.width, viewportH, frame.apps.size)
            if (dock.rect.contains(x, y)) return hitTestDock(x, y, frame.dockScroll)
        } else if (frame.nav.mode == HomeMode.GRID) {
            updateGridMetrics(frame.width, viewportH)
            if (grid.panelRect.contains(x, y)) return hitTestGrid(x, y, frame.nav.gridPage, frame.apps.size)
        }
        return HitTarget.None
    }

    internal fun release(panelsOnly: Boolean = false) {
        if (panelsOnly) surfaceCache.evictPanels(keepDock = false) else surfaceCache.release()
    }

    private fun updateDockMetrics(w: Int, h: Int, appCount: Int) {
        val n = appCount + 1
        val iconSize = 128f * u
        val gap = 34f * u
        val padX = 46f * u
        val padY = 30f * u
        val maxVis = maxDockVisible(w)
        val visibleCount = min(n, maxVis)
        val dockW = if (n <= maxVis) {
            visibleCount * iconSize + (visibleCount - 1) * gap + padX * 2f
        } else {
            w - 2f * 64f * u
        }
        val dockH = iconSize + padY * 2f
        val dockX = (w - dockW) / 2f
        val dockY = h - dockH - 50f * u

        dock.rect.set(dockX, dockY, dockX + dockW, dockY + dockH)
        dock.totalItems = n
        dock.maxVisible = maxVis
        dock.halfSpan = (maxVis - 1) / 2f
        dock.iconSize = iconSize
        dock.halfIcon = iconSize / 2f
        dock.step = iconSize + gap
        dock.tileTop = dockY + padY
    }

    private fun updateGridMetrics(w: Int, h: Int) {
        grid.panelRect.set(70f * u, 218f * u, w - 70f * u, h - 42f * u)
        grid.padX = 48f * u
        grid.gridLeft = grid.panelRect.left + grid.padX
        grid.gridRight = grid.panelRect.right - grid.padX
        grid.gridTop = grid.panelRect.top + 86f * u
        grid.gridBottom = grid.panelRect.bottom - 48f * u
        grid.cellW = (grid.gridRight - grid.gridLeft) / HomeNavigation.GRID_COLS
        grid.cellH = (grid.gridBottom - grid.gridTop) / HomeNavigation.GRID_ROWS
        grid.iconSize = min(grid.cellW * 0.44f, 108f * u)
        grid.dotY = grid.panelRect.bottom - 22f * u
        grid.dotGap = 20f * u
    }

    private fun drawDock(c: Canvas, frame: HomeRenderFrame) {
        updateDockMetrics(frame.width, frame.height, frame.apps.size)
        val dockBg = surfaceCache.panel(true, dock.rect.width().roundToInt(), dock.rect.height().roundToInt(), u)
        drawAlphaBitmap(c, dockBg, dock.rect.left, dock.rect.top, frame.chromeAlpha)
        drawDockTiles(c, frame)
        drawDockChevrons(c, frame.dockScroll, frame.chromeAlpha)
        drawDockTitlePill(c, frame.nav, frame.chromeAlpha, frame.showHint)
    }

    private fun drawDockTiles(c: Canvas, frame: HomeRenderFrame) {
        val centerX = dock.rect.centerX()
        for (pass in 0..1) {
            for (i in 0 until dock.totalItems) {
                val isFocused = frame.nav.row == HomeNavigation.ROW_CONTENT && i == frame.nav.focusIndex
                if ((pass == 0 && isFocused) || (pass == 1 && !isFocused)) continue
                val cx = centerX + (i - frame.dockScroll) * dock.step
                val edgeFade = computeDockEdgeFade(cx)
                if (edgeFade <= 0.02f) continue

                val itemAlpha = (frame.chromeAlpha * edgeFade).roundToInt().coerceIn(0, 255)
                val edgeScale = 0.86f + 0.14f * edgeFade
                val isApp = i < frame.apps.size
                val bmp = if (isApp) frame.apps[i].tileBitmap else surfaceCache.allAppsTile(isFocused)
                val glow = if (isApp) (frame.apps[i].accentColor and 0x00FFFFFF) or 0x3D000000 else 0x3D7FD8A0
                drawTileSurface(c, bmp, cx - dock.halfIcon, dock.tileTop, dock.iconSize, isFocused, frame.focusScale, edgeScale, glow, itemAlpha)
            }
        }
    }

    private fun drawDockChevrons(c: Canvas, dockScroll: Float, alpha: Int) {
        val cy = dock.rect.centerY()
        if (dock.canScrollLeft(dockScroll)) drawEdgeChevron(c, dock.rect.left + 20f * u, cy, -1f, alpha)
        if (dock.canScrollRight(dockScroll)) drawEdgeChevron(c, dock.rect.right - 20f * u, cy, 1f, alpha)
    }

    private fun drawDockTitlePill(c: Canvas, nav: HomeNavigation.NavState, alpha: Int, showHint: Boolean) {
        if (nav.row != HomeNavigation.ROW_CONTENT) return
        val isApp = nav.focusIndex in cachedDockTitles.indices
        val title = if (isApp) cachedDockTitles[nav.focusIndex] else "Все приложения"
        val pillH = 44f * u
        val pillBmp = surfaceCache.titlePill(title, pillH.roundToInt(), u)
        val pillW = pillBmp.width.toFloat()
        val pillBottom = dock.rect.top - 16f * u
        val cx = dock.rect.centerX()
        pillRect.set(cx - pillW / 2f, pillBottom - pillH, cx + pillW / 2f, pillBottom)
        drawAlphaBitmap(c, pillBmp, pillRect.left, pillRect.top, alpha)

        if (isApp && showHint) {
            textPaint.typeface = tfLight
            textPaint.textSize = 18f * u
            textPaint.textAlign = Paint.Align.RIGHT
            drawLegibleText(c, "Удерж. OK — меню", dock.rect.right - 12f * u, dock.rect.top - 26f * u, 0xB8FFFFFF.toInt(), alpha)
        }
    }

    private fun drawGrid(c: Canvas, frame: HomeRenderFrame) {
        updateGridMetrics(frame.width, frame.height)
        val gridBg = surfaceCache.panel(false, grid.panelRect.width().roundToInt(), grid.panelRect.height().roundToInt(), u)
        drawAlphaBitmap(c, gridBg, grid.panelRect.left, grid.panelRect.top, frame.chromeAlpha)
        drawGridHeader(c, frame.chromeAlpha)
        if (frame.apps.isEmpty()) {
            drawGridEmptyState(c, frame.chromeAlpha)
            return
        }
        drawGridCells(c, frame)
        drawGridPageDots(c, frame.apps.size, frame.nav.gridPage, frame.chromeAlpha)
    }

    private fun drawGridHeader(c: Canvas, alpha: Int) {
        val headerY = grid.panelRect.top + 48f * u
        textPaint.typeface = tfMedium
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 26f * u
        textPaint.color = scaleAlpha(0xF2FFFFFF.toInt(), alpha)
        c.drawText(cachedGridHeader, grid.panelRect.left + 44f * u, headerY, textPaint)

        textPaint.typeface = tfLight
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18f * u
        textPaint.color = scaleAlpha(0x9EFFFFFF.toInt(), alpha)
        c.drawText("OK — открыть   ·   Удерж. OK — меню   ·   Назад — док", grid.panelRect.right - 44f * u, headerY, textPaint)

        fillPaint.color = scaleAlpha(0x1CFFFFFF, alpha)
        c.drawRect(grid.panelRect.left + 40f * u, grid.panelRect.top + 68f * u, grid.panelRect.right - 40f * u, grid.panelRect.top + 69.5f * u, fillPaint)
    }

    private fun drawGridEmptyState(c: Canvas, alpha: Int) {
        textPaint.typeface = tfLight
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 26f * u
        textPaint.color = scaleAlpha(0xCCFFFFFF.toInt(), alpha)
        c.drawText("Все приложения скрыты  ·  откройте настройки лаунчера", grid.panelRect.centerX(), (grid.gridTop + grid.gridBottom) / 2f, textPaint)
    }

    private fun drawGridCells(c: Canvas, frame: HomeRenderFrame) {
        val start = frame.nav.gridPage * HomeNavigation.GRID_PER_PAGE
        val end = min(frame.apps.size, start + HomeNavigation.GRID_PER_PAGE)
        val halfIcon = grid.iconSize / 2f
        for (i in start until end) {
            val local = i - start
            val col = local % HomeNavigation.GRID_COLS
            val r = local / HomeNavigation.GRID_COLS
            val cx = grid.gridLeft + grid.cellW * (col + 0.5f)
            val cy = grid.gridTop + grid.cellH * (r + 0.5f) - 14f * u
            val focused = frame.nav.row == HomeNavigation.ROW_CONTENT && i == frame.nav.focusIndex
            val entry = frame.apps[i]
            val glow = (entry.accentColor and 0x00FFFFFF) or 0x3D000000
            drawTileSurface(c, entry.tileBitmap, cx - halfIcon, cy - halfIcon, grid.iconSize, focused, frame.focusScale, 1f, glow, frame.chromeAlpha)
            textPaint.typeface = if (focused) tfMedium else tfLight
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 21f * u
            textPaint.color = scaleAlpha(if (focused) 0xFFFFFFFF.toInt() else 0xD2FFFFFF.toInt(), frame.chromeAlpha)
            val label = if (i in cachedGridLabels.indices) cachedGridLabels[i] else entry.label
            c.drawText(label, cx, cy + halfIcon + 40f * u, textPaint)
        }
    }

    private fun drawGridPageDots(c: Canvas, appCount: Int, currentPage: Int, alpha: Int) {
        val pages = HomeNavigation.gridPageCount(appCount)
        if (pages <= 1) return
        val startX = grid.dotsStartX(pages)
        for (p in 0 until pages) {
            fillPaint.color = scaleAlpha(if (p == currentPage) 0xFFFFFFFF.toInt() else 0x55FFFFFF, alpha)
            c.drawCircle(startX + p * grid.dotGap, grid.dotY, (if (p == currentPage) 5.5f else 3.8f) * u, fillPaint)
        }
    }

    private fun hitTestDock(x: Float, y: Float, dockScroll: Float): HitTarget {
        val chevronZone = 44f * u
        if (x < dock.rect.left + chevronZone && dock.canScrollLeft(dockScroll)) return HitTarget.DockChevron(-1)
        if (x > dock.rect.right - chevronZone && dock.canScrollRight(dockScroll)) return HitTarget.DockChevron(1)
        val idx = ((x - dock.rect.centerX()) / dock.step + dockScroll).roundToInt()
        if (idx !in 0 until dock.totalItems) return HitTarget.None
        val cx = dock.rect.centerX() + (idx - dockScroll) * dock.step
        val edgeFade = computeDockEdgeFade(cx)
        val inTile = abs(x - cx) <= dock.halfIcon * 1.08f && y >= dock.tileTop - 8f * u && y <= dock.tileTop + dock.iconSize + 8f * u
        return if (edgeFade > 0.25f && inTile) HitTarget.AppTile(idx) else HitTarget.None
    }

    private fun hitTestGrid(x: Float, y: Float, gridPage: Int, appCount: Int): HitTarget {
        if (x >= grid.gridLeft && x < grid.gridRight && y >= grid.gridTop && y < grid.gridBottom) {
            val col = floor(((x - grid.gridLeft) / grid.cellW).toDouble()).toInt()
            val r = floor(((y - grid.gridTop) / grid.cellH).toDouble()).toInt()
            if (col in 0 until HomeNavigation.GRID_COLS && r in 0 until HomeNavigation.GRID_ROWS) {
                val idx = gridPage * HomeNavigation.GRID_PER_PAGE + r * HomeNavigation.GRID_COLS + col
                if (idx in 0 until appCount) return HitTarget.AppTile(idx)
            }
            return HitTarget.None
        }
        val pages = HomeNavigation.gridPageCount(appCount)
        if (y >= grid.gridBottom && pages > 1) {
            val startX = grid.dotsStartX(pages)
            val clickedPage = ((x - startX) / grid.dotGap).roundToInt()
            if (clickedPage in 0 until pages && abs(x - (startX + clickedPage * grid.dotGap)) <= grid.dotGap * 0.7f) {
                return HitTarget.GridPageDot(clickedPage)
            }
        }
        return HitTarget.None
    }

    private fun drawAlphaBitmap(c: Canvas, bmp: Bitmap, left: Float, top: Float, alpha: Int) {
        panelBmpPaint.alpha = alpha
        c.drawBitmap(bmp, left, top, panelBmpPaint)
        panelBmpPaint.alpha = 255
    }

    private fun drawTileSurface(
        c: Canvas, bmp: Bitmap?, x: Float, y: Float, size: Float,
        focused: Boolean, focusScale: Float, extraScale: Float, glowColor: Int, alpha: Int
    ) {
        val drawSize = size * (if (focused) focusScale else 1f) * extraScale
        val offset = (size - drawSize) / 2f
        val l = x + offset
        val t = y + offset
        val rad = drawSize * 0.23f
        if (focused) drawFocusHalo(c, l, t, drawSize, rad, glowColor, alpha)
        tmpRect.set(l, t, l + drawSize, t + drawSize)
        if (bmp != null) {
            tilePaint.alpha = alpha
            c.drawBitmap(bmp, null, tmpRect, tilePaint)
            tilePaint.alpha = 255
        } else {
            fillPaint.color = scaleAlpha(0xFF1F2937.toInt(), alpha)
            c.drawRoundRect(tmpRect, rad, rad, fillPaint)
        }
    }

    private fun drawFocusHalo(c: Canvas, l: Float, t: Float, size: Float, rad: Float, glowColor: Int, alpha: Int) {
        tmpRect.set(l - 8f * u, t - 8f * u, l + size + 8f * u, t + size + 8f * u)
        strokePaint.strokeWidth = 10f * u
        strokePaint.color = scaleAlpha(glowColor, alpha)
        c.drawRoundRect(tmpRect, rad + 6f * u, rad + 6f * u, strokePaint)
        tmpRect.set(l - 4f * u, t - 4f * u, l + size + 4f * u, t + size + 4f * u)
        strokePaint.strokeWidth = 3.6f * u
        strokePaint.color = scaleAlpha(0xFFFFFFFF.toInt(), alpha)
        c.drawRoundRect(tmpRect, rad + 3f * u, rad + 3f * u, strokePaint)
    }

    private fun drawEdgeChevron(c: Canvas, cx: Float, cy: Float, dir: Float, alpha: Int) {
        strokePaint.strokeWidth = 3f * u
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = scaleAlpha(0x99FFFFFF.toInt(), alpha)
        val dx = 6f * u * dir
        val dy = 11f * u
        c.drawLine(cx - dx, cy - dy, cx + dx, cy, strokePaint)
        c.drawLine(cx + dx, cy, cx - dx, cy + dy, strokePaint)
    }

    private fun computeDockEdgeFade(cx: Float): Float {
        if (dock.totalItems <= dock.maxVisible) return 1f
        val clipLeft = dock.rect.left + 36f * u
        val clipRight = dock.rect.right - 36f * u
        val fadeSpan = 110f * u
        return when {
            cx - dock.halfIcon < clipLeft + fadeSpan -> ((cx - dock.halfIcon - clipLeft) / fadeSpan).coerceIn(0f, 1f)
            cx + dock.halfIcon > clipRight - fadeSpan -> ((clipRight - (cx + dock.halfIcon)) / fadeSpan).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    private fun drawLegibleText(c: Canvas, text: String, x: Float, y: Float, color: Int, alpha: Int) {
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
