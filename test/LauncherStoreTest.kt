package com.will.ambienthome.data

import android.content.SharedPreferences
import com.will.ambienthome.model.AppearanceState
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.WallpaperSource

/**
 * Автономный набор Kotlin unit-тестов для LauncherStore поверх in-memory FakeSharedPreferences.
 * Проверяет миграцию v1.2 -> v2, очистку orphan-ключа rotate_now, per-source индексы обоев,
 * атомарные обновления appearance/layout и персистентность hidden_pkgs / dock_order / pinned_order.
 */
object LauncherStoreTest {

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
        testFreshDefaultsAndNoSpuriousWrites()
        testLegacyMigrationGuardsAndNullLegacy()
        testOrphanRotateNowCleanup()
        testPerSourceWallpaperIndicesAndFlush()
        testLegacySingleWpIndexFallback()
        testAtomicAppearanceNormalizationAndPersistence()
        testAtomicLayoutNormalizationAndHiddenPersistence()
        testPartialWallpaperIndicesReturnParity()
        testInvalidDiskValuesClampedOnRead()
        testDirtyRamWithUpdateAppearance()
        testV3FeaturesPersistenceAndNormalization()

        println("LauncherStoreTest: passed=$passed failed=$failed")
        if (failed > 0) {
            System.exit(1)
        }
    }

    private fun testFreshDefaultsAndNoSpuriousWrites() {
        val sp = FakeSharedPreferences()
        val store = LauncherStore(sp, legacySp = null)
        val st = store.readState()

        checkEq("default homeMode is DOCK", HomeMode.DOCK, st.appearance.homeMode)
        checkEq("default wallpaperSource is BUILTIN", WallpaperSource.BUILTIN, st.appearance.wallpaperSource)
        checkEq("default interval is 15", AppearanceState.DEFAULT_WP_INTERVAL_MIN, st.appearance.wallpaperIntervalMin)
        checkEq("default dim is 22", AppearanceState.DEFAULT_DIM_PERCENT, st.appearance.dimPercent)
        checkEq("default showClock is true", true, st.appearance.showClock)
        checkEq("default ambient is 5", AppearanceState.DEFAULT_AMBIENT_MIN, st.appearance.ambientTimeoutMin)
        checkEq("default dockOrder empty", emptyList<String>(), st.layout.dockOrder)
        checkEq("default pinned empty", emptyList<String>(), st.layout.pinnedPackages)
        checkEq("default hidden empty", emptySet<String>(), st.layout.hiddenPackages)
        checkEq("migration marked true", true, sp.getBoolean("migrated_v2", false))

        val beforeApplies = sp.applyCount
        val second = LauncherStore(sp, legacySp = null)
        checkEq("re-open migrated store performs 0 disk writes", beforeApplies, sp.applyCount)
        checkEq("re-open reads same state", st, second.readState())
    }

    private fun testLegacyMigrationGuardsAndNullLegacy() {
        val legacy = FakeSharedPreferences(
            mutableMapOf(
                "mode" to HomeMode.GRID.code,
                "interval" to 30,
                "dim" to 35,
                "clock" to false
            )
        )
        val sp = FakeSharedPreferences(mutableMapOf("dim" to 15))
        val store = LauncherStore(sp, legacy)
        val ap = store.readState().appearance

        checkEq("migrated mode -> GRID", HomeMode.GRID, ap.homeMode)
        checkEq("migrated interval -> 30", 30, ap.wallpaperIntervalMin)
        checkEq("existing v2 dim=15 NOT overwritten by legacy dim=35", 15, ap.dimPercent)
        checkEq("migrated clock -> false", false, ap.showClock)
        checkEq("migrated_v2 flag set", true, sp.getBoolean("migrated_v2", false))

        // Повторный запуск после migrated_v2=true не перечитывает legacy повторно
        val mutatedLegacy = FakeSharedPreferences(mutableMapOf("mode" to HomeMode.DOCK.code, "clock" to true))
        val store2 = LauncherStore(sp, mutatedLegacy)
        checkEq("already migrated ignores legacy mode", HomeMode.GRID, store2.readState().appearance.homeMode)
        checkEq("already migrated ignores legacy clock", false, store2.readState().appearance.showClock)
    }

    private fun testOrphanRotateNowCleanup() {
        val spAlreadyMigrated = FakeSharedPreferences(
            mutableMapOf(
                "migrated_v2" to true,
                "rotate_now" to true,
                "dim" to 22
            )
        )
        LauncherStore(spAlreadyMigrated, legacySp = null)
        checkEq("orphan rotate_now removed even when migrated_v2=true", false, spAlreadyMigrated.contains("rotate_now"))
        checkEq("unrelated key dim preserved", 22, spAlreadyMigrated.getInt("dim", -1))
        checkEq("cleanup performed exactly 1 apply", 1, spAlreadyMigrated.applyCount)

        // Второй запуск без rotate_now -> 0 записей на диск
        LauncherStore(spAlreadyMigrated, legacySp = null)
        checkEq("no rotate_now -> 0 extra applies", 1, spAlreadyMigrated.applyCount)
    }

    private fun testPerSourceWallpaperIndicesAndFlush() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)
        val baseApplies = sp.applyCount

        // Запись в RAM без немедленного сброса на диск
        store.recordWallpaperIndex(WallpaperSource.BUILTIN, 3, persistImmediately = false)
        store.recordWallpaperIndex(WallpaperSource.GRADIENT, 2, persistImmediately = false)
        checkEq("RAM BUILTIN index=3", 3, store.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("RAM GRADIENT index=2", 2, store.wallpaperIndexFor(WallpaperSource.GRADIENT))
        checkEq("no disk write before flush", baseApplies, sp.applyCount)
        checkEq("readState sees dirty RAM value", 3, store.readState().appearance.wallpaperIndexFor(WallpaperSource.BUILTIN))

        // Flush пишет оба индекса одним apply()
        store.flushWallpaperIndices()
        checkEq("flush writes once atomically", baseApplies + 1, sp.applyCount)
        checkEq("persisted wp_index_0=3", 3, sp.getInt("wp_index_0", -1))
        checkEq("persisted wp_index_2=2", 2, sp.getInt("wp_index_2", -1))

        // Повторный flush без изменений -> 0 записей
        store.flushWallpaperIndices()
        checkEq("second flush is no-op", baseApplies + 1, sp.applyCount)

        // Немедленная запись + защита от дубля и отрицательного значения
        store.recordWallpaperIndex(WallpaperSource.CUSTOM, -7, persistImmediately = true)
        checkEq("negative index clamped to 0", 0, store.wallpaperIndexFor(WallpaperSource.CUSTOM))
        checkEq("unchanged 0 skips redundant write", baseApplies + 1, sp.applyCount)

        store.recordWallpaperIndex(WallpaperSource.CUSTOM, 4, persistImmediately = true)
        checkEq("immediate write persists CUSTOM=4", 4, sp.getInt("wp_index_1", -1))
        checkEq("immediate write incremented applyCount by 1", baseApplies + 2, sp.applyCount)
        checkEq("BUILTIN untouched when CUSTOM updated", 3, store.wallpaperIndexFor(WallpaperSource.BUILTIN))
    }

    private fun testLegacySingleWpIndexFallback() {
        val sp = FakeSharedPreferences(
            mutableMapOf(
                "migrated_v2" to true,
                "wp_source" to WallpaperSource.GRADIENT.code,
                "wp_index" to 2,
                "wp_index_0" to 4
            )
        )
        val store = LauncherStore(sp, legacySp = null)
        checkEq("active source without per-source key inherits legacy wp_index", 2, store.wallpaperIndexFor(WallpaperSource.GRADIENT))
        checkEq("explicit per-source key overrides default", 4, store.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("other inactive sources default to 0", 0, store.wallpaperIndexFor(WallpaperSource.CUSTOM))
    }

    private fun testAtomicAppearanceNormalizationAndPersistence() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)
        val updated = store.updateAppearance {
            it.copy(
                homeMode = HomeMode.GRID,
                wallpaperSource = WallpaperSource.ALL,
                wallpaperIntervalMin = -10,
                dimPercent = 95,
                showClock = false,
                ambientTimeoutMin = -3,
                wallpaperIndices = mapOf(WallpaperSource.ALL to 6, WallpaperSource.BUILTIN to -2)
            )
        }
        checkEq("interval clamped >= 0", 0, updated.appearance.wallpaperIntervalMin)
        checkEq("dim clamped <= 70", 70, updated.appearance.dimPercent)
        checkEq("ambient clamped >= 0", 0, updated.appearance.ambientTimeoutMin)
        checkEq("ALL index saved", 6, updated.appearance.wallpaperIndexFor(WallpaperSource.ALL))
        checkEq("negative map index clamped to 0", 0, updated.appearance.wallpaperIndexFor(WallpaperSource.BUILTIN))

        val reloaded = LauncherStore(sp, legacySp = null).readState().appearance
        checkEq("round-trip homeMode", HomeMode.GRID, reloaded.homeMode)
        checkEq("round-trip wallpaperSource", WallpaperSource.ALL, reloaded.wallpaperSource)
        checkEq("round-trip dimPercent", 70, reloaded.dimPercent)
        checkEq("round-trip showClock", false, reloaded.showClock)
        checkEq("round-trip ALL index", 6, reloaded.wallpaperIndexFor(WallpaperSource.ALL))
    }

    private fun testAtomicLayoutNormalizationAndHiddenPersistence() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)

        val st1 = store.updateAppsLayout {
            it.copy(
                dockOrder = listOf("  com.a ", "", "com.b", "com.a"),
                pinnedPackages = listOf("com.p2", " com.p1 ", "com.p2", "   "),
                hiddenPackages = setOf("com.z", " com.m ", "", "com.a")
            )
        }
        checkEq("dockOrder trimmed & deduplicated in order", listOf("com.a", "com.b"), st1.layout.dockOrder)
        checkEq("pinned trimmed & deduplicated in order", listOf("com.p2", "com.p1"), st1.layout.pinnedPackages)
        checkEq("hidden trimmed & non-empty", setOf("com.a", "com.m", "com.z"), st1.layout.hiddenPackages)
        checkEq("hidden_pkgs persisted sorted newline-delimited", "com.a\ncom.m\ncom.z", sp.getString("hidden_pkgs", null))

        val reloaded = LauncherStore(sp, legacySp = null).readState().layout
        checkEq("round-trip dockOrder", listOf("com.a", "com.b"), reloaded.dockOrder)
        checkEq("round-trip pinned", listOf("com.p2", "com.p1"), reloaded.pinnedPackages)
        checkEq("round-trip hidden", setOf("com.a", "com.m", "com.z"), reloaded.hiddenPackages)

        // Очистка удаляет ключи из SharedPreferences
        store.updateAppsLayout { it.copy(dockOrder = emptyList(), pinnedPackages = emptyList(), hiddenPackages = emptySet()) }
        checkEq("empty dock_order key removed", false, sp.contains("dock_order"))
        checkEq("empty pinned_order key removed", false, sp.contains("pinned_order"))
        checkEq("empty hidden_pkgs key removed", false, sp.contains("hidden_pkgs"))
    }

    private fun testPartialWallpaperIndicesReturnParity() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)
        store.recordWallpaperIndex(WallpaperSource.BUILTIN, 4, persistImmediately = true)
        store.recordWallpaperIndex(WallpaperSource.CUSTOM, 2, persistImmediately = true)
        store.recordWallpaperIndex(WallpaperSource.GRADIENT, 1, persistImmediately = true)

        val returned = store.updateAppearance {
            it.copy(wallpaperIndices = mapOf(WallpaperSource.ALL to 6, WallpaperSource.BUILTIN to -2))
        }
        val reread = store.readState()
        checkEq("partial wallpaperIndices: returned == readState()", reread, returned)
        checkEq("partial wallpaperIndices: all sources present", WallpaperSource.entries.size, returned.appearance.wallpaperIndices.size)
        checkEq("partial wallpaperIndices: ALL updated to 6", 6, returned.appearance.wallpaperIndexFor(WallpaperSource.ALL))
        checkEq("partial wallpaperIndices: negative BUILTIN clamped to 0", 0, returned.appearance.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("partial wallpaperIndices: omitted CUSTOM preserved at 2", 2, returned.appearance.wallpaperIndexFor(WallpaperSource.CUSTOM))
        checkEq("partial wallpaperIndices: omitted GRADIENT preserved at 1", 1, returned.appearance.wallpaperIndexFor(WallpaperSource.GRADIENT))
    }

    private fun testInvalidDiskValuesClampedOnRead() {
        val sp = FakeSharedPreferences(
            mutableMapOf(
                "migrated_v2" to true,
                "mode" to 99,
                "wp_source" to 99,
                "dim" to 999,
                "wp_interval" to -5,
                "ambient_timeout" to -3,
                "wp_index_0" to -3,
                "wp_index_2" to -10
            )
        )
        val store = LauncherStore(sp, legacySp = null)
        val ap = store.readState().appearance
        checkEq("invalid disk mode falls back to DOCK", HomeMode.DOCK, ap.homeMode)
        checkEq("invalid disk wp_source=99 falls back to BUILTIN", WallpaperSource.BUILTIN, ap.wallpaperSource)
        checkEq("invalid disk dim=999 clamped to 70", 70, ap.dimPercent)
        checkEq("invalid disk wp_interval=-5 clamped to 0", 0, ap.wallpaperIntervalMin)
        checkEq("invalid disk ambient_timeout=-3 clamped to 0", 0, ap.ambientTimeoutMin)
        checkEq("invalid disk wp_index_0=-3 clamped to 0", 0, ap.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("invalid disk wp_index_2=-10 clamped to 0", 0, ap.wallpaperIndexFor(WallpaperSource.GRADIENT))
        checkEq("RAM index for wp_index_0=-3 clamped to 0", 0, store.wallpaperIndexFor(WallpaperSource.BUILTIN))

        val spNegDim = FakeSharedPreferences(mutableMapOf("migrated_v2" to true, "dim" to -12))
        checkEq("invalid disk dim=-12 clamped to 0", 0, LauncherStore(spNegDim, legacySp = null).readState().appearance.dimPercent)
    }

    private fun testDirtyRamWithUpdateAppearance() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)
        store.recordWallpaperIndex(WallpaperSource.BUILTIN, 3, persistImmediately = false)
        store.recordWallpaperIndex(WallpaperSource.GRADIENT, 2, persistImmediately = false)

        val st1 = store.updateAppearance { it.copy(dimPercent = 35) }
        checkEq("dirty RAM + updateAppearance returned == readState()", store.readState(), st1)
        checkEq("dirty RAM BUILTIN persisted by updateAppearance", 3, sp.getInt("wp_index_0", -1))
        checkEq("dirty RAM GRADIENT persisted by updateAppearance", 2, sp.getInt("wp_index_2", -1))
        val appliesAfterUpdate = sp.applyCount
        store.flushWallpaperIndices()
        checkEq("flush after updateAppearance does 0 extra writes", appliesAfterUpdate, sp.applyCount)

        store.recordWallpaperIndex(WallpaperSource.CUSTOM, 5, persistImmediately = false)
        val st2 = store.updateAppearance { it.copy(wallpaperIndices = mapOf(WallpaperSource.ALL to 7)) }
        checkEq("dirty RAM + partial updateAppearance returned == readState()", store.readState(), st2)
        checkEq("dirty RAM CUSTOM=5 preserved in returned", 5, st2.appearance.wallpaperIndexFor(WallpaperSource.CUSTOM))
        checkEq("partial update ALL=7 persisted", 7, sp.getInt("wp_index_3", -1))
        checkEq("omitted dirty CUSTOM still holds old disk value 0 before flush", 0, sp.getInt("wp_index_1", -1))
        store.flushWallpaperIndices()
        checkEq("flush persists remaining dirty CUSTOM=5", 5, sp.getInt("wp_index_1", -1))
    }

    private fun testV3FeaturesPersistenceAndNormalization() {
        val sp = FakeSharedPreferences(mutableMapOf("migrated_v2" to true))
        val store = LauncherStore(sp, legacySp = null)
        val pin = LauncherStore.generatePin()
        checkEq("generated PIN is 6 digits", 6, pin.length)
        check("generated PIN is all digits", pin.all { it in '0'..'9' })
        checkEq("sanitizePin rejects 5 digits", "", LauncherStore.sanitizePin("12345"))
        checkEq("sanitizePin strips spaces for 6 digits", "654321", LauncherStore.sanitizePin(" 654 321 "))

        val st = store.updateFeatures { f ->
            f.copy(
                night = f.night.copy(mode = com.will.ambienthome.model.NightMode.ON, startHour = 25, endHour = -1, nightDimPercent = 99),
                sleepHdmiOff = false,
                weather = com.will.ambienthome.model.WeatherConfig("  Казань  ", 55.7961, 49.1064),
                bootAutoLaunch = f.bootAutoLaunch.copy(enabled = true, packageName = "org.smarttube.stable", lastHandledBootId = "boot-42"),
                wol = f.wol.copy(name = "Игровой ПК", mac = "aa-bb-cc-dd-ee-ff", broadcastIp = "bad-ip"),
                webRemote = f.webRemote.copy(enabled = false, port = 80, pin = pin)
            )
        }
        checkEq("night startHour clamped to 23", 23, st.features.night.startHour)
        checkEq("night endHour clamped to 0", 0, st.features.night.endHour)
        checkEq("nightDim clamped to 82", 82, st.features.night.nightDimPercent)
        checkEq("sleepHdmiOff saved false", false, st.features.sleepHdmiOff)
        checkEq("weather city trimmed", "Казань", st.features.weather.cityName)
        checkEq("weather configured", true, st.features.weather.isConfigured)
        checkEq("wol MAC normalized", "AA:BB:CC:DD:EE:FF", st.features.wol.mac)
        checkEq("wol invalid broadcast falls back", "255.255.255.255", st.features.wol.broadcastIp)
        checkEq("webRemote port clamped to 1024", 1024, st.features.webRemote.port)
        checkEq("webRemote PIN persisted", pin, st.features.webRemote.pin)

        val reloaded = LauncherStore(sp, legacySp = null).readState()
        checkEq("round-trip features", st.features, reloaded.features)
    }

    private class FakeSharedPreferences(
        private val data: MutableMap<String, Any?> = LinkedHashMap()
    ) : SharedPreferences {

        var applyCount: Int = 0
            private set

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val updates = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                put(key, values?.let { LinkedHashSet(it) })
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    updates.remove(key)
                    removals.add(key)
                }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                updates.clear()
                removals.clear()
                return this
            }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) {
                    removals.remove(key)
                    updates[key] = value
                }
                return this
            }

            private fun applyChanges() {
                if (clearAll) data.clear()
                for (k in removals) data.remove(k)
                for ((k, v) in updates) {
                    if (v == null) data.remove(k) else data[k] = v
                }
                applyCount++
            }
        }
    }
}
