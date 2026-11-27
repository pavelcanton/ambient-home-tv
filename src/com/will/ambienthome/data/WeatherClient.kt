package com.will.ambienthome.data

import android.util.Log
import com.will.ambienthome.model.CityPreset
import com.will.ambienthome.model.DailyForecast
import com.will.ambienthome.model.HourlyForecast
import com.will.ambienthome.model.MiniJson
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WeatherIcon
import com.will.ambienthome.model.WeatherSnapshot
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Клиент погоды Open-Meteo (без API-ключа, RAM-кэш, 0 Б на eMMC).
 * Обновляет прогноз не чаще раза в 45 мин только при активном HOME.
 */
object WeatherClient {

    private const val TAG = "AmbientHome"
    const val REFRESH_TTL_MS = 45L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 7000

    val RU_CITY_PRESETS: List<CityPreset> = listOf(
        CityPreset("Москва", "Россия", 55.7558, 37.6173),
        CityPreset("Санкт-Петербург", "Россия", 59.9343, 30.3351),
        CityPreset("Новосибирск", "Россия", 55.0084, 82.9357),
        CityPreset("Красноярск", "Россия", 56.0153, 92.8932),
        CityPreset("Екатеринбург", "Россия", 56.8389, 60.6057),
        CityPreset("Казань", "Россия", 55.7961, 49.1064),
        CityPreset("Нижний Новгород", "Россия", 56.2965, 43.9361),
        CityPreset("Самара", "Россия", 53.1959, 50.1002)
    )

    @Volatile
    private var cachedSnapshot: WeatherSnapshot? = null

    @Volatile
    private var cachedCityKey: String = ""

    fun getCached(config: WeatherConfig): WeatherSnapshot? {
        if (!config.isConfigured) return null
        val key = cityKey(config)
        val snap = cachedSnapshot
        return if (snap != null && cachedCityKey == key) snap else null
    }

    fun isStale(config: WeatherConfig, nowMs: Long = System.currentTimeMillis()): Boolean {
        val snap = getCached(config) ?: return true
        return (nowMs - snap.fetchedAtMs) >= REFRESH_TTL_MS
    }

