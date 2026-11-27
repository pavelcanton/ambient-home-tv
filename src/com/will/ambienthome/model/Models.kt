package com.will.ambienthome.model

import android.content.ComponentName
import android.graphics.Bitmap

enum class HomeMode(val code: Int) {
    DOCK(0),
    GRID(1);

    companion object {
        fun fromCode(code: Int): HomeMode = if (code == 1) GRID else DOCK
    }
}

enum class WallpaperSource(val code: Int, val label: String) {
    BUILTIN(0, "Встроенные фото"),
    CUSTOM(1, "Свои (/sdcard/Pictures)"),
    GRADIENT(2, "Тёмный градиент"),
    ALL(3, "Встроенные + свои"),
    ONLINE_DAILY(4, "Фото дня — Bing / Wikimedia");

    fun requiresMediaPermission(): Boolean = this == CUSTOM || this == ALL

    fun requiresNetwork(): Boolean = this == ONLINE_DAILY

    companion object {
        const val MAX_DECODE_PIXELS: Long = 8_000_000L

        fun fromCode(code: Int): WallpaperSource =
            entries.firstOrNull { it.code == code } ?: BUILTIN

        fun next(current: WallpaperSource): WallpaperSource {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }

        /** Безопасный шаг индекса обоев по кольцу (без переполнения на Int.MAX_VALUE). */
        fun nextIndex(current: Int, count: Int, advance: Boolean = true): Int {
            if (count <= 1) return 0
            val base = current.coerceAtLeast(0) % count
            return if (advance) (base + 1) % count else base
        }

        /**
         * Вычисляет степень понижения разрешения inSampleSize (степень двойки) под экран reqW x reqH
         * с жёстким лимитом декодируемых пикселей (<= 8 МП) против пиков RAM на панорамах.
         */
        fun computeSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
            if (srcW <= 0 || srcH <= 0) return 1
            return stepSample(srcW, srcH, reqW.coerceAtLeast(1), reqH.coerceAtLeast(1), 1)
        }

        private tailrec fun stepSample(
            srcW: Int,
            srcH: Int,
            targetW: Int,
            targetH: Int,
            sample: Int
        ): Int {
            val next = sample * 2
            if (next <= 0 || srcW / next < 1 || srcH / next < 1) return sample
            val fitsTarget = srcW / next >= targetW && srcH / next >= targetH
            val exceedsCap = (srcW.toLong() / sample) * (srcH.toLong() / sample) > MAX_DECODE_PIXELS
            return if (fitsTarget || exceedsCap) {
                stepSample(srcW, srcH, targetW, targetH, next)
            } else {
                sample
            }
        }
    }
}

enum class TopAction(val tooltip: String) {
    WEATHER("Погода и прогноз"),
    BLUETOOTH("Bluetooth-аудио"),
    SLEEP_TIMER("Таймер сна"),
    WIFI("Сеть и Wi-Fi"),
    QUICK_CONTROLS("Быстрые действия"),
    ANDROID_SETTINGS("Настройки Android"),
    LAUNCHER_SETTINGS("Настройки лаунчера")
}

data class AppEntry(
    val packageName: String,
    val className: String,
    val label: String,
    val isTvApp: Boolean,
    val lastUpdateTime: Long,
    val tileBitmap: Bitmap? = null,
    val accentColor: Int = DEFAULT_ACCENT,
    val isVirtual: Boolean = false
) {
    val component: ComponentName
        get() = ComponentName(packageName, className)

    val cacheKey: String
        get() = "$packageName:$lastUpdateTime"

    companion object {
        const val DEFAULT_ACCENT: Int = 0xFF7FD8A0.toInt()
    }
}

data class AppearanceState(
    val homeMode: HomeMode = HomeMode.DOCK,
    val wallpaperSource: WallpaperSource = WallpaperSource.BUILTIN,
    val wallpaperIndices: Map<WallpaperSource, Int> = emptyMap(),
    val wallpaperIntervalMin: Int = DEFAULT_WP_INTERVAL_MIN,
    val dimPercent: Int = DEFAULT_DIM_PERCENT,
    val showClock: Boolean = true,
    val ambientTimeoutMin: Int = DEFAULT_AMBIENT_MIN,
    val clockStyle: AmbientClockStyle = AmbientClockStyle.THIN
) {
    fun wallpaperIndexFor(source: WallpaperSource = wallpaperSource): Int =
        (wallpaperIndices[source] ?: 0).coerceAtLeast(0)

    fun withWallpaperIndex(source: WallpaperSource, index: Int): AppearanceState =
        copy(wallpaperIndices = wallpaperIndices + (source to index.coerceAtLeast(0)))

    companion object {
        const val DEFAULT_WP_INTERVAL_MIN = 15
        const val DEFAULT_DIM_PERCENT = 22
        const val DEFAULT_AMBIENT_MIN = 5
    }
}

