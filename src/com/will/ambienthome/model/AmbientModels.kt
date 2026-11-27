package com.will.ambienthome.model

/**
 * Чистые модели данных для возможностей Ambient Home 3.0.0:
 * стили часов, ночной кино-режим, погода, «Сейчас играет», таймер сна,
 * холодный автозапуск, Wake-on-LAN, USB и LAN Web-пульт.
 */

enum class AmbientClockStyle(val code: Int, val label: String) {
    THIN(0, "Тонкие"),
    CINEMA(1, "Кино"),
    MINIMAL(2, "Минимал"),
    ANALOG(3, "Аналоговые");

    companion object {
        fun fromCode(code: Int): AmbientClockStyle =
            entries.firstOrNull { it.code == code } ?: THIN

        fun next(current: AmbientClockStyle): AmbientClockStyle {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}

enum class NightMode(val code: Int, val label: String) {
    AUTO(0, "Авто"),
    ON(1, "Вкл"),
    OFF(2, "Выкл");

    companion object {
        fun fromCode(code: Int): NightMode =
            entries.firstOrNull { it.code == code } ?: AUTO

        fun next(current: NightMode): NightMode {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}

data class NightConfig(
    val mode: NightMode = NightMode.AUTO,
    val startHour: Int = DEFAULT_START_HOUR,
    val endHour: Int = DEFAULT_END_HOUR,
    val nightDimPercent: Int = DEFAULT_NIGHT_DIM
) {
    companion object {
        const val DEFAULT_START_HOUR = 22
        const val DEFAULT_END_HOUR = 7
        const val DEFAULT_NIGHT_DIM = 55
    }
}

enum class WeatherIcon(val code: Int) {
    CLEAR(0),
    PARTLY_CLOUDY(1),
    CLOUDY(2),
    FOG(3),
    RAIN(4),
    SNOW(5),
    STORM(6)
}

data class WeatherConfig(
    val cityName: String = "",
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN
) {
    val isConfigured: Boolean
        get() = cityName.isNotBlank() && !latitude.isNaN() && !longitude.isNaN() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class HourlyForecast(
    val hourLabel: String,
    val tempC: Int,
    val icon: WeatherIcon
)

data class DailyForecast(
    val dayLabel: String,
    val tempMinC: Int,
    val tempMaxC: Int,
    val icon: WeatherIcon,
    val condition: String
)

data class WeatherSnapshot(
    val cityName: String,
    val tempC: Int,
    val feelsLikeC: Int,
    val windKmh: Int,
    val icon: WeatherIcon,
    val condition: String,
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
    val fetchedAtMs: Long = 0L
) {
    val pillTempText: String
        get() = if (tempC > 0) "+${tempC}°" else "${tempC}°"

    val ambientSummary: String
        get() = "$pillTempText · $condition"
}

data class CityPreset(
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double
)

data class NowPlayingState(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean
) {
    val displayTitle: String
        get() = title.ifBlank { appLabel }

    val displaySubtitle: String
        get() = when {
            artist.isNotBlank() && appLabel.isNotBlank() -> "$artist · $appLabel"
            artist.isNotBlank() -> artist
            else -> appLabel
        }
}

data class SleepTimerState(
    val active: Boolean = false,
    val remainingSec: Int = 0,
    val warningActive: Boolean = false
) {
    val badgeText: String
        get() {
            if (!active || remainingSec <= 0) return ""
            val mins = (remainingSec + 59) / 60
            return if (remainingSec <= 60) "${remainingSec}с" else "${mins}м"
        }
}

data class BootAutoLaunchConfig(
    val enabled: Boolean = false,
    val packageName: String = "",
    val lastHandledBootId: String = ""
)

data class WolTarget(
    val name: String = DEFAULT_NAME,
    val mac: String = "",
    val broadcastIp: String = DEFAULT_BROADCAST
) {
    val isConfigured: Boolean
        get() = CustomShortcuts.isValidMac(mac)

    companion object {
        const val DEFAULT_NAME = "Включить ПК (WOL)"
        const val DEFAULT_BROADCAST = "255.255.255.255"
    }
}

data class WebRemoteConfig(
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val pin: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 8765
    }
}

data class UsbVolumeInfo(
    val id: String,
    val label: String
)

data class DailyWallpaperMeta(
    val url: String,
    val caption: String,
    val dateKey: String
)

data class AmbientOverlayData(
    val clockStyle: AmbientClockStyle = AmbientClockStyle.THIN,
    val showClock: Boolean = true,
    val nightActive: Boolean = false,
    val blackout: Boolean = false,
    val weatherSummary: String = "",
    val sleepBadge: String = "",
    val caption: String = "",
    val nowPlayingLine: String = ""
)

/**
 * Чистый расчёт геометрии Ambient-часов и метаданных (4-corner hop + минутный дрейф)
 * с жёсткой гарантией safe-zone на 720p / 1080p / 4K для всех 4 стилей (100% JVM-тестируемый).
 */
data class AmbientClockLayout(
    val corner: Int,
    val rightAligned: Boolean,
    val topAnchored: Boolean,
    val u: Float,
    val anchorX: Float,
    val anchorY: Float,
    val analogRadius: Float,
    val analogCx: Float,
    val analogCy: Float,
    val subY: Float,
    val nowPlayingY: Float,
    val maxTextWidth: Float
) {
    companion object {
        fun compute(canvasW: Float, canvasH: Float, tickCount: Int, style: AmbientClockStyle): AmbientClockLayout {
            val u = (canvasH / 1080f).coerceAtLeast(0.5f)
            val corner = (tickCount.coerceAtLeast(0) / 15) % 4
            val driftX = (kotlin.math.sin(tickCount * 0.45) * 24.0).toFloat() * u
            val driftY = (kotlin.math.cos(tickCount * 0.35) * 18.0).toFloat() * u
            val rightAligned = corner == 2 || corner == 3
            val topAnchored = corner == 1 || corner == 2
            val anchorX = (if (rightAligned) canvasW - 96f * u else 96f * u) + driftX
            val anchorY = (if (topAnchored) 232f * u else canvasH - 220f * u) + driftY
            val radius = 84f * u
            val analogCx = if (rightAligned) anchorX - radius else anchorX + radius
            val analogCy = anchorY - 76f * u
            val subY = when (style) {
                AmbientClockStyle.THIN -> anchorY + 92f * u
                AmbientClockStyle.CINEMA -> anchorY + 98f * u
                AmbientClockStyle.MINIMAL -> anchorY + 78f * u
                AmbientClockStyle.ANALOG -> analogCy + radius + 74f * u
            }
            val availW = (if (rightAligned) anchorX - 64f * u else canvasW - anchorX - 64f * u)
                .coerceAtLeast(240f * u)
            return AmbientClockLayout(
                corner = corner,
                rightAligned = rightAligned,
                topAnchored = topAnchored,
                u = u,
                anchorX = anchorX,
                anchorY = anchorY,
                analogRadius = radius,
                analogCx = analogCx,
                analogCy = analogCy,
                subY = subY,
                nowPlayingY = subY + 34f * u,
                maxTextWidth = minOf(availW, canvasW * 0.58f)
            )
        }
    }
}

data class QuickControlState(
    val nightActive: Boolean,
    val nightLabel: String,
    val sleepLabel: String,
    val webRemoteEnabled: Boolean,
    val webRemoteUrl: String,
    val webRemotePin: String,
    val usbVolumes: List<UsbVolumeInfo>
)
