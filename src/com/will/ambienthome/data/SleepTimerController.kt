package com.will.ambienthome.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.will.ambienthome.model.SleepTimerState

/**
 * Receiver пробуждения таймера сна даже когда поверх HOME работает SmartTube/Кинопоиск/Nova.
 */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SleepTimerController.ACTION_SLEEP_FIRE) {
            val pending = try {
                goAsync()
            } catch (_: Throwable) {
                null
            }
            SleepTimerController.onAlarmFired(context.applicationContext, pending)
        }
    }
}

/**
 * Чистый потокобезопасный автомат поколений для SleepTimerController (100% JVM-тестируемый).
 * Гарантирует строгую идемпотентность срабатывания и корректный откат громкости при отмене fade.
 */
internal class SleepTriggerState {
    private var armed: Boolean = false
    private var fading: Boolean = false
    private var deadlineElapsedMs: Long = 0L
    private var generation: Long = 0L
    private var savedVolume: Int = -1

    @Synchronized
    internal fun arm(newDeadlineElapsedMs: Long): Pair<Long, Int> {
        val restoreVol = if (fading && savedVolume > 0) savedVolume else -1
        generation++
        armed = true
        fading = false
        deadlineElapsedMs = newDeadlineElapsedMs
        savedVolume = -1
        return generation to restoreVol
    }

    @Synchronized
    internal fun tryBeginSleep(): Long {
        if (!armed || fading) return -1L
        armed = false
        deadlineElapsedMs = 0L
        fading = true
        generation++
        return generation
    }

    @Synchronized
    internal fun recordOriginalVolume(gen: Long, volume: Int) {
        if (gen == generation && fading) savedVolume = volume
    }

    @Synchronized
    internal fun completeFade(gen: Long): Boolean {
        if (gen != generation) return false
        fading = false
        return true
    }

    @Synchronized
    internal fun cancel(): Pair<Long, Int> {
        val restoreVol = if (fading && savedVolume > 0) savedVolume else -1
        generation++
        armed = false
        fading = false
        deadlineElapsedMs = 0L
        savedVolume = -1
        return generation to restoreVol
    }

    @Synchronized
    internal fun isCurrent(gen: Long, requireFading: Boolean = false): Boolean =
        gen == generation && (!requireFading || fading)

    @Synchronized
    internal fun snapshotDeadline(): Long = if (armed) deadlineElapsedMs else 0L
}

/**
 * Контроллер «Таймера сна» для гостиной (15/30/60/90 мин):
 * - 15-секундное отменяемое предупреждение на HOME;
 * - плавное 5-секундное затухание громкости STREAM_MUSIC + управляемый AudioFocus + KEYCODE_MEDIA_PAUSE;
 * - переход в сверхтёмный чёрный ночной Ambient + опциональный KEYCODE_SLEEP (223) через remote.fifo.
 */
object SleepTimerController {

    private const val TAG = "AmbientHome"
    const val ACTION_SLEEP_FIRE = "com.will.ambienthome.ACTION_SLEEP_TIMER_FIRE"
    const val EXTRA_SLEEP_BLACKOUT = "com.will.ambienthome.EXTRA_SLEEP_BLACKOUT"
    private const val WARNING_SEC = 15
    private const val FADE_STEPS = 5
    private const val FADE_STEP_MS = 900L
    private const val RESTORE_DELAY_MS = 800L

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val triggerState = SleepTriggerState()

    @Volatile
    private var appCtxRef: Context? = null

    private var stateListener: ((SleepTimerState) -> Unit)? = null
    private var blackoutListener: (() -> Unit)? = null
    private var fadeAudioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var pendingFadeTask: Runnable? = null
    private var pendingRestoreTask: Runnable? = null
    private var pendingBroadcast: BroadcastReceiver.PendingResult? = null

