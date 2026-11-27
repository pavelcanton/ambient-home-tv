package com.will.ambienthome.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.will.ambienthome.model.CityPreset
import com.will.ambienthome.model.SleepTimerState
import com.will.ambienthome.model.WeatherSnapshot

/**
 * Диалоги новых функций Ambient Home 3.0.0:
 * Прогноз погоды 24ч/3 дня, выбор города, таймер сна, Web-пульт (URL+PIN), списки выбора и ввод текста.
 */
object FeatureDialogs {

    fun showWeatherForecast(
        ctx: Context,
        snap: WeatherSnapshot,
        onRefresh: () -> Unit,
        onChangeCity: () -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 28, padV = 22, radiusDp = 22)
        card.addView(TvDialogs.createTitleView(ctx, "${snap.cityName}  ·  ${snap.pillTempText}  ${snap.condition}", 23f, 6))
        card.addView(subtitleView(ctx, "Ощущается ${formatTemp(snap.feelsLikeC)}  ·  Ветер ${snap.windKmh} км/ч", 540, 14))

        if (snap.hourly.isNotEmpty()) {
            val hourlyLine = snap.hourly.joinToString("   ") { "${it.hourLabel} ${formatTemp(it.tempC)}" }
            card.addView(infoBox(ctx, "Ближайшие 24 ч", hourlyLine, 540))
        }
        if (snap.daily.isNotEmpty()) {
            val dailyText = snap.daily.take(3).joinToString("\n") {
                "${it.dayLabel}:  ${formatTemp(it.tempMinC)} … ${formatTemp(it.tempMaxC)}  ·  ${it.condition}"
            }
            card.addView(infoBox(ctx, "Прогноз на 3 дня", dailyText, 540))
        }

