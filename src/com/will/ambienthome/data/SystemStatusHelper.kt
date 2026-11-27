package com.will.ambienthome.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.will.ambienthome.model.DiagnosticsSnapshot
import com.will.ambienthome.model.SystemStatus
import com.will.ambienthome.model.WifiActionResult
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Быстрое чтение статуса сети и системной телеметрии без запуска внешних shell-процессов на HOME.
 */
object SystemStatusHelper {

    private const val TAG = "AmbientHome"
    private val GPU_DEVFREQ_KEYWORDS = arrayOf("gpu", "mali", "kgsl", "pvrsrv", "panfrost", "adreno")
    private val THERMAL_PREFER_KEYWORDS = arrayOf("soc", "cpu", "tsens", "aml_thermal", "mtktscpu", "rockchip", "gpu")
    private val THERMAL_SKIP_KEYWORDS = arrayOf("battery", "charger", "pmic", "usb", "wifi", "skin")

    private data class WifiMeta(
        val ssid: String = "",
        val rssi: Int = -127,
        val linkSpeedMbps: Int = 0
    )

    fun readSystemStatus(ctx: Context): SystemStatus {
        val (cm, activeNet, caps) = try {
            val mgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = mgr?.activeNetwork
            Triple(mgr, net, if (net != null) mgr.getNetworkCapabilities(net) else null)
        } catch (t: Throwable) {
            Log.w(TAG, "readSystemStatus network capabilities failed", t)
            Triple(null, null, null)
        }

        val wifiConn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethConn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val meta = if (wifiConn) readWifiMeta(ctx, caps) else WifiMeta()
        val ip = if (wifiConn || ethConn) {
            readIpFromLinkProperties(cm, activeNet).ifEmpty {
                readLocalIpv4(if (ethConn) "eth0" else "wlan0")
            }
        } else {
            ""
        }
        return SystemStatus(
            wifiConnected = wifiConn,
            ethernetConnected = ethConn,
            ssid = meta.ssid,
            ip = ip,
            rssi = meta.rssi,
            linkSpeedMbps = meta.linkSpeedMbps
        )
    }

