package com.will.ambienthome.data

import android.content.Context
import android.util.Log
import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.AppearanceState
import com.will.ambienthome.model.AppsLayoutState
import com.will.ambienthome.model.BootAutoLaunchConfig
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.FeatureState
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.MiniJson
import com.will.ambienthome.model.NightConfig
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.WallpaperSource
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WebRemoteConfig
import com.will.ambienthome.model.WolTarget
import java.io.File

/**
 * Человекочитаемый JSON экспорт и импорт настроек Ambient Home 3.0.0.
 * Использует чистый MiniJson (0 внешних зависимостей, 100% JVM-тестируемость).
 */
object SettingsBackupCodec {

    private const val TAG = "AmbientHome"
    private const val BACKUP_FILENAME = "ambient_home_backup.json"
    private const val SCHEMA_VERSION = 3

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val state: LauncherState? = null
    )

    fun encode(state: LauncherState): String {
        val root = LinkedHashMap<String, Any?>()
        root["schemaVersion"] = SCHEMA_VERSION
        root["appearance"] = encodeAppearance(state.appearance)
        root["layout"] = mapOf(
            "pinnedPackages" to state.layout.pinnedPackages,
            "dockOrder" to state.layout.dockOrder,
            "hiddenPackages" to state.layout.hiddenPackages.sorted()
        )
        encodeFeatures(root, state.features)
        return MiniJson.stringify(root, pretty = true) + "\n"
    }

    private fun encodeAppearance(ap: AppearanceState): Map<String, Any?> = mapOf(
        "homeMode" to ap.homeMode.name,
        "wallpaperSource" to ap.wallpaperSource.name,
        "wallpaperIntervalMin" to ap.wallpaperIntervalMin,
        "dimPercent" to ap.dimPercent,
        "showClock" to ap.showClock,
        "ambientTimeoutMin" to ap.ambientTimeoutMin,
        "clockStyle" to ap.clockStyle.name
    )

    private fun encodeFeatures(root: LinkedHashMap<String, Any?>, ft: FeatureState) {
        root["night"] = mapOf(
            "mode" to ft.night.mode.name,
            "startHour" to ft.night.startHour,
            "endHour" to ft.night.endHour,
            "nightDimPercent" to ft.night.nightDimPercent
        )
        root["sleep"] = mapOf("hdmiOff" to ft.sleepHdmiOff)
        root["weather"] = mapOf(
            "cityName" to ft.weather.cityName,
            "latitude" to if (ft.weather.isConfigured) ft.weather.latitude else null,
            "longitude" to if (ft.weather.isConfigured) ft.weather.longitude else null
        )
        root["bootAutoLaunch"] = mapOf(
            "enabled" to ft.bootAutoLaunch.enabled,
            "packageName" to ft.bootAutoLaunch.packageName
        )
        root["wol"] = mapOf(
            "name" to ft.wol.name,
            "mac" to ft.wol.mac,
            "broadcastIp" to ft.wol.broadcastIp
        )
        root["webRemote"] = mapOf(
            "enabled" to ft.webRemote.enabled,
            "port" to ft.webRemote.port,
            "pin" to ft.webRemote.pin
        )
    }

    fun decode(json: String, fallback: LauncherState = LauncherState()): LauncherState? {
        val root = MiniJson.parseObject(json) ?: return null
        val ver = MiniJson.asInt(root["schemaVersion"]) ?: return null
        if (ver !in 1..SCHEMA_VERSION) return null
        val ap = decodeAppearance(root["appearance"] as? Map<*, *>, fallback.appearance)
        val ly = decodeLayout(root["layout"] as? Map<*, *>, fallback.layout)
        val ft = decodeFeatures(root, fallback.features)
        return LauncherState(appearance = ap, layout = ly, features = ft)
    }

    fun exportToDisk(ctx: Context, state: LauncherState): BackupResult {
        val json = encode(state)
        val primary = primaryFile(ctx) ?: return BackupResult(false, "Внешнее хранилище недоступно")
        return try {
            primary.parentFile?.mkdirs()
            primary.writeText(json, Charsets.UTF_8)
            val mirrored = tryMirrorToDownload(json)
            val note = if (mirrored) " + Download/$BACKUP_FILENAME" else ""
            BackupResult(true, "Сохранено: ${primary.name}$note", state)
        } catch (t: Throwable) {
            Log.w(TAG, "exportToDisk failed", t)
            BackupResult(false, "Ошибка записи бэкапа")
        }
    }

    fun importFromDisk(ctx: Context, current: LauncherState): BackupResult {
        val candidates = listOfNotNull(primaryFile(ctx), File("/sdcard/Download", BACKUP_FILENAME))
        val source = candidates.firstOrNull { it.isFile && it.canRead() && it.length() in 10L..262_144L }
            ?: return BackupResult(false, "Файл $BACKUP_FILENAME не найден")
        return try {
            val raw = source.readText(Charsets.UTF_8)
            val decoded = decode(raw, current) ?: return BackupResult(false, "Некорректный JSON бэкапа")
            BackupResult(true, "Импортировано из ${source.name}", decoded)
        } catch (t: Throwable) {
            Log.w(TAG, "importFromDisk failed", t)
            BackupResult(false, "Ошибка чтения бэкапа")
        }
    }

    fun applyImportedState(store: LauncherStore, restored: LauncherState): LauncherState {
        store.updateAppearance { cur ->
            restored.appearance.copy(wallpaperIndices = cur.wallpaperIndices)
        }
        store.updateAppsLayout { restored.layout }
        return store.updateFeatures { cur ->
            restored.features.copy(
                bootAutoLaunch = restored.features.bootAutoLaunch.copy(
                    lastHandledBootId = cur.bootAutoLaunch.lastHandledBootId
                ),
                webRemote = restored.features.webRemote.copy(
                    pin = restored.features.webRemote.pin.ifEmpty { cur.webRemote.pin }
                )
            )
        }
    }

    private fun decodeAppearance(map: Map<*, *>?, base: AppearanceState): AppearanceState {
        if (map == null) return base
        val mode = parseEnum(map["homeMode"] as? String, HomeMode.entries) ?: base.homeMode
        val src = parseEnum(map["wallpaperSource"] as? String, WallpaperSource.entries) ?: base.wallpaperSource
        val clk = parseEnum(map["clockStyle"] as? String, AmbientClockStyle.entries) ?: base.clockStyle
        return base.copy(
            homeMode = mode,
            wallpaperSource = src,
            wallpaperIntervalMin = (MiniJson.asInt(map["wallpaperIntervalMin"]) ?: base.wallpaperIntervalMin).coerceAtLeast(0),
            dimPercent = (MiniJson.asInt(map["dimPercent"]) ?: base.dimPercent).coerceIn(0, 70),
            showClock = (map["showClock"] as? Boolean) ?: base.showClock,
            ambientTimeoutMin = (MiniJson.asInt(map["ambientTimeoutMin"]) ?: base.ambientTimeoutMin).coerceAtLeast(0),
            clockStyle = clk
        )
    }

    private fun decodeLayout(map: Map<*, *>?, base: AppsLayoutState): AppsLayoutState {
        if (map == null) return base
        return AppsLayoutState(
            pinnedPackages = readStrList(map["pinnedPackages"]) ?: base.pinnedPackages,
            dockOrder = readStrList(map["dockOrder"]) ?: base.dockOrder,
            hiddenPackages = readStrList(map["hiddenPackages"])?.toSet() ?: base.hiddenPackages
        )
    }

    private fun decodeFeatures(root: Map<String, Any?>, base: FeatureState): FeatureState {
        val sMap = root["sleep"] as? Map<*, *>
        val hdmiOff = (sMap?.get("hdmiOff") as? Boolean) ?: base.sleepHdmiOff
        return FeatureState(
            night = decodeNight(root["night"] as? Map<*, *>, base.night),
            sleepHdmiOff = hdmiOff,
            weather = decodeWeather(root["weather"] as? Map<*, *>, base.weather),
            bootAutoLaunch = decodeBoot(root["bootAutoLaunch"] as? Map<*, *>, base.bootAutoLaunch),
            wol = decodeWol(root["wol"] as? Map<*, *>, base.wol),
            webRemote = decodeWebRemote(root["webRemote"] as? Map<*, *>, base.webRemote)
        )
    }

    private fun decodeNight(map: Map<*, *>?, base: NightConfig): NightConfig {
        if (map == null) return base
        return NightConfig(
            mode = parseEnum(map["mode"] as? String, NightMode.entries) ?: base.mode,
            startHour = (MiniJson.asInt(map["startHour"]) ?: base.startHour).coerceIn(0, 23),
            endHour = (MiniJson.asInt(map["endHour"]) ?: base.endHour).coerceIn(0, 23),
            nightDimPercent = (MiniJson.asInt(map["nightDimPercent"]) ?: base.nightDimPercent).coerceIn(30, 82)
        )
    }

    private fun decodeWeather(map: Map<*, *>?, base: WeatherConfig): WeatherConfig {
        if (map == null) return base
        val city = (map["cityName"] as? String)?.trim().orEmpty()
        val lat = MiniJson.asDouble(map["latitude"]) ?: Double.NaN
        val lon = MiniJson.asDouble(map["longitude"]) ?: Double.NaN
        return if (city.isNotEmpty() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            WeatherConfig(city, lat, lon)
        } else {
            WeatherConfig()
        }
    }

    private fun decodeBoot(map: Map<*, *>?, base: BootAutoLaunchConfig): BootAutoLaunchConfig {
        if (map == null) return base
        return base.copy(
            enabled = (map["enabled"] as? Boolean) ?: base.enabled,
            packageName = (map["packageName"] as? String)?.trim().orEmpty()
        )
    }

    private fun decodeWol(map: Map<*, *>?, base: WolTarget): WolTarget {
        if (map == null) return base
        return WolTarget(
            name = (map["name"] as? String)?.trim().orEmpty().ifEmpty { WolTarget.DEFAULT_NAME },
            mac = CustomShortcuts.normalizeMac((map["mac"] as? String).orEmpty()).orEmpty(),
            broadcastIp = CustomShortcuts.normalizeBroadcastIp((map["broadcastIp"] as? String).orEmpty())
        )
    }

    private fun decodeWebRemote(map: Map<*, *>?, base: WebRemoteConfig): WebRemoteConfig {
        if (map == null) return base
        return WebRemoteConfig(
            enabled = (map["enabled"] as? Boolean) ?: base.enabled,
            port = (MiniJson.asInt(map["port"]) ?: base.port).coerceIn(1024, 65535),
            pin = LauncherStore.sanitizePin((map["pin"] as? String).orEmpty())
        )
    }

    private fun readStrList(raw: Any?): List<String>? {
        val list = raw as? List<*> ?: return null
        return list.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
    }

    private fun <T : Enum<T>> parseEnum(name: String?, entries: List<T>): T? =
        if (name == null) null else entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private fun primaryFile(ctx: Context): File? =
        ctx.getExternalFilesDir(null)?.let { File(it, BACKUP_FILENAME) }

    private fun tryMirrorToDownload(json: String): Boolean {
        return try {
            val dir = File("/sdcard/Download")
            if (!dir.isDirectory || !dir.canWrite()) return false
            File(dir, BACKUP_FILENAME).writeText(json, Charsets.UTF_8)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
