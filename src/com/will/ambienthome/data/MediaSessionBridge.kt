package com.will.ambienthome.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import com.will.ambienthome.model.NowPlayingState

/**
 * Тонкая заглушка NotificationListenerService для нативного доступа к
 * MediaSessionManager.getActiveSessions() («Сейчас играет»).
 * Ноль обработки уведомлений, ноль фоновой нагрузки, ноль Accessibility.
 */
class AmbientNotificationListener : NotificationListenerService()

/**
 * Нативный мост «Сейчас играет» через MediaSessionManager.getActiveSessions().
 * Подписка на активные медиасессии подключается СТРОГО пока HOME на экране (onResume -> onPause).
 * Если грант NotificationListener ещё не выдан, тихо скрывается без ошибок и спама в логи.
 */
class MediaSessionBridge(
    private val appContext: Context,
    private val mainHandler: Handler,
    private val onStateChanged: (NowPlayingState?) -> Unit
) {

    private val listenerComponent = ComponentName(appContext, AmbientNotificationListener::class.java)
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var lastEmitted: NowPlayingState? = null
    private var cachedLabelPkg: String = ""
    private var cachedLabelValue: String = ""
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        if (!isListenerGranted(appContext)) {
            emit(null)
            return
        }
        val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (!started) return@OnActiveSessionsChangedListener
            bindBestController(controllers.orEmpty())
        }
        try {
            msm.addOnActiveSessionsChangedListener(listener, listenerComponent, mainHandler)
            sessionListener = listener
            bindBestController(msm.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            emit(null)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSessionBridge.start failed", t)
            emit(null)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        detachController()
        val l = sessionListener
        sessionListener = null
        if (l != null) {
            try {
                val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                msm?.removeOnActiveSessionsChangedListener(l)
            } catch (t: Throwable) {
                Log.w(TAG, "MediaSessionBridge.stop failed", t)
            }
        }
    }

    fun currentState(): NowPlayingState? = lastEmitted

    fun togglePlayPause(): Boolean {
        val ctrl = activeController ?: return false
        return try {
            val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "togglePlayPause failed", t)
            false
        }
    }

    fun skipNext(): Boolean {
        val ctrl = activeController ?: return false
        return try {
            ctrl.transportControls.skipToNext()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "skipNext failed", t)
            false
        }
    }

    private fun bindBestController(controllers: List<MediaController>) {
        if (!started) return
        val chosen = pickBestController(controllers)
        if (chosen == null) {
            detachController()
            emit(null)
            return
        }
        if (activeController?.sessionToken != chosen.sessionToken) {
            detachController()
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (started && activeController === chosen) refreshFromController(chosen)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (started && activeController === chosen) refreshFromController(chosen)
                }
                override fun onSessionDestroyed() {
                    if (!started || activeController !== chosen) return
                    detachController()
                    emit(null)
                }
            }
            try {
                chosen.registerCallback(cb, mainHandler)
                activeController = chosen
                controllerCallback = cb
            } catch (t: Throwable) {
                Log.w(TAG, "registerCallback failed", t)
            }
        }
        refreshFromController(chosen)
    }

    private fun pickBestController(controllers: List<MediaController>): MediaController? {
        val playing = controllers.firstOrNull { isPlayingState(it.playbackState?.state) }
        if (playing != null) return playing
        return controllers.firstOrNull { isResumableState(it.playbackState?.state) && hasTitle(it) }
    }

    private fun refreshFromController(ctrl: MediaController) {
        if (!started) return
        val st = ctrl.playbackState?.state
        if (!isPlayingState(st) && !isResumableState(st)) {
            emit(null)
            return
        }
        val meta = ctrl.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        val pkg = ctrl.packageName.orEmpty()
        if (pkg.isEmpty() || (title.isEmpty() && !isPlayingState(st))) {
            emit(null)
            return
        }
        val appLabel = resolveAppLabel(pkg)
        emit(NowPlayingState(pkg, appLabel, title, artist, isPlayingState(st)))
    }

    private fun detachController() {
        val ctrl = activeController
        val cb = controllerCallback
        activeController = null
        controllerCallback = null
        if (ctrl != null && cb != null) {
            try {
                ctrl.unregisterCallback(cb)
            } catch (_: Throwable) {
            }
        }
    }

    private fun emit(next: NowPlayingState?) {
        if (next == lastEmitted) return
        lastEmitted = next
        onStateChanged(next)
    }

    private fun resolveAppLabel(pkg: String): String {
        if (pkg == cachedLabelPkg && cachedLabelValue.isNotEmpty()) return cachedLabelValue
        val label = try {
            val ai = appContext.packageManager.getApplicationInfo(pkg, 0)
            appContext.packageManager.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
        } catch (_: Throwable) {
            pkg
        }
        cachedLabelPkg = pkg
        cachedLabelValue = label
        return label
    }

    private fun hasTitle(ctrl: MediaController): Boolean =
        !ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()

    private fun isPlayingState(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    private fun isResumableState(state: Int?): Boolean =
        state == PlaybackState.STATE_PAUSED

    companion object {
        private const val TAG = "AmbientHome"

        fun isListenerGranted(ctx: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                    ?: return false
                val cn = ComponentName(ctx, AmbientNotificationListener::class.java)
                flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString())
            } catch (_: Throwable) {
                false
            }
        }
    }
}
