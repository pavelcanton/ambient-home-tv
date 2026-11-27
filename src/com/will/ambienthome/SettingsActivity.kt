package com.will.ambienthome

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.will.ambienthome.data.AppRepository
import com.will.ambienthome.data.LauncherStore
import com.will.ambienthome.data.SystemStatusHelper
import com.will.ambienthome.data.WallpaperRepository
import com.will.ambienthome.model.DiagnosticsSnapshot
import com.will.ambienthome.model.HomeMode
import com.will.ambienthome.model.LauncherState
import com.will.ambienthome.model.WallpaperSource
import com.will.ambienthome.ui.TvDialogs
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Экран настроек Ambient Home 3.0.0 + закреплённая панель системной диагностики и проверки Wi-Fi.
 * Расширенные секции (Ночной режим, Погода, Таймер сна, Web-пульт, WOL, JSON) вынесены в SettingsExtraHelper.
 */
class SettingsActivity : Activity() {

    companion object {
        private const val TAG = "AmbientHome"
        const val EXTRA_ROTATE_WALLPAPER = "com.will.ambienthome.EXTRA_ROTATE_WALLPAPER"
        const val EXTRA_SKIP_APP_REFRESH = "com.will.ambienthome.EXTRA_SKIP_APP_REFRESH"
        private const val REQ_MEDIA_PERM = 2001
        private const val STATE_EXTERNAL_OPENED = "external_opened"
        private const val STATE_RESET_CONFIRMED = "reset_confirmed"
        private val LOCALE_RU: Locale = Locale.forLanguageTag("ru")
        private val WP_INTERVALS = intArrayOf(0, 5, 10, 15, 30, 60)
        private val DIM_VALUES = intArrayOf(0, 15, 22, 35, 50)
        private val AMBIENT_VALUES = intArrayOf(0, 3, 5, 10, 15)
    }

    private lateinit var store: LauncherStore
    private lateinit var extraHelper: SettingsExtraHelper
    private var state: LauncherState = LauncherState()

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var scrollBox: LinearLayout
    private lateinit var diagNetView: TextView
    private lateinit var diagHwView: TextView

    private lateinit var rowMode: TextView
    private lateinit var rowWpSource: TextView
    private lateinit var rowWpNow: TextView
    private lateinit var rowWpInterval: TextView
    private lateinit var rowDim: TextView
    private lateinit var rowClock: TextView
    private lateinit var rowAmbient: TextView
    private lateinit var rowHidden: TextView
    private lateinit var rowResetOrder: TextView
    private lateinit var rowReconnectWifi: TextView
    private lateinit var rowRefreshDiag: TextView

