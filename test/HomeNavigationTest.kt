package com.will.ambienthome.model

/**
 * Автономный набор Kotlin unit-тестов для HomeNavigation и per-source AppearanceState (Ambient Home 3.0.0).
 * Запускается автоматически при каждой сборке launcher/build.ps1.
 */
object HomeNavigationTest {

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
        testGridBottomRowDownDoesNotJumpSideways()
        testGridUpperRowDownSnapsToRaggedBottomRow()
        testGridMultiPageUpAndDownNavigation()
        testGridEmptyAppsNavigation()
        testBackFromGridTopBarReturnsToGrid()
        testBackFromGridContentRespectsTemporaryFlag()
        testBackFromAmbientAndDockTopBar()
        testHomePressedInTempGridAmbientAndTopBar()
        testDockDpadWrapAndTopBarTransitions()
        testDockChevronScrollAdvancesEveryClickOddMaxVis()
        testDockChevronScrollAdvancesEveryClickEvenMaxVis()
        testDockChevronNoOpWhenFitsOnScreen()
        testPerSourceWallpaperIndicesPreserved()
        testClampBounds()
        testGridPageCountAndForcedPrimaryGridTransition()
        testGridHorizontalPageCrossing()
        testDockChevronDirZeroAndEdgeClamp()
        testHomePressedTempGridWhenPersistedModeIsGrid()
        testDynamicTopCountAndMediaRowNavigation()

