package com.will.ambienthome.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.will.ambienthome.R
import com.will.ambienthome.model.AppEntry
import com.will.ambienthome.model.QuickControlState
import com.will.ambienthome.model.UsbVolumeInfo

/**
 * Базовая библиотека матовых тёмных TV-диалогов Ambient Home 3.0.0:
 * контекстное меню плитки, Quick Control Sheet, возврат скрытых приложений и подтверждения.
 */
object TvDialogs {

    interface AppMenuListener {
        fun onMoveLeft(entry: AppEntry)
        fun onMoveRight(entry: AppEntry)
        fun onTogglePin(entry: AppEntry)
        fun onOpenAppInfo(entry: AppEntry)
        fun onHideApp(entry: AppEntry)
    }

    interface QuickControlsListener {
        fun onAmbientNow()
        fun onNextWallpaper()
        fun onToggleNight()
        fun onOpenSleepTimer()
        fun onOpenWebRemote()
        fun onReconnectWifi()
        fun onEjectUsb(vol: UsbVolumeInfo)
    }

    fun showAppContext(
        ctx: Context,
        entry: AppEntry,
        isPinned: Boolean,
        canMoveLeft: Boolean,
        canMoveRight: Boolean,
        onDismiss: () -> Unit,
        listener: AppMenuListener
    ): Dialog {
        val dialog = createDialog(ctx, onDismiss, yOffsetDp = -36)
        val card = createCard(ctx, padH = 28, padV = 24, radiusDp = 22)
        card.addView(buildAppContextHeader(ctx, entry))

        var firstFocusable: TextView? = null
        fun addAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
            if (!enabled) return
            val row = createActionRow(ctx, label, widthDp = 440, textSp = 19f, padH = 20, padV = 13) {
                dialog.dismiss()
                onClick()
            }
            if (firstFocusable == null) firstFocusable = row
            card.addView(row)
        }

        addAction("◀   Сдвинуть влево", canMoveLeft) { listener.onMoveLeft(entry) }
        addAction("▶   Сдвинуть вправо", canMoveRight) { listener.onMoveRight(entry) }
        addAction(if (isPinned) "☆   Убрать из избранного" else "★   Закрепить в избранном") {
            listener.onTogglePin(entry)
        }
        addAction("ℹ   О приложении (Android)", !entry.isVirtual) { listener.onOpenAppInfo(entry) }
        addAction("⊘   Скрыть с главного экрана") { listener.onHideApp(entry) }