        val refreshRow = TvDialogs.createActionRow(ctx, "↻   Обновить прогноз сейчас", 540, 18f, 18, 12) {
            dialog.dismiss()
            onRefresh()
        }
        val cityRow = TvDialogs.createActionRow(ctx, "📍   Сменить город", 540, 18f, 18, 12) {
            dialog.dismiss()
            onChangeCity()
        }
        card.addView(refreshRow)
        card.addView(cityRow)
        dialog.setContentView(card)
        dialog.show()
        refreshRow.requestFocus()
        return dialog
    }

    fun showCityPicker(
        ctx: Context,
        presets: List<CityPreset>,
        currentCity: String,
        onPick: (CityPreset) -> Unit,
        onSearchPrompt: () -> Unit,
        onDisable: () -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 26, padV = 20, radiusDp = 20)
        val cur = currentCity.ifBlank { "Выключено" }
        card.addView(TvDialogs.createTitleView(ctx, "Город погоды (сейчас: $cur)", 21f, 12))

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val searchRow = TvDialogs.createActionRow(ctx, "🔍   Найти любой город (Open-Meteo)…", 500, 17f, 18, 11) {
            dialog.dismiss()
            onSearchPrompt()
        }
        listBox.addView(searchRow)
        for (city in presets) {
            val label = if (city.region.isNotEmpty()) "${city.name}  ·  ${city.region}" else city.name
            listBox.addView(TvDialogs.createActionRow(ctx, label, 500, 17f, 18, 11) {
                dialog.dismiss()
                onPick(city)
            })
        }
        listBox.addView(TvDialogs.createActionRow(ctx, "⊘   Отключить погоду", 500, 17f, 18, 11) {
            dialog.dismiss()
            onDisable()
        })

        card.addView(wrapScroll(ctx, listBox, 58))
        dialog.setContentView(card)
        dialog.show()
        searchRow.requestFocus()
        return dialog
    }

    fun showSleepTimerPicker(
        ctx: Context,
        current: SleepTimerState,
        hdmiOffEnabled: Boolean,
        bridgeAvailable: Boolean = true,
        onPickMinutes: (Int) -> Unit,
        onToggleHdmiOff: () -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 26, padV = 22, radiusDp = 20)
        val header = if (current.active) "Таймер сна (осталось ${current.badgeText})" else "Таймер сна"
        card.addView(TvDialogs.createTitleView(ctx, header, 22f, 12))

        var first: TextView? = null
        val options = listOf(0 to "Выключить таймер", 15 to "15 минут", 30 to "30 минут", 60 to "60 минут", 90 to "90 минут")
        for ((min, label) in options) {
            val row = TvDialogs.createActionRow(ctx, "🌙   $label", 460, 18f, 18, 12) {
                dialog.dismiss()
                onPickMinutes(min)
            }
            if (first == null) first = row
            card.addView(row)
        }
        if (bridgeAvailable) {
            val hdmiLabel = if (hdmiOffEnabled) "Режим HDMI-выкл (через root-мост): Вкл" else "Режим HDMI-выкл (через root-мост): Выкл"
            card.addView(TvDialogs.createActionRow(ctx, hdmiLabel, 460, 16f, 18, 11) {
                dialog.dismiss()
                onToggleHdmiOff()
            })
        } else {
            card.addView(subtitleView(ctx, "Режим сна: пауза медиа и чёрный экран (HDMI-выкл требует опциональный root-мост)", 460, 4))
        }
        dialog.setContentView(card)
        dialog.show()
        first?.requestFocus()
        return dialog
    }

    fun showWebRemoteInfo(
        ctx: Context,
        enabled: Boolean,
        url: String,
        pin: String,
        onToggleEnabled: () -> Unit,
        onRotatePin: () -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 28, padV = 22, radiusDp = 22)
        card.addView(TvDialogs.createTitleView(ctx, "LAN Web-пульт (Телефон / ПК)", 22f, 8))
        val detail = if (enabled) {
            "Адрес в браузере:  $url\nPIN-код доступа (X-Auth-Token):  $pin\nРаботает в вашей локальной сети (LAN) даже поверх плееров."
        } else {
            "Сервер Web-пульта выключен (0% ресурсов)."
        }
        card.addView(infoBox(ctx, "Подключение", detail, 520))

        val toggleRow = TvDialogs.createActionRow(
            ctx,
            if (enabled) "⏹   Выключить Web-пульт" else "▶   Включить Web-пульт",
            520,
            18f,
            18,
            12
        ) {
            dialog.dismiss()
            onToggleEnabled()
        }
        card.addView(toggleRow)
        if (enabled) {
            card.addView(TvDialogs.createActionRow(ctx, "🔑   Сгенерировать новый PIN", 520, 18f, 18, 12) {
                dialog.dismiss()
                onRotatePin()
            })
        }
        dialog.setContentView(card)
        dialog.show()
        toggleRow.requestFocus()
        return dialog
    }

    fun showNotificationAccessInfo(
        ctx: Context,
        granted: Boolean,
        adbCommand: String,
        onOpenSettings: () -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 28, padV = 22, radiusDp = 22)
        card.addView(TvDialogs.createTitleView(ctx, "Пилюля «Сейчас играет»", 22f, 8))
        val detail = if (granted) {
            "Доступ к медиасессиям (NotificationListener) выдан.\nПилюля автоматически появится на HOME при воспроизведении."
        } else {
            "Требуется разрешение NotificationListener (0% нагрузки в простое).\n\n1. Попробуйте открыть системные настройки ниже.\n2. Если на Android TV нет этого пункта, выполните через ADB:\n$adbCommand"
        }
        card.addView(infoBox(ctx, if (granted) "Статус" else "Как включить", detail, 560))
        var first: TextView? = null
        if (!granted) {
            val openRow = TvDialogs.createActionRow(ctx, "⚙   Открыть настройки доступа к уведомлениям", 560, 17f, 18, 12) {
                dialog.dismiss()
                onOpenSettings()
            }
            first = openRow
            card.addView(openRow)
        }
        val closeRow = TvDialogs.createActionRow(ctx, "Закрыть", 560, 18f, 18, 12) { dialog.dismiss() }
        if (first == null) first = closeRow
        card.addView(closeRow)
        dialog.setContentView(card)
        dialog.show()
        first.requestFocus()
        return dialog
    }

    fun showChoiceList(
        ctx: Context,
        title: String,
        items: List<Pair<String, String>>,
        onPick: (String) -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 26, padV = 20, radiusDp = 20)
        card.addView(TvDialogs.createTitleView(ctx, title, 21f, 12))
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var first: TextView? = null
        for ((key, label) in items) {
            val row = TvDialogs.createActionRow(ctx, label, 500, 17f, 18, 11) {
                dialog.dismiss()
                onPick(key)
            }
            if (first == null) first = row
            listBox.addView(row)
        }
        card.addView(wrapScroll(ctx, listBox, 58))
        dialog.setContentView(card)
        dialog.show()
        first?.requestFocus()
        return dialog
    }

    fun showTextInput(
        ctx: Context,
        title: String,
        hint: String,
        initialValue: String,
        onSubmit: (String) -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = TvDialogs.createDialog(ctx, onDismiss)
        val card = TvDialogs.createCard(ctx, padH = 28, padV = 22, radiusDp = 20)
        card.addView(TvDialogs.createTitleView(ctx, title, 21f, 12))
        val input = EditText(ctx).apply {
            setText(initialValue)
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setPadding(TvDialogs.dp(ctx, 16), TvDialogs.dp(ctx, 12), TvDialogs.dp(ctx, 16), TvDialogs.dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(0xFF192230.toInt())
                cornerRadius = TvDialogs.dp(ctx, 12).toFloat()
                setStroke(TvDialogs.dp(ctx, 1), 0x667FD8A0)
            }
            layoutParams = LinearLayout.LayoutParams(TvDialogs.dp(ctx, 480), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = TvDialogs.dp(ctx, 14)
            }
        }
        card.addView(input)
        card.addView(TvDialogs.createActionRow(ctx, "Сохранить", 480, 18f, 18, 12) {
            val value = input.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            onSubmit(value)
        })
        card.addView(TvDialogs.createActionRow(ctx, "Отмена", 480, 18f, 18, 12) { dialog.dismiss() })
        dialog.setContentView(card)
        dialog.show()
        input.requestFocus()
        return dialog
    }

    private fun subtitleView(ctx: Context, text: String, widthDp: Int, bottomDp: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xB8FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(TvDialogs.dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = TvDialogs.dp(ctx, bottomDp)
            }
            setPadding(TvDialogs.dp(ctx, 4), 0, TvDialogs.dp(ctx, 4), 0)
        }

    private fun infoBox(ctx: Context, heading: String, body: String, widthDp: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(TvDialogs.dp(ctx, 16), TvDialogs.dp(ctx, 12), TvDialogs.dp(ctx, 16), TvDialogs.dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(0xFF141C28.toInt())
                cornerRadius = TvDialogs.dp(ctx, 14).toFloat()
                setStroke(TvDialogs.dp(ctx, 1), 0x26FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(TvDialogs.dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = TvDialogs.dp(ctx, 12)
            }
            addView(TextView(ctx).apply {
                text = heading.uppercase()
                setTextColor(0xFF7FD8A0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, TvDialogs.dp(ctx, 6))
            })
            addView(TextView(ctx).apply {
                text = body
                setTextColor(0xE6FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
        }

    private fun wrapScroll(ctx: Context, content: LinearLayout, maxHeightPct: Int): ScrollView {
        val maxH = (ctx.resources.displayMetrics.heightPixels * maxHeightPct) / 100
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (content.childCount >= 6) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun formatTemp(t: Int): String = if (t > 0) "+${t}°" else "${t}°"
}
