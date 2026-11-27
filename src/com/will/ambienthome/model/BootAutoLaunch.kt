package com.will.ambienthome.model

/**
 * Чистая конечная машина Wi-Fi-aware холодного автозапуска приложения (Ambient Home 3.0.0).
 * Опирается на /proc/sys/kernel/random/boot_id вместо фонового BOOT_COMPLETED-демона.
 */
object BootAutoLaunch {

    const val COUNTDOWN_SECONDS = 3
    const val WIFI_WAIT_TIMEOUT_MS = 25_000L

    fun shouldTrigger(currentBootId: String, config: BootAutoLaunchConfig): Boolean {
        val cleanBootId = currentBootId.trim()
        if (!config.enabled || config.packageName.isBlank() || cleanBootId.isEmpty()) {
            return false
        }
        return cleanBootId != config.lastHandledBootId.trim()
    }

    fun countdownRemainingSec(elapsedMs: Long, totalSec: Int = COUNTDOWN_SECONDS): Int {
        if (totalSec <= 0) return 0
        val spent = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
        return (totalSec - spent).coerceAtLeast(0)
    }

    fun isWifiWaitTimedOut(waitElapsedMs: Long, timeoutMs: Long = WIFI_WAIT_TIMEOUT_MS): Boolean =
        waitElapsedMs >= timeoutMs.coerceAtLeast(1000L)

    fun bannerText(appLabel: String, remainingSec: Int, waitingWifi: Boolean): String {
        val name = appLabel.ifBlank { "приложение" }
        return if (waitingWifi) {
            "Автозапуск $name: ожидание сети…  ·  Нажмите кнопку для отмены"
        } else {
            "Автозапуск $name через ${remainingSec.coerceAtLeast(1)} с  ·  Нажмите кнопку для отмены"
        }
    }
}