    fun readDiagnostics(ctx: Context, preloadedStatus: SystemStatus? = null): DiagnosticsSnapshot {
        val tempC = readSocTempCFrom(File("/sys/class/thermal"))
        val (memTotalKb, memAvailKb) = readMemInfoKb()
        val uptimeSec = readFirstLineQuiet(File("/proc/uptime"))
            ?.substringBefore(' ')
            ?.substringBefore('.')
            ?.toLongOrNull() ?: 0L
        val cpuGov = readFirstLineQuiet(File("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"))
            ?: readFirstLineQuiet(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"))
            ?: ""
        val cpuFreqKhz = readFirstLineQuiet(File("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"))
            ?: readFirstLineQuiet(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
        val cpuFreqMhz = ((cpuFreqKhz?.toLongOrNull() ?: 0L) / 1000L).toInt().coerceAtLeast(0)
        val gpuFreqMhz = readGpuFreqMhzFrom(File("/sys/class/devfreq"))

        return DiagnosticsSnapshot(
            socTempC = tempC,
            memAvailMb = memAvailKb / 1024L,
            memTotalMb = memTotalKb / 1024L,
            uptimeSec = uptimeSec,
            cpuGov = cpuGov,
            cpuFreqMhz = cpuFreqMhz,
            gpuFreqMhz = gpuFreqMhz,
            status = preloadedStatus ?: readSystemStatus(ctx)
        )
    }

    /**
     * Универсальное чтение частоты GPU из /sys/class/devfreq (Rockchip, Amlogic, Qualcomm, MediaTek).
     * Возвращает 0, если интерфейс отсутствует или ограничен политикой SELinux.
     */
    internal fun readGpuFreqMhzFrom(devfreqDir: File): Int {
        val primary = File(devfreqDir, "ff700000.gpu/cur_freq")
        parseDevfreqMhz(readFirstLineQuiet(primary)).let { if (it > 0) return it }
        val entries = try {
            devfreqDir.listFiles()?.sortedBy { it.name.lowercase() }
        } catch (_: Throwable) {
            null
        } ?: return 0
        for (dir in entries) {
            if (!dir.isDirectory) continue
            val lower = dir.name.lowercase()
            if (GPU_DEVFREQ_KEYWORDS.any { lower.contains(it) }) {
                val mhz = parseDevfreqMhz(readFirstLineQuiet(File(dir, "cur_freq")))
                if (mhz > 0) return mhz
            }
        }
        return 0
    }

    /**
     * Универсальное чтение температуры SoC из /sys/class/thermal/thermal_zone*.
     * Сначала выбирает зону SoC/CPU, затем безопасно откатывается к thermal_zone0.
     */
    internal fun readSocTempCFrom(thermalDir: File): Float? {
        val zones = try {
            thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
        } catch (_: Throwable) {
            null
        }.orEmpty()
        var firstValid: Float? = null
        for (zone in zones) {
            val type = readFirstLineQuiet(File(zone, "type"))?.lowercase().orEmpty()
            if (type.isNotEmpty() && THERMAL_SKIP_KEYWORDS.any { type.contains(it) }) continue
            val temp = parseThermalTempC(readFirstLineQuiet(File(zone, "temp"))) ?: continue
            if (type.isNotEmpty() && THERMAL_PREFER_KEYWORDS.any { type.contains(it) }) return temp
            if (firstValid == null) firstValid = temp
        }
        return firstValid ?: parseThermalTempC(readFirstLineQuiet(File(thermalDir, "thermal_zone0/temp")))
    }

    /**
     * Честная проверка и запрос сканирования/переподключения через штатный Android WifiManager
     * (без root-команд и без wpa_cli). Реально проверяет возвращаемые Boolean-флаги
     * и честно сообщает пользователю об ограничениях Android 10+.
     */
    @Suppress("DEPRECATION")
    fun softReconnectWifi(ctx: Context): WifiActionResult {
        val wm = try {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (t: Throwable) {
            Log.w(TAG, "wifiAction: getSystemService(WIFI_SERVICE) failed", t)
            null
        } ?: return WifiActionResult(false, "Служба Wi-Fi недоступна")

        val enabled = try {
            wm.isWifiEnabled
        } catch (t: Throwable) {
            Log.w(TAG, "wifiAction: isWifiEnabled failed", t)
            false
        }
        if (!enabled) {
            Log.i(TAG, "wifiAction: isWifiEnabled=false")
            return WifiActionResult(false, "Wi-Fi выключен — откройте Настройки Wi-Fi Android")
        }

        val scanOk = try {
            wm.startScan()
        } catch (t: Throwable) {
            Log.w(TAG, "wifiAction: startScan failed", t)
            false
        }
        val reconnOk = try {
            wm.reconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "wifiAction: reconnect failed", t)
            false
        }
        val st = readSystemStatus(ctx)
        Log.i(
            TAG,
            "wifiAction: enabled=$enabled startScan=$scanOk reconnect=$reconnOk connected=${st.wifiConnected} ssid=${st.ssid}"
        )
        return formatWifiActionResult(reconnOk, scanOk, st)
    }

    fun readBootId(): String = readFirstLineQuiet(File("/proc/sys/kernel/random/boot_id")).orEmpty()

    fun readDisplayModes(ctx: Context): List<Triple<Int, Int, Int>> {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                ?: return emptyList()
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return emptyList()
            display.supportedModes.map { m ->
                Triple(m.physicalWidth, m.physicalHeight, Math.round(m.refreshRate))
            }.distinct()
        } catch (t: Throwable) {
            Log.w(TAG, "readDisplayModes failed", t)
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiMeta(ctx: Context, caps: NetworkCapabilities?): WifiMeta {
        var ssid = ""
        var rssi = -127
        var linkSpeed = 0
        try {
            val transportInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.transportInfo as? WifiInfo
            } else {
                null
            }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val candidates = listOfNotNull(transportInfo, wm?.connectionInfo)
            for (info in candidates) {
                if (ssid.isEmpty()) {
                    val cleaned = SystemStatus.cleanSsid(info.ssid)
                    if (cleaned.isNotEmpty()) ssid = cleaned
                }
                if (rssi <= -127 && info.rssi > -127) rssi = info.rssi
                if (linkSpeed <= 0 && info.linkSpeed > 0) linkSpeed = info.linkSpeed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readSystemStatus wifi info failed", t)
        }
        return WifiMeta(ssid = ssid, rssi = rssi, linkSpeedMbps = linkSpeed)
    }

    private fun formatWifiActionResult(
        reconnOk: Boolean,
        scanOk: Boolean,
        st: SystemStatus
    ): WifiActionResult = when {
        reconnOk -> WifiActionResult(true, "Запрошено переподключение (WifiManager: ok)", st)
        scanOk -> WifiActionResult(
            true,
            "Скан запущен (Android 10+ блокирует reconnect для приложений — при обрыве откройте Настройки Wi-Fi)",
            st
        )
        st.wifiConnected -> WifiActionResult(
            false,
            "Wi-Fi уже подключён (${st.ssid.ifEmpty { st.ip.ifEmpty { "без IP" } }}); Android 10+ ограничил скан/reconnect — для ручной смены откройте Настройки Wi-Fi",
            st
        )
        else -> WifiActionResult(
            false,
            "Android 10+ отклонил запрос (scan=false, reconnect=false) — откройте Настройки Wi-Fi Android",
            st
        )
    }

    private fun readIpFromLinkProperties(cm: ConnectivityManager?, net: android.net.Network?): String {
        if (cm == null || net == null) return ""
        return try {
            val lp = cm.getLinkProperties(net) ?: return ""
            for (la in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress
                    if (!host.isNullOrEmpty()) return host
                }
            }
            ""
        } catch (t: Throwable) {
            Log.w(TAG, "getLinkProperties failed", t)
            ""
        }
    }

    private fun readLocalIpv4(preferredIface: String): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            var fallback = ""
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (ni.name == preferredIface) return ip
                        if (fallback.isEmpty()) fallback = ip
                    }
                }
            }
            fallback
        } catch (t: Throwable) {
            Log.w(TAG, "readLocalIpv4 failed", t)
            ""
        }
    }

    private fun readMemInfoKb(): Pair<Long, Long> = readMemInfoKbFrom(File("/proc/meminfo"))

    internal fun readMemInfoKbFrom(file: File): Pair<Long, Long> {
        var memTotalKb = 0L
        var memAvailKb = 0L
        try {
            if (file.isFile && file.canRead()) {
                file.bufferedReader().use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        when {
                            memTotalKb == 0L && line.startsWith("MemTotal:") -> memTotalKb = parseFirstLong(line)
                            memAvailKb == 0L && line.startsWith("MemAvailable:") -> memAvailKb = parseFirstLong(line)
                        }
                        if (memTotalKb > 0L && memAvailKb > 0L) break
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return memTotalKb to memAvailKb
    }

    private fun parseDevfreqMhz(raw: String?): Int {
        val v = raw?.trim()?.toLongOrNull() ?: return 0
        if (v <= 0L) return 0
        return when {
            v >= 10_000_000L -> (v / 1_000_000L).toInt()
            v >= 10_000L -> (v / 1_000L).toInt()
            else -> v.toInt()
        }
    }

    private fun parseThermalTempC(raw: String?): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        val c = if (v > 1000f) v / 1000f else v
        return if (c in 1.0f..125.0f) c else null
    }

    private fun readFirstLineQuiet(file: File): String? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.bufferedReader().use { it.readLine()?.trim() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseFirstLong(line: String): Long {
        val sb = StringBuilder()
        var started = false
        for (ch in line) {
            if (ch in '0'..'9') {
                sb.append(ch)
                started = true
            } else if (started) {
                break
            }
        }
        return sb.toString().toLongOrNull() ?: 0L
    }
}