    private var customWallpapersCount: Int = 0
    private var hasMediaPerm: Boolean = true
    private var lastIp: String = ""
    private var wifiActionNote: String = ""
    private var resetOrderConfirmed: Boolean = false
    private var firstResume: Boolean = true
    private var resumed: Boolean = false
    private var externalSettingsOpened: Boolean = false
    private var hiddenDialogGen: Long = 0L
    private var wifiActionGen: Long = 0L
    private var activeDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SKIP_APP_REFRESH, true))
        if (savedInstanceState != null) {
            resetOrderConfirmed = savedInstanceState.getBoolean(STATE_RESET_CONFIRMED, false)
            if (savedInstanceState.getBoolean(STATE_EXTERNAL_OPENED, false)) markExternalSettingsOpened()
        }
        store = LauncherStore(this)
        state = store.readState()
        setTitle(R.string.settings)

        setContentView(buildRootLayout())
        extraHelper = SettingsExtraHelper(this, store, mainHandler, ::runIo, buildExtraHost())
        buildAppearanceSection()
        extraHelper.buildNightAndAmbientRows()
        extraHelper.buildSmartWidgetsSection()
        extraHelper.buildCompanionAndBackupSection()
        buildAppsSection()
        buildSystemSection()

        hasMediaPerm = WallpaperRepository.hasMediaPermission(this)
        if (state.appearance.wallpaperSource.requiresMediaPermission() && !hasMediaPerm) {
            ensureWallpaperStoragePermission()
        }
        rowMode.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EXTERNAL_OPENED, externalSettingsOpened)
        outState.putBoolean(STATE_RESET_CONFIRMED, resetOrderConfirmed)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        wifiActionGen++
        state = store.readState()
        hasMediaPerm = WallpaperRepository.hasMediaPermission(this)
        if (!firstResume) wifiActionNote = ""
        firstResume = false
        bindRows()
        loadDiagnosticsAsync()
    }

    override fun onPause() {
        resumed = false
        hiddenDialogGen++
        wifiActionGen++
        super.onPause()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_PERM) {
            hasMediaPerm = WallpaperRepository.hasMediaPermission(this)
            bindRows()
        }
    }

    private fun buildExtraHost(): SettingsExtraHelper.Host = object : SettingsExtraHelper.Host {
        override fun addSection(title: String) = addSectionHeader(title)
        override fun addRow(onClick: () -> Unit): TextView = addRow("", onClick)
        override fun currentState(): LauncherState = state
        override fun currentIp(): String = lastIp
        override fun onStateChanged(next: LauncherState) {
            state = next
            bindRows()
        }
        override fun onDialogShown(dlg: Dialog) {
            if (activeDialog !== dlg) activeDialog?.dismiss()
            activeDialog = dlg
        }
        override fun onDialogDismissed(dlg: Dialog) {
            if (activeDialog === dlg) activeDialog = null
        }
    }

    private fun buildRootLayout(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D13.toInt())
            setPadding(dp(56), dp(28), dp(56), dp(24))
        }
        val verName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "3.0.1"
        } catch (_: Throwable) {
            "3.0.1"
        }
        outer.addView(TextView(this).apply {
            text = "Ambient Home $verName  ·  Настройки"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(dp(8), 0, 0, dp(14))
        })
        outer.addView(buildDiagnosticsCard())

        scrollBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        outer.addView(scroll)
        return outer
    }

    private fun buildAppearanceSection() {
        addSectionHeader("Внешний вид, обои и ночной режим")
        rowMode = addRow {
            state = store.updateAppearance {
                it.copy(homeMode = if (it.homeMode == HomeMode.DOCK) HomeMode.GRID else HomeMode.DOCK)
            }
            bindRows()
        }
        rowWpSource = addRow {
            state = store.updateAppearance { it.copy(wallpaperSource = WallpaperSource.next(it.wallpaperSource)) }
            if (state.appearance.wallpaperSource.requiresMediaPermission()) ensureWallpaperStoragePermission()
            bindRows()
        }
        rowWpNow = addRow {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_ROTATE_WALLPAPER, true).putExtra(EXTRA_SKIP_APP_REFRESH, !externalSettingsOpened)
            )
            finish()
        }
        rowWpInterval = addRow {
            state = store.updateAppearance { it.copy(wallpaperIntervalMin = nextOf(WP_INTERVALS, it.wallpaperIntervalMin)) }
            bindRows()
        }
        rowDim = addRow {
            state = store.updateAppearance { it.copy(dimPercent = nextOf(DIM_VALUES, it.dimPercent)) }
            bindRows()
        }
        rowClock = addRow {
            state = store.updateAppearance { it.copy(showClock = !it.showClock) }
            bindRows()
        }
        rowAmbient = addRow {
            state = store.updateAppearance { it.copy(ambientTimeoutMin = nextOf(AMBIENT_VALUES, it.ambientTimeoutMin)) }
            bindRows()
        }
    }

    private fun buildAppsSection() {
        addSectionHeader("Приложения")
        rowHidden = addRow { openHiddenAppsDialog() }
        rowResetOrder = addRow { openResetOrderConfirmDialog() }
    }

    private fun buildSystemSection() {
        addSectionHeader("Сеть и система")
        rowReconnectWifi = addRow {
            val gen = ++wifiActionGen
            wifiActionNote = "проверка и сканирование..."
            bindRows()
            runIo {
                val res = SystemStatusHelper.softReconnectWifi(applicationContext)
                val snap = SystemStatusHelper.readDiagnostics(applicationContext, res.status)
                mainHandler.post {
                    if (!resumed || isFinishing || isDestroyed || gen != wifiActionGen) return@post
                    wifiActionNote = res.message
                    bindDiagnostics(snap)
                    bindRows()
                }
            }
        }
        rowRefreshDiag = addRow { loadDiagnosticsAsync() }
        addRow("Настройки Wi-Fi Android") { markExternalSettingsOpened(); AppRepository.openWifiSettings(this) }
        addRow("Системные настройки Android") { markExternalSettingsOpened(); AppRepository.openAndroidSettings(this) }
    }

    private fun markExternalSettingsOpened() {
        externalSettingsOpened = true
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SKIP_APP_REFRESH, false))
    }

    private fun buildDiagnosticsCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF101722.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x2E7FD8A0)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        diagNetView = TextView(this).apply {
            text = "Сеть: считывание..."
            setTextColor(0xFF7FD8A0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        diagHwView = TextView(this).apply {
            text = "Система: считывание..."
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(diagNetView)
        card.addView(diagHwView)
        return card
    }

    private fun bindRows() {
        bindAppearanceRows()
        extraHelper.bindRows()
        val hiddenCount = state.layout.hiddenPackages.size
        rowHidden.text = line("Скрытые приложения", if (hiddenCount == 0) "Нет" else "$hiddenCount (нажмите, чтобы вернуть)")
        rowResetOrder.text = if (resetOrderConfirmed) "Порядок и избранное (★) сброшены ✓" else "Сбросить порядок и избранное (★)"
        rowReconnectWifi.text = if (wifiActionNote.isEmpty()) "Сканировать / переподключить Wi-Fi (WifiManager)" else "Wi-Fi  ·  $wifiActionNote"
        rowRefreshDiag.text = "Обновить диагностику системы"
    }

    private fun bindAppearanceRows() {
        val ap = state.appearance
        rowMode.text = line("Режим главного экрана", if (ap.homeMode == HomeMode.DOCK) "Док (Apple TV)" else "Сетка приложений")
        val wpInfo = if (ap.wallpaperSource.requiresMediaPermission()) {
            if (!hasMediaPerm) "${ap.wallpaperSource.label} (нет доступа к фото)"
            else "${ap.wallpaperSource.label} (найдено: $customWallpapersCount)"
        } else ap.wallpaperSource.label
        rowWpSource.text = line("Источник обоев", wpInfo)
        rowWpNow.text = "Сменить обои сейчас"
        rowWpInterval.text = line("Автосмена обоев", if (ap.wallpaperIntervalMin <= 0) "Выкл" else "${ap.wallpaperIntervalMin} мин")
        val dimText = if (ap.wallpaperSource == WallpaperSource.GRADIENT) "${ap.dimPercent} % (для фото)" else "${ap.dimPercent} %"
        rowDim.text = line("Затемнение обоев", dimText)
        rowClock.text = line("Часы и дата", if (ap.showClock) "Вкл" else "Выкл")
        rowAmbient.text = line("Ambient-заставка на HOME", if (ap.ambientTimeoutMin <= 0) "Выкл" else "через ${ap.ambientTimeoutMin} мин")
    }

    private fun loadDiagnosticsAsync() {
        runIo {
            val snap = SystemStatusHelper.readDiagnostics(applicationContext)
            val perm = WallpaperRepository.hasMediaPermission(applicationContext)
            val customCount = if (perm) WallpaperRepository.listCustomFiles().size else 0
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                hasMediaPerm = perm
                customWallpapersCount = customCount
                lastIp = snap.status.ip
                bindDiagnostics(snap)
                bindRows()
            }
        }
    }

    private fun bindDiagnostics(d: DiagnosticsSnapshot) {
        val st = d.status
        val netText = when {
            st.ethernetConnected -> "Сеть: Ethernet · IP ${st.ip.ifEmpty { "—" }}"
            st.wifiConnected -> buildWifiNetLine(st)
            else -> "Сеть: НЕТ ПОДКЛЮЧЕНИЯ"
        }
        diagNetView.text = netText
        diagNetView.setTextColor(if (st.isOnline) 0xFF7FD8A0.toInt() else 0xFFF87171.toInt())
        diagHwView.text = d.formatHardwareLine()
    }

    private fun buildWifiNetLine(st: com.will.ambienthome.model.SystemStatus): String {
        val parts = ArrayList<String>(4)
        parts.add("Wi-Fi: ${st.ssid.ifEmpty { "подключён" }}")
        parts.add("IP ${st.ip.ifEmpty { "—" }}")
        if (st.rssi > -120) parts.add("${st.rssi} dBm")
        if (st.linkSpeedMbps > 0) parts.add("${st.linkSpeedMbps} Мбит/с")
        return parts.joinToString(" · ")
    }

    private fun openHiddenAppsDialog() {
        val hidden = state.layout.hiddenPackages.sorted()
        if (hidden.isEmpty()) return
        val gen = ++hiddenDialogGen
        runIo {
            val resolved = AppRepository.resolveLabels(applicationContext, hidden)
                .sortedWith(compareBy<Pair<String, String>> { it.second.lowercase(LOCALE_RU) }.thenBy { it.first })
            mainHandler.post {
                if (!resumed || isFinishing || isDestroyed || gen != hiddenDialogGen) return@post
                activeDialog?.dismiss()
                var dlg: Dialog? = null
                dlg = TvDialogs.showHiddenApps(
                    ctx = this,
                    hiddenItems = resolved,
                    onUnhide = { pkg ->
                        state = store.updateAppsLayout { it.copy(hiddenPackages = it.hiddenPackages - pkg) }
                        bindRows()
                    },
                    onDismiss = { if (activeDialog === dlg) activeDialog = null }
                )
                activeDialog = dlg
            }
        }
    }

    private fun openResetOrderConfirmDialog() {
        hiddenDialogGen++
        activeDialog?.dismiss()
        var dlg: Dialog? = null
        dlg = TvDialogs.showConfirm(
            ctx = this,
            title = "Сбросить порядок и избранное (★)?",
            message = "Все закреплённые приложения (★) и ручной порядок в доке вернутся к стандартным.",
            confirmLabel = "Сбросить порядок и избранное",
            onConfirm = {
                state = store.updateAppsLayout { it.copy(dockOrder = emptyList(), pinnedPackages = emptyList()) }
                resetOrderConfirmed = true
                bindRows()
            },
            onDismiss = { if (activeDialog === dlg) activeDialog = null }
        )
        activeDialog = dlg
    }

    private fun addSectionHeader(title: String) {
        scrollBox.addView(TextView(this).apply {
            text = title.uppercase(LOCALE_RU)
            setTextColor(0xFF7FD8A0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.08f
            setPadding(dp(10), dp(14), 0, dp(8))
        })
    }

    private fun addRow(initialText: String = "", onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = initialText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
        scrollBox.addView(tv)
        return tv
    }

    private fun ensureWallpaperStoragePermission() {
        hasMediaPerm = WallpaperRepository.hasMediaPermission(this)
        if (!hasMediaPerm) {
            try {
                requestPermissions(arrayOf(WallpaperRepository.requiredMediaPermission()), REQ_MEDIA_PERM)
            } catch (t: Throwable) {
                Log.w(TAG, "requestPermissions failed", t)
            }
        }
    }

    private fun runIo(task: () -> Unit) {
        if (io.isShutdown || isFinishing || isDestroyed) return
        try {
            io.execute { try { task() } catch (t: Throwable) { Log.w(TAG, "io task failed", t) } }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "runIo rejected", e)
        }
    }

    private fun line(name: String, value: String): String = "$name    ·   $value"

    private fun nextOf(values: IntArray, current: Int): Int {
        val idx = values.indexOf(current)
        return values[if (idx < 0) 0 else (idx + 1) % values.size]
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