        println("HomeNavigationTest: passed=$passed failed=$failed")
        if (failed > 0) {
            System.exit(1)
        }
    }

    private fun testGridBottomRowDownDoesNotJumpSideways() {
        // 14 приложений: строка 0 (0..5), строка 1 (6..11), неполная нижняя строка 2 (12, 13)
        val s12 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 12, gridPage = 0)
        val r12 = HomeNavigation.move(s12, dx = 0, dy = 1, appCount = 14)
        checkEq("H4a: bottom-row col 0 DPAD_DOWN stays at 12", 12, r12.state.focusIndex)
        checkEq("H4a: bottom-row col 0 focusChanged=false", false, r12.focusChanged)

        val s13 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 13, gridPage = 0)
        val r13 = HomeNavigation.move(s13, dx = 0, dy = 1, appCount = 14)
        checkEq("H4a: bottom-row last item DPAD_DOWN stays at 13", 13, r13.state.focusIndex)
        checkEq("H4a: bottom-row last item focusChanged=false", false, r13.focusChanged)

        // Единственная строка (3 приложения)
        val sSingle = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 0, gridPage = 0)
        val rSingle = HomeNavigation.move(sSingle, dx = 0, dy = 1, appCount = 3)
        checkEq("H4a: single-row grid DPAD_DOWN stays at 0", 0, rSingle.state.focusIndex)
        checkEq("H4a: single-row grid focusChanged=false", false, rSingle.focusChanged)
    }

    private fun testGridUpperRowDownSnapsToRaggedBottomRow() {
        // 14 приложений: переход со строки 1, колонки 4 (индекс 10) вниз -> должен прилипнуть к 13
        val s10 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 10, gridPage = 0)
        val r10 = HomeNavigation.move(s10, dx = 0, dy = 1, appCount = 14)
        checkEq("H4a: upper row col 4 snaps to ragged last item 13", 13, r10.state.focusIndex)
        checkEq("H4a: upper row snap focusChanged=true", true, r10.focusChanged)

        // Переход со строки 1, колонки 0 (индекс 6) вниз -> индекс 12
        val s6 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 6, gridPage = 0)
        val r6 = HomeNavigation.move(s6, dx = 0, dy = 1, appCount = 14)
        checkEq("H4a: upper row col 0 moves straight down to 12", 12, r6.state.focusIndex)
    }

    private fun testGridMultiPageUpAndDownNavigation() {
        // 20 приложений: стр. 0 (0..17), стр. 1 (18..19)
        val s15 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 15, gridPage = 0)
        val rDown = HomeNavigation.move(s15, dx = 0, dy = 1, appCount = 20)
        checkEq("multi-page down crosses to page 1 item 19", 19, rDown.state.focusIndex)
        checkEq("multi-page down updates gridPage=1", 1, rDown.state.gridPage)

        // На стр. 1 (последняя строка 18..19) DPAD_DOWN не прыгает с 18 на 19
        val s18 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 18, gridPage = 1)
        val rBottom = HomeNavigation.move(s18, dx = 0, dy = 1, appCount = 20)
        checkEq("H4a: page 1 bottom-row col 0 stays at 18", 18, rBottom.state.focusIndex)
        checkEq("H4a: page 1 bottom-row focusChanged=false", false, rBottom.focusChanged)

        // Стр. 1 верхняя строка (индекс 19, col 1) -> DPAD_UP возвращает на стр. 0, строка 2, col 1 (индекс 13)
        val s19 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 19, gridPage = 1)
        val rUp = HomeNavigation.move(s19, dx = 0, dy = -1, appCount = 20)
        checkEq("multi-page up returns to page 0", 0, rUp.state.gridPage)
        checkEq("multi-page up preserves col 1 -> index 13", 13, rUp.state.focusIndex)

        // Стр. 0 верхняя строка -> DPAD_UP переводит фокус в ROW_TOP
        val s2 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 2, gridPage = 0)
        val rTop = HomeNavigation.move(s2, dx = 0, dy = -1, appCount = 20)
        checkEq("page 0 top row DPAD_UP enters ROW_TOP", HomeNavigation.ROW_TOP, rTop.state.row)
    }

    private fun testGridEmptyAppsNavigation() {
        val empty = HomeNavigation.NavState(mode = HomeMode.GRID, row = HomeNavigation.ROW_CONTENT, focusIndex = 0)
        val rUp = HomeNavigation.move(empty, dx = 0, dy = -1, appCount = 0)
        checkEq("empty grid DPAD_UP enters ROW_TOP", HomeNavigation.ROW_TOP, rUp.state.row)
        val rDown = HomeNavigation.move(empty, dx = 0, dy = 1, appCount = 0)
        checkEq("empty grid DPAD_DOWN is no-op", false, rDown.focusChanged)
    }

    private fun testBackFromGridTopBarReturnsToGrid() {
        // H4b: в Сетке фокус на топ-баре -> BACK возвращает в Сетку и НЕ пишет DOCK
        val inGridTop = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = false,
            row = HomeNavigation.ROW_TOP,
            topFocus = 1,
            focusIndex = 5
        )
        val res1 = HomeNavigation.onBack(inGridTop, appCount = 12)
        checkEq("H4b: BACK from Grid top bar stays in GRID", HomeMode.GRID, res1.state.mode)
        checkEq("H4b: BACK from Grid top bar returns to ROW_CONTENT", HomeNavigation.ROW_CONTENT, res1.state.row)
        checkEq("H4b: BACK from Grid top bar preserves focusIndex", 5, res1.state.focusIndex)
        checkEq("H4b: BACK from Grid top bar does NOT persist DOCK", false, res1.persistDockMode)

        // Повторный BACK теперь закрывает основную Сетку -> DOCK + persist=true
        val res2 = HomeNavigation.onBack(res1.state, appCount = 12)
        checkEq("H4b: second BACK exits Grid to DOCK", HomeMode.DOCK, res2.state.mode)
        checkEq("H4b: second BACK persists DOCK for primary Grid", true, res2.persistDockMode)
    }

    private fun testBackFromGridContentRespectsTemporaryFlag() {
        val tempGrid = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = true,
            row = HomeNavigation.ROW_CONTENT,
            focusIndex = 4
        )
        val res = HomeNavigation.onBack(tempGrid, appCount = 12)
        checkEq("temp Grid BACK returns to DOCK", HomeMode.DOCK, res.state.mode)
        checkEq("temp Grid BACK clears openedGridFromDock", false, res.state.openedGridFromDock)
        checkEq("temp Grid BACK does not persist DOCK", false, res.persistDockMode)
    }

    private fun testBackFromAmbientAndDockTopBar() {
        val amb = HomeNavigation.NavState(mode = HomeMode.GRID, ambientActive = true)
        val rAmb = HomeNavigation.onBack(amb, appCount = 10)
        checkEq("BACK wakes Ambient", false, rAmb.state.ambientActive)
        checkEq("BACK waking Ambient preserves mode", HomeMode.GRID, rAmb.state.mode)

        val dockTop = HomeNavigation.NavState(mode = HomeMode.DOCK, row = HomeNavigation.ROW_TOP, topFocus = 2)
        val rDockTop = HomeNavigation.onBack(dockTop, appCount = 10)
        checkEq("BACK from Dock top bar returns to content", HomeNavigation.ROW_CONTENT, rDockTop.state.row)
        checkEq("BACK from Dock top bar focusChanged=true", true, rDockTop.focusChanged)
    }

    private fun testHomePressedInTempGridAmbientAndTopBar() {
        // H4c: временная Сетка из Дока + топ-бар + Ambient -> HOME всё сбрасывает в DOCK content
        val messy = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = true,
            row = HomeNavigation.ROW_TOP,
            topFocus = 2,
            focusIndex = 3,
            ambientActive = true
        )
        val res = HomeNavigation.onHomePressed(messy, persistedMode = HomeMode.DOCK, appCount = 10)
        checkEq("H4c: HOME wakes Ambient", false, res.ambientActive)
        checkEq("H4c: HOME returns from TopBar to Content", HomeNavigation.ROW_CONTENT, res.row)
        checkEq("H4c: HOME closes temp Grid to persisted DOCK", HomeMode.DOCK, res.mode)
        checkEq("H4c: HOME clears openedGridFromDock", false, res.openedGridFromDock)

        // Основная Сетка (openedGridFromDock=false) по HOME остаётся Сеткой
        val primaryGrid = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = false,
            row = HomeNavigation.ROW_TOP,
            ambientActive = true
        )
        val resPrimary = HomeNavigation.onHomePressed(primaryGrid, persistedMode = HomeMode.GRID, appCount = 10)
        checkEq("H4c: HOME keeps primary GRID", HomeMode.GRID, resPrimary.mode)
        checkEq("H4c: HOME leaves TopBar in primary GRID", HomeNavigation.ROW_CONTENT, resPrimary.row)
        checkEq("H4c: HOME wakes Ambient in primary GRID", false, resPrimary.ambientActive)
    }

    private fun testDockDpadWrapAndTopBarTransitions() {
        // 5 приложений => dockTotal = 6 (индексы 0..5)
        val s0 = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0)
        val rLeft = HomeNavigation.move(s0, dx = -1, dy = 0, appCount = 5)
        checkEq("Dock LEFT wraps 0 -> 5 (All Apps)", 5, rLeft.state.focusIndex)

        val rRight = HomeNavigation.move(rLeft.state, dx = 1, dy = 0, appCount = 5)
        checkEq("Dock RIGHT wraps 5 -> 0", 0, rRight.state.focusIndex)

        // Большой отрицательный dx (< -n) обязан заворачиваться в [0, n-1], а не давать отрицательный индекс
        val rBigNeg = HomeNavigation.move(HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0), dx = -5, dy = 0, appCount = 2)
        checkEq("Dock large negative dx=-5 wraps safely into [0..2]", 1, rBigNeg.state.focusIndex)
        val rZeroApps = HomeNavigation.move(HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0), dx = -1, dy = 0, appCount = 0)
        checkEq("Dock with 0 apps (only AllApps) is no-op", false, rZeroApps.focusChanged)

        val rUp = HomeNavigation.move(s0, dx = 0, dy = -1, appCount = 5)
        checkEq("Dock UP enters ROW_TOP", HomeNavigation.ROW_TOP, rUp.state.row)

        val rTopRight = HomeNavigation.move(rUp.state, dx = 1, dy = 0, appCount = 5, topCount = 3)
        checkEq("TopBar RIGHT moves 0 -> 1", 1, rTopRight.state.topFocus)
        val rTopClamp = HomeNavigation.move(rTopRight.state.copy(topFocus = 2), dx = 1, dy = 0, appCount = 5, topCount = 3)
        checkEq("TopBar RIGHT clamps at 2", 2, rTopClamp.state.topFocus)
        checkEq("TopBar clamped RIGHT focusChanged=false", false, rTopClamp.focusChanged)
    }

    private fun testDockChevronScrollAdvancesEveryClickOddMaxVis() {
        // 14 приложений => n=15, maxVis=9, halfSpan=4.0, minScroll=4.0, maxScroll=10.0
        val appCount = 14
        val maxVis = 9
        var st = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0)
        var scroll = HomeNavigation.computeTargetDockScroll(st.focusIndex, appCount, maxVis)
        checkEq("initial scroll clamped at 4.0", 4.0f, scroll)

        // H4d: 1-й клик по '>' из focusIndex=0 обязан сразу сдвинуть ленту (4.0 -> 5.0)!
        val click1 = HomeNavigation.scrollDockChevron(st, dir = 1, currentDockScroll = scroll, appCount = appCount, maxVisible = maxVis)
        val scroll1 = HomeNavigation.computeTargetDockScroll(click1.state.focusIndex, appCount, maxVis)
        checkEq("H4d: first '>' click jumps past clamp to idx=5", 5, click1.state.focusIndex)
        check("H4d: first '>' click visibly increases targetDockScroll ($scroll -> $scroll1)", scroll1 > scroll)

        // 2-й клик подряд пока анимация ещё не доехала (currentDockScroll=4.4)
        val click2 = HomeNavigation.scrollDockChevron(click1.state, dir = 1, currentDockScroll = 4.4f, appCount = appCount, maxVisible = maxVis)
        val scroll2 = HomeNavigation.computeTargetDockScroll(click2.state.focusIndex, appCount, maxVis)
        checkEq("H4d: rapid '>' click while animating advances to idx=6", 6, click2.state.focusIndex)
        check("H4d: rapid '>' click increases scroll ($scroll1 -> $scroll2)", scroll2 > scroll1)

        // Симметрия у правого края: focusIndex=14, scroll=10.0, клик по '<' обязан сразу сдвинуть (10.0 -> 9.0)!
        val rightEdge = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 14)
        val rightScroll = HomeNavigation.computeTargetDockScroll(14, appCount, maxVis)
        checkEq("right edge scroll clamped at 10.0", 10.0f, rightScroll)
        val leftClick = HomeNavigation.scrollDockChevron(rightEdge, dir = -1, currentDockScroll = rightScroll, appCount = appCount, maxVisible = maxVis)
        val leftScroll = HomeNavigation.computeTargetDockScroll(leftClick.state.focusIndex, appCount, maxVis)
        checkEq("H4d: first '<' click from right edge jumps to idx=9", 9, leftClick.state.focusIndex)
        check("H4d: first '<' click visibly decreases scroll ($rightScroll -> $leftScroll)", leftScroll < rightScroll)
    }

    private fun testDockChevronScrollAdvancesEveryClickEvenMaxVis() {
        // 13 приложений => n=14, maxVis=8, halfSpan=3.5, minScroll=3.5, maxScroll=9.5
        val appCount = 13
        val maxVis = 8
        val start = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0)
        val s0 = HomeNavigation.computeTargetDockScroll(0, appCount, maxVis)
        checkEq("even maxVis minScroll=3.5", 3.5f, s0)

        val r1 = HomeNavigation.scrollDockChevron(start, dir = 1, currentDockScroll = s0, appCount = appCount, maxVisible = maxVis)
        val s1 = HomeNavigation.computeTargetDockScroll(r1.state.focusIndex, appCount, maxVis)
        checkEq("H4d even maxVis '>' jumps to idx=4", 4, r1.state.focusIndex)
        check("H4d even maxVis '>' visibly advances scroll ($s0 -> $s1)", s1 > s0)

        val end = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 13)
        val sEnd = HomeNavigation.computeTargetDockScroll(13, appCount, maxVis)
        val l1 = HomeNavigation.scrollDockChevron(end, dir = -1, currentDockScroll = sEnd, appCount = appCount, maxVisible = maxVis)
        val sL1 = HomeNavigation.computeTargetDockScroll(l1.state.focusIndex, appCount, maxVis)
        checkEq("H4d even maxVis '<' jumps to idx=9", 9, l1.state.focusIndex)
        check("H4d even maxVis '<' visibly decreases scroll ($sEnd -> $sL1)", sL1 < sEnd)
    }

    private fun testDockChevronNoOpWhenFitsOnScreen() {
        val st = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 2)
        val res = HomeNavigation.scrollDockChevron(st, dir = 1, currentDockScroll = 2.5f, appCount = 5, maxVisible = 9)
        checkEq("chevron no-op when dock fits on screen", false, res.focusChanged)
        checkEq("chevron no-op preserves focusIndex", 2, res.state.focusIndex)
    }

    private fun testPerSourceWallpaperIndicesPreserved() {
        // M2c: смена источника BUILTIN -> GRADIENT -> BUILTIN не затирает индекс BUILTIN
        var ap = AppearanceState()
            .withWallpaperIndex(WallpaperSource.BUILTIN, 4)
            .withWallpaperIndex(WallpaperSource.GRADIENT, 2)
            .withWallpaperIndex(WallpaperSource.CUSTOM, 7)
        checkEq("M2c: BUILTIN index=4", 4, ap.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("M2c: GRADIENT index=2", 2, ap.wallpaperIndexFor(WallpaperSource.GRADIENT))
        checkEq("M2c: CUSTOM index=7", 7, ap.wallpaperIndexFor(WallpaperSource.CUSTOM))
        checkEq("M2c: ALL default index=0", 0, ap.wallpaperIndexFor(WallpaperSource.ALL))

        ap = ap.copy(wallpaperSource = WallpaperSource.GRADIENT).withWallpaperIndex(WallpaperSource.GRADIENT, 0)
        checkEq("M2c: updating GRADIENT does not clobber BUILTIN", 4, ap.wallpaperIndexFor(WallpaperSource.BUILTIN))
        checkEq("M2c: negative index clamped to 0", 0, ap.withWallpaperIndex(WallpaperSource.ALL, -3).wallpaperIndexFor(WallpaperSource.ALL))
    }

    private fun testClampBounds() {
        val bad = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 99, gridPage = 5, topFocus = 9)
        val clamped = HomeNavigation.clamp(bad, appCount = 8, topCount = 3)
        checkEq("clamp grid focusIndex", 7, clamped.focusIndex)
        checkEq("clamp gridPage", 0, clamped.gridPage)
        checkEq("clamp topFocus", 2, clamped.topFocus)

        val emptyGrid = HomeNavigation.clamp(bad, appCount = 0)
        checkEq("clamp empty grid focusIndex=0", 0, emptyGrid.focusIndex)
        checkEq("clamp empty grid gridPage=0", 0, emptyGrid.gridPage)
    }

    private fun testGridPageCountAndForcedPrimaryGridTransition() {
        checkEq("gridPageCount(0) == 1", 1, HomeNavigation.gridPageCount(0))
        checkEq("gridPageCount(-3) == 1", 1, HomeNavigation.gridPageCount(-3))
        checkEq("gridPageCount(18) == 1", 1, HomeNavigation.gridPageCount(18))
        checkEq("gridPageCount(19) == 2", 2, HomeNavigation.gridPageCount(19))

        // Сценарий: открыли Сетку временно из Дока (openedGridFromDock=true), затем в настройках
        // переключили основной режим на GRID (forceMode -> openedGridFromDock=false).
        val promotedToPrimary = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = false,
            row = HomeNavigation.ROW_CONTENT,
            focusIndex = 2
        )
        val afterHome = HomeNavigation.onHomePressed(promotedToPrimary, persistedMode = HomeMode.GRID, appCount = 10)
        checkEq("promoted primary GRID stays GRID on HOME", HomeMode.GRID, afterHome.mode)
        val afterBack = HomeNavigation.onBack(promotedToPrimary, appCount = 10)
        checkEq("promoted primary GRID persists DOCK on BACK", true, afterBack.persistDockMode)
    }

    private fun testGridHorizontalPageCrossing() {
        // 20 приложений: стр. 0 (0..17), стр. 1 (18..19)
        val s17 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 17, gridPage = 0)
        val rRight = HomeNavigation.move(s17, dx = 1, dy = 0, appCount = 20)
        checkEq("Grid horizontal right crosses 17 -> 18", 18, rRight.state.focusIndex)
        checkEq("Grid horizontal right updates gridPage 0 -> 1", 1, rRight.state.gridPage)
        checkEq("Grid horizontal right focusChanged=true", true, rRight.focusChanged)
        checkEq("Grid horizontal right animateFocus=true", true, rRight.animateFocus)

        val rLeft = HomeNavigation.move(rRight.state, dx = -1, dy = 0, appCount = 20)
        checkEq("Grid horizontal left crosses 18 -> 17", 17, rLeft.state.focusIndex)
        checkEq("Grid horizontal left updates gridPage 1 -> 0", 0, rLeft.state.gridPage)
        checkEq("Grid horizontal left focusChanged=true", true, rLeft.focusChanged)

        val s0 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 0, gridPage = 0)
        val rStartClamp = HomeNavigation.move(s0, dx = -1, dy = 0, appCount = 20)
        checkEq("Grid horizontal left clamps at 0", 0, rStartClamp.state.focusIndex)
        checkEq("Grid horizontal left clamp focusChanged=false", false, rStartClamp.focusChanged)

        val s19 = HomeNavigation.NavState(mode = HomeMode.GRID, focusIndex = 19, gridPage = 1)
        val rEndClamp = HomeNavigation.move(s19, dx = 1, dy = 0, appCount = 20)
        checkEq("Grid horizontal right clamps at 19", 19, rEndClamp.state.focusIndex)
        checkEq("Grid horizontal right clamp focusChanged=false", false, rEndClamp.focusChanged)
    }

    private fun testDockChevronDirZeroAndEdgeClamp() {
        val appCount = 14
        val maxVis = 9
        val mid = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 5)
        val rZero = HomeNavigation.scrollDockChevron(mid, dir = 0, currentDockScroll = 5.0f, appCount = appCount, maxVisible = maxVis)
        checkEq("chevron dir=0 focusChanged=false", false, rZero.focusChanged)
        checkEq("chevron dir=0 animateFocus=false", false, rZero.animateFocus)
        checkEq("chevron dir=0 preserves state", mid, rZero.state)

        val atRightEnd = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 14)
        val rRightClamp = HomeNavigation.scrollDockChevron(atRightEnd, dir = 1, currentDockScroll = 10.0f, appCount = appCount, maxVisible = maxVis)
        checkEq("chevron right at last item clamps to 14", 14, rRightClamp.state.focusIndex)
        checkEq("chevron right at last item focusChanged=false", false, rRightClamp.focusChanged)

        val atLeftEnd = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 0)
        val rLeftClamp = HomeNavigation.scrollDockChevron(atLeftEnd, dir = -1, currentDockScroll = 4.0f, appCount = appCount, maxVisible = maxVis)
        checkEq("chevron left at first item clamps to 0", 0, rLeftClamp.state.focusIndex)
        checkEq("chevron left at first item focusChanged=false", false, rLeftClamp.focusChanged)

        val fromTopAtLeft = HomeNavigation.NavState(mode = HomeMode.DOCK, row = HomeNavigation.ROW_TOP, focusIndex = 0)
        val rFromTop = HomeNavigation.scrollDockChevron(fromTopAtLeft, dir = -1, currentDockScroll = 4.0f, appCount = appCount, maxVisible = maxVis)
        checkEq("chevron from ROW_TOP returns to ROW_CONTENT", HomeNavigation.ROW_CONTENT, rFromTop.state.row)
        checkEq("chevron from ROW_TOP focusChanged=true", true, rFromTop.focusChanged)
    }

    private fun testHomePressedTempGridWhenPersistedModeIsGrid() {
        val tempGrid = HomeNavigation.NavState(
            mode = HomeMode.GRID,
            openedGridFromDock = true,
            row = HomeNavigation.ROW_TOP,
            topFocus = 1,
            focusIndex = 3,
            ambientActive = true
        )
        val res = HomeNavigation.onHomePressed(tempGrid, persistedMode = HomeMode.GRID, appCount = 12)
        checkEq("HOME with openedGridFromDock=true & persistedMode=GRID stays GRID", HomeMode.GRID, res.mode)
        checkEq("HOME with persistedMode=GRID clears openedGridFromDock", false, res.openedGridFromDock)
        checkEq("HOME with persistedMode=GRID exits ROW_TOP", HomeNavigation.ROW_CONTENT, res.row)
        checkEq("HOME with persistedMode=GRID wakes Ambient", false, res.ambientActive)
        checkEq("HOME with persistedMode=GRID preserves focusIndex", 3, res.focusIndex)

        val back = HomeNavigation.onBack(res, appCount = 12)
        checkEq("subsequent BACK now persists DOCK because openedGridFromDock was cleared", true, back.persistDockMode)
    }

    private fun testDynamicTopCountAndMediaRowNavigation() {
        val sDock = HomeNavigation.NavState(mode = HomeMode.DOCK, focusIndex = 1)
        val toMedia = HomeNavigation.move(sDock, dx = 0, dy = -1, appCount = 5, topCount = 4, hasMedia = true)
        checkEq("Dock UP enters ROW_MEDIA when media active", HomeNavigation.ROW_MEDIA, toMedia.state.row)
        checkEq("Default mediaFocus is PLAY_PAUSE", HomeNavigation.MEDIA_BTN_PLAY_PAUSE, toMedia.state.mediaFocus)

        val left = HomeNavigation.move(toMedia.state, dx = -1, dy = 0, appCount = 5, topCount = 4, hasMedia = true)
        checkEq("Media LEFT moves to OPEN", HomeNavigation.MEDIA_BTN_OPEN, left.state.mediaFocus)
        val right = HomeNavigation.move(toMedia.state, dx = 1, dy = 0, appCount = 5, topCount = 4, hasMedia = true)
        checkEq("Media RIGHT moves to NEXT", HomeNavigation.MEDIA_BTN_NEXT, right.state.mediaFocus)

        val toTop = HomeNavigation.move(toMedia.state, dx = 0, dy = -1, appCount = 5, topCount = 6, hasMedia = true)
        checkEq("Media UP enters ROW_TOP", HomeNavigation.ROW_TOP, toTop.state.row)
        val backMedia = HomeNavigation.move(toTop.state, dx = 0, dy = 1, appCount = 5, topCount = 6, hasMedia = true)
        checkEq("TopBar DOWN enters ROW_MEDIA when active", HomeNavigation.ROW_MEDIA, backMedia.state.row)
        val backDock = HomeNavigation.move(backMedia.state, dx = 0, dy = 1, appCount = 5, topCount = 6, hasMedia = true)
        checkEq("Media DOWN returns to ROW_CONTENT", HomeNavigation.ROW_CONTENT, backDock.state.row)

        val backFromMedia = HomeNavigation.onBack(toMedia.state, appCount = 5, topCount = 6)
        checkEq("BACK from ROW_MEDIA returns to ROW_CONTENT", HomeNavigation.ROW_CONTENT, backFromMedia.state.row)
        val clampedGone = HomeNavigation.clamp(toMedia.state, appCount = 5, topCount = 4, hasMedia = false)
        checkEq("clamp exits ROW_MEDIA when media disappears", HomeNavigation.ROW_CONTENT, clampedGone.row)
    }
}
