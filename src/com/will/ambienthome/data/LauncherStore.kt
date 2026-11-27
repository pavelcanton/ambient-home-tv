package com.will.ambienthome.data

import android.content.Context
import android.content.SharedPreferences
import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.AppearanceState
import com.will.ambienthome.model.AppsLayoutState
import com.will.ambienthome.model.BootAutoLaunchConfig
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.FeatureState
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.NightConfig
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.WallpaperSource
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WebRemoteConfig
import com.will.ambienthome.model.WolTarget
import java.security.SecureRandom

/**
 * Единое атомарное хранилище состояния Ambient Home 3.0.0 поверх SharedPreferences.
 * Владеет RAM-буфером индексов обоев на каждый WallpaperSource (без EnumMap и лишних записей на флеш)
 * и сохраняет 100% обратную совместимость ключей с v2.0.x–v2.1.1 при обновлении.
 */
class LauncherStore private constructor(
    private val sp: SharedPreferences,
    legacySpProvider: () -> SharedPreferences?
) {

    constructor(ctx: Context) : this(
        sp = ctx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        legacySpProvider = { ctx.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE) }
    )

    internal constructor(
        sp: SharedPreferences,
        legacySp: SharedPreferences?
    ) : this(sp = sp, legacySpProvider = { legacySp })

    private val ramWpIndices = IntArray(WallpaperSource.entries.size)
    private val persistedWpIndices = IntArray(WallpaperSource.entries.size)
    private val dirtyWpIndices = BooleanArray(WallpaperSource.entries.size)

    init {
        migrateLegacyIfNeeded(legacySpProvider)
        cleanupOrphanKeys()
        readWallpaperIndices(readActiveWallpaperSource())
    }

    fun readState(): LauncherState = LauncherState(
        appearance = readAppearance(),
        layout = readLayout(),
        features = readFeatures()
    )

    fun updateAppearance(transform: (AppearanceState) -> AppearanceState): LauncherState {
        val current = readAppearance()
        val updated = normalizeAppearance(transform(current))
        writeAppearance(updated)
        return readState()
    }

    fun updateAppsLayout(transform: (AppsLayoutState) -> AppsLayoutState): LauncherState {
        val current = readLayout()
        val updated = normalizeLayout(transform(current))
        writeLayout(updated)
        return LauncherState(appearance = readAppearance(), layout = updated, features = readFeatures())
    }

    fun updateFeatures(transform: (FeatureState) -> FeatureState): LauncherState {
        val current = readFeatures()
        val updated = normalizeFeatures(transform(current))
        writeFeatures(updated)
        return LauncherState(appearance = readAppearance(), layout = readLayout(), features = updated)
    }

    fun wallpaperIndexFor(source: WallpaperSource): Int = ramWpIndices[source.ordinal]

    fun recordWallpaperIndex(source: WallpaperSource, index: Int, persistImmediately: Boolean) {
        val safe = index.coerceAtLeast(0)
        val ord = source.ordinal
        ramWpIndices[ord] = safe
        if (persistImmediately) {
            if (persistedWpIndices[ord] != safe) {
                sp.edit().putInt(wpIndexKey(source), safe).apply()
                persistedWpIndices[ord] = safe
            }
            dirtyWpIndices[ord] = false
        } else {
            dirtyWpIndices[ord] = (persistedWpIndices[ord] != safe)
        }
    }

    fun flushWallpaperIndices() {
        var edit: SharedPreferences.Editor? = null
        for (src in WallpaperSource.entries) {
            val ord = src.ordinal
            if (dirtyWpIndices[ord]) {
                val cur = ramWpIndices[ord]
                if (persistedWpIndices[ord] != cur) {
                    edit = (edit ?: sp.edit()).putInt(wpIndexKey(src), cur)
                    persistedWpIndices[ord] = cur
                }
                dirtyWpIndices[ord] = false
            }
        }
        edit?.apply()
    }

    private fun readActiveWallpaperSource(): WallpaperSource =
        WallpaperSource.fromCode(sp.getInt(KEY_WP_SOURCE, WallpaperSource.BUILTIN.code))

    private fun readAppearance(): AppearanceState {
        val source = readActiveWallpaperSource()
        return AppearanceState(
            homeMode = HomeMode.fromCode(sp.getInt(KEY_MODE, HomeMode.DOCK.code)),
            wallpaperSource = source,
            wallpaperIndices = readWallpaperIndices(source),
            wallpaperIntervalMin = sp.getInt(KEY_WP_INTERVAL, AppearanceState.DEFAULT_WP_INTERVAL_MIN).coerceAtLeast(0),
            dimPercent = sp.getInt(KEY_DIM, AppearanceState.DEFAULT_DIM_PERCENT).coerceIn(0, 70),
            showClock = sp.getBoolean(KEY_CLOCK, true),
            ambientTimeoutMin = sp.getInt(KEY_AMBIENT_TIMEOUT, AppearanceState.DEFAULT_AMBIENT_MIN).coerceAtLeast(0),
            clockStyle = AmbientClockStyle.fromCode(sp.getInt(KEY_CLOCK_STYLE, AmbientClockStyle.THIN.code))
        )
    }

    private fun readFeatures(): FeatureState {
        val latRaw = sp.getString(KEY_WEATHER_LAT, null)?.toDoubleOrNull() ?: Double.NaN
        val lonRaw = sp.getString(KEY_WEATHER_LON, null)?.toDoubleOrNull() ?: Double.NaN
        return FeatureState(
            night = NightConfig(
                mode = NightMode.fromCode(sp.getInt(KEY_NIGHT_MODE, NightMode.AUTO.code)),
                startHour = sp.getInt(KEY_NIGHT_START, NightConfig.DEFAULT_START_HOUR).coerceIn(0, 23),
                endHour = sp.getInt(KEY_NIGHT_END, NightConfig.DEFAULT_END_HOUR).coerceIn(0, 23),
                nightDimPercent = sp.getInt(KEY_NIGHT_DIM, NightConfig.DEFAULT_NIGHT_DIM).coerceIn(30, 82)
            ),
            sleepHdmiOff = sp.getBoolean(KEY_SLEEP_HDMI_OFF, true),
            weather = WeatherConfig(
                cityName = sp.getString(KEY_WEATHER_CITY, "")?.trim().orEmpty(),
                latitude = latRaw,
                longitude = lonRaw
            ),
            bootAutoLaunch = BootAutoLaunchConfig(
                enabled = sp.getBoolean(KEY_BOOT_ENABLED, false),
                packageName = sp.getString(KEY_BOOT_PKG, "")?.trim().orEmpty(),
                lastHandledBootId = sp.getString(KEY_BOOT_LAST_ID, "")?.trim().orEmpty()
            ),
            wol = WolTarget(
                name = sp.getString(KEY_WOL_NAME, WolTarget.DEFAULT_NAME)?.trim().orEmpty()
                    .ifEmpty { WolTarget.DEFAULT_NAME },
                mac = sp.getString(KEY_WOL_MAC, "")?.trim().orEmpty(),
                broadcastIp = CustomShortcuts.normalizeBroadcastIp(
                    sp.getString(KEY_WOL_BCAST, WolTarget.DEFAULT_BROADCAST).orEmpty()
                )
            ),
            webRemote = WebRemoteConfig(
                enabled = sp.getBoolean(KEY_WEB_ENABLED, true),
                port = sp.getInt(KEY_WEB_PORT, WebRemoteConfig.DEFAULT_PORT).coerceIn(1024, 65535),
                pin = sp.getString(KEY_WEB_PIN, "")?.trim().orEmpty()
            )
        )
    }

    private fun readWallpaperIndices(activeSource: WallpaperSource): Map<WallpaperSource, Int> {
        val legacyIdx = sp.getInt(KEY_WP_INDEX, 0).coerceAtLeast(0)
        val out = LinkedHashMap<WallpaperSource, Int>(WallpaperSource.entries.size)
        for (src in WallpaperSource.entries) {
            val ord = src.ordinal
            val diskVal = readDiskWallpaperIndex(src, activeSource, legacyIdx)
            persistedWpIndices[ord] = diskVal
            val effective = if (dirtyWpIndices[ord]) ramWpIndices[ord] else {
                ramWpIndices[ord] = diskVal
                diskVal
            }
            out[src] = effective
        }
        return out
    }

    private fun readDiskWallpaperIndex(
        src: WallpaperSource,
        activeSource: WallpaperSource,
        legacyIdx: Int
    ): Int {
        val key = wpIndexKey(src)
        return when {
            sp.contains(key) -> sp.getInt(key, 0).coerceAtLeast(0)
            src == activeSource -> legacyIdx
            else -> 0
        }
    }

    private fun readLayout(): AppsLayoutState = AppsLayoutState(
        dockOrder = readOrderedList(KEY_DOCK_ORDER),
        pinnedPackages = readOrderedList(KEY_PINNED),
        hiddenPackages = readOrderedList(KEY_HIDDEN).toSet()
    )

    private fun readOrderedList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun normalizeAppearance(state: AppearanceState): AppearanceState = state.copy(
        wallpaperIntervalMin = state.wallpaperIntervalMin.coerceAtLeast(0),
        dimPercent = state.dimPercent.coerceIn(0, 70),
        ambientTimeoutMin = state.ambientTimeoutMin.coerceAtLeast(0),
        wallpaperIndices = state.wallpaperIndices.mapValues { (_, idx) -> idx.coerceAtLeast(0) }
    )

    private fun normalizeLayout(state: AppsLayoutState): AppsLayoutState = AppsLayoutState(
        dockOrder = state.dockOrder.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        pinnedPackages = state.pinnedPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        hiddenPackages = state.hiddenPackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    )

    private fun normalizeFeatures(f: FeatureState): FeatureState {
        val cleanMac = CustomShortcuts.normalizeMac(f.wol.mac).orEmpty()
        val cleanCity = f.weather.cityName.trim().take(64)
        val validCoords = cleanCity.isNotEmpty() && !f.weather.latitude.isNaN() && !f.weather.longitude.isNaN() &&
            f.weather.latitude in -90.0..90.0 && f.weather.longitude in -180.0..180.0
        val cleanPin = sanitizePin(f.webRemote.pin)
        return f.copy(
            night = f.night.copy(
                startHour = f.night.startHour.coerceIn(0, 23),
                endHour = f.night.endHour.coerceIn(0, 23),
                nightDimPercent = f.night.nightDimPercent.coerceIn(30, 82)
            ),
            weather = if (validCoords) {
                WeatherConfig(cleanCity, f.weather.latitude, f.weather.longitude)
            } else {
                WeatherConfig()
            },
            bootAutoLaunch = f.bootAutoLaunch.copy(
                packageName = f.bootAutoLaunch.packageName.trim(),
                lastHandledBootId = f.bootAutoLaunch.lastHandledBootId.trim().take(64)
            ),
            wol = WolTarget(
                name = f.wol.name.trim().take(32).ifEmpty { WolTarget.DEFAULT_NAME },
                mac = cleanMac,
                broadcastIp = CustomShortcuts.normalizeBroadcastIp(f.wol.broadcastIp)
            ),
            webRemote = WebRemoteConfig(
                enabled = f.webRemote.enabled,
                port = f.webRemote.port.coerceIn(1024, 65535),
                pin = cleanPin
            )
        )
    }

    private fun writeAppearance(state: AppearanceState) {
        val edit = sp.edit()
            .putInt(KEY_MODE, state.homeMode.code)
            .putInt(KEY_WP_SOURCE, state.wallpaperSource.code)
            .putInt(KEY_WP_INTERVAL, state.wallpaperIntervalMin)
            .putInt(KEY_DIM, state.dimPercent)
            .putBoolean(KEY_CLOCK, state.showClock)
            .putInt(KEY_AMBIENT_TIMEOUT, state.ambientTimeoutMin)
            .putInt(KEY_CLOCK_STYLE, state.clockStyle.code)
        for ((src, idx) in state.wallpaperIndices) {
            val ord = src.ordinal
            edit.putInt(wpIndexKey(src), idx)
            ramWpIndices[ord] = idx
            persistedWpIndices[ord] = idx
            dirtyWpIndices[ord] = false
        }
        edit.apply()
    }

    private fun writeLayout(state: AppsLayoutState) {
        val edit = sp.edit()
        if (state.dockOrder.isEmpty()) edit.remove(KEY_DOCK_ORDER)
        else edit.putString(KEY_DOCK_ORDER, state.dockOrder.joinToString("\n"))

        if (state.pinnedPackages.isEmpty()) edit.remove(KEY_PINNED)
        else edit.putString(KEY_PINNED, state.pinnedPackages.joinToString("\n"))

        if (state.hiddenPackages.isEmpty()) edit.remove(KEY_HIDDEN)
        else edit.putString(KEY_HIDDEN, state.hiddenPackages.sorted().joinToString("\n"))

        edit.apply()
    }

    private fun writeFeatures(f: FeatureState) {
        val edit = sp.edit()
            .putInt(KEY_NIGHT_MODE, f.night.mode.code)
            .putInt(KEY_NIGHT_START, f.night.startHour)
            .putInt(KEY_NIGHT_END, f.night.endHour)
            .putInt(KEY_NIGHT_DIM, f.night.nightDimPercent)
            .putBoolean(KEY_SLEEP_HDMI_OFF, f.sleepHdmiOff)
            .putBoolean(KEY_BOOT_ENABLED, f.bootAutoLaunch.enabled)
            .putString(KEY_BOOT_PKG, f.bootAutoLaunch.packageName)
            .putString(KEY_BOOT_LAST_ID, f.bootAutoLaunch.lastHandledBootId)
            .putString(KEY_WOL_NAME, f.wol.name)
            .putString(KEY_WOL_MAC, f.wol.mac)
            .putString(KEY_WOL_BCAST, f.wol.broadcastIp)
            .putBoolean(KEY_WEB_ENABLED, f.webRemote.enabled)
            .putInt(KEY_WEB_PORT, f.webRemote.port)
            .putString(KEY_WEB_PIN, f.webRemote.pin)
        if (f.weather.isConfigured) {
            edit.putString(KEY_WEATHER_CITY, f.weather.cityName)
                .putString(KEY_WEATHER_LAT, f.weather.latitude.toString())
                .putString(KEY_WEATHER_LON, f.weather.longitude.toString())
        } else {
            edit.remove(KEY_WEATHER_CITY).remove(KEY_WEATHER_LAT).remove(KEY_WEATHER_LON)
        }
        edit.apply()
    }

    private fun wpIndexKey(source: WallpaperSource): String = "$KEY_WP_INDEX_PREFIX${source.code}"

    private fun cleanupOrphanKeys() {
        if (sp.contains(KEY_ORPHAN_ROTATE_NOW)) {
            sp.edit().remove(KEY_ORPHAN_ROTATE_NOW).apply()
        }
    }

    private fun migrateLegacyIfNeeded(legacySpProvider: () -> SharedPreferences?) {
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val old = legacySpProvider()
        val edit = sp.edit().putBoolean(KEY_MIGRATED, true)
        if (old != null) {
            if (!sp.contains(KEY_MODE) && old.contains("mode")) {
                edit.putInt(KEY_MODE, old.getInt("mode", HomeMode.DOCK.code))
            }
            if (!sp.contains(KEY_WP_INTERVAL) && old.contains("interval")) {
                edit.putInt(KEY_WP_INTERVAL, old.getInt("interval", AppearanceState.DEFAULT_WP_INTERVAL_MIN))
            }
            if (!sp.contains(KEY_DIM) && old.contains("dim")) {
                edit.putInt(KEY_DIM, old.getInt("dim", AppearanceState.DEFAULT_DIM_PERCENT))
            }
            if (!sp.contains(KEY_CLOCK) && old.contains("clock")) {
                edit.putBoolean(KEY_CLOCK, old.getBoolean("clock", true))
            }
        }
        edit.apply()
    }

    companion object {
        private const val FILE_NAME = "ambient_home_v2"
        private const val LEGACY_FILE_NAME = "home"
        private const val KEY_MIGRATED = "migrated_v2"
        private const val KEY_ORPHAN_ROTATE_NOW = "rotate_now"
        private const val KEY_MODE = "mode"
        private const val KEY_WP_SOURCE = "wp_source"
        private const val KEY_WP_INDEX = "wp_index"
        private const val KEY_WP_INDEX_PREFIX = "wp_index_"
        private const val KEY_WP_INTERVAL = "wp_interval"
        private const val KEY_DIM = "dim"
        private const val KEY_CLOCK = "clock"
        private const val KEY_AMBIENT_TIMEOUT = "ambient_timeout"
        private const val KEY_CLOCK_STYLE = "clock_style"
        private const val KEY_DOCK_ORDER = "dock_order"
        private const val KEY_PINNED = "pinned_order"
        private const val KEY_HIDDEN = "hidden_pkgs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_NIGHT_START = "night_start_hour"
        private const val KEY_NIGHT_END = "night_end_hour"
        private const val KEY_NIGHT_DIM = "night_dim"
        private const val KEY_SLEEP_HDMI_OFF = "sleep_hdmi_off"
        private const val KEY_WEATHER_CITY = "weather_city"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_BOOT_ENABLED = "boot_launch_enabled"
        private const val KEY_BOOT_PKG = "boot_launch_pkg"
        private const val KEY_BOOT_LAST_ID = "boot_last_id"
        private const val KEY_WOL_NAME = "wol_name"
        private const val KEY_WOL_MAC = "wol_mac"
        private const val KEY_WOL_BCAST = "wol_bcast"
        private const val KEY_WEB_ENABLED = "web_remote_enabled"
        private const val KEY_WEB_PORT = "web_remote_port"
        private const val KEY_WEB_PIN = "web_remote_pin"

        private val RNG = SecureRandom()

        fun generatePin(): String {
            val sb = StringBuilder(6)
            for (i in 0 until 6) {
                sb.append(('0'.code + RNG.nextInt(10)).toChar())
            }
            return sb.toString()
        }

        fun sanitizePin(raw: String): String {
            val digits = raw.trim().filter { it in '0'..'9' }
            return if (digits.length == 6) digits else ""
        }
    }
}
