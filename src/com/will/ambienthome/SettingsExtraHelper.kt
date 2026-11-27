package com.will.ambienthome

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import com.will.ambienthome.data.AppRepository
import com.will.ambienthome.data.LauncherStore
import com.will.ambienthome.data.MediaSessionBridge
import com.will.ambienthome.data.RootBridgeClient
import com.will.ambienthome.data.SettingsBackupCodec
import com.will.ambienthome.data.SleepTimerController
import com.will.ambienthome.data.SystemStatusHelper
import com.will.ambienthome.data.WeatherClient
import com.will.ambienthome.data.WolSender
import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.ui.FeatureDialogs
import java.util.Calendar

/**
 * Помощник расширенных секций настроек Ambient Home 3.0.0:
 * Ночной режим, Стили часов, Таймер сна, Погода, Автозапуск, Web-пульт, Wake-on-LAN и JSON-бэкап.
 */
internal class SettingsExtraHelper(
    private val activity: Activity,
    private val store: LauncherStore,
    private val mainHandler: Handler,
    private val ioRunner: (() -> Unit) -> Unit,
    private val host: Host
) {

    interface Host {
        fun addSection(title: String)
        fun addRow(onClick: () -> Unit): TextView
        fun currentState(): LauncherState
        fun currentIp(): String
        fun onStateChanged(next: LauncherState)
        fun onDialogChanged(dlg: Dialog?)
    }

    private companion object {
        val NIGHT_WINDOWS = listOf(22 to 7, 21 to 7, 23 to 8, 20 to 6)
    }

    private lateinit var rowClockStyle: TextView
    private lateinit var rowNightMode: TextView
    private lateinit var rowNightWindow: TextView
    private lateinit var rowSleepTimer: TextView
    private lateinit var rowWeather: TextView
    private lateinit var rowNowPlaying: TextView
    private lateinit var rowBootLaunch: TextView
    private lateinit var rowWebRemote: TextView
    private lateinit var rowWol: TextView
    private lateinit var rowBackupExport: TextView
    private lateinit var rowBackupImport: TextView

    private var backupNote: String = ""

    fun buildNightAndAmbientRows() {
        rowClockStyle = host.addRow {
            val next = store.updateAppearance { it.copy(clockStyle = AmbientClockStyle.next(it.clockStyle)) }
            host.onStateChanged(next)
        }
        rowNightMode = host.addRow {
            val next = store.updateFeatures { f ->
                f.copy(night = f.night.copy(mode = NightMode.next(f.night.mode)))
            }
            host.onStateChanged(next)
        }
        rowNightWindow = host.addRow {
            val cur = host.currentState().features.night
            val idx = NIGHT_WINDOWS.indexOfFirst { it.first == cur.startHour && it.second == cur.endHour }
            val (s, e) = NIGHT_WINDOWS[(idx + 1) % NIGHT_WINDOWS.size]
            val next = store.updateFeatures { f -> f.copy(night = f.night.copy(startHour = s, endHour = e)) }
            host.onStateChanged(next)
        }
        rowSleepTimer = host.addRow { openSleepDialog() }
    }

    fun buildSmartWidgetsSection() {
        host.addSection("Умные виджеты и автозапуск")
        rowWeather = host.addRow { openCityPicker() }
        rowNowPlaying = host.addRow { openNowPlayingDialog() }
        rowBootLaunch = host.addRow { openBootAppPicker() }
    }

    fun buildCompanionAndBackupSection() {
        host.addSection("LAN Web-пульт, Wake-on-LAN и бэкап")
        rowWebRemote = host.addRow { openWebRemoteDialog() }
        rowWol = host.addRow { openWolDialog() }
        rowBackupExport = host.addRow { exportBackup() }
        rowBackupImport = host.addRow { importBackup() }
    }

    fun bindRows() {
        val st = host.currentState()
        val cal = Calendar.getInstance()
        val nightNow = NightSchedule.isNightActive(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), st.features.night)
        rowClockStyle.text = line("Стиль Ambient-часов", "${st.appearance.clockStyle.label} (4-corner hop 15 мин)")
        rowNightMode.text = line("Ночной кино-режим", NightSchedule.describeStatus(st.features.night, nightNow))
        rowNightWindow.text = line("Расписание ночного режима", "${NightSchedule.formatSchedule(st.features.night)} · dim ${st.features.night.nightDimPercent}%")
        val sleep = SleepTimerController.currentState()
        val sleepVal = if (sleep.active) "осталось ${sleep.badgeText}" else "Выкл"
        val bridgeOk = RootBridgeClient.isAvailable(activity)
        val sleepModeVal = if (bridgeOk && st.features.sleepHdmiOff) "HDMI-off через мост" else "чёрный экран"
        rowSleepTimer.text = line("Таймер сна", "$sleepVal ($sleepModeVal)")

        val w = st.features.weather
        rowWeather.text = line("Погода (Open-Meteo)", if (w.isConfigured) w.cityName else "Выкл (нажмите для выбора города)")
        val npGranted = MediaSessionBridge.isListenerGranted(activity)
        rowNowPlaying.text = line("Пилюля «Сейчас играет»", if (npGranted) "Активна (0% в простое)" else "Нет гранта NotificationListener")
        val boot = st.features.bootAutoLaunch
        rowBootLaunch.text = line("Автозапуск после ребута", if (boot.enabled && boot.packageName.isNotEmpty()) boot.packageName else "Выкл")

        val web = st.features.webRemote
        val pin = LanWebRemoteService.ensurePinGenerated(store)
        val ip = host.currentIp().ifEmpty { "<IP ТВ>" }
        rowWebRemote.text = line("LAN Web-пульт", if (web.enabled) "http://$ip:${web.port}  ·  PIN $pin" else "Выкл")
        val wol = st.features.wol
        rowWol.text = line("Wake-on-LAN ПК", if (wol.isConfigured) "${wol.name} (${wol.mac})" else "Не настроен (нажмите для ввода MAC)")
        rowBackupExport.text = if (backupNote.isEmpty()) "Экспорт настроек в JSON" else "Экспорт JSON · $backupNote"
        rowBackupImport.text = "Импорт настроек из JSON (ambient_home_backup.json)"
    }

    private fun openSleepDialog() {
        val st = host.currentState()
        val dlg = FeatureDialogs.showSleepTimerPicker(
            ctx = activity,
            current = SleepTimerController.currentState(),
            hdmiOffEnabled = st.features.sleepHdmiOff,
            bridgeAvailable = RootBridgeClient.isAvailable(activity),
            onPickMinutes = { mins ->
                SleepTimerController.startTimer(activity, mins)
                bindRows()
            },
            onToggleHdmiOff = {
                val next = store.updateFeatures { it.copy(sleepHdmiOff = !it.sleepHdmiOff) }
                host.onStateChanged(next)
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openNowPlayingDialog() {
        val granted = MediaSessionBridge.isListenerGranted(activity)
        val cmd = "adb shell cmd notification allow_listener ${activity.packageName}/${activity.packageName}.data.AmbientNotificationListener"
        val dlg = FeatureDialogs.showNotificationAccessInfo(
            ctx = activity,
            granted = granted,
            adbCommand = cmd,
            onOpenSettings = { openNotificationListenerSettings() },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openNotificationListenerSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Throwable) {
            toast("Экран недоступен на этом Android TV — используйте команду ADB")
        }
    }

    private fun openCityPicker() {
        val dlg = FeatureDialogs.showCityPicker(
            ctx = activity,
            presets = WeatherClient.RU_CITY_PRESETS,
            currentCity = host.currentState().features.weather.cityName,
            onPick = { city ->
                val next = store.updateFeatures { it.copy(weather = WeatherConfig(city.name, city.lat, city.lon)) }
                host.onStateChanged(next)
            },
            onSearchPrompt = { openCitySearch() },
            onDisable = {
                val next = store.updateFeatures { it.copy(weather = WeatherConfig()) }
                host.onStateChanged(next)
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openCitySearch() {
        val dlg = FeatureDialogs.showTextInput(
            ctx = activity,
            title = "Поиск города (Open-Meteo)",
            hint = "Введите название города",
            initialValue = host.currentState().features.weather.cityName,
            onSubmit = { query ->
                if (query.length < 2) return@showTextInput
                ioRunner {
                    val found = WeatherClient.searchCity(query).firstOrNull()
                    mainHandler.post {
                        if (found != null) {
                            val next = store.updateFeatures { it.copy(weather = WeatherConfig(found.name, found.lat, found.lon)) }
                            host.onStateChanged(next)
                            toast("Выбран город: ${found.name}")
                        } else toast("Город не найден")
                    }
                }
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openBootAppPicker() {
        ioRunner {
            val apps = AppRepository.loadAll(activity.applicationContext).sortedBy { it.label.lowercase() }
            val choices = ArrayList<Pair<String, String>>(apps.size + 1)
            choices.add("" to "Выкл (оставаться на Ambient Home)")
            for (a in apps) choices.add(a.packageName to "${a.label}  (${a.packageName})")
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val dlg = FeatureDialogs.showChoiceList(
                    ctx = activity,
                    title = "Автозапуск после холодного ребута",
                    items = choices,
                    onPick = { pkg ->
                        val next = store.updateFeatures { f ->
                            f.copy(bootAutoLaunch = f.bootAutoLaunch.copy(enabled = pkg.isNotEmpty(), packageName = pkg))
                        }
                        host.onStateChanged(next)
                    },
                    onDismiss = { host.onDialogChanged(null) }
                )
                host.onDialogChanged(dlg)
            }
        }
    }

    private fun openWebRemoteDialog() {
        val st = host.currentState()
        val pin = LanWebRemoteService.ensurePinGenerated(store)
        val ip = host.currentIp().ifEmpty { "<IP ТВ>" }
        val dlg = FeatureDialogs.showWebRemoteInfo(
            ctx = activity,
            enabled = st.features.webRemote.enabled,
            url = "http://$ip:${st.features.webRemote.port}",
            pin = pin,
            onToggleEnabled = {
                val next = store.updateFeatures { f -> f.copy(webRemote = f.webRemote.copy(enabled = !f.webRemote.enabled)) }
                LanWebRemoteService.syncRunning(activity, next.features.webRemote.enabled)
                host.onStateChanged(next)
            },
            onRotatePin = {
                val newPin = LauncherStore.generatePin()
                val next = store.updateFeatures { f -> f.copy(webRemote = f.webRemote.copy(pin = newPin)) }
                host.onStateChanged(next)
                toast("Новый PIN: $newPin")
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun openWolDialog() {
        val cur = host.currentState().features.wol
        val dlg = FeatureDialogs.showTextInput(
            ctx = activity,
            title = "Wake-on-LAN MAC-адрес ПК",
            hint = "AA:BB:CC:DD:EE:FF (пусто — убрать плитку)",
            initialValue = cur.mac,
            onSubmit = { raw ->
                if (raw.isEmpty()) {
                    val next = store.updateFeatures { f -> f.copy(wol = f.wol.copy(mac = "")) }
                    host.onStateChanged(next)
                    toast("Плитка WOL скрыта")
                    return@showTextInput
                }
                val norm = CustomShortcuts.normalizeMac(raw)
                if (norm == null) {
                    toast("Некорректный MAC-адрес")
                    return@showTextInput
                }
                val next = store.updateFeatures { f -> f.copy(wol = f.wol.copy(mac = norm)) }
                host.onStateChanged(next)
                ioRunner {
                    val res = WolSender.send(next.features.wol, SystemStatusHelper.readSystemStatus(activity).ip)
                    mainHandler.post { toast("Сохранено · ${res.message}") }
                }
            },
            onDismiss = { host.onDialogChanged(null) }
        )
        host.onDialogChanged(dlg)
    }

    private fun exportBackup() {
        val snapshot = host.currentState()
        ioRunner {
            val res = SettingsBackupCodec.exportToDisk(activity.applicationContext, snapshot)
            mainHandler.post {
                backupNote = res.message
                toast(res.message)
                bindRows()
            }
        }
    }

    private fun importBackup() {
        val current = host.currentState()
        ioRunner {
            val res = SettingsBackupCodec.importFromDisk(activity.applicationContext, current)
            mainHandler.post {
                val restored = res.state
                if (res.success && restored != null) {
                    val applied = SettingsBackupCodec.applyImportedState(store, restored)
                    LanWebRemoteService.syncRunning(activity, applied.features.webRemote.enabled)
                    host.onStateChanged(applied)
                }
                toast(res.message)
            }
        }
    }

    private fun line(name: String, value: String): String = "$name    ·   $value"

    private fun toast(msg: String) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
