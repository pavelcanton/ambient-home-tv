package com.will.ambienthome.data

import com.will.ambienthome.model.AmbientClockStyle
import com.will.ambienthome.model.AppearanceState
import com.will.ambienthome.model.AppsLayoutState
import com.will.ambienthome.model.BootAutoLaunchConfig
import com.will.ambienthome.model.FeatureState
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.MiniJson
import com.will.ambienthome.model.NightConfig
import com.will.ambienthome.model.NightMode
import com.will.ambienthome.model.WallpaperSource
import com.will.ambienthome.model.WeatherConfig
import com.will.ambienthome.model.WeatherIcon
import com.will.ambienthome.model.WebRemoteConfig
import com.will.ambienthome.model.WolTarget

/**
 * Автономный набор Kotlin unit-тестов для SettingsBackupCodec, WeatherClient,
 * OnlineWallpaperProvider, RootBridgeClient и безопасности LanWebRemoteRouter.
 */
object DataAndRemoteTest {

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
        testSettingsBackupRoundTripAndInvalid()
        testWeatherForecastAndGeocodingParsing()
        testOnlineWallpaperBingAndWikiParsing()
        testRootBridgePackageProtection()
        testWebRemoteAuthAndConstantTime()
        testWebRemotePinThrottlingLockoutAndRecovery()
        testWebRemoteRoutingAndValidation()
        testWebRemoteWallpaperUploadSecurity()
        testSystemStatusGpuDevfreqDiscovery()
        testSystemStatusThermalZoneDiscovery()

