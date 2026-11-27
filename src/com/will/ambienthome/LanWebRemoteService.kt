package com.will.ambienthome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.will.ambienthome.data.AppRepository
import com.will.ambienthome.data.AudioDeviceObserver
import com.will.ambienthome.data.LanWebRemoteRouter
import com.will.ambienthome.data.LanWebRemoteServer
import com.will.ambienthome.data.LauncherStore
import com.will.ambienthome.data.RootBridgeClient
import com.will.ambienthome.data.SleepTimerController
import com.will.ambienthome.data.SystemStatusHelper
import com.will.ambienthome.data.WeatherClient
import com.will.ambienthome.data.WolSender
import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.MiniJson
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WebRemoteConfig
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Лёгкий локальный foreground-сервис LAN Web-пульта (:8765), работающий даже поверх плееров
 * с 0.0% CPU в простое (блокирующий ServerSocket.accept()). Выключается полностью из Настроек.
 */
class LanWebRemoteService : Service(), LanWebRemoteRouter.Backend {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: LauncherStore
    private var server: LanWebRemoteServer? = null
    private var cachedHtml: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        store = LauncherStore(applicationContext)
        ensurePinGenerated(store)
        startSilentForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = store.readState().features.webRemote
        if (!cfg.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server?.isRunning() != true) {
            val router = LanWebRemoteRouter(this)
            val srv = LanWebRemoteServer(cfg.port, router)
            if (srv.start()) {
                server = srv
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun currentPin(): String = store.readState().features.webRemote.pin

    override fun htmlShell(): ByteArray {
        cachedHtml?.let { return it }
        return try {
            resources.openRawResource(R.raw.web_remote).use { it.readBytes() }.also { cachedHtml = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read web_remote.html", t)
            "<!doctype html><html><body>Ambient Home Remote</body></html>".toByteArray(Charsets.UTF_8)
        }
    }

    override fun statusJson(): Map<String, Any?> {
        val st = store.readState()
        val diag = SystemStatusHelper.readDiagnostics(applicationContext)
        val cal = Calendar.getInstance()
        val nightNow = NightSchedule.isNightActive(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), st.features.night)
        val sleep = SleepTimerController.currentState()
        val weather = WeatherClient.getCached(st.features.weather)
        val apps = AppRepository.orderVisible(AppRepository.loadAll(applicationContext, st.features.wol), st.layout)
            .map { mapOf("package" to it.packageName, "label" to it.label, "virtual" to it.isVirtual) }
        val usb = AudioDeviceObserver.listExternalUsbVolumes(applicationContext)
            .map { mapOf("id" to it.id, "label" to it.label) }
        val modes = SystemStatusHelper.readDisplayModes(applicationContext)
            .map { mapOf("w" to it.first, "h" to it.second, "fps" to it.third) }
        return linkedMapOf(
            "ok" to true,
            "version" to "3.0.0",
            "bridgeAvailable" to RootBridgeClient.isAvailable(applicationContext),
            "nightActive" to nightNow,
            "nightMode" to st.features.night.mode.name,
            "nightLabel" to NightSchedule.describeStatus(st.features.night, nightNow),
            "nightStart" to st.features.night.startHour,
            "nightEnd" to st.features.night.endHour,
            "sleepActive" to sleep.active,
            "sleepRemainingSec" to sleep.remainingSec,
            "sleepBadge" to sleep.badgeText,
            "wolConfigured" to st.features.wol.isConfigured,
            "wolName" to st.features.wol.name,
            "wolMac" to st.features.wol.mac,
            "weatherCity" to st.features.weather.cityName,
            "weatherSummary" to (weather?.ambientSummary ?: ""),
            "telemetry" to telemetryMap(diag),
            "apps" to apps,
            "usb" to usb,
            "displayModes" to modes
        )
    }

    private fun telemetryMap(diag: com.will.ambienthome.model.DiagnosticsSnapshot): Map<String, Any?> = mapOf(
        "socTempC" to diag.socTempC,
        "cpuGov" to diag.cpuGov,
        "cpuFreqMhz" to diag.cpuFreqMhz,
        "gpuFreqMhz" to diag.gpuFreqMhz,
        "memAvailMb" to diag.memAvailMb,
        "memTotalMb" to diag.memTotalMb,
        "uptimeSec" to diag.uptimeSec,
        "ssid" to diag.status.ssid,
        "ip" to diag.status.ip,
        "rssi" to diag.status.rssi,
        "linkSpeedMbps" to diag.status.linkSpeedMbps
    )

    override fun dispatchKeyOrText(keyCode: Int?, text: String?): Pair<Boolean, String> {
        if (keyCode != null) {
            val res = RootBridgeClient.sendKey(applicationContext, keyCode)
            return res.ok to (if (res.ok) "key_sent" else res.error)
        }
        val payload = text ?: return false to "empty_text"
        if (!RootBridgeClient.isAvailable(applicationContext)) {
            return false to "bridge_fifo_missing"
        }
        val clipOk = commitClipboardSync(payload)
        if (!clipOk) return false to "clipboard_failed"
        val res = RootBridgeClient.sendSimple(applicationContext, "PASTE")
        return res.ok to (if (res.ok) "pasted" else res.error)
    }

    override fun dispatchApp(action: String, pkg: String): Pair<Boolean, String> {
        if (action == "forcestop") {
            val res = RootBridgeClient.sendForceStop(applicationContext, pkg)
            return res.ok to (if (res.ok) "force_stopped" else res.error)
        }
        if (CustomShortcuts.isWolShortcut(pkg)) {
            return dispatchWol()
        }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
            ?: return false to "app_not_found"
        return try {
            startActivity(launch.addFlags(flags))
            true to "launched"
        } catch (t: Throwable) {
            Log.w(TAG, "Remote launch failed for $pkg", t)
            false to "launch_failed"
        }
    }

    override fun dispatchAction(action: String, params: Map<String, Any?>): Pair<Boolean, String> {
        return when (action) {
            "night_toggle" -> updateNightMode(null)
            "night_on" -> updateNightMode(NightMode.ON)
            "night_off" -> updateNightMode(NightMode.OFF)
            "night_auto" -> updateNightMode(NightMode.AUTO)
            "sleep_timer" -> {
                val mins = (MiniJson.asInt(params["minutes"]) ?: 0).coerceIn(0, 240)
                mainHandler.post { SleepTimerController.startTimer(applicationContext, mins) }
                true to if (mins == 0) "sleep_cancelled" else "sleep_${mins}m"
            }
            "next_wallpaper", "ambient_now", "media_play_pause", "media_next" -> {
                notifyUiEvent(action)
                true to action
            }
            "wol" -> dispatchWol()
            "hdmi_sleep" -> bridgeToPair(RootBridgeClient.sendSimple(applicationContext, "SLEEP"), "sleep_sent")
            "hdmi_wake" -> bridgeToPair(RootBridgeClient.sendSimple(applicationContext, "WAKE"), "wake_sent")
            "afr" -> {
                val fps = MiniJson.asInt(params["fps"]) ?: MiniJson.asInt(params["mode"]) ?: 0
                bridgeToPair(RootBridgeClient.sendAfr(applicationContext, fps), "afr_$fps")
            }
            "usb_eject" -> {
                val vol = (params["volume"] as? String)?.trim().orEmpty()
                bridgeToPair(RootBridgeClient.sendEject(applicationContext, vol), "ejected")
            }
            "wifi_reconnect" -> {
                val res = SystemStatusHelper.softReconnectWifi(applicationContext)
                res.success to res.message
            }
            "config" -> applyRemoteConfig(params)
            else -> false to "unknown_action"
        }
    }

    override fun saveWallpaper(safeFileName: String, bytes: ByteArray): Pair<Boolean, String> {
        val saved = saveViaMediaStore(safeFileName, bytes) || saveViaDirectFile(safeFileName, bytes)
        if (saved) {
            notifyUiEvent("wallpaper_uploaded")
            return true to "saved:$safeFileName"
        }
        return false to "storage_write_failed"
    }

    private fun updateNightMode(explicit: NightMode?): Pair<Boolean, String> {
        store.updateFeatures { f ->
            val next = explicit ?: when (f.night.mode) {
                NightMode.ON -> NightMode.OFF
                else -> NightMode.ON
            }
            f.copy(night = f.night.copy(mode = next))
        }
        notifyUiEvent("state_changed")
        return true to "night_updated"
    }

    private fun applyRemoteConfig(params: Map<String, Any?>): Pair<Boolean, String> {
        val weatherUpdate = resolveRemoteWeather(params) ?: return false to "city_not_found"
        val wolMacRaw = params["wolMac"] as? String
        if (wolMacRaw != null && wolMacRaw.isNotBlank() && !CustomShortcuts.isValidMac(wolMacRaw)) {
            return false to "invalid_mac"
        }
        store.updateFeatures { f -> mergeFeatureConfig(f, weatherUpdate, wolMacRaw, params) }
        (params["clockStyle"] as? String)?.let { styleName ->
            AmbientClockStyle.entries.firstOrNull { it.name.equals(styleName, ignoreCase = true) }?.let { cs ->
                store.updateAppearance { it.copy(clockStyle = cs) }
            }
        }
        notifyUiEvent("state_changed")
        return true to "config_saved"
    }

    private fun resolveRemoteWeather(params: Map<String, Any?>): WeatherConfig? {
        val cityQuery = (params["weatherCity"] as? String)?.trim() ?: return WeatherConfig(cityName = KEEP_WEATHER_SENTINEL)
        if (cityQuery.isEmpty()) return WeatherConfig()
        val lat = MiniJson.asDouble(params["weatherLat"])
        val lon = MiniJson.asDouble(params["weatherLon"])
        if (lat != null && lon != null) return WeatherConfig(cityQuery, lat, lon)
        val found = WeatherClient.searchCity(cityQuery).firstOrNull() ?: return null
        return WeatherConfig(found.name, found.lat, found.lon)
    }

    private fun mergeFeatureConfig(
        f: com.will.ambienthome.model.FeatureState,
        weatherUpdate: WeatherConfig,
        wolMacRaw: String?,
        params: Map<String, Any?>
    ): com.will.ambienthome.model.FeatureState {
        var next = f
        if (weatherUpdate.cityName != KEEP_WEATHER_SENTINEL) next = next.copy(weather = weatherUpdate)
        if (wolMacRaw != null) {
            val name = (params["wolName"] as? String)?.trim().orEmpty().ifEmpty { f.wol.name }
            next = next.copy(wol = f.wol.copy(name = name, mac = wolMacRaw.trim()))
        }
        val startH = MiniJson.asInt(params["nightStart"])
        val endH = MiniJson.asInt(params["nightEnd"])
        if (startH != null || endH != null) {
            next = next.copy(
                night = next.night.copy(
                    startHour = startH ?: next.night.startHour,
                    endHour = endH ?: next.night.endHour
                )
            )
        }
        return next
    }

    private fun dispatchWol(): Pair<Boolean, String> {
        val st = store.readState()
        val ip = SystemStatusHelper.readSystemStatus(applicationContext).ip
        val res = WolSender.send(st.features.wol, ip)
        return res.success to res.message
    }

    private fun commitClipboardSync(text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("remote", text))
                ok = cm != null
            } catch (t: Throwable) {
                Log.w(TAG, "setPrimaryClip failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500L, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun saveViaMediaStore(safeFileName: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val mime = if (safeFileName.endsWith(".png")) "image/png" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeFileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Wallpapers")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore insert failed", t)
            null
        } ?: return false
        return try {
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                safeDeleteMediaUri(uri)
                return false
            }
            stream.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            true
        } catch (t: Throwable) {
            safeDeleteMediaUri(uri)
            Log.w(TAG, "MediaStore wallpaper save failed", t)
            false
        }
    }

    private fun safeDeleteMediaUri(uri: android.net.Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun saveViaDirectFile(safeFileName: String, bytes: ByteArray): Boolean {
        return try {
            val dir = File("/sdcard/Pictures/Wallpapers")
            dir.mkdirs()
            val dirCanon = dir.canonicalPath
            val target = File(dir, safeFileName)
            if (target.canonicalFile.parent != dirCanon) return false
            val tmp = File(dir, ".tmp_$safeFileName")
            tmp.writeBytes(bytes)
            tmp.renameTo(target)
        } catch (t: Throwable) {
            Log.w(TAG, "Direct wallpaper save failed", t)
            false
        }
    }

    private fun startSilentForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "LAN Web-пульт", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            nm?.createNotificationChannel(ch)
            val note = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Ambient Home Web-пульт")
                .setContentText("Активен в локальной сети (:${WebRemoteConfig.DEFAULT_PORT})")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, note)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun bridgeToPair(res: RootBridgeClient.BridgeResult, okMsg: String): Pair<Boolean, String> =
        res.ok to (if (res.ok) okMsg else res.error)

    companion object {
        private const val TAG = "AmbientHome"
        private const val CHANNEL_ID = "ambient_web_remote"
        private const val NOTIFICATION_ID = 3087
        private const val KEEP_WEATHER_SENTINEL = "\u0000KEEP"

        @Volatile
        private var uiEventListener: ((String) -> Unit)? = null

        fun setUiEventListener(listener: ((String) -> Unit)?) {
            uiEventListener = listener
        }

        fun ensurePinGenerated(store: LauncherStore): String {
            val existing = store.readState().features.webRemote.pin
            if (existing.length == 6) return existing
            val pin = LauncherStore.generatePin()
            store.updateFeatures { it.copy(webRemote = it.webRemote.copy(pin = pin)) }
            return pin
        }

        fun syncRunning(ctx: Context, enabled: Boolean) {
            val intent = Intent(ctx, LanWebRemoteService::class.java)
            try {
                if (enabled) ctx.startForegroundService(intent) else ctx.stopService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "LanWebRemoteService syncRunning($enabled) failed", t)
            }
        }

        private fun notifyUiEvent(event: String) {
            uiEventListener?.invoke(event)
        }
    }
}
