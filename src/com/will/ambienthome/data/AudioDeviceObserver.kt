package com.will.ambienthome.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.storage.StorageManager
import android.provider.Settings
import android.content.Intent
import android.content.ComponentName
import android.util.Log
import com.will.ambienthome.model.UsbVolumeInfo

/**
 * H1-безопасный наблюдатель Bluetooth-аудио (0 BT-сканирований, 0% CPU).
 * Использует AudioManager.registerAudioDeviceCallback() строго пока HOME активен.
 * Также предоставляет чтение подключённых USB-накопителей через StorageManager.
 */
class AudioDeviceObserver(
    private val appContext: Context,
    private val mainHandler: Handler,
    private val onBtAudioChanged: (Boolean) -> Unit
) {

    private var callback: AudioDeviceCallback? = null
    private var lastConnected: Boolean = false

    fun start() {
        if (callback != null) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (callback === this) refresh(am)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (callback === this) refresh(am)
            }
        }
        try {
            am.registerAudioDeviceCallback(cb, mainHandler)
            callback = cb
            refresh(am)
        } catch (t: Throwable) {
            Log.w(TAG, "registerAudioDeviceCallback failed", t)
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.unregisterAudioDeviceCallback(cb)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterAudioDeviceCallback failed", t)
        }
    }

    fun isBtAudioConnected(): Boolean = lastConnected

    private fun refresh(am: AudioManager) {
        val connected = try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.any { isBluetoothAudioType(it.type) }
        } catch (t: Throwable) {
            Log.w(TAG, "getDevices(OUTPUTS) failed", t)
            false
        }
        if (connected != lastConnected) {
            lastConnected = connected
            onBtAudioChanged(connected)
        }
    }

    private fun isBluetoothAudioType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == TYPE_BLE_HEADSET_COMPAT ||
            type == TYPE_BLE_SPEAKER_COMPAT

    companion object {
        private const val TAG = "AmbientHome"
        private const val TYPE_BLE_HEADSET_COMPAT = 26
        private const val TYPE_BLE_SPEAKER_COMPAT = 27

        fun openBluetoothSettings(ctx: Context) {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            try {
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(flags))
                return
            } catch (_: Throwable) {
            }
            try {
                val tvBt = Intent().apply {
                    component = ComponentName(
                        "com.android.tv.settings",
                        "com.android.tv.settings.accessories.AddAccessoryActivity"
                    )
                    addFlags(flags)
                }
                ctx.startActivity(tvBt)
                return
            } catch (_: Throwable) {
            }
            AppRepository.openAndroidSettings(ctx)
        }

        fun listExternalUsbVolumes(ctx: Context): List<UsbVolumeInfo> {
            return try {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
                val out = ArrayList<UsbVolumeInfo>()
                for (vol in sm.storageVolumes) {
                    if (!vol.isRemovable || vol.isPrimary) continue
                    if (vol.state != android.os.Environment.MEDIA_MOUNTED) continue
                    val uuid = vol.uuid?.trim().orEmpty()
                    val voldId = try {
                        (vol.javaClass.getMethod("getId").invoke(vol) as? String)?.trim().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val label = try {
                        vol.getDescription(ctx)?.trim().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }.ifEmpty { "USB ($uuid)" }
                    val id = when {
                        voldId.isNotEmpty() -> voldId
                        uuid.isNotEmpty() -> "public:$uuid"
                        else -> continue
                    }
                    out.add(UsbVolumeInfo(id = id, label = label))
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "listExternalUsbVolumes failed", t)
                emptyList()
            }
        }

        fun openStorageSettings(ctx: Context) {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            try {
                ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(flags))
            } catch (_: Throwable) {
                AppRepository.openAndroidSettings(ctx)
            }
        }
    }
}