        println("DataAndRemoteTest: passed=$passed failed=$failed")
        if (failed > 0) System.exit(1)
    }

    private fun testSettingsBackupRoundTripAndInvalid() {
        val orig = LauncherState(
            appearance = AppearanceState(
                homeMode = HomeMode.GRID,
                wallpaperSource = WallpaperSource.ONLINE_DAILY,
                wallpaperIntervalMin = 30,
                dimPercent = 35,
                showClock = false,
                ambientTimeoutMin = 10,
                clockStyle = AmbientClockStyle.ANALOG
            ),
            layout = AppsLayoutState(
                dockOrder = listOf("org.smarttube.stable", "ru.kinopoisk.tv"),
                pinnedPackages = listOf("org.smarttube.stable"),
                hiddenPackages = setOf("com.android.chrome")
            ),
            features = FeatureState(
                night = NightConfig(NightMode.ON, 23, 8, 60),
                sleepHdmiOff = false,
                weather = WeatherConfig("Казань", 55.7961, 49.1064),
                bootAutoLaunch = BootAutoLaunchConfig(true, "org.smarttube.stable", ""),
                wol = WolTarget("Домашний ПК", "AA:BB:CC:DD:EE:FF", "192.168.1.255"),
                webRemote = WebRemoteConfig(true, 8765, "482910")
            )
        )
        val json = SettingsBackupCodec.encode(orig)
        val decoded = SettingsBackupCodec.decode(json)
        check("backup decode non-null", decoded != null)
        checkEq("backup round-trip appearance", orig.appearance, decoded?.appearance)
        checkEq("backup round-trip layout", orig.layout, decoded?.layout)
        checkEq("backup round-trip features", orig.features, decoded?.features)

        checkEq("invalid JSON returns null", null, SettingsBackupCodec.decode("{broken"))
        checkEq("future schemaVersion=99 rejected", null, SettingsBackupCodec.decode("{\"schemaVersion\":99}"))
        checkEq("missing schemaVersion rejected", null, SettingsBackupCodec.decode("{\"appearance\":{}}"))
    }

    private fun testWeatherForecastAndGeocodingParsing() {
        val sampleForecast = """
        {
          "current": {"temperature_2m": 4.6, "apparent_temperature": 1.2, "weather_code": 61, "wind_speed_10m": 14.4},
          "hourly": {
            "time": ["2026-11-27T12:00","2026-11-27T13:00","2026-11-27T14:00","2026-11-27T15:00"],
            "temperature_2m": [4.6, 5.0, 4.8, 3.9],
            "weather_code": [61, 3, 2, 0]
          },
          "daily": {
            "time": ["2026-11-27","2026-11-28","2026-11-29"],
            "weather_code": [61, 71, 0],
            "temperature_2m_max": [6.1, -1.2, -5.4],
            "temperature_2m_min": [0.4, -7.8, -12.1]
          }
        }
        """.trimIndent()
        val snap = WeatherClient.parseForecastJson(sampleForecast, "Казань", 1000L)
        check("forecast parsed", snap != null)
        checkEq("temp rounded 4.6 -> 5", 5, snap?.tempC)
        checkEq("feels rounded 1.2 -> 1", 1, snap?.feelsLikeC)
        checkEq("wind rounded 14.4 -> 14", 14, snap?.windKmh)
        checkEq("WMO 61 -> RAIN", WeatherIcon.RAIN, snap?.icon)
        checkEq("WMO 61 label Дождь", "Дождь", snap?.condition)
        checkEq("hourly sampled every 3h -> 2 points", 2, snap?.hourly?.size)
        checkEq("daily 3 days parsed", 3, snap?.daily?.size)
        checkEq("day 1 snow icon", WeatherIcon.SNOW, snap?.daily?.get(1)?.icon)
        checkEq("day 2 clear min -12", -12, snap?.daily?.get(2)?.tempMinC)
        checkEq("invalid forecast returns null", null, WeatherClient.parseForecastJson("{}", "X", 0L))

        val sampleGeo = """{"results":[{"name":"Казань","admin1":"Татарстан","country":"Россия","latitude":55.7961,"longitude":49.1064}]}"""
        val cities = WeatherClient.parseGeocodingJson(sampleGeo)
        checkEq("geocoding returns 1 city", 1, cities.size)
        checkEq("city name", "Казань", cities[0].name)
        checkEq("city region", "Татарстан, Россия", cities[0].region)
        checkEq("WMO 95 -> STORM", WeatherIcon.STORM, WeatherClient.mapWmoCode(95).first)
        checkEq("WMO 45 -> FOG", WeatherIcon.FOG, WeatherClient.mapWmoCode(45).first)
    }

    private fun testOnlineWallpaperBingAndWikiParsing() {
        val bingJson = """
        {"images":[
          {"startdate":"20261127","url":"/th?id=OHR.Baikal_RU-RU123_1920x1080.jpg","title":"Зимний Байкал","copyright":"Озеро Байкал, Россия (© Photo)"}
        ]}
        """.trimIndent()
        val bing = OnlineWallpaperProvider.parseBingJson(bingJson)
        checkEq("bing 1 image parsed", 1, bing.size)
        checkEq("bing https prefix added", "https://www.bing.com/th?id=OHR.Baikal_RU-RU123_1920x1080.jpg", bing[0].url)
        checkEq("bing dateKey", "20261127", bing[0].dateKey)
        check("bing caption stripped copyright parens", !bing[0].caption.contains("(©"))
        check("bing caption includes title", bing[0].caption.contains("Байкал"))

        val wikiJson = """
        {"query":{"pages":{"101":{"title":"File:Altai Mountains.jpg","imageinfo":[{"thumburl":"https://upload.wikimedia.org/thumb/Altai.jpg"}]}}}}
        """.trimIndent()
        val wiki = OnlineWallpaperProvider.parseWikimediaJson(wikiJson)
        checkEq("wiki 1 image parsed", 1, wiki.size)
        checkEq("wiki caption cleaned", "Altai Mountains", wiki[0].caption)
        checkEq("wiki url", "https://upload.wikimedia.org/thumb/Altai.jpg", wiki[0].url)
    }

    private fun testRootBridgePackageProtection() {
        checkEq("SmartTube allowed", true, RootBridgeClient.isAllowedForceStopPackage("org.smarttube.stable"))
        checkEq("systemui blocked", false, RootBridgeClient.isAllowedForceStopPackage("com.android.systemui"))
        checkEq("self launcher blocked", false, RootBridgeClient.isAllowedForceStopPackage("com.will.ambienthome"))
        checkEq("bootfix blocked", false, RootBridgeClient.isAllowedForceStopPackage("com.android.bootfix"))
        checkEq("latinime blocked", false, RootBridgeClient.isAllowedForceStopPackage("com.android.inputmethod.latin"))
        checkEq("shell injection blocked", false, RootBridgeClient.isAllowedForceStopPackage("org.a;id"))
        checkEq("no dot blocked", false, RootBridgeClient.isAllowedForceStopPackage("android"))
        checkEq("leading dot pkg blocked", false, RootBridgeClient.isAllowedForceStopPackage(".com.foo"))
        checkEq("trailing dot pkg blocked", false, RootBridgeClient.isAllowedForceStopPackage("com.foo."))
        checkEq("double dot pkg blocked", false, RootBridgeClient.isAllowedForceStopPackage("com..foo"))
        checkEq("valid vold volume id", true, RootBridgeClient.isValidVolumeId("public:8,1"))
        checkEq("leading comma volume rejected", false, RootBridgeClient.isValidVolumeId(",public:8,1"))
        checkEq("over 64 char volume rejected", false, RootBridgeClient.isValidVolumeId("public:" + "1".repeat(60)))
    }

    private fun testWebRemoteAuthAndConstantTime() {
        checkEq("constantTime equal", true, LanWebRemoteRouter.constantTimeEquals("123456", "123456"))
        checkEq("constantTime diff char", false, LanWebRemoteRouter.constantTimeEquals("123456", "123457"))
        checkEq("constantTime diff len", false, LanWebRemoteRouter.constantTimeEquals("123456", "12345"))
        checkEq("constantTime empty rejected", false, LanWebRemoteRouter.constantTimeEquals("", ""))

        val backend = FakeBackend(pin = "739104")
        val router = LanWebRemoteRouter(backend)

        val rootRes = router.route(LanWebRemoteRouter.HttpRequest("GET", "/"))
        checkEq("GET / is 200 without auth", 200, rootRes.status)
        check("GET / never leaks PIN", !rootRes.body.toString(Charsets.UTF_8).contains("739104"))

        val pingRes = router.route(LanWebRemoteRouter.HttpRequest("GET", "/api/ping"))
        checkEq("GET /api/ping is 200 without auth", 200, pingRes.status)
        check("GET /api/ping never leaks PIN", !pingRes.body.toString(Charsets.UTF_8).contains("739104"))

        val protectedEndpoints = listOf(
            "GET" to "/api/status",
            "POST" to "/api/key",
            "POST" to "/api/text",
            "POST" to "/api/launch",
            "POST" to "/api/forcestop",
            "POST" to "/api/action",
            "POST" to "/api/config",
            "POST" to "/api/wallpaper"
        )
        for ((m, p) in protectedEndpoints) {
            val freshRouter = LanWebRemoteRouter(backend)
            val noToken = freshRouter.route(LanWebRemoteRouter.HttpRequest(m, p))
            checkEq("$m $p without token -> 401", 401, noToken.status)
            val wrongToken = freshRouter.route(LanWebRemoteRouter.HttpRequest(m, p, headers = mapOf("X-Auth-Token" to "000000")))
            checkEq("$m $p wrong token -> 401", 401, wrongToken.status)
        }
        val emptyPinRouter = LanWebRemoteRouter(FakeBackend(pin = ""))
        checkEq("empty server PIN fails closed 401", 401, emptyPinRouter.route(LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = mapOf("X-Auth-Token" to ""))).status)
        checkEq("pre-body auth allows /", 0, router.checkAuthForPath("/", emptyMap()))
        checkEq("pre-body auth allows /api/ping", 0, router.checkAuthForPath("/api/ping", emptyMap()))
        checkEq("pre-body auth rejects unauth /api/wallpaper", 401, router.checkAuthForPath("/api/wallpaper", emptyMap()))
        checkEq("pre-body auth rejects wrong PIN on /api/wallpaper", 401, router.checkAuthForPath("/api/wallpaper", mapOf("X-Auth-Token" to "111111")))
        checkEq("pre-body auth accepts right PIN on /api/wallpaper", 0, router.checkAuthForPath("/api/wallpaper", mapOf("X-Auth-Token" to "739104")))
    }

    private fun testWebRemotePinThrottlingLockoutAndRecovery() {
        var nowMs = 1_000L
        val router = LanWebRemoteRouter(FakeBackend(pin = "739104"), clockMs = { nowMs })
        val bad = LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = mapOf("X-Auth-Token" to "000000"))
        val good = LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = mapOf("X-Auth-Token" to "739104"))

        for (attempt in 1..5) {
            checkEq("bad PIN attempt #$attempt -> 401", 401, router.route(bad).status)
        }
        checkEq("6th attempt within 15s locked -> 429", 429, router.route(bad).status)
        checkEq("valid PIN also blocked during 15s lockout -> 429", 429, router.route(good).status)
        checkEq("pre-body check returns 429 during lockout", 429, router.checkAuthForPath("/api/wallpaper", mapOf("X-Auth-Token" to "739104")))
        checkEq("/api/ping still works during lockout -> 200", 200, router.route(LanWebRemoteRouter.HttpRequest("GET", "/api/ping")).status)

        nowMs += LanWebRemoteRouter.AUTH_LOCKOUT_MS - 1L
        checkEq("still 429 at 14.999s", 429, router.route(good).status)
        nowMs += 1L
        checkEq("recovers after 15s with valid PIN -> 200", 200, router.route(good).status)

        // Valid PIN resets failure counter before hitting 5
        for (i in 1..4) checkEq("4 failures #$i -> 401", 401, router.route(bad).status)
        checkEq("valid PIN resets counter -> 200", 200, router.route(good).status)
        for (i in 1..4) checkEq("4 more failures after reset #$i -> 401", 401, router.route(bad).status)
        checkEq("still not locked at 4 -> 200 on valid", 200, router.route(good).status)
    }

    private fun testWebRemoteRoutingAndValidation() {
        val backend = FakeBackend(pin = "739104")
        val router = LanWebRemoteRouter(backend)
        val auth = mapOf("X-Auth-Token" to "739104")

        val st = router.route(LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = auth))
        checkEq("GET /api/status with PIN -> 200", 200, st.status)

        val keyOk = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/key", headers = auth, body = """{"code":23}""".toByteArray()))
        checkEq("POST /api/key 23 -> 200", 200, keyOk.status)
        checkEq("backend received key 23", 23, backend.lastKey)

        val keyBad = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/key", headers = auth, body = """{"code":999}""".toByteArray()))
        checkEq("POST /api/key 999 -> 400", 400, keyBad.status)

        val textOk = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/text", headers = auth, body = """{"text":"Привет, SmartTube!"}""".toByteArray(Charsets.UTF_8)))
        checkEq("POST /api/text cyrillic -> 200", 200, textOk.status)
        checkEq("backend received cyrillic text", "Привет, SmartTube!", backend.lastText)

        val textHuge = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/text", headers = auth, body = MiniJson.stringify(mapOf("text" to "а".repeat(2500))).toByteArray()))
        checkEq("POST /api/text >2000 chars -> 400", 400, textHuge.status)

        val pkgBad = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/forcestop", headers = auth, body = """{"package":"org.a;rm -rf /"}""".toByteArray()))
        checkEq("POST /api/forcestop injection -> 400", 400, pkgBad.status)
        checkEq("POST /api/launch leading dot -> 400", 400, router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/launch", headers = auth, body = """{"package":".org.foo"}""".toByteArray())).status)
        checkEq("POST /api/launch trailing dot -> 400", 400, router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/launch", headers = auth, body = """{"package":"org.foo."}""".toByteArray())).status)
        checkEq("POST /api/launch double dot -> 400", 400, router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/launch", headers = auth, body = """{"package":"org..foo"}""".toByteArray())).status)

        val actOk = router.route(LanWebRemoteRouter.HttpRequest("POST", "/api/action", headers = auth, body = """{"action":"sleep_timer","minutes":60}""".toByteArray()))
        checkEq("POST /api/action MacroDroid -> 200", 200, actOk.status)
        checkEq("backend action recorded", "sleep_timer", backend.lastAction)

        val notFound = router.route(LanWebRemoteRouter.HttpRequest("GET", "/api/unknown", headers = auth))
        checkEq("unknown /api route -> 404", 404, notFound.status)
    }

    private fun testWebRemoteWallpaperUploadSecurity() {
        checkEq("valid jpg", "photo_1.jpg", LanWebRemoteRouter.sanitizeWallpaperFilename("photo_1.jpg"))
        checkEq("uppercase ext normalized", "Wall-2.png", LanWebRemoteRouter.sanitizeWallpaperFilename("Wall-2.PNG"))
        checkEq("traversal .. rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("../evil.jpg"))
        checkEq("slash rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("sub/a.jpg"))
        checkEq("backslash rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("sub\\a.jpg"))
        checkEq("null byte rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("a.jpg\u0000.png"))
        checkEq("non-image ext rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("script.sh"))
        checkEq("space rejected", null, LanWebRemoteRouter.sanitizeWallpaperFilename("my photo.jpg"))

        val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        val htmlBytes = "<html><script>alert(1)</script></html>".toByteArray()

        checkEq("valid JPEG magic", true, LanWebRemoteRouter.hasValidImageMagic(jpgBytes, "a.jpg"))
        checkEq("valid PNG magic", true, LanWebRemoteRouter.hasValidImageMagic(pngBytes, "a.png"))
        checkEq("PNG disguised as JPG rejected", false, LanWebRemoteRouter.hasValidImageMagic(pngBytes, "a.jpg"))
        checkEq("JPEG disguised as PNG rejected", false, LanWebRemoteRouter.hasValidImageMagic(jpgBytes, "a.png"))
        checkEq("HTML disguised as PNG rejected", false, LanWebRemoteRouter.hasValidImageMagic(htmlBytes, "a.png"))

        val backend = FakeBackend(pin = "739104")
        val router = LanWebRemoteRouter(backend)
        val okJpg = router.route(
            LanWebRemoteRouter.HttpRequest(
                "POST", "/api/wallpaper",
                headers = mapOf("X-Auth-Token" to "739104", "X-Filename" to "living_room.jpg"),
                body = jpgBytes
            )
        )
        checkEq("valid JPEG upload -> 200", 200, okJpg.status)
        checkEq("saved filename matches", "living_room.jpg", backend.lastWallpaperName)

        val badMagic = router.route(
            LanWebRemoteRouter.HttpRequest(
                "POST", "/api/wallpaper",
                headers = mapOf("X-Auth-Token" to "739104", "X-Filename" to "fake.jpg"),
                body = htmlBytes
            )
        )
        checkEq("HTML spoofed as jpg -> 400", 400, badMagic.status)

        val badName = router.route(
            LanWebRemoteRouter.HttpRequest(
                "POST", "/api/wallpaper",
                headers = mapOf("X-Auth-Token" to "739104", "X-Filename" to "../../etc.jpg"),
                body = jpgBytes
            )
        )
        checkEq("traversal filename -> 400", 400, badName.status)
    }

    private fun testSystemStatusGpuDevfreqDiscovery() {
        val tmpRoot = java.nio.file.Files.createTempDirectory("ambient_gpu_test").toFile()
        try {
            val devfreq = java.io.File(tmpRoot, "devfreq").apply { mkdirs() }
            java.io.File(devfreq, "dmc").apply { mkdirs(); java.io.File(this, "cur_freq").writeText("800000000\n") }
            checkEq("non-gpu devfreq ignored", 0, SystemStatusHelper.readGpuFreqMhzFrom(devfreq))

            java.io.File(devfreq, "3d00000.qcom,kgsl-3d0").apply {
                mkdirs()
                java.io.File(this, "cur_freq").writeText("585000000\n")
            }
            checkEq("generic kgsl GPU Hz -> MHz", 585, SystemStatusHelper.readGpuFreqMhzFrom(devfreq))

            java.io.File(devfreq, "ff700000.gpu").apply {
                mkdirs()
                java.io.File(this, "cur_freq").writeText("400000000\n")
            }
            checkEq("fast-path ff700000.gpu preferred", 400, SystemStatusHelper.readGpuFreqMhzFrom(devfreq))

            val devfreqKhz = java.io.File(tmpRoot, "devfreq_khz").apply { mkdirs() }
            java.io.File(devfreqKhz, "13000000.mali").apply {
                mkdirs()
                java.io.File(this, "cur_freq").writeText("650000\n")
            }
            checkEq("kHz GPU devfreq normalized to MHz", 650, SystemStatusHelper.readGpuFreqMhzFrom(devfreqKhz))
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private fun testSystemStatusThermalZoneDiscovery() {
        val tmpRoot = java.nio.file.Files.createTempDirectory("ambient_thm_test").toFile()
        try {
            val thermal = java.io.File(tmpRoot, "thermal").apply { mkdirs() }
            java.io.File(thermal, "thermal_zone0").apply {
                mkdirs()
                java.io.File(this, "type").writeText("battery\n")
                java.io.File(this, "temp").writeText("32000\n")
            }
            java.io.File(thermal, "thermal_zone1").apply {
                mkdirs()
                java.io.File(this, "type").writeText("soc-thermal\n")
                java.io.File(this, "temp").writeText("58400\n")
            }
            checkEq("prefers soc-thermal over battery", 58.4f, SystemStatusHelper.readSocTempCFrom(thermal))

            val thermalFallback = java.io.File(tmpRoot, "thermal_fb").apply { mkdirs() }
            java.io.File(thermalFallback, "thermal_zone0").apply {
                mkdirs()
                java.io.File(this, "temp").writeText("51200\n")
            }
            checkEq("falls back to thermal_zone0 without type", 51.2f, SystemStatusHelper.readSocTempCFrom(thermalFallback))

            val thermalBad = java.io.File(tmpRoot, "thermal_bad").apply { mkdirs() }
            java.io.File(thermalBad, "thermal_zone0").apply {
                mkdirs()
                java.io.File(this, "temp").writeText("0\n")
            }
            checkEq("rejects 0C bogus thermal reading", null, SystemStatusHelper.readSocTempCFrom(thermalBad))
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private class FakeBackend(private val pin: String) : LanWebRemoteRouter.Backend {
        var lastKey: Int? = null
        var lastText: String? = null
        var lastAction: String? = null
        var lastWallpaperName: String? = null

        override fun currentPin(): String = pin
        override fun htmlShell(): ByteArray = "<html><body>Ambient Home Remote</body></html>".toByteArray()
        override fun statusJson(): Map<String, Any?> = mapOf("ok" to true, "version" to "3.0.0")
        override fun dispatchKeyOrText(keyCode: Int?, text: String?): Pair<Boolean, String> {
            lastKey = keyCode
            lastText = text
            return true to "ok"
        }
        override fun dispatchApp(action: String, pkg: String): Pair<Boolean, String> = true to "$action:$pkg"
        override fun dispatchAction(action: String, params: Map<String, Any?>): Pair<Boolean, String> {
            lastAction = action
            return true to action
        }
        override fun saveWallpaper(safeFileName: String, bytes: ByteArray): Pair<Boolean, String> {
            lastWallpaperName = safeFileName
            return true to "saved"
        }
    }
}
