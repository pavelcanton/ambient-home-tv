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
            SleepTimerController.onAlarmFired(context.applicationContext)
        }
    }
}

/**
 * Контроллер «Таймера сна» для гостиной (15/30/60/90 мин):
 * - 15-секундное отменяемое предупреждение на HOME;
 * - плавное 5-секундное затухание громкости STREAM_MUSIC + захват AudioFocus + KEYCODE_MEDIA_PAUSE;
 * - переход в сверхтёмный чёрный ночной Ambient + опциональный KEYCODE_SLEEP (223) через remote.fifo.
 */
object SleepTimerController {

    private const val TAG = "AmbientHome"
    const val ACTION_SLEEP_FIRE = "com.will.ambienthome.ACTION_SLEEP_TIMER_FIRE"
    const val EXTRA_SLEEP_BLACKOUT = "com.will.ambienthome.EXTRA_SLEEP_BLACKOUT"
    private const val WARNING_SEC = 15
    private const val FADE_STEPS = 5
    private const val FADE_STEP_MS = 900L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var deadlineElapsedMs: Long = 0L

    @Volatile
    private var appCtxRef: Context? = null

    private var stateListener: ((SleepTimerState) -> Unit)? = null
    private var blackoutListener: (() -> Unit)? = null
    private var fading: Boolean = false

    private val tickTask = object : Runnable {
        override fun run() {
            val dl = deadlineElapsedMs
            if (dl <= 0L) {
                emitState(SleepTimerState())
                return
            }
            val remMs = dl - SystemClock.elapsedRealtime()
            if (remMs <= 0L) {
                val ctx = appCtxRef
                deadlineElapsedMs = 0L
                emitState(SleepTimerState())
                if (ctx != null) executeSleepSequence(ctx)
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
        fading = false
        val delayMs = minutes.coerceIn(1, 240) * 60_000L
        deadlineElapsedMs = SystemClock.elapsedRealtime() + delayMs
        scheduleAlarm(appCtx, deadlineElapsedMs)
        handler.removeCallbacks(tickTask)
        handler.post(tickTask)
    }

    fun cancel(ctx: Context) {
        val appCtx = ctx.applicationContext
        deadlineElapsedMs = 0L
        fading = false
        handler.removeCallbacks(tickTask)
        cancelAlarm(appCtx)
        emitState(SleepTimerState())
    }

    fun currentState(): SleepTimerState {
        val dl = deadlineElapsedMs
        if (dl <= 0L) return SleepTimerState()
        val remMs = dl - SystemClock.elapsedRealtime()
        if (remMs <= 0L) return SleepTimerState()
        val remSec = ((remMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        return SleepTimerState(active = true, remainingSec = remSec, warningActive = remSec <= WARNING_SEC)
    }

    internal fun onAlarmFired(appCtx: Context) {
        appCtxRef = appCtx
        if (deadlineElapsedMs <= 0L && fading) return
        deadlineElapsedMs = 0L
        handler.removeCallbacks(tickTask)
        emitState(SleepTimerState())
        executeSleepSequence(appCtx)
    }

    private fun executeSleepSequence(appCtx: Context) {
        if (fading) return
        fading = true
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val origVol = try {
            am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        } catch (_: Throwable) {
            0
        }
        fadeStep(appCtx, am, origVol, step = 1)
    }

    private fun fadeStep(appCtx: Context, am: AudioManager?, origVol: Int, step: Int) {
        if (!fading) return
        if (am != null && origVol > 0 && step <= FADE_STEPS) {
            val targetVol = ((origVol * (FADE_STEPS - step)) / FADE_STEPS).coerceAtLeast(0)
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (_: Throwable) {
            }
            handler.postDelayed({ fadeStep(appCtx, am, origVol, step + 1) }, FADE_STEP_MS)
            return
        }
        finishSleep(appCtx, am, origVol)
    }

    private fun finishSleep(appCtx: Context, am: AudioManager?, origVol: Int) {
        fading = false
        pausePlaybackAndStealFocus(am)
        if (am != null && origVol > 0) {
            handler.postDelayed({
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, origVol, 0)
                } catch (_: Throwable) {
                }
            }, 800L)
        }
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
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        } catch (t: Throwable) {
            Log.w(TAG, "SleepTimer pausePlayback failed", t)
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
