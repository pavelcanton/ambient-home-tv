package com.will.ambienthome

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.will.ambienthome.data.AppRepository
import com.will.ambienthome.data.AudioDeviceObserver
import com.will.ambienthome.data.LauncherStore
import com.will.ambienthome.data.MediaSessionBridge
import com.will.ambienthome.data.SleepTimerController
import com.will.ambienthome.data.SystemStatusHelper
import com.will.ambienthome.data.WallpaperController
import com.will.ambienthome.model.AmbientOverlayData
import com.will.ambienthome.model.AppEntry
import com.will.ambienthome.model.AppOrdering
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.HomeNavigation
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.NowPlayingState
import com.will.ambienthome.model.SleepTimerState
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.TopAction
import com.will.ambienthome.ui.HomeCanvasView
import com.will.ambienthome.ui.TvDialogs
import com.will.ambienthome.ui.WallpaperSurfaceView
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Ambient Home 3.0.0 — главная активность лаунчера на чистом Kotlin.
 * Оркестрация обоев выделена в WallpaperController, фичи и диалоги — в MainFeatureCoordinator.
 */
class MainActivity : Activity(), HomeCanvasView.Host {

    private companion object {
        const val TAG = "AmbientHome"
        const val REQ_SETTINGS = 1001
        const val NETWORK_DEBOUNCE_MS = 400L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var store: LauncherStore
    private lateinit var wallpaperView: WallpaperSurfaceView
    private lateinit var homeView: HomeCanvasView
    private lateinit var wallpaperController: WallpaperController
    private lateinit var audioObserver: AudioDeviceObserver
    private lateinit var mediaBridge: MediaSessionBridge
    private lateinit var features: MainFeatureCoordinator
    private lateinit var systemObservers: MainSystemObservers

    private var state: LauncherState = LauncherState()
    private var lastStatus: SystemStatus = SystemStatus()
    private var allApps: List<AppEntry> = emptyList()
    private var visibleApps: List<AppEntry> = emptyList()
    private var btAudioConnected: Boolean = false
    private var sleepState: SleepTimerState = SleepTimerState()
    private var nowPlaying: NowPlayingState? = null
    private var ambientCaption: String = ""
    private var ambientBlackout: Boolean = false
    private var pendingSleepBlackout: Boolean = false

    @Volatile
    private var resumed: Boolean = false
    private var loadedOnce: Boolean = false
    private var skipNextResumeAppRefresh: Boolean = false
    private var modeBeforeSettings: HomeMode = HomeMode.DOCK

    private var activeDialog: Dialog? = null

    private val clockTickTask = object : Runnable {
        override fun run() {
            if (!resumed) return
            homeView.updateClockText()
            if (wallpaperController.checkNightTransition()) pushWidgetsState()
            scheduleNextMinuteTick()
        }
    }

    private val ambientIdleTask = Runnable {
        if (!resumed || ambientBlackout || state.appearance.ambientTimeoutMin <= 0) return@Runnable
        if (activeDialog?.isShowing == true) {
            resetAmbientTimer()
            return@Runnable
        }
        ambientBlackout = false
        homeView.setAmbientMode(true)
    }

    private val networkStatusTask = Runnable {
        if (!resumed) return@Runnable
        runIo {
            val st = SystemStatusHelper.readSystemStatus(applicationContext)
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    lastStatus = st
                    homeView.setSystemStatus(st)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LauncherStore(this)
        state = store.readState()
        val initW = resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        val initH = resources.displayMetrics.heightPixels.coerceAtLeast(720)

        initViewsAndControllers(initW, initH)
        consumeSleepBlackoutExtra(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        LanWebRemoteService.ensurePinGenerated(store)
        LanWebRemoteService.syncRunning(this, state.features.webRemote.enabled)
        refreshAppsAsync(preservePkg = null)
        features.checkColdBootAutoLaunch { pkg -> allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeDialog?.dismiss()
        activeDialog = null
        state = store.readState()
        if (consumeSleepBlackoutExtra(intent)) {
            if (resumed) {
                pendingSleepBlackout = false
                enterSleepBlackout()
            }
            return
        }
        ambientBlackout = false
        homeView.onHomePressed(state.appearance.homeMode)
        resetAmbientTimer()
    }

    override fun onResume() {
        super.onResume()
        val first = !loadedOnce
        loadedOnce = true
        resumed = true
        val blackoutOnResume = pendingSleepBlackout || consumeSleepBlackoutExtra(intent)
        pendingSleepBlackout = false
        ambientBlackout = blackoutOnResume
        state = store.readState()

        applyImmersive()
        homeView.setAmbientMode(blackoutOnResume, forceSync = blackoutOnResume)
        homeView.updateClockText()
        homeView.applyAppearance(state.appearance.homeMode, state.appearance.showClock, forceMode = false, resetTopFocus = !first)
        homeView.requestFocus()

        val skipRefresh = skipNextResumeAppRefresh
        skipNextResumeAppRefresh = false
        if (!first && !skipRefresh) refreshAppsAsync(preservePkg = null)

        wallpaperController.onResume(state)
        audioObserver.start()
        mediaBridge.start()
        features.onResume()
        SleepTimerController.attachListeners(
            onState = { st -> sleepState = st; pushWidgetsState() },
            onBlackout = { enterSleepBlackout() }
        )
        LanWebRemoteService.setUiEventListener { evt -> mainHandler.post { handleRemoteEvent(evt) } }

        systemObservers.start()
        scheduleNetworkStatusRefresh()
        scheduleNextMinuteTick()
        if (blackoutOnResume) mainHandler.removeCallbacks(ambientIdleTask) else resetAmbientTimer()
        pushWidgetsState()
    }

    override fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(clockTickTask)
        mainHandler.removeCallbacks(ambientIdleTask)
        mainHandler.removeCallbacks(networkStatusTask)
        wallpaperController.onPause()
        audioObserver.stop()
        mediaBridge.stop()
        features.onPause()
        SleepTimerController.detachListeners()
        LanWebRemoteService.setUiEventListener(null)
        systemObservers.stop()
        super.onPause()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        wallpaperController.onDestroy()
        features.onDestroy()
        audioObserver.stop()
        mediaBridge.stop()
        systemObservers.stop()
        io.shutdownNow()
        wallpaperView.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS) {
            state = store.readState()
            val modeChanged = state.appearance.homeMode != modeBeforeSettings
            homeView.applyAppearance(state.appearance.homeMode, state.appearance.showClock, forceMode = modeChanged, resetTopFocus = true)
            LanWebRemoteService.syncRunning(this, state.features.webRemote.enabled)
            skipNextResumeAppRefresh = data?.getBooleanExtra(SettingsActivity.EXTRA_SKIP_APP_REFRESH, false) == true
            refreshAppsAsync(preservePkg = null)
            val rotateNow = resultCode == RESULT_OK && data?.getBooleanExtra(SettingsActivity.EXTRA_ROTATE_WALLPAPER, false) == true
            wallpaperController.syncState(state)
            wallpaperController.requestIfNeeded(advance = rotateNow, persistImmediately = rotateNow)
            features.refreshWeatherIfDue(force = false)
            pushWidgetsState()
        }
    }

    // ------------------------------------------------------------ HomeCanvasView.Host

    override fun onLaunch(entry: AppEntry) {
        if (CustomShortcuts.isWolShortcut(entry.packageName)) {
            features.perform(MainFeatureCoordinator.Action.WOL)
            return
        }
        if (!AppRepository.launch(this, entry)) refreshAppsAsync(preservePkg = null)
    }

    override fun onOpenAppMenu(entry: AppEntry) {
        val idx = visibleApps.indexOfFirst { it.packageName == entry.packageName }
        if (idx < 0) return
        val pinnedSet = state.layout.pinnedPackages.toSet()
        val pinnedCount = visibleApps.count { it.packageName in pinnedSet }
        val isPinned = entry.packageName in pinnedSet
        val bounds = AppOrdering.computeMoveBounds(idx, visibleApps.size, pinnedCount, isPinned)

        activeDialog?.dismiss()
        var dlg: Dialog? = null
        dlg = TvDialogs.showAppContext(
            ctx = this, entry = entry, isPinned = isPinned,
            canMoveLeft = bounds.canMoveLeft, canMoveRight = bounds.canMoveRight,
            onDismiss = { if (activeDialog === dlg) { activeDialog = null; resetAmbientTimer() } },
            listener = buildAppMenuListener()
        )
        activeDialog = dlg
    }

    override fun onTopAction(action: TopAction) {
        when (action) {
            TopAction.WEATHER -> features.perform(MainFeatureCoordinator.Action.WEATHER)
            TopAction.BLUETOOTH -> AudioDeviceObserver.openBluetoothSettings(this)
            TopAction.SLEEP_TIMER -> features.perform(MainFeatureCoordinator.Action.SLEEP_PICKER)
            TopAction.WIFI -> AppRepository.openWifiSettings(this)
            TopAction.QUICK_CONTROLS -> features.perform(MainFeatureCoordinator.Action.QUICK_CONTROLS)
            TopAction.ANDROID_SETTINGS -> AppRepository.openAndroidSettings(this)
            TopAction.LAUNCHER_SETTINGS -> {
                modeBeforeSettings = state.appearance.homeMode
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
            }
        }
    }

    override fun onMediaAction(button: Int) {
        when (button) {
            HomeNavigation.MEDIA_BTN_PLAY_PAUSE -> mediaBridge.togglePlayPause()
            HomeNavigation.MEDIA_BTN_NEXT -> mediaBridge.skipNext()
            else -> nowPlaying?.packageName?.let { launchByPackage(it) }
        }
    }

    override fun onHomeEvent(event: HomeCanvasView.HomeEvent) {
        when (event) {
            HomeCanvasView.HomeEvent.PERSIST_DOCK_MODE -> {
                state = store.updateAppearance { it.copy(homeMode = HomeMode.DOCK) }
            }
            HomeCanvasView.HomeEvent.USER_ACTIVITY -> {
                ambientBlackout = false
                resetAmbientTimer()
            }
            HomeCanvasView.HomeEvent.CANCEL_COUNTDOWN -> {
                if (!features.cancelBootAutoLaunch() && sleepState.warningActive) {
                    SleepTimerController.cancel(this)
                }
            }
        }
    }

    override fun onRequestWallpaperBake(width: Int, height: Int) {
        wallpaperController.syncState(state, width, height)
        wallpaperController.requestIfNeeded(advance = false, persistImmediately = false)
    }

    override fun onAmbientClockUpdate(active: Boolean, time: String, date: String, tickCount: Int) {
        val overlay = AmbientOverlayData(
            clockStyle = state.appearance.clockStyle,
            showClock = state.appearance.showClock,
            nightActive = wallpaperController.isNightNow(),
            blackout = ambientBlackout,
            weatherSummary = features.weatherSnapshot?.ambientSummary.orEmpty(),
            sleepBadge = sleepState.badgeText,
            caption = ambientCaption,
            nowPlayingLine = nowPlaying?.let { "${it.displayTitle} · ${it.appLabel}" }.orEmpty()
        )
        wallpaperView.setAmbientClock(active, time, date, tickCount, overlay)
    }

    // ------------------------------------------------------------ Инициализация и помощники

    private fun initViewsAndControllers(initW: Int, initH: Int) {
        wallpaperView = WallpaperSurfaceView(this)
        homeView = HomeCanvasView(this, this, state.appearance.homeMode, state.appearance.showClock)
        val root = FrameLayout(this).apply {
            addView(wallpaperView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(homeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        wallpaperController = WallpaperController(applicationContext, store, mainHandler, ::runIo) { bmp, cap ->
            ambientCaption = cap
            wallpaperView.setWallpaper(bmp)
        }
        wallpaperController.syncState(state, initW, initH)
        audioObserver = AudioDeviceObserver(applicationContext, mainHandler) { bt ->
            btAudioConnected = bt
            pushWidgetsState()
        }
        mediaBridge = MediaSessionBridge(applicationContext, mainHandler) { np ->
            nowPlaying = np
            pushWidgetsState()
        }
        features = MainFeatureCoordinator(this, store, mainHandler, ::runIo, buildFeatureCallbacks())
        systemObservers = MainSystemObservers(
            context = this,
            onPackagesChanged = { refreshAppsAsync(preservePkg = null) },
            onNetworkChanged = { scheduleNetworkStatusRefresh() }
        )
    }

    private fun buildFeatureCallbacks(): MainFeatureCoordinator.Callbacks =
        object : MainFeatureCoordinator.Callbacks {
            override fun currentState(): LauncherState = state
            override fun currentStatus(): SystemStatus = lastStatus
            override fun isNightNow(): Boolean = wallpaperController.isNightNow()
            override fun onStateMutated(next: LauncherState, refreshApps: Boolean) {
                state = next
                wallpaperController.syncState(state)
                wallpaperController.requestIfNeeded(advance = false, persistImmediately = false)
                if (refreshApps) refreshAppsAsync(preservePkg = null)
                pushWidgetsState()
            }
            override fun onDialogShown(dialog: Dialog) {
                if (activeDialog !== dialog) activeDialog?.dismiss()
                activeDialog = dialog
            }
            override fun onDialogDismissed(dialog: Dialog) {
                if (activeDialog === dialog) {
                    activeDialog = null
                    resetAmbientTimer()
                }
            }
            override fun onEvent(event: MainFeatureCoordinator.Event, arg: String) {
                when (event) {
                    MainFeatureCoordinator.Event.AMBIENT_NOW -> {
                        ambientBlackout = false
                        homeView.setAmbientMode(true, forceSync = true)
                    }
                    MainFeatureCoordinator.Event.NEXT_WALLPAPER -> wallpaperController.requestIfNeeded(advance = true, persistImmediately = true)
                    MainFeatureCoordinator.Event.WIDGETS_DIRTY -> pushWidgetsState()
                    MainFeatureCoordinator.Event.LAUNCH_PACKAGE -> launchByPackage(arg)
                }
            }
        }

    private fun buildAppMenuListener(): TvDialogs.AppMenuListener = object : TvDialogs.AppMenuListener {
        override fun onMoveLeft(entry: AppEntry) = shiftApp(entry.packageName, -1)
        override fun onMoveRight(entry: AppEntry) = shiftApp(entry.packageName, +1)
        override fun onTogglePin(entry: AppEntry) {
            state = store.updateAppsLayout { layout ->
                val nextPinned = if (entry.packageName in layout.pinnedPackages) {
                    layout.pinnedPackages - entry.packageName
                } else listOf(entry.packageName) + layout.pinnedPackages
                layout.copy(pinnedPackages = nextPinned)
            }
            applyOrderedApps(preservePkg = entry.packageName)
        }
        override fun onOpenAppInfo(entry: AppEntry) = AppRepository.openAppInfo(this@MainActivity, entry.packageName)
        override fun onHideApp(entry: AppEntry) {
            state = store.updateAppsLayout { it.copy(hiddenPackages = it.hiddenPackages + entry.packageName) }
            applyOrderedApps(preservePkg = null)
        }
    }

    private fun handleRemoteEvent(evt: String) {
        if (isFinishing || isDestroyed) return
        when (evt) {
            "next_wallpaper" -> wallpaperController.requestIfNeeded(advance = true, persistImmediately = true)
            "ambient_now" -> { ambientBlackout = false; homeView.setAmbientMode(true, forceSync = true) }
            "media_play_pause" -> mediaBridge.togglePlayPause()
            "media_next" -> mediaBridge.skipNext()
            "wallpaper_uploaded" -> wallpaperController.requestIfNeeded(advance = false, persistImmediately = false, forceReload = true)
            "state_changed" -> {
                state = store.readState()
                wallpaperController.syncState(state)
                wallpaperController.requestIfNeeded(advance = false, persistImmediately = false)
                features.refreshWeatherIfDue(force = true)
                refreshAppsAsync(preservePkg = null)
                pushWidgetsState()
            }
        }
    }

    private fun enterSleepBlackout() {
        mainHandler.removeCallbacks(ambientIdleTask)
        ambientBlackout = true
        homeView.setAmbientMode(true, forceSync = true)
    }

    private fun consumeSleepBlackoutExtra(incoming: Intent?): Boolean {
        if (incoming?.getBooleanExtra(SleepTimerController.EXTRA_SLEEP_BLACKOUT, false) != true) return false
        incoming.removeExtra(SleepTimerController.EXTRA_SLEEP_BLACKOUT)
        pendingSleepBlackout = true
        return true
    }

    private fun pushWidgetsState() {
        val banner = when {
            features.bootBannerText.isNotEmpty() -> features.bootBannerText
            sleepState.warningActive -> "Таймер сна: выключение через ${sleepState.remainingSec} с  ·  Нажмите кнопку для отмены"
            else -> ""
        }
        homeView.setWidgetsState(
            nightActive = wallpaperController.isNightNow(),
            btConnected = btAudioConnected,
            weather = features.weatherSnapshot,
            sleepTimer = sleepState,
            nowPlaying = nowPlaying,
            countdownBanner = banner
        )
    }

    private fun launchByPackage(pkg: String) {
        val existing = allApps.firstOrNull { it.packageName == pkg }
        if (existing != null) {
            onLaunch(existing)
            return
        }
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (t: Throwable) {
            Log.w(TAG, "launchByPackage failed for $pkg", t)
        }
    }

    private fun runIo(task: () -> Unit): Boolean {
        if (io.isShutdown || isFinishing || isDestroyed) return false
        return try {
            io.execute { try { task() } catch (t: Throwable) { Log.w(TAG, "io task failed", t) } }
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "runIo rejected", e)
            false
        }
    }

    private fun refreshAppsAsync(preservePkg: String?) {
        val wol = state.features.wol
        runIo {
            val loaded = AppRepository.loadAll(applicationContext, wol)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                allApps = loaded
                applyOrderedApps(preservePkg)
            }
        }
    }

    private fun applyOrderedApps(preservePkg: String?) {
        visibleApps = AppRepository.orderVisible(allApps, state.layout)
        homeView.setApps(visibleApps, state.layout.pinnedPackages.toSet(), preservePkg)
    }

    private fun shiftApp(pkg: String, delta: Int) {
        val shifted = AppOrdering.shiftPackageOrder(
            visiblePkgs = visibleApps.map { it.packageName },
            allPinned = state.layout.pinnedPackages,
            pkg = pkg,
            delta = delta
        ) ?: return
        state = store.updateAppsLayout { layout ->
            layout.copy(dockOrder = shifted.newDockOrder, pinnedPackages = shifted.newPinnedOrder ?: layout.pinnedPackages)
        }
        applyOrderedApps(preservePkg = pkg)
    }

    private fun scheduleNextMinuteTick() {
        mainHandler.removeCallbacks(clockTickTask)
        if (!resumed) return
        val delay = (60_000L - (System.currentTimeMillis() % 60_000L)) + 60L
        mainHandler.postDelayed(clockTickTask, delay)
    }

    private fun resetAmbientTimer() {
        mainHandler.removeCallbacks(ambientIdleTask)
        val min = state.appearance.ambientTimeoutMin
        if (!resumed || ambientBlackout || min <= 0) return
        mainHandler.postDelayed(ambientIdleTask, min * 60_000L)
    }

    private fun scheduleNetworkStatusRefresh() {
        mainHandler.removeCallbacks(networkStatusTask)
        if (!resumed) return
        mainHandler.postDelayed(networkStatusTask, NETWORK_DEBOUNCE_MS)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
