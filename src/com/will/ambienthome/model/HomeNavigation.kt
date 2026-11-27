package com.will.ambienthome.model

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Чистая (без Android View/Canvas) конечная машина навигации главного экрана Ambient Home 3.0.0.
 * Покрывает DPAD (H4a), BACK из топ-бара/медиа (H4b), HOME из временной Сетки / Ambient / Топ-бара (H4c),
 * air-mouse стрелки дока (H4d), динамический топ-бар и пилюлю «Сейчас играет» (ROW_MEDIA).
 */
object HomeNavigation {

    const val ROW_TOP = 0
    const val ROW_CONTENT = 1
    const val ROW_MEDIA = 2

    const val MEDIA_BTN_OPEN = 0
    const val MEDIA_BTN_PLAY_PAUSE = 1
    const val MEDIA_BTN_NEXT = 2
    const val MEDIA_BTN_COUNT = 3

    const val GRID_COLS = 6
    const val GRID_ROWS = 3
    const val GRID_PER_PAGE = GRID_COLS * GRID_ROWS

    data class NavState(
        val mode: HomeMode = HomeMode.DOCK,
        val openedGridFromDock: Boolean = false,
        val row: Int = ROW_CONTENT,
        val topFocus: Int = 0,
        val focusIndex: Int = 0,
        val gridPage: Int = 0,
        val ambientActive: Boolean = false,
        val mediaFocus: Int = MEDIA_BTN_PLAY_PAUSE
    )

    data class MoveResult(
        val state: NavState,
        val focusChanged: Boolean,
        val animateFocus: Boolean
    )

    data class BackResult(
        val state: NavState,
        val persistDockMode: Boolean,
        val focusChanged: Boolean
    )

    fun clamp(
        state: NavState,
        appCount: Int,
        topCount: Int = TopAction.entries.size,
        hasMedia: Boolean = false
    ): NavState {
        val safeCount = appCount.coerceAtLeast(0)
        val maxIdx = if (state.mode == HomeMode.DOCK) dockTotal(safeCount) - 1 else safeCount - 1
        val clampedFocus = if (maxIdx < 0) 0 else state.focusIndex.coerceIn(0, maxIdx)
        val clampedPage = if (safeCount == 0) {
            0
        } else {
            (clampedFocus / GRID_PER_PAGE).coerceIn(0, gridPageCount(safeCount) - 1)
        }
        val maxTop = (topCount.coerceAtLeast(1) - 1)
        val clampedTop = state.topFocus.coerceIn(0, maxTop)
        val clampedRow = if (state.row == ROW_MEDIA && (!hasMedia || state.mode != HomeMode.DOCK)) {
            ROW_CONTENT
        } else {
            state.row
        }
        val clampedMedia = state.mediaFocus.coerceIn(0, MEDIA_BTN_COUNT - 1)
        return state.copy(
            row = clampedRow,
            focusIndex = clampedFocus,
            gridPage = clampedPage,
            topFocus = clampedTop,
            mediaFocus = clampedMedia
        )
    }

    fun computeTargetDockScroll(focusIndex: Int, appCount: Int, maxVisible: Int): Float {
        val n = dockTotal(appCount.coerceAtLeast(0))
        if (n <= maxVisible) return (n - 1) / 2f
        val halfSpan = (maxVisible - 1) / 2f
        return focusIndex.toFloat().coerceIn(halfSpan, (n - 1) - halfSpan)
    }

    fun move(
        state: NavState,
        dx: Int,
        dy: Int,
        appCount: Int,
        topCount: Int = TopAction.entries.size,
        hasMedia: Boolean = false
    ): MoveResult {
        val safeCount = appCount.coerceAtLeast(0)
        val safeTop = topCount.coerceAtLeast(1)
        val mediaAllowed = hasMedia && state.mode == HomeMode.DOCK
        if (state.row == ROW_TOP) {
            return moveInTopBar(state, dx, dy, safeTop, mediaAllowed)
        }
        if (state.row == ROW_MEDIA) {
            return moveInMediaBar(state, dx, dy, mediaAllowed)
        }
        return if (state.mode == HomeMode.DOCK) {
            moveInDock(state, dx, dy, safeCount, mediaAllowed)
        } else {
            moveInGrid(state, dx, dy, safeCount)
        }
    }