        dialog.setContentView(card)
        dialog.show()
        firstFocusable?.requestFocus()
        return dialog
    }

    fun showQuickControls(
        ctx: Context,
        state: QuickControlState,
        onDismiss: () -> Unit,
        listener: QuickControlsListener
    ): Dialog {
        val dialog = createDialog(ctx, onDismiss, yOffsetDp = -12)
        val card = createCard(ctx, padH = 28, padV = 22, radiusDp = 22)
        card.addView(createTitleView(ctx, "Быстрые действия", 22f, bottomDp = 12))

        var first: TextView? = null
        fun addItem(label: String, onClick: () -> Unit) {
            val row = createActionRow(ctx, label, widthDp = 500, textSp = 18f, padH = 20, padV = 12) {
                dialog.dismiss()
                onClick()
            }
            if (first == null) first = row
            card.addView(row)
        }

        addItem("☾   Заставка сейчас") { listener.onAmbientNow() }
        addItem("🖼   Следующие обои") { listener.onNextWallpaper() }
        addItem("🎞   Ночной кино-режим · ${state.nightLabel}") { listener.onToggleNight() }
        addItem("🌙   Таймер сна · ${state.sleepLabel}") { listener.onOpenSleepTimer() }
        val remoteSub = if (state.webRemoteEnabled) {
            "${state.webRemoteUrl} · PIN ${state.webRemotePin}"
        } else {
            "Выключен"
        }
        addItem("📱   Web-пульт · $remoteSub") { listener.onOpenWebRemote() }
        addItem("📶   Переподключить Wi-Fi") { listener.onReconnectWifi() }
        for (vol in state.usbVolumes) {
            addItem("⏏   Безопасно извлечь USB · ${vol.label}") { listener.onEjectUsb(vol) }
        }

        dialog.setContentView(card)
        dialog.show()
        first?.requestFocus()
        return dialog
    }

    fun showHiddenApps(
        ctx: Context,
        hiddenItems: List<Pair<String, String>>,
        onUnhide: (String) -> Unit,
        onDismiss: () -> Unit
    ): Dialog {
        val dialog = createDialog(ctx, onDismiss)
        val card = createCard(ctx, padH = 26, padV = 22, radiusDp = 20)
        card.addView(createTitleView(ctx, "Вернуть скрытое приложение (${hiddenItems.size})", 21f, bottomDp = 14))
        val (listScroll, firstFocusable) = buildHiddenAppsList(ctx, hiddenItems, dialog, onUnhide)
        card.addView(listScroll)
        dialog.setContentView(card)
        dialog.show()
        firstFocusable?.requestFocus()
        return dialog
    }

    fun showConfirm(
        ctx: Context,
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String = "Отмена",
        onConfirm: () -> Unit,
        onDismiss: () -> Unit = {}
    ): Dialog {
        val dialog = createDialog(ctx, onDismiss)
        val card = createCard(ctx, padH = 28, padV = 24, radiusDp = 20)
        card.addView(createTitleView(ctx, title, 22f, bottomDp = 10))
        card.addView(TextView(ctx).apply {
            text = message
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 480), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(ctx, 18)
            }
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
        })
        val cancelRow = createActionRow(ctx, cancelLabel, widthDp = 480, textSp = 18f, padH = 20, padV = 13) {
            dialog.dismiss()
        }
        val confirmRow = createActionRow(ctx, confirmLabel, widthDp = 480, textSp = 18f, padH = 20, padV = 13) {
            dialog.dismiss()
            onConfirm()
        }
        card.addView(cancelRow)
        card.addView(confirmRow)
        dialog.setContentView(card)
        dialog.show()
        cancelRow.requestFocus()
        return dialog
    }

    private fun buildAppContextHeader(ctx: Context, entry: AppEntry): LinearLayout {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 18))
        }
        val iconView = ImageView(ctx).apply {
            entry.tileBitmap?.let { setImageBitmap(it) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)).apply {
                marginEnd = dp(ctx, 16)
            }
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(ctx).apply {
            text = entry.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        titleCol.addView(TextView(ctx).apply {
            text = entry.packageName
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        header.addView(iconView)
        header.addView(titleCol)
        return header
    }

    private fun buildHiddenAppsList(
        ctx: Context,
        hiddenItems: List<Pair<String, String>>,
        dialog: Dialog,
        onUnhide: (String) -> Unit
    ): Pair<ScrollView, TextView?> {
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var firstFocusable: TextView? = null
        for ((pkg, label) in hiddenItems) {
            val row = createActionRow(ctx, "↩  $label  ($pkg)", widthDp = 500, textSp = 17f, padH = 18, padV = 12) {
                dialog.dismiss()
                onUnhide(pkg)
            }
            if (firstFocusable == null) firstFocusable = row
            listBox.addView(row)
        }
        val maxListH = (ctx.resources.displayMetrics.heightPixels * 0.60f).toInt()
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (hiddenItems.size >= 6) maxListH else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return scroll to firstFocusable
    }

    internal fun createTitleView(ctx: Context, title: String, sp: Float, bottomDp: Int): TextView =
        TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, bottomDp))
        }

    internal fun createDialog(ctx: Context, onDismiss: () -> Unit, yOffsetDp: Int = 0): Dialog =
        Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.setDimAmount(0.68f)
                if (yOffsetDp != 0) {
                    val lp = win.attributes
                    lp.gravity = Gravity.CENTER
                    lp.y = dp(ctx, yOffsetDp)
                    win.attributes = lp
                }
            }
            setOnDismissListener { onDismiss() }
        }

    internal fun createCard(ctx: Context, padH: Int, padV: Int, radiusDp: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, padH), dp(ctx, padV), dp(ctx, padH), dp(ctx, padV))
            background = GradientDrawable().apply {
                setColor(0xFC10151E.toInt())
                cornerRadius = dp(ctx, radiusDp).toFloat()
                setStroke(dp(ctx, 1), 0x38FFFFFF)
            }
        }

    internal fun createActionRow(
        ctx: Context,
        label: String,
        widthDp: Int,
        textSp: Float,
        padH: Int,
        padV: Int,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = false
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(ctx, padH), dp(ctx, padV), dp(ctx, padH), dp(ctx, padV))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(ctx, 8)
        }
        setOnClickListener { onClick() }
    }

    internal fun dp(ctx: Context, v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)
}
