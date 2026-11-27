package com.will.ambienthome.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.will.ambienthome.model.AppEntry
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.HomeNavigation
import com.will.ambienthome.model.NowPlayingState
import com.will.ambienthome.model.SleepTimerState
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.TopAction
import com.will.ambienthome.model.WeatherSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp

/**
 * Компактный View-контроллер главного экрана Ambient Home 3.0.0 (60 fps даже на бюджетных TV GPU).
 * Навигация выделена в HomeNavigation, отрисовка — в HomeRenderer + TopBarRenderer + HomeWidgetsRenderer.
 */
class HomeCanvasView(
    context: Context,
    private val host: Host,
    initialMode: HomeMode,
    initialShowClock: Boolean
) : View(context) {

    enum class HomeEvent {
        PERSIST_DOCK_MODE,
        USER_ACTIVITY,
        CANCEL_COUNTDOWN
    }

    interface Host {
        fun onLaunch(entry: AppEntry)
        fun onOpenAppMenu(entry: AppEntry)
        fun onTopAction(action: TopAction)
        fun onMediaAction(button: Int)
        fun onHomeEvent(event: HomeEvent)
        fun onRequestWallpaperBake(width: Int, height: Int)
        fun onAmbientClockUpdate(active: Boolean, time: String, date: String, tickCount: Int)
    }

    private companion object {
        const val FOCUS_ANIM_MS = 155f
        val LOCALE_RU: Locale = Locale.forLanguageTag("ru")
    }

    private val renderer = HomeRenderer()
    private val renderFrame = HomeRenderFrame()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", LOCALE_RU)
    private val tmpDate = Date()

    private var apps: List<AppEntry> = emptyList()
    private var pinnedSet: Set<String> = emptySet()
    private var status: SystemStatus = SystemStatus()
    private var nav = HomeNavigation.NavState(mode = initialMode)

    private var nightActive: Boolean = false
    private var btConnected: Boolean = false
    private var weather: WeatherSnapshot? = null
    private var sleepTimer: SleepTimerState = SleepTimerState()
    private var nowPlaying: NowPlayingState? = null
    private var countdownBanner: String = ""
    private var visibleTopCount: Int = 4

    private var dockScroll: Float = 0f
    private var targetDockScroll: Float = 0f
    private var lastFrameMs: Long = 0L
    private var focusScale: Float = 1.14f
    private var focusAnimStartMs: Long = 0L
    private var focusAnimating: Boolean = false

    private var minuteTickCount: Int = 0
    private var cachedTime: String = ""
    private var cachedDate: String = ""
    private var showClockEnabled: Boolean = initialShowClock

    private var showHintUntilMs: Long = SystemClock.uptimeMillis() + 14_000L
    private val hideHintTask = Runnable { invalidate() }

    private var lastBakedW: Int = 0
    private var lastBakedH: Int = 0
    private var centerLongPressed: Boolean = false
    private var suppressNextCenterKeyUp: Boolean = false
    private var suppressNextTouchUp: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateClockText()
        visibleTopCount = renderer.syncTopMeta(status, weather, btConnected, sleepTimer)
    }

    // ------------------------------------------------------------ Публичный узкий интерфейс (7 методов)

    fun setApps(visibleApps: List<AppEntry>, pinned: Set<String>, preservePackage: String? = null) {
        val prevPkg = preservePackage ?: currentFocusedApp()?.packageName
        apps = visibleApps
        pinnedSet = pinned
        var nextNav = nav
        if (prevPkg != null) {
            val newIdx = apps.indexOfFirst { it.packageName == prevPkg }
            if (newIdx >= 0) nextNav = nextNav.copy(focusIndex = newIdx)
        }
        nav = HomeNavigation.clamp(nextNav, apps.size, visibleTopCount, hasMediaNow())
        renderer.syncApps(apps, pinnedSet, currentViewWidth())
        updateTargetDockScroll(snap = true)
        invalidate()
    }

    fun setSystemStatus(newStatus: SystemStatus) {
        val visualChanged = status.visualKey() != newStatus.visualKey()
        status = newStatus
        if (visualChanged) {
            visibleTopCount = renderer.syncTopMeta(status, weather, btConnected, sleepTimer)
            nav = HomeNavigation.clamp(nav, apps.size, visibleTopCount, hasMediaNow())
            if (!nav.ambientActive) invalidate()
        }
    }

    fun setWidgetsState(
        nightActive: Boolean,
        btConnected: Boolean,
        weather: WeatherSnapshot?,
        sleepTimer: SleepTimerState,
        nowPlaying: NowPlayingState?,
        countdownBanner: String
    ) {
        val changed = this.nightActive != nightActive ||
            this.btConnected != btConnected ||
            this.weather != weather ||
            this.sleepTimer != sleepTimer ||
            this.nowPlaying != nowPlaying ||
            this.countdownBanner != countdownBanner
        this.nightActive = nightActive
        this.btConnected = btConnected
        this.weather = weather
        this.sleepTimer = sleepTimer
        this.nowPlaying = nowPlaying
        this.countdownBanner = countdownBanner
        if (!changed) return
        visibleTopCount = renderer.syncTopMeta(status, weather, btConnected, sleepTimer)
        nav = HomeNavigation.clamp(nav, apps.size, visibleTopCount, hasMediaNow())
        if (nav.ambientActive) syncAmbientClock() else invalidate()
    }

    fun applyAppearance(
        mode: HomeMode,
        showClock: Boolean,
        forceMode: Boolean = false,
        resetTopFocus: Boolean = false
    ) {
        val clockChanged = showClockEnabled != showClock
        showClockEnabled = showClock
        val modeUpdated = forceMode && (nav.mode != mode || nav.openedGridFromDock)
        if (modeUpdated) switchMode(mode, fromDockButton = false)
        val topReset = resetTopFocus && (nav.row == HomeNavigation.ROW_TOP || nav.row == HomeNavigation.ROW_MEDIA)
        if (topReset) {
            nav = nav.copy(row = HomeNavigation.ROW_CONTENT)
            triggerFocusAnim()
        }
        if (clockChanged && nav.ambientActive) syncAmbientClock()
        if (topReset || (clockChanged && !nav.ambientActive && !modeUpdated)) invalidate()
    }

    fun onHomePressed(persistedMode: HomeMode) {
        val wasAmbient = nav.ambientActive
        val prev = nav
        nav = HomeNavigation.onHomePressed(nav, persistedMode, apps.size, visibleTopCount)
        if (wasAmbient) syncAmbientClock()
        if (nav != prev || wasAmbient) {
            updateTargetDockScroll(snap = nav.mode != prev.mode)
            if (nav.row == HomeNavigation.ROW_CONTENT) triggerFocusAnim()
            invalidate()
        }
    }

    fun updateClockText() {
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz
        dateFmt.timeZone = tz
        tmpDate.time = System.currentTimeMillis()
        val newTime = timeFmt.format(tmpDate)
        val rawDate = dateFmt.format(tmpDate)
        val newDate = if (rawDate.isNotEmpty()) {
            rawDate.substring(0, 1).uppercase(LOCALE_RU) + rawDate.substring(1)
        } else rawDate
        val changed = newTime != cachedTime || newDate != cachedDate
        cachedTime = newTime
        cachedDate = newDate
        if (!changed) return
        minuteTickCount++
        if (nav.ambientActive) syncAmbientClock() else if (showClockEnabled) invalidate()
    }

    fun setAmbientMode(enabled: Boolean) {
        if (nav.ambientActive == enabled) return
        nav = nav.copy(ambientActive = enabled)
        syncAmbientClock()
        if (enabled) renderer.release(panelsOnly = true)
        invalidate()
    }

    // ------------------------------------------------------------ Жизненный цикл View

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleHintHide()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideHintTask)
        resetInputGuards()
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) resetInputGuards()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        visibleTopCount = renderer.syncViewport(w, h, status, weather, btConnected, sleepTimer, apps, pinnedSet)
        if (w > 0 && h > 0 && (w != lastBakedW || h != lastBakedH)) {
            lastBakedW = w
            lastBakedH = h
            updateTargetDockScroll(snap = true)
            host.onRequestWallpaperBake(w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16f else (now - lastFrameMs).coerceIn(1L, 50L).toFloat()
        lastFrameMs = now
        val needsNextFrame = stepFocusAndScroll(now, dt)
        val chromeAlpha = if (nav.ambientActive) 0 else 255
        if (chromeAlpha > 0) {
            populateRenderFrame(w, h, chromeAlpha, now)
            renderer.draw(canvas, renderFrame)
        }
        if (needsNextFrame) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------ Пульт и Air-Mouse

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        host.onHomeEvent(HomeEvent.USER_ACTIVITY)
        if (countdownBanner.isNotEmpty()) {
            host.onHomeEvent(HomeEvent.CANCEL_COUNTDOWN)
            if (isCenterKey(keyCode)) suppressNextCenterKeyUp = true
            return true
        }
        if (nav.ambientActive) {
            setAmbientMode(false)
            if (isCenterKey(keyCode)) suppressNextCenterKeyUp = true
            return true
        }
        return handleActiveKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenterKey(keyCode) && !suppressNextCenterKeyUp) {
            val entry = currentFocusedApp()
            if (entry != null) {
                centerLongPressed = true
                host.onOpenAppMenu(entry)
                return true
            }
            if (nav.row == HomeNavigation.ROW_TOP) {
                val act = renderer.topActionAt(nav.topFocus)
                if (act == TopAction.LAUNCHER_SETTINGS || act == TopAction.ANDROID_SETTINGS || act == TopAction.QUICK_CONTROLS) {
                    centerLongPressed = true
                    host.onTopAction(TopAction.QUICK_CONTROLS)
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenterKey(keyCode)) {
            if (suppressNextCenterKeyUp) {
                suppressNextCenterKeyUp = false
                centerLongPressed = false
                return true
            }
            if (event.isTracking && !centerLongPressed && !event.isCanceled) activateCurrent()
            centerLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.onHomeEvent(HomeEvent.USER_ACTIVITY)
                if (countdownBanner.isNotEmpty()) {
                    host.onHomeEvent(HomeEvent.CANCEL_COUNTDOWN)
                    suppressNextTouchUp = true
                } else if (nav.ambientActive) {
                    setAmbientMode(false)
                    suppressNextTouchUp = true
                } else {
                    suppressNextTouchUp = false
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                suppressNextTouchUp = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (suppressNextTouchUp) {
                    suppressNextTouchUp = false
                    return true
                }
            }
            else -> return super.onTouchEvent(event)
        }
        val vw = currentViewWidth()
        populateRenderFrame(vw, if (height > 0) height else resources.displayMetrics.heightPixels, 255, SystemClock.uptimeMillis())
        dispatchHitTarget(renderer.hitTest(event.x, event.y, renderFrame), vw)
        return true
    }

    // ------------------------------------------------------------ Внутренние хелперы

    private fun handleActiveKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dispatchMove(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> dispatchMove(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> dispatchMove(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> dispatchMove(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    suppressNextCenterKeyUp = false
                    centerLongPressed = false
                    event.startTracking()
                }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                val entry = currentFocusedApp()
                if (entry != null) host.onOpenAppMenu(entry)
                else host.onTopAction(TopAction.QUICK_CONTROLS)
                true
            }
            KeyEvent.KEYCODE_SETTINGS -> {
                host.onTopAction(TopAction.ANDROID_SETTINGS)
                true
            }
            KeyEvent.KEYCODE_BACK -> handleBackKey()
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleBackKey(): Boolean {
        val res = HomeNavigation.onBack(nav, apps.size, visibleTopCount)
        val modeChanged = res.state.mode != nav.mode
        nav = res.state
        if (res.persistDockMode) host.onHomeEvent(HomeEvent.PERSIST_DOCK_MODE)
        if (res.focusChanged) {
            updateTargetDockScroll(snap = modeChanged)
            triggerFocusAnim()
            invalidate()
        }
        return true
    }

    private fun populateRenderFrame(w: Int, h: Int, chromeAlpha: Int, now: Long) {
        renderFrame.updateCore(
            w, h, nav, apps, status, showClockEnabled, cachedTime, cachedDate,
            dockScroll, focusScale, chromeAlpha, now < showHintUntilMs
        )
        renderFrame.updateWidgets(nightActive, btConnected, weather, sleepTimer, nowPlaying, countdownBanner)
    }

    private fun dispatchHitTarget(hit: HomeRenderer.HitTarget, viewWidth: Int) {
        when (hit) {
            is HomeRenderer.HitTarget.TopButton -> {
                nav = nav.copy(row = HomeNavigation.ROW_TOP, topFocus = hit.index)
                invalidate()
                host.onTopAction(hit.action)
            }
            is HomeRenderer.HitTarget.MediaControl -> {
                nav = nav.copy(row = HomeNavigation.ROW_MEDIA, mediaFocus = hit.button)
                invalidate()
                host.onMediaAction(hit.button)
            }
            is HomeRenderer.HitTarget.DockChevron -> {
                val res = HomeNavigation.scrollDockChevron(nav, hit.dir, dockScroll, apps.size, renderer.maxDockVisible(viewWidth))
                nav = res.state
                if (res.focusChanged) {
                    updateTargetDockScroll(snap = false)
                    if (res.animateFocus) triggerFocusAnim()
                    invalidate()
                }
            }
            is HomeRenderer.HitTarget.AppTile -> {
                nav = nav.copy(row = HomeNavigation.ROW_CONTENT, focusIndex = hit.index)
                if (nav.mode == HomeMode.DOCK) updateTargetDockScroll(snap = false)
                triggerFocusAnim()
                invalidate()
                activateCurrent()
            }
            is HomeRenderer.HitTarget.GridPageDot -> {
                val nextIdx = (hit.page * HomeNavigation.GRID_PER_PAGE).coerceIn(0, (apps.size - 1).coerceAtLeast(0))
                nav = nav.copy(row = HomeNavigation.ROW_CONTENT, gridPage = hit.page, focusIndex = nextIdx)
                triggerFocusAnim()
                invalidate()
            }
            HomeRenderer.HitTarget.None -> Unit
        }
    }

    private fun dispatchMove(dx: Int, dy: Int): Boolean {
        val res = HomeNavigation.move(nav, dx, dy, apps.size, visibleTopCount, hasMediaNow())
        nav = res.state
        if (res.focusChanged) {
            if (nav.mode == HomeMode.DOCK) updateTargetDockScroll(snap = false)
            if (res.animateFocus) triggerFocusAnim()
            invalidate()
        }
        return true
    }

    private fun switchMode(newMode: HomeMode, fromDockButton: Boolean) {
        val idx = if (nav.focusIndex in apps.indices) nav.focusIndex else 0
        nav = HomeNavigation.clamp(
            nav.copy(mode = newMode, openedGridFromDock = fromDockButton, row = HomeNavigation.ROW_CONTENT, focusIndex = idx),
            apps.size,
            visibleTopCount,
            hasMediaNow()
        )
        updateTargetDockScroll(snap = true)
        triggerFocusAnim()
        invalidate()
    }

    private fun activateCurrent() {
        if (nav.row == HomeNavigation.ROW_TOP) {
            host.onTopAction(renderer.topActionAt(nav.topFocus))
            return
        }
        if (nav.row == HomeNavigation.ROW_MEDIA) {
            host.onMediaAction(nav.mediaFocus)
            return
        }
        if (nav.mode == HomeMode.DOCK && nav.focusIndex == apps.size) {
            switchMode(HomeMode.GRID, fromDockButton = true)
            return
        }
        currentFocusedApp()?.let { host.onLaunch(it) }
    }

    private fun syncAmbientClock() {
        host.onAmbientClockUpdate(nav.ambientActive && showClockEnabled, cachedTime, cachedDate, minuteTickCount)
    }

    private fun hasMediaNow(): Boolean = nowPlaying != null && nav.mode == HomeMode.DOCK

    private fun currentFocusedApp(): AppEntry? =
        if (nav.row == HomeNavigation.ROW_CONTENT && nav.focusIndex in apps.indices) apps[nav.focusIndex] else null

    private fun currentViewWidth(): Int = if (width > 0) width else resources.displayMetrics.widthPixels

    private fun isCenterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    private fun resetInputGuards() {
        suppressNextCenterKeyUp = false
        suppressNextTouchUp = false
        centerLongPressed = false
    }

    private fun updateTargetDockScroll(snap: Boolean) {
        val maxVis = renderer.maxDockVisible(currentViewWidth())
        targetDockScroll = HomeNavigation.computeTargetDockScroll(nav.focusIndex, apps.size, maxVis)
        if (snap) dockScroll = targetDockScroll
    }

    private fun stepFocusAndScroll(now: Long, dtMs: Float): Boolean {
        var active = false
        if (focusAnimating) {
            val t = ((now - focusAnimStartMs) / FOCUS_ANIM_MS).coerceIn(0f, 1f)
            val inv = 1f - t
            focusScale = 1f + 0.14f * (1f - inv * inv * inv)
            if (t >= 1f) {
                focusScale = 1.14f
                focusAnimating = false
            } else active = true
        }
        val diff = targetDockScroll - dockScroll
        if (abs(diff) > 0.002f) {
            dockScroll += diff * (1f - exp(-dtMs * 0.016f)).coerceIn(0.12f, 0.55f)
            active = true
        } else {
            dockScroll = targetDockScroll
        }
        return active
    }

    private fun scheduleHintHide() {
        removeCallbacks(hideHintTask)
        val remaining = showHintUntilMs - SystemClock.uptimeMillis()
        if (remaining > 0L) postDelayed(hideHintTask, remaining + 60L)
    }

    private fun triggerFocusAnim() {
        focusScale = 1f
        focusAnimStartMs = SystemClock.uptimeMillis()
        focusAnimating = true
    }
}
