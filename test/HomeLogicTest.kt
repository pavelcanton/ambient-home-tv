package com.will.ambienthome.model

/**
 * Автономный набор Kotlin unit-тестов для моделей, AppOrdering, SystemStatus и WallpaperSource (без Gradle/Android).
 * Запускается автоматически при каждой сборке launcher/build.ps1.
 */
object HomeLogicTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
        } else {
            failed++
            System.err.println("  FAIL [$name]${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
    }

    private fun <T> checkEq(name: String, expected: T, actual: T) {
        check(name, expected == actual, "expected=$expected actual=$actual")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        testWifiPillVisualKeyIgnoresRssiAndLinkSpeedJitter()
        testWifiPillVisualKeyDetectsVisualChanges()
        testWallpaperSourceNextIndexAndPermission()
        testBackFromGridPersistence()
        testTopActionTooltipsAndEntries()
        testCleanSsid()
        testOrderPackageNames()
        testMoveBoundsAndShiftRespectPinnedBoundary()
        testShiftPreservesOffscreenPinnedApps()
        testComputeSampleSizePanoramaPixelCap()
        testDiagnosticsHardwareLineOmitsUnavailableFields()

        println("HomeLogicTest: passed=$passed failed=$failed")
        if (failed > 0) {
            System.exit(1)
        }
    }

    private fun testWifiPillVisualKeyIgnoresRssiAndLinkSpeedJitter() {
        val s1 = SystemStatus(wifiConnected = true, ssid = "HomeWiFi-5G", rssi = -48, linkSpeedMbps = 433)
        val s2 = SystemStatus(wifiConnected = true, ssid = "HomeWiFi-5G", rssi = -53, linkSpeedMbps = 390)
        checkEq("visualKey ignores RSSI/speed jitter inside 4 bars", s1.visualKey(), s2.visualKey())
        check("raw SystemStatus differs on jitter", s1 != s2)
    }

    private fun testWifiPillVisualKeyDetectsVisualChanges() {
        val s4 = SystemStatus(wifiConnected = true, ssid = "HomeWiFi-5G", rssi = -52, linkSpeedMbps = 433)
        val s3 = SystemStatus(wifiConnected = true, ssid = "HomeWiFi-5G", rssi = -62, linkSpeedMbps = 433)
        val sEth = SystemStatus(wifiConnected = false, ethernetConnected = true, ip = "192.0.2.10")
        val sOff = SystemStatus()
        check("bars 4 vs 3 changes key", s4.visualKey() != s3.visualKey())
        check("wifi vs eth changes key", s4.visualKey() != sEth.visualKey())
        check("wifi vs offline changes key", s4.visualKey() != sOff.visualKey())
        checkEq("eth shortLabel is LAN", "LAN", sEth.shortLabel)
        checkEq("offline shortLabel", "Нет сети", sOff.shortLabel)
    }

    private fun testWallpaperSourceNextIndexAndPermission() {
        checkEq("wrap 4->0 for size 5", 0, WallpaperSource.nextIndex(4, 5, advance = true))
        checkEq("advance 2->3 for size 5", 3, WallpaperSource.nextIndex(2, 5, advance = true))
        checkEq("no-advance keeps wrapped index", 2, WallpaperSource.nextIndex(7, 5, advance = false))
        checkEq("no-advance clamps negative to 0", 0, WallpaperSource.nextIndex(-5, 5, advance = false))
        checkEq("single item stays 0", 0, WallpaperSource.nextIndex(0, 1, advance = true))
        checkEq("empty playlist stays 0", 0, WallpaperSource.nextIndex(3, 0, advance = true))
        checkEq("negative index clamped on advance", 1, WallpaperSource.nextIndex(-5, 5, advance = true))
        checkEq(
            "Int.MAX_VALUE does not overflow",
            ((Int.MAX_VALUE % 5) + 1) % 5,
            WallpaperSource.nextIndex(Int.MAX_VALUE, 5, advance = true)
        )
        checkEq("BUILTIN does not require media permission", false, WallpaperSource.BUILTIN.requiresMediaPermission())
        checkEq("GRADIENT does not require media permission", false, WallpaperSource.GRADIENT.requiresMediaPermission())
        checkEq("CUSTOM requires media permission", true, WallpaperSource.CUSTOM.requiresMediaPermission())
        checkEq("ALL requires media permission", true, WallpaperSource.ALL.requiresMediaPermission())
        checkEq("ONLINE_DAILY does not require media permission", false, WallpaperSource.ONLINE_DAILY.requiresMediaPermission())
        checkEq("ONLINE_DAILY requires network", true, WallpaperSource.ONLINE_DAILY.requiresNetwork())
        checkEq("BUILTIN does not require network", false, WallpaperSource.BUILTIN.requiresNetwork())
        checkEq("WallpaperSource.fromCode(4)", WallpaperSource.ONLINE_DAILY, WallpaperSource.fromCode(4))
        checkEq("WallpaperSource.next(ALL) -> ONLINE_DAILY", WallpaperSource.ONLINE_DAILY, WallpaperSource.next(WallpaperSource.ALL))
        checkEq("WallpaperSource.next(ONLINE_DAILY) -> BUILTIN", WallpaperSource.BUILTIN, WallpaperSource.next(WallpaperSource.ONLINE_DAILY))
    }

    private fun testBackFromGridPersistence() {
        val tempGrid = HomeNavigation.NavState(mode = HomeMode.GRID, openedGridFromDock = true)
        val primaryGrid = HomeNavigation.NavState(mode = HomeMode.GRID, openedGridFromDock = false)
        checkEq("temporary grid from dock does not persist", false, HomeNavigation.onBack(tempGrid, 5).persistDockMode)
        checkEq("primary grid mode persists switch to dock", true, HomeNavigation.onBack(primaryGrid, 5).persistDockMode)
    }

    private fun testTopActionTooltipsAndEntries() {
        checkEq("TopAction has 7 entries", 7, TopAction.entries.size)
        for (action in TopAction.entries) {
            check("TopAction.${action.name} has non-blank tooltip", action.tooltip.isNotBlank())
        }
    }

    private fun testCleanSsid() {
        checkEq("strip quotes", "HomeWiFi-5G", SystemStatus.cleanSsid("\"HomeWiFi-5G\""))
        checkEq("strip outer whitespace and quotes", "HomeWiFi-5G", SystemStatus.cleanSsid("  \"HomeWiFi-5G\"  "))
        checkEq("reject unknown ssid", "", SystemStatus.cleanSsid("<unknown ssid>"))
        checkEq("reject quoted unknown ssid", "", SystemStatus.cleanSsid("\"<unknown ssid>\""))
        checkEq("reject 0x", "", SystemStatus.cleanSsid("0x"))
        checkEq("reject null", "", SystemStatus.cleanSsid(null))
        checkEq("reject blank", "", SystemStatus.cleanSsid("   "))
    }

    private fun testOrderPackageNames() {
        val installed = listOf(
            "com.zebra" to "Zebra",
            "org.smarttube.stable" to "SmartTube",
            "com.alpha" to "Alpha",
            "ru.kinopoisk.tv" to "Kinopoisk",
            "com.hidden" to "HiddenApp",
            "com.custom" to "Custom"
        )
        val ordered = AppOrdering.orderPackageNames(
            installed = installed,
            hidden = setOf("com.hidden"),
            pinned = listOf("com.zebra"),
            dockOrder = listOf("com.custom"),
            defaultPriority = listOf("org.smarttube.stable", "ru.kinopoisk.tv")
        )
        checkEq(
            "ordering precedence pinned > dock > default > alpha, excluding hidden",
            listOf("com.zebra", "com.custom", "org.smarttube.stable", "ru.kinopoisk.tv", "com.alpha"),
            ordered
        )
    }

    private fun testMoveBoundsAndShiftRespectPinnedBoundary() {
        val visible = listOf("p1", "p2", "u1", "u2")
        val pinned = listOf("p1", "p2")

        checkEq("p1 bounds", AppOrdering.MoveBounds(false, true), AppOrdering.computeMoveBounds(0, 4, 2, true))
        checkEq("p2 bounds cannot cross into unpinned", AppOrdering.MoveBounds(true, false), AppOrdering.computeMoveBounds(1, 4, 2, true))
        checkEq("u1 bounds cannot cross into pinned", AppOrdering.MoveBounds(false, true), AppOrdering.computeMoveBounds(2, 4, 2, false))
        checkEq("u2 bounds", AppOrdering.MoveBounds(true, false), AppOrdering.computeMoveBounds(3, 4, 2, false))

        checkEq("p2 cannot shift right across boundary", null, AppOrdering.shiftPackageOrder(visible, pinned, "p2", +1))
        checkEq("u1 cannot shift left across boundary", null, AppOrdering.shiftPackageOrder(visible, pinned, "u1", -1))

        val swappedPinned = AppOrdering.shiftPackageOrder(visible, pinned, "p1", +1)
        checkEq("p1 shifts right within pinned", listOf("p2", "p1", "u1", "u2"), swappedPinned?.newDockOrder)
        checkEq("pinned order updated", listOf("p2", "p1"), swappedPinned?.newPinnedOrder)

        val swappedUnpinned = AppOrdering.shiftPackageOrder(visible, pinned, "u2", -1)
        checkEq("u2 shifts left within unpinned", listOf("p1", "p2", "u2", "u1"), swappedUnpinned?.newDockOrder)
        checkEq("unpinned shift leaves pinned untouched", null, swappedUnpinned?.newPinnedOrder)
    }

    private fun testShiftPreservesOffscreenPinnedApps() {
        val visible = listOf("p1", "p2", "u1")
        val allPinned = listOf("p1", "p2", "p_hidden")
        val shifted = AppOrdering.shiftPackageOrder(visible, allPinned, "p2", -1)
        checkEq("offscreen pinned preserved at tail", listOf("p2", "p1", "p_hidden"), shifted?.newPinnedOrder)
        // Защита от несортированного списка (не бросает IndexOutOfBoundsException)
        checkEq("unsorted pinned outside prefix returns null", null, AppOrdering.shiftPackageOrder(listOf("u1", "p1"), listOf("p1"), "p1", -1))
    }

    private fun testComputeSampleSizePanoramaPixelCap() {
        checkEq("1080p exact -> sample 1", 1, WallpaperSource.computeSampleSize(1920, 1080, 1920, 1080))
        checkEq("4K -> sample 2", 2, WallpaperSource.computeSampleSize(3840, 2160, 1920, 1080))
        checkEq("8K -> sample 4", 4, WallpaperSource.computeSampleSize(7680, 4320, 1920, 1080))
        checkEq("exact 8MP boundary 4000x2000 stays sample 1 for 4K target", 1, WallpaperSource.computeSampleSize(4000, 2000, 3840, 2160))
        checkEq("above 8MP boundary 4001x2000 bumps to sample 2", 2, WallpaperSource.computeSampleSize(4001, 2000, 3840, 2160))
        checkEq("panorama 12000x1200 capped to sample 2 (<= 8MP)", 2, WallpaperSource.computeSampleSize(12000, 1200, 1920, 1080))
        checkEq("extreme panorama 30000x1200 capped to sample 4", 4, WallpaperSource.computeSampleSize(30000, 1200, 1920, 1080))
        checkEq("tall strip 1080x20000 capped to sample 2", 2, WallpaperSource.computeSampleSize(1080, 20000, 1920, 1080))
        checkEq("1px tall ultra-wide strip does not divide-by-0 or loop", 1, WallpaperSource.computeSampleSize(16_000_000, 1, 1920, 1080))
        checkEq("zero/negative dims safely return 1", 1, WallpaperSource.computeSampleSize(0, -100, 1920, 1080))
    }

    private fun testDiagnosticsHardwareLineOmitsUnavailableFields() {
        val full = DiagnosticsSnapshot(
            socTempC = 54.3f,
            memAvailMb = 2500L,
            memTotalMb = 4096L,
            uptimeSec = 3720L,
            cpuGov = "interactive",
            cpuFreqMhz = 1200,
            gpuFreqMhz = 400,
            status = SystemStatus()
        ).formatHardwareLine()
        check("full telemetry includes SoC", full.contains("SoC: 54.3 °C"))
        check("full telemetry includes CPU", full.contains("CPU: interactive @ 1200 МГц"))
        check("full telemetry includes GPU", full.contains("GPU: 400 МГц"))
        check("full telemetry includes RAM", full.contains("RAM: 2500/4096 МБ"))
        check("full telemetry includes Uptime", full.contains("Uptime: 1ч 2м"))

        val restricted = DiagnosticsSnapshot(
            socTempC = null,
            memAvailMb = 1500L,
            memTotalMb = 2048L,
            uptimeSec = 600L,
            cpuGov = "?",
            cpuFreqMhz = 0,
            gpuFreqMhz = 0,
            status = SystemStatus()
        ).formatHardwareLine()
        check("restricted telemetry omits SoC", !restricted.contains("SoC"))
        check("restricted telemetry omits CPU ?", !restricted.contains("CPU") && !restricted.contains("?"))
        check("restricted telemetry omits GPU 0", !restricted.contains("GPU"))
        check("restricted telemetry keeps RAM", restricted.contains("RAM: 1500/2048 МБ"))

        val empty = DiagnosticsSnapshot(
            socTempC = null,
            memAvailMb = 0L,
            memTotalMb = 0L,
            uptimeSec = 0L,
            cpuGov = "",
            cpuFreqMhz = 0,
            gpuFreqMhz = 0,
            status = SystemStatus()
        ).formatHardwareLine()
        check("completely restricted shows fallback notice", empty.contains("ограничены"))
    }
}
