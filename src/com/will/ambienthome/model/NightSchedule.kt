package com.will.ambienthome.model

import java.util.Locale

/**
 * Чистая логика «Ночного кино-режима» (Ambient Home 3.0.0):
 * расписание с переходом через полночь (например, 22:00–07:00), ручной тумблер,
 * расчёт затемнения обоев, тёплого янтарного оттенка и приглушённой яркости часов.
 */
object NightSchedule {

    private const val WARM_AMBER_TINT_ARGB: Int = 0x38FF8C2E
    private const val NIGHT_CLOCK_ALPHA: Int = 184
    private const val DAY_CLOCK_ALPHA: Int = 245

    fun isNightActive(hour: Int, minute: Int, config: NightConfig): Boolean {
        return when (config.mode) {
            NightMode.ON -> true
            NightMode.OFF -> false
            NightMode.AUTO -> isWithinWindow(hour, minute, config.startHour, config.endHour)
        }
    }

    fun effectiveDimPercent(baseDimPercent: Int, nightActive: Boolean, config: NightConfig): Int {
        val dayDim = baseDimPercent.coerceIn(0, 70)
        if (!nightActive) return dayDim
        val target = config.nightDimPercent.coerceIn(30, 82)
        return maxOf(dayDim, target)
    }

    fun warmTintColor(nightActive: Boolean): Int =
        if (nightActive) WARM_AMBER_TINT_ARGB else 0

    fun clockLuminanceAlpha(nightActive: Boolean): Int =
        if (nightActive) NIGHT_CLOCK_ALPHA else DAY_CLOCK_ALPHA

    fun formatSchedule(config: NightConfig): String {
        val s = config.startHour.coerceIn(0, 23)
        val e = config.endHour.coerceIn(0, 23)
        return String.format(Locale.US, "%02d:00–%02d:00", s, e)
    }

    fun describeStatus(config: NightConfig, nightActive: Boolean): String {
        return when (config.mode) {
            NightMode.ON -> "Вкл вручную (${config.nightDimPercent}%)"
            NightMode.OFF -> "Выкл"
            NightMode.AUTO -> {
                val activeMark = if (nightActive) "активен" else "ожидание"
                "Авто ${formatSchedule(config)} ($activeMark)"
            }
        }
    }

    private fun isWithinWindow(hour: Int, minute: Int, startHour: Int, endHour: Int): Boolean {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val start = startHour.coerceIn(0, 23) * 60
        val end = endHour.coerceIn(0, 23) * 60
        if (start == end) return false
        val now = h * 60 + m
        return if (start > end) {
            now >= start || now < end
        } else {
            now in start until end
        }
    }
}
