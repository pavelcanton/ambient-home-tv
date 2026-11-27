package com.will.ambienthome

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
import com.will.ambienthome.data.AudioDeviceObserver
import com.will.ambienthome.data.LauncherStore
import com.will.ambienthome.data.RootBridgeClient
import com.will.ambienthome.data.SleepTimerController
import com.will.ambienthome.data.SystemStatusHelper
import com.will.ambienthome.data.WeatherClient
import com.will.ambienthome.data.WolSender
import com.will.ambienthome.model.BootAutoLaunch
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.QuickControlState
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.UsbVolumeInfo
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WeatherSnapshot
import com.will.ambienthome.ui.FeatureDialogs
import com.will.ambienthome.ui.TvDialogs

/**
 * Координатор интерактивных фич главного экрана Ambient Home 3.0.0:
 * Quick Control Sheet, Погода (обновление раз в 45 мин при активном HOME + диалог),
 * Таймер сна, Web-пульт (URL+PIN), Wake-on-LAN и Wi-Fi-aware холодный автозапуск.
 */
internal class MainFeatureCoordinator(
    private val activity: Activity,
    private val store: LauncherStore,
    private val mainHandler: Handler,
    private val ioRunner: (() -> Unit) -> Boolean,
    private val host: Callbacks
) {

    enum class Action { QUICK_CONTROLS, WEATHER, SLEEP_PICKER, WOL }
    enum class Event { AMBIENT_NOW, NEXT_WALLPAPER, WIDGETS_DIRTY, LAUNCH_PACKAGE }

    interface Callbacks {
        fun currentState(): LauncherState
        fun currentStatus(): SystemStatus
        fun isNightNow(): Boolean
        fun onStateMutated(next: LauncherState, refreshApps: Boolean = false)
        fun onDialogChanged(dialog: Dialog?)
        fun onEvent(event: Event, arg: String = "")
    }

    var weatherSnapshot: WeatherSnapshot? = null
        private set
    var bootBannerText: String = ""
        private set

    private var bootPendingPkg: String = ""
    private var bootPendingLabel: String = ""
    private var bootWaitStartMs: Long = 0L
    private var bootCountdownStartMs: Long = 0L
    private var bootWaitingWifi: Boolean = false
    private var resumed: Boolean = false

    private val weatherRefreshTask = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshWeatherIfDue(force = false)
            mainHandler.postDelayed(this, WeatherClient.REFRESH_TTL_MS)
        }
    }

    private val bootTickTask = object : Runnable {
        override fun run() {
            if (bootPendingPkg.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            if (bootWaitingWifi) {
                val online = host.currentStatus().isOnline
                if (online || BootAutoLaunch.isWifiWaitTimedOut(now - bootWaitStartMs)) {
                    bootWaitingWifi = false
                    bootCountdownStartMs = now
                } else {
                    bootBannerText = BootAutoLaunch.bannerText(bootPendingLabel, 3, waitingWifi = true)
                    host.onEvent(Event.WIDGETS_DIRTY)
                    mainHandler.postDelayed(this, 600L)
                    return
                }
            }
            val rem = BootAutoLaunch.countdownRemainingSec(now - bootCountdownStartMs)
            if (rem <= 0) {
                val pkg = bootPendingPkg
                cancelBootAutoLaunch()
                host.onEvent(Event.LAUNCH_PACKAGE, pkg)
                return
            }
            bootBannerText = BootAutoLaunch.bannerText(bootPendingLabel, rem, waitingWifi = false)
            host.onEvent(Event.WIDGETS_DIRTY)
            mainHandler.postDelayed(this, 500L)
        }
    }

    fun onResume() {
        resumed = true
        refreshWeatherIfDue(force = false)
        mainHandler.removeCallbacks(weatherRefreshTask)
        mainHandler.postDelayed(weatherRefreshTask, WeatherClient.REFRESH_TTL_MS)
    }

    fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(weatherRefreshTask)
    }

    fun onDestroy() {
        resumed = false
        mainHandler.removeCallbacks(weatherRefreshTask)
        mainHandler.removeCallbacks(bootTickTask)
    }

    fun checkColdBootAutoLaunch(resolveLabel: (String) -> String) {
        val cfg = host.currentState().features.bootAutoLaunch
        if (!cfg.enabled || cfg.packageName.isBlank()) return
        ioRunner {
            val bootId = SystemStatusHelper.readBootId()
            if (!BootAutoLaunch.shouldTrigger(bootId, cfg)) return@ioRunner
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val updated = store.updateFeatures { f ->
                    f.copy(bootAutoLaunch = f.bootAutoLaunch.copy(lastHandledBootId = bootId))
                }
                host.onStateMutated(updated, refreshApps = false)
                bootPendingPkg = cfg.packageName
                bootPendingLabel = resolveLabel(cfg.packageName)
                bootWaitStartMs = SystemClock.uptimeMillis()
                bootWaitingWifi = !host.currentStatus().isOnline
                bootCountdownStartMs = bootWaitStartMs
                mainHandler.post(bootTickTask)
            }
        }
    }

    fun cancelBootAutoLaunch(): Boolean {
        if (bootPendingPkg.isEmpty()) return false
        bootPendingPkg = ""
        bootBannerText = ""
        mainHandler.removeCallbacks(bootTickTask)
        host.onEvent(Event.WIDGETS_DIRTY)
        return true
    }

    fun perform(action: Action) {
        when (action) {
            Action.QUICK_CONTROLS -> openQuickControls()
            Action.WEATHER -> openWeatherDialog()
            Action.SLEEP_PICKER -> openSleepPicker()
            Action.WOL -> triggerWol()
        }
    }

    fun refreshWeatherIfDue(force: Boolean) {
        val cfg = host.currentState().features.weather
        if (!cfg.isConfigured) {
            if (weatherSnapshot != null) {
                weatherSnapshot = null
                host.onEvent(Event.WIDGETS_DIRTY)
            }
            return
        }
        WeatherClient.getCached(cfg)?.let {
            if (weatherSnapshot != it) {
                weatherSnapshot = it
                host.onEvent(Event.WIDGETS_DIRTY)
            }
        }
        if (!force && !WeatherClient.isStale(cfg)) return
        ioRunner {
            val fresh = WeatherClient.fetchForecast(cfg)
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (fresh != null) {
                    weatherSnapshot = fresh
                    host.onEvent(Event.WIDGETS_DIRTY)
                }
            }
        }
    }

    private fun triggerWol() {
        val target = host.currentState().features.wol
        val localIp = host.currentStatus().ip
        ioRunner {
            val res = WolSender.send(target, localIp)
            mainHandler.post { toast(res.message) }
        }
    }

    private fun openQuickControls() {
        val st = host.currentState()
        val pin = LanWebRemoteService.ensurePinGenerated(store)
        val ip = host.currentStatus().ip.ifEmpty { "<IP ТВ>" }
        val sleep = SleepTimerController.currentState()
        val qc = QuickControlState(
            nightActive = host.isNightNow(),
            nightLabel = NightSchedule.describeStatus(st.features.night, host.isNightNow()),
            sleepLabel = if (sleep.active) "осталось ${sleep.badgeText}" else "Выкл",
            webRemoteEnabled = st.features.webRemote.enabled,
            webRemoteUrl = "http://$ip:${st.features.webRemote.port}",
            webRemotePin = pin,
            usbVolumes = AudioDeviceObserver.listExternalUsbVolumes(activity)
        )
        val dlg = TvDialogs.showQuickControls(
            ctx = activity,
            state = qc,
            onDismiss = { host.onDialogChanged(null) },
            listener = object : TvDialogs.QuickControlsListener {
                override fun onAmbientNow() = host.onEvent(Event.AMBIENT_NOW)
                override fun onNextWallpaper() = host.onEvent(Event.NEXT_WALLPAPER)
                override fun onToggleNight() = toggleNightMode()
                override fun onOpenSleepTimer() = openSleepPicker()
                override fun onOpenWebRemote() = openWebRemoteDialog()
                override fun onReconnectWifi() = reconnectWifi()
                override fun onEjectUsb(vol: UsbVolumeInfo) = ejectUsb(vol)
            }
        )
        host.onDialogChanged(dlg)
    }

    private fun openWeatherDialog() {
        val snap = weatherSnapshot
        if (snap == null) {
            openCityPicker()
            return
        }
        val dlg = FeatureDialogs.showWeatherForecast(
            ctx = activity,
            snap = snap,
            onRefresh = { refreshWeatherIfDue(force = true) },
            onChangeCity = { openCityPicker() },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openSleepPicker() {
        val st = host.currentState()
        val dlg = FeatureDialogs.showSleepTimerPicker(
            ctx = activity,
            current = SleepTimerController.currentState(),
            hdmiOffEnabled = st.features.sleepHdmiOff,
            bridgeAvailable = RootBridgeClient.isAvailable(activity),
            onPickMinutes = { mins ->
                SleepTimerController.startTimer(activity, mins)
                toast(if (mins > 0) "Таймер сна: $mins мин" else "Таймер сна выключен")
            },
            onToggleHdmiOff = {
                val next = store.updateFeatures { it.copy(sleepHdmiOff = !it.sleepHdmiOff) }
                host.onStateMutated(next)
                openSleepPicker()
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openCityPicker() {
        val cur = host.currentState().features.weather.cityName
        val dlg = FeatureDialogs.showCityPicker(
            ctx = activity,
            presets = WeatherClient.RU_CITY_PRESETS,
            currentCity = cur,
            onPick = { city ->
                val next = store.updateFeatures { it.copy(weather = WeatherConfig(city.name, city.lat, city.lon)) }
                host.onStateMutated(next)
                refreshWeatherIfDue(force = true)
            },
            onSearchPrompt = { openCitySearchInput() },
            onDisable = {
                val next = store.updateFeatures { it.copy(weather = WeatherConfig()) }
                weatherSnapshot = null
                host.onStateMutated(next)
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openCitySearchInput() {
        val dlg = FeatureDialogs.showTextInput(
            ctx = activity,
            title = "Поиск города (Open-Meteo)",
            hint = "Например: Москва или Казань",
            initialValue = host.currentState().features.weather.cityName,
            onSubmit = { query ->
                if (query.length < 2) return@showTextInput
                ioRunner {
                    val found = WeatherClient.searchCity(query).firstOrNull()
                    mainHandler.post {
                        if (found != null) {
                            val next = store.updateFeatures {
                                it.copy(weather = WeatherConfig(found.name, found.lat, found.lon))
                            }
                            host.onStateMutated(next)
                            refreshWeatherIfDue(force = true)
                            toast("Город: ${found.name}")
                        } else toast("Город не найден")
                    }
                }
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openWebRemoteDialog() {
        val st = host.currentState()
        val pin = LanWebRemoteService.ensurePinGenerated(store)
        val ip = host.currentStatus().ip.ifEmpty { "<IP ТВ>" }
        val url = "http://$ip:${st.features.webRemote.port}"
        val dlg = FeatureDialogs.showWebRemoteInfo(
            ctx = activity,
            enabled = st.features.webRemote.enabled,
            url = url,
            pin = pin,
            onToggleEnabled = {
                val next = store.updateFeatures { f ->
                    f.copy(webRemote = f.webRemote.copy(enabled = !f.webRemote.enabled))
                }
                LanWebRemoteService.syncRunning(activity, next.features.webRemote.enabled)
                host.onStateMutated(next)
            },
            onRotatePin = {
                val newPin = LauncherStore.generatePin()
                val next = store.updateFeatures { f -> f.copy(webRemote = f.webRemote.copy(pin = newPin)) }
                host.onStateMutated(next)
                toast("Новый PIN: $newPin")
                openWebRemoteDialog()
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun toggleNightMode() {
        val next = store.updateFeatures { f ->
            val target = if (host.isNightNow()) NightMode.OFF else NightMode.ON
            f.copy(night = f.night.copy(mode = target))
        }
        host.onStateMutated(next)
    }

    private fun reconnectWifi() {
        ioRunner {
            val res = SystemStatusHelper.softReconnectWifi(activity.applicationContext)
            mainHandler.post { toast(res.message) }
        }
    }

    private fun ejectUsb(vol: UsbVolumeInfo) {
        ioRunner {
            val res = RootBridgeClient.sendEject(activity.applicationContext, vol.id)
            mainHandler.post {
                if (res.ok) toast("USB безопасно извлечён: ${vol.label}")
                else AudioDeviceObserver.openStorageSettings(activity)
            }
        }
    }

    private fun toast(msg: String) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