data class AppsLayoutState(
    val dockOrder: List<String> = emptyList(),
    val pinnedPackages: List<String> = emptyList(),
    val hiddenPackages: Set<String> = emptySet()
)

data class FeatureState(
    val night: NightConfig = NightConfig(),
    val sleepHdmiOff: Boolean = true,
    val weather: WeatherConfig = WeatherConfig(),
    val bootAutoLaunch: BootAutoLaunchConfig = BootAutoLaunchConfig(),
    val wol: WolTarget = WolTarget(),
    val webRemote: WebRemoteConfig = WebRemoteConfig()
)

data class LauncherState(
    val appearance: AppearanceState = AppearanceState(),
    val layout: AppsLayoutState = AppsLayoutState(),
    val features: FeatureState = FeatureState()
)

data class SystemStatus(
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
    val ssid: String = "",
    val ip: String = "",
    val rssi: Int = -127,
    val linkSpeedMbps: Int = 0
) {
    val isOnline: Boolean
        get() = wifiConnected || ethernetConnected

    val shortLabel: String
        get() = when {
            ethernetConnected -> "LAN"
            wifiConnected && ssid.isNotEmpty() -> ssid
            wifiConnected -> "Wi-Fi"
            else -> "Нет сети"
        }

    /** 0..4 bars based on RSSI */
    val signalBars: Int
        get() = when {
            !wifiConnected -> 0
            rssi >= -55 -> 4
            rssi >= -67 -> 3
            rssi >= -77 -> 2
            else -> 1
        }

    /**
     * Визуальный ключ кэша пилюли Wi-Fi/LAN.
     * Намеренно исключает rssi и linkSpeedMbps, чтобы колебания сигнала не вызывали
     * пересоздание Bitmap внутри onDraw() при неизменном числе полосок.
     */
    data class WifiPillVisualKey(
        val wifiConnected: Boolean,
        val ethernetConnected: Boolean,
        val shortLabel: String,
        val signalBars: Int
    )

    fun visualKey(): WifiPillVisualKey = WifiPillVisualKey(
        wifiConnected = wifiConnected,
        ethernetConnected = ethernetConnected,
        shortLabel = shortLabel,
        signalBars = signalBars
    )

    companion object {
        /** Очищает сырой SSID из Android WifiInfo от кавычек и плейсхолдеров. */
        fun cleanSsid(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val trimmed = raw.trim().removeSurrounding("\"").trim()
            if (trimmed.isEmpty() || trimmed.equals("<unknown ssid>", ignoreCase = true) || trimmed == "0x") {
                return ""
            }
            return trimmed
        }
    }
}

data class DiagnosticsSnapshot(
    val socTempC: Float?,
    val memAvailMb: Long,
    val memTotalMb: Long,
    val uptimeSec: Long,
    val cpuGov: String,
    val cpuFreqMhz: Int,
    val gpuFreqMhz: Int,
    val status: SystemStatus
) {
    /** Форматирует компактную строку телеметрии, скрывая недоступные/ограниченные Android датчики. */
    fun formatHardwareLine(): String {
        val parts = ArrayList<String>(5)
        socTempC?.let { parts.add(String.format(java.util.Locale.US, "SoC: %.1f °C", it)) }
        val gov = cpuGov.trim().takeIf { it.isNotEmpty() && it != "?" }
        when {
            gov != null && cpuFreqMhz > 0 -> parts.add("CPU: $gov @ $cpuFreqMhz МГц")
            cpuFreqMhz > 0 -> parts.add("CPU: $cpuFreqMhz МГц")
            gov != null -> parts.add("CPU: $gov")
        }
        if (gpuFreqMhz > 0) parts.add("GPU: $gpuFreqMhz МГц")
        if (memTotalMb > 0L) parts.add("RAM: $memAvailMb/$memTotalMb МБ")
        if (uptimeSec > 0L) parts.add("Uptime: ${uptimeSec / 3600L}ч ${(uptimeSec % 3600L) / 60L}м")
        return if (parts.isEmpty()) "Системные датчики ограничены Android" else parts.joinToString("  ·  ")
    }
}

data class WifiActionResult(
    val success: Boolean,
    val message: String,
    val status: SystemStatus? = null
)