    private val tickTask = object : Runnable {
        override fun run() {
            val dl = triggerState.snapshotDeadline()
            if (dl <= 0L) {
                emitState(SleepTimerState())
                return
            }
            val remMs = dl - SystemClock.elapsedRealtime()
            if (remMs <= 0L) {
                val ctx = appCtxRef
                if (ctx != null) triggerSleepOnce(ctx, null) else emitState(SleepTimerState())
                return
            }
            val remSec = ((remMs + 999L) / 1000L).toInt().coerceAtLeast(1)
            emitState(SleepTimerState(active = true, remainingSec = remSec, warningActive = remSec <= WARNING_SEC))
            val delay = if (remSec <= WARNING_SEC + 2) 1000L else 10_000L
            handler.postDelayed(this, delay)
        }
    }

    fun attachListeners(
        onState: (SleepTimerState) -> Unit,
        onBlackout: () -> Unit
    ) {
        stateListener = onState
        blackoutListener = onBlackout
        onState(currentState())
    }

    fun detachListeners() {
        stateListener = null
        blackoutListener = null
    }

    fun startTimer(ctx: Context, minutes: Int) {
        val appCtx = ctx.applicationContext
        appCtxRef = appCtx
        if (minutes <= 0) {
            cancel(appCtx)
            return
        }
        val delayMs = minutes.coerceIn(1, 240) * 60_000L
        val deadline = SystemClock.elapsedRealtime() + delayMs
        val (_, restoreVol) = triggerState.arm(deadline)
        clearPendingRunnables()
        restoreVolumeAndAbandonFocus(appCtx, restoreVol)
        finishPendingBroadcast()
        scheduleAlarm(appCtx, deadline)
        handler.removeCallbacks(tickTask)
        handler.post(tickTask)
    }

    fun cancel(ctx: Context) {
        val appCtx = ctx.applicationContext
        val (_, restoreVol) = triggerState.cancel()
        handler.removeCallbacks(tickTask)
        clearPendingRunnables()
        restoreVolumeAndAbandonFocus(appCtx, restoreVol)
        finishPendingBroadcast()
        cancelAlarm(appCtx)
        emitState(SleepTimerState())
    }

    fun currentState(): SleepTimerState {
        val dl = triggerState.snapshotDeadline()
        if (dl <= 0L) return SleepTimerState()
        val remMs = dl - SystemClock.elapsedRealtime()
        if (remMs <= 0L) return SleepTimerState()
        val remSec = ((remMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        return SleepTimerState(active = true, remainingSec = remSec, warningActive = remSec <= WARNING_SEC)
    }

    internal fun onAlarmFired(appCtx: Context, pending: BroadcastReceiver.PendingResult? = null) {
        appCtxRef = appCtx
        triggerSleepOnce(appCtx, pending)
    }

    private fun triggerSleepOnce(appCtx: Context, pending: BroadcastReceiver.PendingResult?) {
        val gen = triggerState.tryBeginSleep()
        if (gen < 0L) {
            safeFinishBroadcast(pending)
            return
        }
        cancelAlarm(appCtx)
        handler.removeCallbacks(tickTask)
        clearPendingRunnables()
        finishPendingBroadcast()
        pendingBroadcast = pending
        emitState(SleepTimerState())
        executeSleepSequence(appCtx, gen)
    }

    private fun executeSleepSequence(appCtx: Context, gen: Long) {
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val origVol = try {
            am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        } catch (_: Throwable) {
            0
        }
        fadeAudioManager = am
        triggerState.recordOriginalVolume(gen, origVol)
        fadeStep(appCtx, am, origVol, step = 1, gen = gen)
    }

    private fun fadeStep(appCtx: Context, am: AudioManager?, origVol: Int, step: Int, gen: Long) {
        if (!triggerState.isCurrent(gen, requireFading = true)) return
        if (am != null && origVol > 0 && step <= FADE_STEPS) {
            val targetVol = ((origVol * (FADE_STEPS - step)) / FADE_STEPS).coerceAtLeast(0)
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (_: Throwable) {
            }
            val nextTask = Runnable { fadeStep(appCtx, am, origVol, step + 1, gen) }
            pendingFadeTask = nextTask
            handler.postDelayed(nextTask, FADE_STEP_MS)
            return
        }
        pendingFadeTask = null
        finishSleep(appCtx, am, origVol, gen)
    }

    private fun finishSleep(appCtx: Context, am: AudioManager?, origVol: Int, gen: Long) {
        if (!triggerState.completeFade(gen)) return
        pausePlaybackAndStealFocus(am)
        schedulePostSleepCleanup(am, origVol, gen)
        val listener = blackoutListener
        if (listener != null) {
            listener.invoke()
        } else {
            launchBlackoutHome(appCtx)
        }
        val hdmiOff = try {
            LauncherStore(appCtx).readState().features.sleepHdmiOff
        } catch (_: Throwable) {
            true
        }
        if (hdmiOff && RootBridgeClient.isAvailable(appCtx)) {
            val res = RootBridgeClient.sendSimple(appCtx, "SLEEP")
            Log.i(TAG, "SleepTimer HDMI-off via bridge: ok=${res.ok} err=${res.error}")
        }
    }

    private fun schedulePostSleepCleanup(am: AudioManager?, origVol: Int, gen: Long) {
        if (am == null) {
            fadeAudioManager = null
            finishPendingBroadcast()
            return
        }
        val cleanupTask = Runnable {
            pendingRestoreTask = null
            if (!triggerState.isCurrent(gen)) return@Runnable
            if (origVol > 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, origVol, 0)
                } catch (_: Throwable) {
                }
            }
            abandonFocus(am)
            fadeAudioManager = null
            finishPendingBroadcast()
        }
        pendingRestoreTask = cleanupTask
        handler.postDelayed(cleanupTask, RESTORE_DELAY_MS)
    }

