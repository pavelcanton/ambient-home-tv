package com.will.ambienthome.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.NightSchedule
import com.will.ambienthome.model.WallpaperSource
import java.util.Calendar

/**
 * Контроллер оркестрации обоев (выделен из MainActivity для Deslop).
 * Отвечает за коалесцинг запросов, перепекание при смене ночного режима/сигнатуры файлов,
 * авторотацию по таймеру и обновление «Фото дня» (Bing/Wikimedia) при активном HOME.
 */
class WallpaperController(
    private val appContext: Context,
    private val store: LauncherStore,
    private val mainHandler: Handler,
    private val ioRunner: (() -> Unit) -> Boolean,
    private val netRunner: (() -> Unit) -> Boolean = ioRunner,
    private val onWallpaperReady: (Bitmap, String) -> Unit
) {

    private companion object {
        const val TAG = "AmbientHome"
    }

    private data class WpRequestKey(
        val source: WallpaperSource,
        val dimPercent: Int,
        val nightActive: Boolean,
        val nightDim: Int,
        val hasMediaPerm: Boolean,
        val customSignature: String,
        val width: Int,
        val height: Int
    )

    private var state: LauncherState = LauncherState()
    private var screenW: Int = 1920
    private var screenH: Int = 1080
    private var resumed: Boolean = false
    private var alive: Boolean = true

    private var requestGen: Long = 0L
    private var appliedKey: WpRequestKey? = null
    private var appliedWasFallback: Boolean = false
    private var pendingBaseKey: WpRequestKey? = null
    private var pendingPersist: Boolean = false
    private var lastNightActive: Boolean = false

    private val rotateTask = object : Runnable {
        override fun run() {
            if (!resumed || !alive) return
            requestIfNeeded(advance = true, persistImmediately = false)
            scheduleRotation()
        }
    }

    fun syncState(newState: LauncherState, width: Int = screenW, height: Int = screenH) {
        state = newState
        if (width > 0) screenW = width
        if (height > 0) screenH = height
        lastNightActive = isNightNow()
    }

    fun onResume(newState: LauncherState) {
        resumed = true
        state = newState
        lastNightActive = isNightNow()
        requestIfNeeded(advance = false, persistImmediately = false)
        scheduleRotation()
        maybeRefreshDailyOnline()
    }

    fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(rotateTask)
        store.flushWallpaperIndices()
    }

    fun onDestroy() {
        alive = false
        resumed = false
        mainHandler.removeCallbacks(rotateTask)
    }

    fun checkNightTransition(): Boolean {
        val nowNight = isNightNow()
        if (nowNight != lastNightActive) {
            lastNightActive = nowNight
            requestIfNeeded(advance = false, persistImmediately = false)
            return true
        }
        return false
    }

    fun isNightNow(): Boolean {
        val cal = Calendar.getInstance()
        return NightSchedule.isNightActive(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            config = state.features.night
        )
    }

    fun requestIfNeeded(advance: Boolean, persistImmediately: Boolean, forceReload: Boolean = false) {
        val baseKey = buildBaseKey()
        val appliedSnap = appliedKey
        val baseChanged = appliedSnap?.copy(customSignature = "") != baseKey
        val dynamicSig = baseKey.hasMediaPerm || baseKey.source == WallpaperSource.ONLINE_DAILY
        if (!advance && !forceReload && !dynamicSig && !baseChanged && pendingBaseKey == null) return
        if (!advance && !forceReload && pendingBaseKey == baseKey) {
            pendingPersist = pendingPersist || persistImmediately
            return
        }
        val sameSource = pendingBaseKey?.source == baseKey.source
        pendingBaseKey = baseKey
        pendingPersist = if (sameSource) pendingPersist || persistImmediately else persistImmediately
        val gen = ++requestGen
        val startIdx = store.wallpaperIndexFor(baseKey.source)
        val wasFallback = appliedWasFallback
        val nightCfg = state.features.night

        val submitted = ioRunner {
            executeBake(gen, baseKey, appliedSnap, startIdx, advance, forceReload, wasFallback, nightCfg)
        }
        if (!submitted) {
            pendingBaseKey = null
            pendingPersist = false
        }
    }

    private fun executeBake(
        gen: Long,
        baseKey: WpRequestKey,
        appliedSnap: WpRequestKey?,
        startIdx: Int,
        advance: Boolean,
        forceReload: Boolean,
        wasFallback: Boolean,
        nightCfg: com.will.ambienthome.model.NightConfig
    ) {
        var fullKey = baseKey
        var result: WallpaperRepository.BakedWallpaper? = null
        try {
            val sig = when {
                baseKey.source == WallpaperSource.ONLINE_DAILY -> OnlineWallpaperProvider.cacheSignature(appContext)
                baseKey.hasMediaPerm -> WallpaperRepository.customFilesSignature()
                else -> ""
            }
            fullKey = baseKey.copy(customSignature = sig)
            val keyChanged = forceReload || fullKey != appliedSnap
            if (advance || keyChanged) {
                result = WallpaperRepository.loadAndBakeStep(
                    ctx = appContext,
                    source = fullKey.source,
                    currentIndex = startIdx,
                    advance = advance,
                    forceReloadIfSingle = keyChanged,
                    previousWasFallback = wasFallback,
                    dimPercent = fullKey.dimPercent,
                    targetW = fullKey.width,
                    targetH = fullKey.height,
                    nightActive = fullKey.nightActive,
                    nightConfig = nightCfg
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wallpaper load failed", t)
            result = null
        } finally {
            mainHandler.post { onBakeFinished(gen, fullKey, result) }
        }
    }

    private fun onBakeFinished(
        gen: Long,
        reqKey: WpRequestKey,
        result: WallpaperRepository.BakedWallpaper?
    ) {
        if (result == null) {
            if (gen == requestGen) {
                pendingBaseKey = null
                pendingPersist = false
            }
            return
        }
        if (!alive || gen != requestGen) {
            if (!result.bitmap.isRecycled) result.bitmap.recycle()
            return
        }
        val shouldPersist = pendingPersist || !resumed
        pendingBaseKey = null
        pendingPersist = false
        appliedKey = reqKey
        appliedWasFallback = result.isFallback
        store.recordWallpaperIndex(reqKey.source, result.resolvedIndex, persistImmediately = shouldPersist)
        onWallpaperReady(result.bitmap, result.caption)
    }

    private fun buildBaseKey(): WpRequestKey {
        val ap = state.appearance
        val permRelevant = ap.wallpaperSource.requiresMediaPermission()
        val hasPerm = permRelevant && WallpaperRepository.hasMediaPermission(appContext)
        val nightActive = isNightNow()
        return WpRequestKey(
            source = ap.wallpaperSource,
            dimPercent = if (ap.wallpaperSource == WallpaperSource.GRADIENT) 0 else ap.dimPercent,
            nightActive = nightActive,
            nightDim = state.features.night.nightDimPercent,
            hasMediaPerm = hasPerm,
            customSignature = "",
            width = screenW,
            height = screenH
        )
    }

    private fun scheduleRotation() {
        mainHandler.removeCallbacks(rotateTask)
        val min = state.appearance.wallpaperIntervalMin
        if (!resumed || !alive || min <= 0) return
        mainHandler.postDelayed(rotateTask, min * 60_000L)
    }

    private fun maybeRefreshDailyOnline() {
        if (state.appearance.wallpaperSource != WallpaperSource.ONLINE_DAILY) return
        netRunner {
            val changed = OnlineWallpaperProvider.refreshIfDue(appContext)
            if (changed) {
                mainHandler.post {
                    if (alive && resumed) {
                        requestIfNeeded(advance = false, persistImmediately = false, forceReload = true)
                    }
                }
            }
        }
    }
}
