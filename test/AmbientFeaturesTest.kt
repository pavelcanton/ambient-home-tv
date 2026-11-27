package com.will.ambienthome.model

/**
 * Автономный набор Kotlin unit-тестов для новых моделей Ambient Home 3.0.0:
 * NightSchedule, BootAutoLaunch, CustomShortcuts (WoL), MiniJson, AmbientClockStyle и SleepTimerState.
 */
object AmbientFeaturesTest {

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
        testNightScheduleOvernightAndManual()
        testNightScheduleDaytimeDimAndLuminance()
        testBootAutoLaunchStateMachine()
        testCustomShortcutsMacAndMagicPacket()
        testCustomShortcutsBroadcastHelpers()
        testMiniJsonParsingAndSerialization()
        testMiniJsonStrictUnicodeBoundsAndNonFinite()
        testClockStylesAndSleepBadge()
        testAmbientClockLayoutSafeBoundsAllResolutions()
        testAmbientOverlayBlackoutDecoupledFromShowClock()

        println("AmbientFeaturesTest: passed=$passed failed=$failed")
        if (failed > 0) System.exit(1)
    }

    private fun testNightScheduleOvernightAndManual() {
        val auto = NightConfig(mode = NightMode.AUTO, startHour = 22, endHour = 7, nightDimPercent = 55)
        checkEq("21:59 is day", false, NightSchedule.isNightActive(21, 59, auto))
        checkEq("22:00 is night", true, NightSchedule.isNightActive(22, 0, auto))
        checkEq("23:30 is night", true, NightSchedule.isNightActive(23, 30, auto))
        checkEq("00:00 is night", true, NightSchedule.isNightActive(0, 0, auto))
        checkEq("06:59 is night", true, NightSchedule.isNightActive(6, 59, auto))
        checkEq("07:00 is day", false, NightSchedule.isNightActive(7, 0, auto))
        checkEq("14:00 is day", false, NightSchedule.isNightActive(14, 0, auto))

        val forcedOn = auto.copy(mode = NightMode.ON)
        val forcedOff = auto.copy(mode = NightMode.OFF)
        checkEq("ON active at noon", true, NightSchedule.isNightActive(12, 0, forcedOn))
        checkEq("OFF inactive at midnight", false, NightSchedule.isNightActive(0, 0, forcedOff))

        val sameHours = auto.copy(startHour = 22, endHour = 22)
        checkEq("equal start/end returns false", false, NightSchedule.isNightActive(22, 0, sameHours))
    }

    private fun testNightScheduleDaytimeDimAndLuminance() {
        val daytime = NightConfig(mode = NightMode.AUTO, startHour = 9, endHour = 17, nightDimPercent = 60)
        checkEq("08:59 outside 09-17", false, NightSchedule.isNightActive(8, 59, daytime))
        checkEq("09:00 inside 09-17", true, NightSchedule.isNightActive(9, 0, daytime))
        checkEq("16:59 inside 09-17", true, NightSchedule.isNightActive(16, 59, daytime))
        checkEq("17:00 outside 09-17", false, NightSchedule.isNightActive(17, 0, daytime))

        checkEq("day dim preserves base 22", 22, NightSchedule.effectiveDimPercent(22, false, daytime))
        checkEq("night dim raises 22 -> 60", 60, NightSchedule.effectiveDimPercent(22, true, daytime))
        checkEq("night dim preserves higher base 70", 70, NightSchedule.effectiveDimPercent(70, true, daytime))
        check("warm tint non-zero at night", NightSchedule.warmTintColor(true) != 0)
        checkEq("warm tint 0 during day", 0, NightSchedule.warmTintColor(false))
        check("clock alpha dimmer at night", NightSchedule.clockLuminanceAlpha(true) < NightSchedule.clockLuminanceAlpha(false))
        checkEq("format schedule", "09:00–17:00", NightSchedule.formatSchedule(daytime))
    }

    private fun testBootAutoLaunchStateMachine() {
        val disabled = BootAutoLaunchConfig(enabled = false, packageName = "org.smarttube.stable")
        checkEq("disabled never triggers", false, BootAutoLaunch.shouldTrigger("boot-1", disabled))
        val emptyPkg = BootAutoLaunchConfig(enabled = true, packageName = "")
        checkEq("empty pkg never triggers", false, BootAutoLaunch.shouldTrigger("boot-1", emptyPkg))
        val ready = BootAutoLaunchConfig(enabled = true, packageName = "org.smarttube.stable", lastHandledBootId = "boot-1")
        checkEq("same boot_id does not re-trigger", false, BootAutoLaunch.shouldTrigger("boot-1", ready))
        checkEq("new boot_id triggers", true, BootAutoLaunch.shouldTrigger("boot-2", ready))
        checkEq("blank boot_id never triggers", false, BootAutoLaunch.shouldTrigger("   ", ready))

        checkEq("countdown at 0ms = 3", 3, BootAutoLaunch.countdownRemainingSec(0L))
        checkEq("countdown at 1500ms = 2", 2, BootAutoLaunch.countdownRemainingSec(1500L))
        checkEq("countdown at 3200ms = 0", 0, BootAutoLaunch.countdownRemainingSec(3200L))
        checkEq("wifi wait not timed out at 5s", false, BootAutoLaunch.isWifiWaitTimedOut(5000L))
        checkEq("wifi wait timed out at 25s", true, BootAutoLaunch.isWifiWaitTimedOut(25000L))
        check("banner wifi mentions network", BootAutoLaunch.bannerText("SmartTube", 3, true).contains("ожидание сети"))
        check("banner countdown mentions sec", BootAutoLaunch.bannerText("SmartTube", 2, false).contains("2 с"))
    }

    private fun testCustomShortcutsMacAndMagicPacket() {
        checkEq("colon MAC normalized", "AA:BB:CC:DD:EE:FF", CustomShortcuts.normalizeMac("aa:bb:cc:dd:ee:ff"))
        checkEq("dash MAC normalized", "01:23:45:67:89:AB", CustomShortcuts.normalizeMac("01-23-45-67-89-ab"))
        checkEq("bare 12-hex normalized", "DE:AD:BE:EF:12:34", CustomShortcuts.normalizeMac("deadbeef1234"))
        checkEq("mixed separators rejected", null, CustomShortcuts.normalizeMac("aa:bb-cc:dd:ee:ff"))
        checkEq("all zeros rejected", null, CustomShortcuts.normalizeMac("00:00:00:00:00:00"))
        checkEq("broadcast FF rejected", null, CustomShortcuts.normalizeMac("FF:FF:FF:FF:FF:FF"))
        checkEq("invalid hex rejected", null, CustomShortcuts.normalizeMac("GG:BB:CC:DD:EE:FF"))
        checkEq("short MAC rejected", null, CustomShortcuts.normalizeMac("AA:BB:CC"))

        val pkt = CustomShortcuts.buildMagicPacket("10:20:30:40:50:60")
        check("magic packet non-null", pkt != null)
        checkEq("magic packet length = 102", 102, pkt?.size)
        if (pkt != null) {
            var headerOk = true
            for (i in 0 until 6) if (pkt[i] != 0xFF.toByte()) headerOk = false
            check("first 6 bytes are 0xFF", headerOk)
            val expectedMac = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
            var repeatsOk = true
            for (rep in 0 until 16) {
                for (b in 0 until 6) {
                    if (pkt[6 + rep * 6 + b] != expectedMac[b]) repeatsOk = false
                }
            }
            check("16 MAC repetitions match exactly", repeatsOk)
        }
        checkEq("isWolShortcut true", true, CustomShortcuts.isWolShortcut(CustomShortcuts.WOL_PACKAGE))
        checkEq("isWolShortcut false", false, CustomShortcuts.isWolShortcut("org.smarttube.stable"))
    }

    private fun testCustomShortcutsBroadcastHelpers() {
        checkEq("valid broadcast kept", "192.168.1.255", CustomShortcuts.normalizeBroadcastIp("192.168.1.255"))
        checkEq("invalid octet falls back", "255.255.255.255", CustomShortcuts.normalizeBroadcastIp("192.168.300.1"))
        checkEq("empty falls back", "255.255.255.255", CustomShortcuts.normalizeBroadcastIp(""))
        checkEq("subnet broadcast derived", "192.0.2.255", CustomShortcuts.deriveSubnetBroadcast("192.0.2.10"))
        checkEq("invalid IP returns null", null, CustomShortcuts.deriveSubnetBroadcast("not-an-ip"))
    }

    private fun testMiniJsonParsingAndSerialization() {
        val raw = """{"ok":true,"n":42,"f":-3.5,"cyr":"Привет \"мир\"\n\u0410","arr":[1,null,false,{"k":"v"}]}"""
        val obj = MiniJson.parseObject(raw)
        check("valid JSON parses", obj != null)
        checkEq("bool true", true, obj?.get("ok"))
        checkEq("int 42", 42, MiniJson.asInt(obj?.get("n")))
        checkEq("double -3.5", -3.5, MiniJson.asDouble(obj?.get("f")))
        checkEq("cyrillic + escapes", "Привет \"мир\"\nА", obj?.get("cyr"))
        val arr = obj?.get("arr") as? List<*>
        checkEq("array size 4", 4, arr?.size)
        checkEq("array null element", null, arr?.get(1))
        checkEq("nested object value", "v", (arr?.get(3) as? Map<*, *>)?.get("k"))

        val roundTrip = MiniJson.parseObject(MiniJson.stringify(obj, pretty = true))
        checkEq("pretty stringify round-trips cyrillic", "Привет \"мир\"\nА", roundTrip?.get("cyr"))
        checkEq("trailing garbage rejected", null, MiniJson.parseObject("{\"a\":1} trailing"))
        checkEq("unclosed object rejected", null, MiniJson.parseObject("{\"a\":1"))
        val deep = "[".repeat(40) + "]".repeat(40)
        checkEq("excessive depth (>32) rejected safely", null, MiniJson.parseArray(deep))
    }

    private fun testClockStylesAndSleepBadge() {
        checkEq("4 clock styles", 4, AmbientClockStyle.entries.size)
        checkEq("next THIN -> CINEMA", AmbientClockStyle.CINEMA, AmbientClockStyle.next(AmbientClockStyle.THIN))
        checkEq("next ANALOG -> THIN", AmbientClockStyle.THIN, AmbientClockStyle.next(AmbientClockStyle.ANALOG))
        checkEq("sleep inactive empty badge", "", SleepTimerState(false, 600).badgeText)
        checkEq("sleep 45s shows 45с", "45с", SleepTimerState(true, 45).badgeText)
        checkEq("sleep 2520s shows 42м", "42м", SleepTimerState(true, 2520).badgeText)
        checkEq("weather temp positive prefix", "+4°", WeatherSnapshot("M", 4, 2, 10, WeatherIcon.CLEAR, "Ясно").pillTempText)
        checkEq("weather temp negative", "-12°", WeatherSnapshot("M", -12, -16, 8, WeatherIcon.SNOW, "Снег").pillTempText)
    }

    private fun testMiniJsonStrictUnicodeBoundsAndNonFinite() {
        checkEq("valid lowercase hex \\u0430", "а", MiniJson.parseObject("""{"s":"\u0430"}""")?.get("s"))
        checkEq("valid uppercase hex \\u041F", "П", MiniJson.parseObject("""{"s":"\u041F"}""")?.get("s"))
        checkEq("signed negative \\u-001 rejected", null, MiniJson.parseObject("""{"s":"\u-001"}"""))
        checkEq("signed positive \\u+001 rejected", null, MiniJson.parseObject("""{"s":"\u+001"}"""))
        checkEq("space in \\u 041 rejected", null, MiniJson.parseObject("""{"s":"\u 041"}"""))
        checkEq("non-hex \\u041g rejected", null, MiniJson.parseObject("""{"s":"\u041g"}"""))

        checkEq("asInt max int ok", Int.MAX_VALUE, MiniJson.asInt(Int.MAX_VALUE.toLong()))
        checkEq("asInt min int ok", Int.MIN_VALUE, MiniJson.asInt(Int.MIN_VALUE.toLong()))
        checkEq("asInt overflow Long -> null", null, MiniJson.asInt(Int.MAX_VALUE.toLong() + 1L))
        checkEq("asInt wrap-around 4294967319L -> null", null, MiniJson.asInt(4_294_967_319L))
        checkEq("asInt underflow Long -> null", null, MiniJson.asInt(Int.MIN_VALUE.toLong() - 1L))
        checkEq("asInt +Infinity -> null", null, MiniJson.asInt(Double.POSITIVE_INFINITY))
        checkEq("asInt NaN -> null", null, MiniJson.asInt(Double.NaN))
        checkEq("asDouble +Infinity -> null", null, MiniJson.asDouble(Double.POSITIVE_INFINITY))
        checkEq("asDouble NaN -> null", null, MiniJson.asDouble(Double.NaN))
        checkEq("JSON overflow 1e309 rejected", null, MiniJson.parseObject("""{"x":1e309}"""))
        checkEq("JSON overflow -1e400 rejected", null, MiniJson.parseObject("""{"x":-1e400}"""))
    }

    private fun testAmbientClockLayoutSafeBoundsAllResolutions() {
        val resolutions = listOf(1280f to 720f, 1920f to 1080f, 3840f to 2160f)
        for ((w, h) in resolutions) {
            var allVerticalSafe = true
            var allHorizontalSafe = true
            var allAnalogDialSafe = true
            var worstBottomU = 0f
            var minTextWidthU = Float.MAX_VALUE
            for (style in AmbientClockStyle.entries) {
                for (tick in 0 until 60) {
                    val l = AmbientClockLayout.compute(w, h, tick, style)
                    val bottomU = (l.nowPlayingY + 12f * l.u) / l.u
                    if (bottomU > worstBottomU) worstBottomU = bottomU
                    if (bottomU > 1044f) allVerticalSafe = false
                    val edgeMarginU = if (l.rightAligned) (w - l.anchorX) / l.u else l.anchorX / l.u
                    if (edgeMarginU < 68f) allHorizontalSafe = false
                    val textWidthU = l.maxTextWidth / l.u
                    if (textWidthU < minTextWidthU) minTextWidthU = textWidthU
                    if (style == AmbientClockStyle.ANALOG) {
                        val dialTopU = (l.analogCy - l.analogRadius) / l.u
                        val dialBotU = (l.analogCy + l.analogRadius) / l.u
                        val dialLeftU = (l.analogCx - l.analogRadius) / l.u
                        val dialRightU = (l.analogCx + l.analogRadius) / l.u
                        if (dialTopU < 44f || dialBotU > 920f || dialLeftU < 64f || dialRightU > (w / l.u) - 64f) {
                            allAnalogDialSafe = false
                        }
                    }
                }
            }
            val label = "${w.toInt()}x${h.toInt()}"
            check("$label vertical safe <=1044u (worst=$worstBottomU)", allVerticalSafe)
            check("$label horizontal safe >=68u", allHorizontalSafe)
            check("$label analog dial inside safe-zone", allAnalogDialSafe)
            check("$label maxTextWidth >= 500u (min=$minTextWidthU)", minTextWidthU >= 500f)
        }
    }

    private fun testAmbientOverlayBlackoutDecoupledFromShowClock() {
        val ov = AmbientOverlayData(showClock = false, blackout = true)
        checkEq("blackout true even when showClock=false", true, ov.blackout)
        checkEq("showClock preserved false", false, ov.showClock)
        val def = AmbientOverlayData()
        checkEq("default showClock is true", true, def.showClock)
    }
}