    private fun launchBlackoutHome(appCtx: Context) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(appCtx.packageName, "${appCtx.packageName}.MainActivity")
                putExtra(EXTRA_SLEEP_BLACKOUT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            appCtx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "SleepTimer launchBlackoutHome failed", t)
        }
    }

    private fun pausePlaybackAndStealFocus(am: AudioManager?) {
        if (am == null) return
        try {
            abandonFocus(am)
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            activeFocusRequest = req
            am.requestAudioFocus(req)
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        } catch (t: Throwable) {
            Log.w(TAG, "SleepTimer pausePlayback failed", t)
        }
    }

    private fun restoreVolumeAndAbandonFocus(appCtx: Context, restoreVol: Int) {
        val am = fadeAudioManager ?: (appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        fadeAudioManager = null
        if (am != null && restoreVol > 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
            } catch (_: Throwable) {
            }
        }
        abandonFocus(am)
    }

    private fun abandonFocus(am: AudioManager?) {
        val req = activeFocusRequest ?: return
        activeFocusRequest = null
        if (am == null) return
        try {
            am.abandonAudioFocusRequest(req)
        } catch (_: Throwable) {
        }
    }

    private fun clearPendingRunnables() {
        pendingFadeTask?.let { handler.removeCallbacks(it) }
        pendingFadeTask = null
        pendingRestoreTask?.let { handler.removeCallbacks(it) }
        pendingRestoreTask = null
    }

    private fun finishPendingBroadcast() {
        val pending = pendingBroadcast ?: return
        pendingBroadcast = null
        safeFinishBroadcast(pending)
    }

    private fun safeFinishBroadcast(pending: BroadcastReceiver.PendingResult?) {
        if (pending == null) return
        try {
            pending.finish()
        } catch (_: Throwable) {
        }
    }

    private fun scheduleAlarm(appCtx: Context, triggerAtElapsedMs: Long) {
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = alarmPendingIntent(appCtx)
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi)
        } catch (t: Throwable) {
            Log.w(TAG, "AlarmManager schedule failed", t)
        }
    }

    private fun cancelAlarm(appCtx: Context) {
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(alarmPendingIntent(appCtx))
        } catch (_: Throwable) {
        }
    }

    private fun alarmPendingIntent(appCtx: Context): PendingIntent {
        val intent = Intent(appCtx, SleepTimerReceiver::class.java).apply { action = ACTION_SLEEP_FIRE }
        return PendingIntent.getBroadcast(
            appCtx,
            3001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun emitState(st: SleepTimerState) {
        stateListener?.invoke(st)
    }
}
