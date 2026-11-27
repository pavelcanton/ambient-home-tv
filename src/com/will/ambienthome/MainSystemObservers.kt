package com.will.ambienthome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Наблюдатели системных событий (изменения пакетов и состояния сети) для MainActivity.
 * Подключаются строго в onResume() и отключаются в onPause() (0% фоновой работы).
 */
internal class MainSystemObservers(
    private val context: Context,
    private val onPackagesChanged: () -> Unit,
    private val onNetworkChanged: () -> Unit
) {

    private companion object {
        const val TAG = "AmbientHome"
    }

    private var packageReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var active: Boolean = false

    fun start() {
        active = true
        registerPackageReceiver()
        registerNetworkObserver()
    }

    fun stop() {
        active = false
        unregisterPackageReceiver()
        unregisterNetworkObserver()
    }

    private fun registerPackageReceiver() {
        if (packageReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!active) return
                val action = intent?.action ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (replacing && (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED)) return
                onPackagesChanged()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            packageReceiver = receiver
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver(package) failed", t)
        }
    }

    private fun unregisterPackageReceiver() {
        val r = packageReceiver ?: return
        packageReceiver = null
        try { context.unregisterReceiver(r) } catch (_: Throwable) {}
    }

    private fun registerNetworkObserver() {
        if (netCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkChanged()
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", t)
        }
    }

    private fun unregisterNetworkObserver() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }
}