    fun fetchForecast(config: WeatherConfig): WeatherSnapshot? {
        if (!config.isConfigured) return null
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=4&timezone=auto",
            config.latitude,
            config.longitude
        )
        val body = httpGet(url) ?: return getCached(config)
        val parsed = parseForecastJson(body, config.cityName, System.currentTimeMillis()) ?: return getCached(config)
        cachedCityKey = cityKey(config)
        cachedSnapshot = parsed
        return parsed
    }

    fun searchCity(query: String): List<CityPreset> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=6&language=ru&format=json"
        val body = httpGet(url) ?: return emptyList()
        return parseGeocodingJson(body)
    }

    fun parseForecastJson(json: String, cityName: String, fetchedAtMs: Long): WeatherSnapshot? {
        val root = MiniJson.parseObject(json) ?: return null
        val current = root["current"] as? Map<*, *> ?: return null
        val temp = MiniJson.asDouble(current["temperature_2m"])?.roundToInt() ?: return null
        val feels = MiniJson.asDouble(current["apparent_temperature"])?.roundToInt() ?: temp
        val wind = MiniJson.asDouble(current["wind_speed_10m"])?.roundToInt() ?: 0
        val code = MiniJson.asInt(current["weather_code"]) ?: 0
        val (icon, cond) = mapWmoCode(code)
        val hourly = parseHourly(root["hourly"] as? Map<*, *>)
        val daily = parseDaily(root["daily"] as? Map<*, *>)
        return WeatherSnapshot(
            cityName = cityName,
            tempC = temp,
            feelsLikeC = feels,
            windKmh = wind,
            icon = icon,
            condition = cond,
            hourly = hourly,
            daily = daily,
            fetchedAtMs = fetchedAtMs
        )
    }

    fun parseGeocodingJson(json: String): List<CityPreset> {
        val root = MiniJson.parseObject(json) ?: return emptyList()
        val results = root["results"] as? List<*> ?: return emptyList()
        val out = ArrayList<CityPreset>(results.size)
        for (raw in results) {
            val obj = raw as? Map<*, *> ?: continue
            val name = (obj["name"] as? String)?.trim().orEmpty()
            val lat = MiniJson.asDouble(obj["latitude"]) ?: continue
            val lon = MiniJson.asDouble(obj["longitude"]) ?: continue
            if (name.isEmpty()) continue
            val admin = (obj["admin1"] as? String)?.trim().orEmpty()
            val country = (obj["country"] as? String)?.trim().orEmpty()
            val region = listOf(admin, country).filter { it.isNotEmpty() }.joinToString(", ")
            out.add(CityPreset(name, region, lat, lon))
        }
        return out
    }

    fun mapWmoCode(code: Int): Pair<WeatherIcon, String> = when (code) {
        0 -> WeatherIcon.CLEAR to "Ясно"
        1, 2 -> WeatherIcon.PARTLY_CLOUDY to "Переменная облачность"
        3 -> WeatherIcon.CLOUDY to "Пасмурно"
        45, 48 -> WeatherIcon.FOG to "Туман"
        51, 53, 55, 56, 57 -> WeatherIcon.RAIN to "Морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIcon.RAIN to "Дождь"
        71, 73, 75, 77, 85, 86 -> WeatherIcon.SNOW to "Снег"
        95, 96, 99 -> WeatherIcon.STORM to "Гроза"
        else -> WeatherIcon.CLOUDY to "Облачно"
    }

    private fun parseHourly(hourly: Map<*, *>?): List<HourlyForecast> {
        if (hourly == null) return emptyList()
        val times = hourly["time"] as? List<*> ?: return emptyList()
        val temps = hourly["temperature_2m"] as? List<*> ?: return emptyList()
        val codes = hourly["weather_code"] as? List<*> ?: return emptyList()
        val limit = minOf(times.size, temps.size, codes.size, 24)
        val out = ArrayList<HourlyForecast>()
        var i = 0
        while (i < limit) {
            val tStr = (times[i] as? String).orEmpty()
            val hhmm = if (tStr.contains('T')) tStr.substringAfter('T').take(5) else tStr.take(5)
            val temp = MiniJson.asDouble(temps[i])?.roundToInt()
            val code = MiniJson.asInt(codes[i]) ?: 0
            if (hhmm.isNotEmpty() && temp != null) {
                out.add(HourlyForecast(hhmm, temp, mapWmoCode(code).first))
            }
            i += 3
        }
        return out
    }

    private fun parseDaily(daily: Map<*, *>?): List<DailyForecast> {
        if (daily == null) return emptyList()
        val dates = daily["time"] as? List<*> ?: return emptyList()
        val maxs = daily["temperature_2m_max"] as? List<*> ?: return emptyList()
        val mins = daily["temperature_2m_min"] as? List<*> ?: return emptyList()
        val codes = daily["weather_code"] as? List<*> ?: return emptyList()
        val limit = minOf(dates.size, maxs.size, mins.size, codes.size, 4)
        val out = ArrayList<DailyForecast>(limit)
        for (i in 0 until limit) {
            val dStr = (dates[i] as? String).orEmpty()
            val maxT = MiniJson.asDouble(maxs[i])?.roundToInt() ?: continue
            val minT = MiniJson.asDouble(mins[i])?.roundToInt() ?: continue
            val code = MiniJson.asInt(codes[i]) ?: 0
            val (icon, cond) = mapWmoCode(code)
            val label = formatDayLabel(i, dStr)
            out.add(DailyForecast(label, minT, maxT, icon, cond))
        }
        return out
    }

    private fun formatDayLabel(index: Int, isoDate: String): String = when (index) {
        0 -> "Сегодня ($isoDate)"
        1 -> "Завтра ($isoDate)"
        else -> isoDate
    }

    private fun cityKey(config: WeatherConfig): String =
        "${config.cityName}:${config.latitude}:${config.longitude}"

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AmbientHome/3.0 (AndroidTV)")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "WeatherClient GET failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