    fun onBack(state: NavState, appCount: Int, topCount: Int = TopAction.entries.size): BackResult {
        if (state.ambientActive) {
            return BackResult(
                state = state.copy(ambientActive = false),
                persistDockMode = false,
                focusChanged = false
            )
        }
        if (state.row == ROW_TOP || state.row == ROW_MEDIA) {
            return BackResult(
                state = state.copy(row = ROW_CONTENT),
                persistDockMode = false,
                focusChanged = true
            )
        }
        if (state.mode == HomeMode.GRID) {
            val persist = !state.openedGridFromDock
            val next = clamp(
                state.copy(mode = HomeMode.DOCK, openedGridFromDock = false, row = ROW_CONTENT),
                appCount,
                topCount
            )
            return BackResult(state = next, persistDockMode = persist, focusChanged = true)
        }
        return BackResult(state = state, persistDockMode = false, focusChanged = false)
    }

    fun onHomePressed(
        state: NavState,
        persistedMode: HomeMode,
        appCount: Int,
        topCount: Int = TopAction.entries.size
    ): NavState {
        var next = state.copy(ambientActive = false)
        if (next.row == ROW_TOP || next.row == ROW_MEDIA) {
            next = next.copy(row = ROW_CONTENT)
        }
        if (next.mode == HomeMode.GRID && next.openedGridFromDock) {
            next = next.copy(mode = persistedMode, openedGridFromDock = false, row = ROW_CONTENT)
        }
        return clamp(next, appCount, topCount)
    }

    fun scrollDockChevron(
        state: NavState,
        dir: Int,
        currentDockScroll: Float,
        appCount: Int,
        maxVisible: Int
    ): MoveResult {
        val safeCount = appCount.coerceAtLeast(0)
        val n = dockTotal(safeCount)
        if (dir == 0 || n <= maxVisible) {
            return MoveResult(state, focusChanged = false, animateFocus = false)
        }
        val targetIdx = if (dir > 0) {
            val pastClamp = floor(currentDockScroll.toDouble() + 0.001).toInt() + 1
            maxOf(state.focusIndex + 1, pastClamp).coerceAtMost(n - 1)
        } else {
            val pastClamp = ceil(currentDockScroll.toDouble() - 0.001).toInt() - 1
            minOf(state.focusIndex - 1, pastClamp).coerceAtLeast(0)
        }
        val next = state.copy(row = ROW_CONTENT, focusIndex = targetIdx)
        val changed = next.focusIndex != state.focusIndex || next.row != state.row
        return MoveResult(next, focusChanged = changed, animateFocus = changed)
    }

    fun gridPageCount(appCount: Int): Int =
        ((appCount.coerceAtLeast(0) + GRID_PER_PAGE - 1) / GRID_PER_PAGE).coerceAtLeast(1)

    private fun dockTotal(appCount: Int): Int = appCount + 1

    private fun moveInTopBar(
        state: NavState,
        dx: Int,
        dy: Int,
        topCount: Int,
        mediaAllowed: Boolean
    ): MoveResult {
        if (dy > 0) {
            val targetRow = if (mediaAllowed) ROW_MEDIA else ROW_CONTENT
            return MoveResult(
                state.copy(row = targetRow, mediaFocus = MEDIA_BTN_PLAY_PAUSE),
                focusChanged = true,
                animateFocus = targetRow == ROW_CONTENT
            )
        }
        if (dx != 0) {
            val nextTop = (state.topFocus + dx).coerceIn(0, topCount - 1)
            val changed = nextTop != state.topFocus
            return MoveResult(state.copy(topFocus = nextTop), focusChanged = changed, animateFocus = false)
        }
        return MoveResult(state, focusChanged = false, animateFocus = false)
    }

