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
        testWeatherMonotonicCacheAndBoundedRead()
        testWeatherOfflineBackoffAndInFlightLock()
        testOnlineWallpaperBingAndWikiParsing()
        testOnlineWallpaperHostAllowlist()
        testOnlineWallpaperPartialReadPruneAndBackoff()
        testSleepTriggerStateIdempotencyAndFadeCancel()
        testSleepTimerSmartTickDelay()
        testRootBridgePackageProtection()
        testWebRemoteAuthAndConstantTime()
        testWebRemotePinThrottlingLockoutAndRecovery()
        testWebRemoteMonotonicLockoutOrigin()
        testWebRemoteRoutingAndValidation()
        testWebRemoteWallpaperUploadSecurity()
        testWallpaperFileHeaderMin64AndMagic()
        testLanWebRemoteServerHardening()
        testLanWebRemoteServerBoundedPool503()
        testSystemStatusGpuDevfreqDiscovery()
        testSystemStatusThermalZoneDiscovery()
        testSystemStatusMemInfoEarlyBreak()

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
        check("backup JSON omits plaintext PIN value", !json.contains("482910"))
        check("backup JSON omits pin key", !json.contains("\"pin\""))

        val existingDevice = LauncherState(
            features = FeatureState(webRemote = WebRemoteConfig(enabled = false, port = 8765, pin = "999888"))
        )
        val decoded = SettingsBackupCodec.decode(json, fallback = existingDevice)
        check("backup decode non-null", decoded != null)
        checkEq("backup round-trip appearance", orig.appearance, decoded?.appearance)
        checkEq("backup round-trip layout", orig.layout, decoded?.layout)
        val expectedFeatures = orig.features.copy(
            webRemote = orig.features.webRemote.copy(pin = "999888")
        )
        checkEq("backup round-trip features preserves device PIN", expectedFeatures, decoded?.features)

        val craftedWithPin = """{"schemaVersion":3,"webRemote":{"enabled":true,"port":8888,"pin":"111111"}}"""
        val decodedCrafted = SettingsBackupCodec.decode(craftedWithPin, fallback = existingDevice)
        checkEq("crafted backup JSON cannot overwrite device PIN", "999888", decodedCrafted?.features?.webRemote?.pin)
        checkEq("crafted backup JSON still restores port", 8888, decodedCrafted?.features?.webRemote?.port)

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

    private fun testWeatherMonotonicCacheAndBoundedRead() {
        val cfg = WeatherConfig("Казань", 55.7961, 49.1064)
        val other = WeatherConfig("Москва", 55.7558, 37.6173)
        val snap = com.will.ambienthome.model.WeatherSnapshot(
            cityName = "Казань", tempC = 2, feelsLikeC = 0, windKmh = 10,
            icon = WeatherIcon.CLEAR, condition = "Ясно", fetchedAtMs = 999_999_999_999L
        )
        WeatherClient.clearCacheForTest()
        checkEq("empty weather cache is stale", true, WeatherClient.isStale(cfg, 10_000L))
        WeatherClient.putCacheForTest(cfg, snap, fetchedElapsedMs = 10_000L)
        checkEq("atomic weather cache hit", snap, WeatherClient.getCached(cfg))
        checkEq("different city misses cache", null, WeatherClient.getCached(other))
        checkEq("fresh before monotonic TTL despite wall clock", false, WeatherClient.isStale(cfg, 10_000L + WeatherClient.REFRESH_TTL_MS - 1L))
        checkEq("stale at monotonic TTL boundary", true, WeatherClient.isStale(cfg, 10_000L + WeatherClient.REFRESH_TTL_MS))
        checkEq("stale if monotonic clock inverted", true, WeatherClient.isStale(cfg, 9_999L))
        WeatherClient.clearCacheForTest()

        val exactCap = ByteArray(OnlineWallpaperProvider.MAX_HTTP_JSON_BYTES) { 'a'.code.toByte() }
        val exactRead = OnlineWallpaperProvider.readBoundedUtf8(java.io.ByteArrayInputStream(exactCap))
        checkEq("bounded reader accepts exact 256KB", OnlineWallpaperProvider.MAX_HTTP_JSON_BYTES, exactRead?.length)
        val overCap = ByteArray(OnlineWallpaperProvider.MAX_HTTP_JSON_BYTES + 1) { 'a'.code.toByte() }
        checkEq("bounded reader rejects 256KB + 1B", null, OnlineWallpaperProvider.readBoundedUtf8(java.io.ByteArrayInputStream(overCap)))
    }

    private fun testOnlineWallpaperPartialReadPruneAndBackoff() {
        val tmpRoot = java.nio.file.Files.createTempDirectory("ambient_wp_test").toFile()
        try {
            // 1-byte-per-read JPEG stream (TLS split packet regression)
            val jpegPayload = ByteArray(9000) { 0x42 }
            jpegPayload[0] = 0xFF.toByte()
            jpegPayload[1] = 0xD8.toByte()
            jpegPayload[2] = 0xFF.toByte()
            val oneByteStream = object : java.io.InputStream() {
                private var idx = 0
                override fun read(): Int = if (idx < jpegPayload.size) jpegPayload[idx++].toInt() and 0xFF else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (idx >= jpegPayload.size) return -1
                    b[off] = jpegPayload[idx++]
                    return 1
                }
            }
            val outJpg = java.io.File(tmpRoot, "partial.jpg")
            checkEq("1-byte chunked valid JPEG accepted", true, OnlineWallpaperProvider.streamWithMagicCheck(oneByteStream, outJpg))
            checkEq("1-byte chunked JPEG full size written", 9000L, outJpg.length())

            val badPayload = ByteArray(9000) { 0x41 }
            val badOneByte = object : java.io.InputStream() {
                private var idx = 0
                override fun read(): Int = if (idx < badPayload.size) badPayload[idx++].toInt() and 0xFF else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (idx >= badPayload.size) return -1
                    b[off] = badPayload[idx++]
                    return 1
                }
            }
            checkEq("1-byte chunked non-JPEG rejected", false, OnlineWallpaperProvider.streamWithMagicCheck(badOneByte, java.io.File(tmpRoot, "bad.jpg")))
            checkEq("2-byte truncated JPEG rejected", false, OnlineWallpaperProvider.streamWithMagicCheck(java.io.ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte())), java.io.File(tmpRoot, "short.jpg")))

            // Cache pruning by lastModified() descending (wiki file must NOT pin over newer Bing files)
            val cacheDir = java.io.File(tmpRoot, "cache").apply { mkdirs() }
            val nowMs = System.currentTimeMillis()
            val wikiOld = java.io.File(cacheDir, "daily_wiki_999.jpg").apply { writeBytes(jpegPayload); setLastModified(nowMs - 400_000L) }
            val bing1 = java.io.File(cacheDir, "daily_20261125.jpg").apply { writeBytes(jpegPayload); setLastModified(nowMs - 300_000L) }
            val bing2 = java.io.File(cacheDir, "daily_20261126.jpg").apply { writeBytes(jpegPayload); setLastModified(nowMs - 200_000L) }
            val bing3 = java.io.File(cacheDir, "daily_20261127.jpg").apply { writeBytes(jpegPayload); setLastModified(nowMs - 100_000L) }
            val captions = mutableMapOf(
                wikiOld.name to "Old Wiki",
                bing1.name to "Bing 1",
                bing2.name to "Bing 2",
                bing3.name to "Bing 3"
            )
            OnlineWallpaperProvider.pruneOldCache(cacheDir, captions)
            checkEq("old daily_wiki evicted by mtime", false, wikiOld.exists())
            checkEq("old daily_wiki caption removed", false, captions.containsKey(wikiOld.name))
            checkEq("newer Bing 1 retained", true, bing1.exists())
            checkEq("newer Bing 2 retained", true, bing2.exists())
            checkEq("newer Bing 3 retained", true, bing3.exists())

            // Offline failure backoff when cache is empty
            OnlineWallpaperProvider.resetRefreshThrottleForTest()
            checkEq("initial refresh allowed when cache empty", true, OnlineWallpaperProvider.shouldAttemptRefresh(10_000L, hasCache = false, force = false))
            OnlineWallpaperProvider.recordAttemptResult(10_000L, success = false)
            checkEq("offline backoff blocks immediate retry with empty cache", false, OnlineWallpaperProvider.shouldAttemptRefresh(10_000L + 60_000L, hasCache = false, force = false))
            checkEq("force bypasses offline backoff", true, OnlineWallpaperProvider.shouldAttemptRefresh(10_000L + 60_000L, hasCache = false, force = true))
            checkEq("offline backoff expires after 15 min", true, OnlineWallpaperProvider.shouldAttemptRefresh(10_000L + OnlineWallpaperProvider.FAILURE_BACKOFF_MS, hasCache = false, force = false))
            OnlineWallpaperProvider.recordAttemptResult(1_000_000L, success = true)
            checkEq("success enforces 12h cooldown with cache", false, OnlineWallpaperProvider.shouldAttemptRefresh(1_000_000L + 3600_000L, hasCache = true, force = false))
            checkEq("12h cooldown expires", true, OnlineWallpaperProvider.shouldAttemptRefresh(1_000_000L + OnlineWallpaperProvider.REFRESH_INTERVAL_MS, hasCache = true, force = false))
            OnlineWallpaperProvider.resetRefreshThrottleForTest()
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private fun testSleepTriggerStateIdempotencyAndFadeCancel() {
        val st = SleepTriggerState()
        checkEq("unarmed trigger rejected", -1L, st.tryBeginSleep())

        val (armGen, armRestore) = st.arm(50_000L)
        check("arm returns positive gen", armGen > 0L)
        checkEq("arm from idle needs no vol restore", -1, armRestore)
        checkEq("armed deadline exposed", 50_000L, st.snapshotDeadline())

        val fireGen = st.tryBeginSleep()
        check("first trigger succeeds", fireGen > armGen)
        checkEq("disarmed after trigger", 0L, st.snapshotDeadline())
        checkEq("duplicate alarm during fade rejected", -1L, st.tryBeginSleep())

        st.recordOriginalVolume(fireGen, 14)
        checkEq("completeFade succeeds for active gen", true, st.completeFade(fireGen))
        checkEq("late alarm AFTER finishSleep STILL rejected (H1)", -1L, st.tryBeginSleep())

        // Fade cancel mid-fade restores original volume and invalidates old generation (M2)
        st.arm(90_000L)
        val gen2 = st.tryBeginSleep()
        st.recordOriginalVolume(gen2, 11)
        checkEq("isFading true before cancel", true, st.isCurrent(gen2, requireFading = true))
        val (_, cancelRestore) = st.cancel()
        checkEq("mid-fade cancel returns original volume 11", 11, cancelRestore)
        checkEq("old fade gen invalidated after cancel", false, st.isCurrent(gen2, requireFading = true))
        checkEq("old completeFade rejected after cancel", false, st.completeFade(gen2))
        checkEq("second cancel does not re-restore volume", -1, st.cancel().second)
    }

    private fun testWallpaperFileHeaderMin64AndMagic() {
        val tmpRoot = java.nio.file.Files.createTempDirectory("ambient_magic_test").toFile()
        try {
            val png64 = ByteArray(64).apply {
                val h = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                System.arraycopy(h, 0, this, 0, h.size)
            }
            val png63 = png64.copyOf(63)
            val jpg64 = ByteArray(64).apply {
                this[0] = 0xFF.toByte(); this[1] = 0xD8.toByte(); this[2] = 0xFF.toByte()
            }
            val webp64 = ByteArray(64).apply {
                "RIFF".toByteArray().copyInto(this, 0)
                "WEBP".toByteArray().copyInto(this, 8)
            }
            val html200 = "<html><body>not an image</body></html>".repeat(6).toByteArray()

            val fPng64 = java.io.File(tmpRoot, "small.png").apply { writeBytes(png64) }
            val fPng63 = java.io.File(tmpRoot, "tiny.png").apply { writeBytes(png63) }
            val fJpg64 = java.io.File(tmpRoot, "small.jpg").apply { writeBytes(jpg64) }
            val fWebp64 = java.io.File(tmpRoot, "small.webp").apply { writeBytes(webp64) }
            val fFakeJpg = java.io.File(tmpRoot, "fake.jpg").apply { writeBytes(html200) }
            val fPngAsJpg = java.io.File(tmpRoot, "mismatch.jpg").apply { writeBytes(png64) }

            checkEq("valid 64B PNG accepted", true, LanWebRemoteRouter.hasValidImageFileHeader(fPng64))
            checkEq("63B PNG below min threshold rejected", false, LanWebRemoteRouter.hasValidImageFileHeader(fPng63))
            checkEq("valid 64B JPEG accepted", true, LanWebRemoteRouter.hasValidImageFileHeader(fJpg64))
            checkEq("valid 64B WebP accepted", true, LanWebRemoteRouter.hasValidImageFileHeader(fWebp64))
            checkEq("200B HTML renamed .jpg rejected", false, LanWebRemoteRouter.hasValidImageFileHeader(fFakeJpg))
            checkEq("64B PNG renamed .jpg rejected", false, LanWebRemoteRouter.hasValidImageFileHeader(fPngAsJpg))
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private fun testLanWebRemoteServerHardening() {
        val backend = FakeBackend(pin = "739104")
        val router = LanWebRemoteRouter(backend)
        val server = LanWebRemoteServer(
            port = 0,
            router = router,
            socketTimeoutMs = 400,
            headerDeadlineMs = 350L,
            bodyDeadlineMs = 350L
        )
        checkEq("server starts on ephemeral port", true, server.start())
        val port = server.boundPort()
        try {
            // 1. Security headers + version 3.0.2 on GET /api/ping
            val pingRaw = rawHttp(port, "GET /api/ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            check("ping HTTP 200", pingRaw.startsWith("HTTP/1.1 200 OK\r\n"))
            check("header X-Frame-Options: DENY", pingRaw.contains("X-Frame-Options: DENY\r\n"))
            check("header Referrer-Policy: no-referrer", pingRaw.contains("Referrer-Policy: no-referrer\r\n"))
            check("header X-Content-Type-Options: nosniff", pingRaw.contains("X-Content-Type-Options: nosniff\r\n"))
            check("header Cache-Control: no-store", pingRaw.contains("Cache-Control: no-store\r\n"))
            check("header CSP frame-ancestors none", pingRaw.contains("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n"))
            check("ping reports version 3.0.2", pingRaw.contains("\"version\":\"3.0.2\""))

            // 1b. Large multi-header request succeeds cleanly with fast buffered read
            val padHeaders = (1..25).joinToString("") { "X-Pad-$it: ${"v".repeat(120)}\r\n" }
            val multiHdrRes = rawHttp(port, "GET /api/ping HTTP/1.1\r\nHost: localhost\r\n$padHeaders\r\n")
            check("multi-header buffered read -> 200 OK", multiHdrRes.startsWith("HTTP/1.1 200 OK\r\n"))

            // 2. Clean TCP EOF writes 0 bytes (never 431)
            val eofBytes = java.net.Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 1000
                s.shutdownOutput()
                s.getInputStream().readAllBytes()
            }
            checkEq("clean TCP EOF returns 0 bytes (no 431)", 0, eofBytes.size)

            // 3. Single auth check per authorized request (no duplicate checkAuthForPath)
            backend.pinReads = 0
            val statusRaw = rawHttp(port, "GET /api/status HTTP/1.1\r\nHost: localhost\r\nX-Auth-Token: 739104\r\n\r\n")
            check("authorized status 200", statusRaw.startsWith("HTTP/1.1 200 OK\r\n"))
            checkEq("auth checked exactly once per request", 1, backend.pinReads)

            // 4. Expect: 100-continue support
            val continueRes = java.net.Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 2000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val body = """{"code":23}"""
                val reqHead = "POST /api/key HTTP/1.1\r\nHost: localhost\r\nX-Auth-Token: 739104\r\nExpect: 100-continue\r\nContent-Length: ${body.length}\r\n\r\n"
                out.write(reqHead.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val firstBuf = ByteArray(64)
                val n1 = inp.read(firstBuf)
                val prelim = if (n1 > 0) String(firstBuf, 0, n1, Charsets.ISO_8859_1) else ""
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                prelim + String(inp.readAllBytes(), Charsets.UTF_8)
            }
            check("Expect: 100-continue yields 100 Continue", continueRes.startsWith("HTTP/1.1 100 Continue\r\n\r\n"))
            check("Expect: 100-continue completes with 200 OK", continueRes.contains("HTTP/1.1 200 OK\r\n"))
            checkEq("Expect: 100-continue delivered key 23", 23, backend.lastKey)

            // 5. Slowloris header deadline triggers 408
            val slowHeaderRes = java.net.Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 2000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                try {
                    out.write("GET /api/ping HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    repeat(6) {
                        if (inp.available() > 0) return@repeat
                        Thread.sleep(90L)
                        if (inp.available() > 0) return@repeat
                        out.write("X-Slow: a\r\n".toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                } catch (_: Throwable) {
                }
                readHttpIgnoringReset(inp)
            }
            check("Slowloris header deadline returns 408", slowHeaderRes.startsWith("HTTP/1.1 408 Request Timeout\r\n"))

            // 6. Slowloris body deadline triggers 408
            val slowBodyRes = java.net.Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 2000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                try {
                    out.write("POST /api/key HTTP/1.1\r\nHost: localhost\r\nX-Auth-Token: 739104\r\nContent-Length: 50\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    repeat(6) {
                        if (inp.available() > 0) return@repeat
                        Thread.sleep(90L)
                        if (inp.available() > 0) return@repeat
                        out.write(' '.code)
                        out.flush()
                    }
                } catch (_: Throwable) {
                }
                readHttpIgnoringReset(inp)
            }
            check("Slowloris body deadline returns 408", slowBodyRes.startsWith("HTTP/1.1 408 Request Timeout\r\n"))
        } finally {
            server.stop()
        }
    }

    private fun testWeatherOfflineBackoffAndInFlightLock() {
        val cfg = WeatherConfig("Казань", 55.7961, 49.1064)
        val other = WeatherConfig("Москва", 55.7558, 37.6173)
        val validJson = """{"current":{"temperature_2m":3.0,"apparent_temperature":1.0,"weather_code":0,"wind_speed_10m":5.0}}"""
        val origClock = WeatherClient.elapsedClockMs
        var nowMs = 50_000L
        try {
            WeatherClient.elapsedClockMs = { nowMs }
            WeatherClient.clearCacheForTest()

            var fetchCalls = 0
            val failRes = WeatherClient.fetchForecastWith(cfg, force = false) { fetchCalls++; null }
            checkEq("offline weather failure returns null without cache", null, failRes)
            checkEq("first failure calls fetcher once", 1, fetchCalls)
            checkEq("failure backoff active immediately", true, WeatherClient.isInFailureBackoff(cfg, nowMs + 1000L))
            checkEq("isStale false during 15m failure backoff", false, WeatherClient.isStale(cfg, nowMs + 60_000L))
            checkEq("other city still stale during backoff", true, WeatherClient.isStale(other, nowMs + 60_000L))

            nowMs += 60_000L
            WeatherClient.fetchForecastWith(cfg, force = false) { fetchCalls++; validJson }
            checkEq("non-forced retry skipped during backoff", 1, fetchCalls)

            val forcedOk = WeatherClient.fetchForecastWith(cfg, force = true) { fetchCalls++; validJson }
            check("forced retry bypasses failure backoff", forcedOk != null)
            checkEq("forced retry called fetcher", 2, fetchCalls)
            checkEq("success clears failure backoff", false, WeatherClient.isInFailureBackoff(cfg, nowMs + 1000L))

            // Failure backoff expires after FAILURE_BACKOFF_MS
            WeatherClient.clearCacheForTest()
            WeatherClient.fetchForecastWith(cfg, force = false) { null }
            checkEq("stale false before 15m backoff expiry", false, WeatherClient.isStale(cfg, nowMs + WeatherClient.FAILURE_BACKOFF_MS - 1L))
            checkEq("stale true at 15m backoff expiry", true, WeatherClient.isStale(cfg, nowMs + WeatherClient.FAILURE_BACKOFF_MS))

            // Concurrent in-flight lock deduplicates duplicate calls
            WeatherClient.clearCacheForTest()
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(2)
            val t1 = Thread {
                WeatherClient.fetchForecastWith(cfg, force = false) {
                    calls.incrementAndGet()
                    entered.countDown()
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    validJson
                }
                done.countDown()
            }
            val t2 = Thread {
                entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
                WeatherClient.fetchForecastWith(cfg, force = false) {
                    calls.incrementAndGet()
                    validJson
                }
                done.countDown()
            }
            t1.start()
            t2.start()
            entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(40L)
            release.countDown()
            done.await(3, java.util.concurrent.TimeUnit.SECONDS)
            checkEq("concurrent fetchLock deduplicates second call", 1, calls.get())
            WeatherClient.clearCacheForTest()
        } finally {
            WeatherClient.elapsedClockMs = origClock
        }
    }

    private fun testOnlineWallpaperHostAllowlist() {
        checkEq("www.bing.com allowed", true, OnlineWallpaperProvider.isAllowedImageHost("https://www.bing.com/th?id=OHR.jpg"))
        checkEq("cn.bing.com subdomain allowed", true, OnlineWallpaperProvider.isAllowedImageHost("https://cn.bing.com/th?id=a.jpg"))
        checkEq("apex bing.com allowed", true, OnlineWallpaperProvider.isAllowedImageHost("https://bing.com/th?id=a.jpg"))
        checkEq("upload.wikimedia.org allowed", true, OnlineWallpaperProvider.isAllowedImageHost("https://upload.wikimedia.org/wikipedia/commons/a.jpg"))
        checkEq("http bing rejected", false, OnlineWallpaperProvider.isAllowedImageHost("http://www.bing.com/th?id=a.jpg"))
        checkEq("suffix spoof evil-bing.example.com rejected", false, OnlineWallpaperProvider.isAllowedImageHost("https://evil-bing.example.com/a.jpg"))
        checkEq("subdomain spoof bing.com.example.com rejected", false, OnlineWallpaperProvider.isAllowedImageHost("https://www.bing.com.example.com/a.jpg"))
        checkEq("userinfo spoof rejected", false, OnlineWallpaperProvider.isAllowedImageHost("https://www.bing.com@example.com/a.jpg"))
        checkEq("non-443 port rejected", false, OnlineWallpaperProvider.isAllowedImageHost("https://upload.wikimedia.org:8443/a.jpg"))
        checkEq("arbitrary https host rejected", false, OnlineWallpaperProvider.isAllowedImageHost("https://example.org/a.jpg"))
        checkEq("invalid scheme rejected", false, OnlineWallpaperProvider.isAllowedImageHost("javascript:alert(1)"))

        val mixedBing = """{"images":[
            {"startdate":"20261127","url":"https://example.org/phish.jpg","title":"Evil"},
            {"startdate":"20261126","url":"/th?id=OHR.Good_1920x1080.jpg","title":"Good"}
        ]}"""
        val parsedBing = OnlineWallpaperProvider.parseBingJson(mixedBing)
        checkEq("parseBingJson filters out evil host", 1, parsedBing.size)
        checkEq("parseBingJson keeps valid Bing URL", "https://www.bing.com/th?id=OHR.Good_1920x1080.jpg", parsedBing[0].url)

        val mixedWiki = """{"query":{"pages":{
            "1":{"title":"File:Bad.jpg","imageinfo":[{"thumburl":"https://example.org/bad.jpg"}]},
            "2":{"title":"File:Good.jpg","imageinfo":[{"thumburl":"https://upload.wikimedia.org/thumb/Good.jpg"}]}
        }}}"""
        val parsedWiki = OnlineWallpaperProvider.parseWikimediaJson(mixedWiki)
        checkEq("parseWikimediaJson filters out non-upload host", 1, parsedWiki.size)
        checkEq("parseWikimediaJson keeps upload.wikimedia.org", "https://upload.wikimedia.org/thumb/Good.jpg", parsedWiki[0].url)
    }

    private fun testSleepTimerSmartTickDelay() {
        checkEq("zero remMs -> 0", 0L, SleepTimerController.nextTickDelayMs(0L))
        checkEq("90m exact aligns to 60s boundary", 60_050L, SleepTimerController.nextTickDelayMs(5_400_000L))
        checkEq("2m30s aligns to 30s minute boundary", 30_050L, SleepTimerController.nextTickDelayMs(150_000L))
        checkEq("60.001s clamps to >=200ms anti-spin", 200L, SleepTimerController.nextTickDelayMs(60_001L))
        checkEq("60s exact switches to 1s window", 1_025L, SleepTimerController.nextTickDelayMs(60_000L))
        checkEq("15.5s aligns to 15s warning boundary", 525L, SleepTimerController.nextTickDelayMs(15_500L))
        checkEq("1s aligns to 1025ms", 1_025L, SleepTimerController.nextTickDelayMs(1_000L))
    }

    private fun testWebRemoteMonotonicLockoutOrigin() {
        var nowMs = -5_000_000L
        val router = LanWebRemoteRouter(FakeBackend(pin = "739104"), clockMs = { nowMs })
        val bad = LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = mapOf("X-Auth-Token" to "000000"))
        val good = LanWebRemoteRouter.HttpRequest("GET", "/api/status", headers = mapOf("X-Auth-Token" to "739104"))
        repeat(5) { router.route(bad) }
        checkEq("negative monotonic clock locks out -> 429", 429, router.route(good).status)
        nowMs += LanWebRemoteRouter.AUTH_LOCKOUT_MS - 1L
        checkEq("negative monotonic clock still 429 before 15s", 429, router.route(good).status)
        nowMs += 1L
        checkEq("negative monotonic clock unlocks at 15s", 200, router.route(good).status)
        val defaultRouter = LanWebRemoteRouter(FakeBackend(pin = "739104"))
        repeat(5) { defaultRouter.route(bad) }
        checkEq("default monotonic clock router locks out -> 429", 429, defaultRouter.route(good).status)
    }

    private fun testLanWebRemoteServerBoundedPool503() {
        val backend = FakeBackend(pin = "739104")
        val router = LanWebRemoteRouter(backend)
        val server = LanWebRemoteServer(
            port = 0,
            router = router,
            socketTimeoutMs = 1500,
            headerDeadlineMs = 1500L,
            bodyDeadlineMs = 1500L,
            maxWorkers = 1,
            maxQueue = 1
        )
        checkEq("bounded pool server starts", true, server.start())
        val port = server.boundPort()
        val holders = ArrayList<java.net.Socket>()
        try {
            // Occupy the 1 worker + 1 queue slot with open sockets that send partial headers
            repeat(2) {
                val s = java.net.Socket("127.0.0.1", port)
                s.getOutputStream().write("GET /api/ping HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1))
                s.getOutputStream().flush()
                holders.add(s)
                Thread.sleep(40L)
            }
            var got503 = ""
            for (attempt in 0 until 4) {
                val res = rawHttp(port, "GET /api/ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
                if (res.startsWith("HTTP/1.1 503 Service Unavailable\r\n")) {
                    got503 = res
                    break
                }
            }
            check("overflow request returns 503 Service Unavailable", got503.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
            check("503 body has service_unavailable code", got503.contains("\"error\":\"service_unavailable\""))
        } finally {
            holders.forEach { try { it.close() } catch (_: Throwable) {} }
            server.stop()
        }
    }

    private fun testSystemStatusMemInfoEarlyBreak() {
        val tmp = java.nio.file.Files.createTempFile("ambient_meminfo_", ".txt").toFile()
        try {
            tmp.writeText(
                "MemTotal:        4012340 kB\n" +
                    "MemFree:          512000 kB\n" +
                    "MemAvailable:    2910400 kB\n" +
                    "Buffers:           90000 kB\n" +
                    "Cached:          1200000 kB\n"
            )
            val (tot, avail) = SystemStatusHelper.readMemInfoKbFrom(tmp)
            checkEq("meminfo total parsed", 4_012_340L, tot)
            checkEq("meminfo available parsed", 2_910_400L, avail)
        } finally {
            tmp.delete()
        }
    }

    private fun readHttpIgnoringReset(inp: java.io.InputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                baos.write(buf, 0, n)
            }
        } catch (_: java.net.SocketException) {
        }
        return baos.toByteArray().toString(Charsets.UTF_8)
    }

    private fun rawHttp(port: Int, payload: String): String =
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 2000
            s.getOutputStream().write(payload.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            String(s.getInputStream().readAllBytes(), Charsets.UTF_8)
        }

    private class FakeBackend(private val pin: String) : LanWebRemoteRouter.Backend {
        var pinReads: Int = 0
        var lastKey: Int? = null
        var lastText: String? = null
        var lastAction: String? = null
        var lastWallpaperName: String? = null

        override fun currentPin(): String {
            pinReads++
            return pin
        }
        override fun htmlShell(): ByteArray = "<html><body>Ambient Home Remote</body></html>".toByteArray()
        override fun statusJson(): Map<String, Any?> = mapOf("ok" to true, "version" to "3.0.2")
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