    private fun moveInMediaBar(state: NavState, dx: Int, dy: Int, mediaAllowed: Boolean): MoveResult {
        if (!mediaAllowed) {
            return MoveResult(state.copy(row = ROW_CONTENT), focusChanged = true, animateFocus = true)
        }
        if (dy < 0) {
            return MoveResult(state.copy(row = ROW_TOP), focusChanged = true, animateFocus = false)
        }
        if (dy > 0) {
            return MoveResult(state.copy(row = ROW_CONTENT), focusChanged = true, animateFocus = true)
        }
        if (dx != 0) {
            val next = (state.mediaFocus + dx).coerceIn(0, MEDIA_BTN_COUNT - 1)
            val changed = next != state.mediaFocus
            return MoveResult(state.copy(mediaFocus = next), focusChanged = changed, animateFocus = false)
        }
        return MoveResult(state, focusChanged = false, animateFocus = false)
    }

    private fun moveInDock(
        state: NavState,
        dx: Int,
        dy: Int,
        appCount: Int,
        mediaAllowed: Boolean
    ): MoveResult {
        if (dy < 0) {
            val targetRow = if (mediaAllowed) ROW_MEDIA else ROW_TOP
            return MoveResult(
                state.copy(row = targetRow, mediaFocus = MEDIA_BTN_PLAY_PAUSE),
                focusChanged = true,
                animateFocus = false
            )
        }
        if (dx != 0) {
            val n = dockTotal(appCount)
            if (n <= 1) return MoveResult(state, focusChanged = false, animateFocus = false)
            val nextIdx = ((state.focusIndex + dx) % n + n) % n
            return MoveResult(state.copy(focusIndex = nextIdx), focusChanged = true, animateFocus = true)
        }
        return MoveResult(state, focusChanged = false, animateFocus = false)
    }

    private fun moveInGrid(state: NavState, dx: Int, dy: Int, appCount: Int): MoveResult {
        if (appCount <= 0) {
            return if (dy < 0) {
                MoveResult(state.copy(row = ROW_TOP), focusChanged = true, animateFocus = false)
            } else {
                MoveResult(state, focusChanged = false, animateFocus = false)
            }
        }
        val local = (state.focusIndex - state.gridPage * GRID_PER_PAGE).coerceIn(0, GRID_PER_PAGE - 1)
        if (dy < 0 && local / GRID_COLS == 0) {
            return moveUpFromGridTopRow(state, local % GRID_COLS, appCount)
        }
        if (dy > 0 && state.focusIndex / GRID_COLS >= (appCount - 1) / GRID_COLS) {
            return MoveResult(state, focusChanged = false, animateFocus = false)
        }
        val step = if (dx != 0) dx else dy * GRID_COLS
        val next = (state.focusIndex + step).coerceIn(0, appCount - 1)
        if (next == state.focusIndex) {
            return MoveResult(state, focusChanged = false, animateFocus = false)
        }
        return MoveResult(
            state = state.copy(focusIndex = next, gridPage = next / GRID_PER_PAGE),
            focusChanged = true,
            animateFocus = true
        )
    }

    private fun moveUpFromGridTopRow(state: NavState, col: Int, appCount: Int): MoveResult {
        if (state.gridPage <= 0) {
            return MoveResult(state.copy(row = ROW_TOP), focusChanged = true, animateFocus = false)
        }
        val prevPage = state.gridPage - 1
        val prevIdx = (prevPage * GRID_PER_PAGE + (GRID_ROWS - 1) * GRID_COLS + col)
            .coerceAtMost(appCount - 1)
        return MoveResult(
            state = state.copy(gridPage = prevPage, focusIndex = prevIdx),
            focusChanged = true,
            animateFocus = true
        )
    }
}
