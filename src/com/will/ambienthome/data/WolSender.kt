package com.will.ambienthome.data

import android.util.Log
import com.will.ambienthome.model.CustomShortcuts
import com.will.ambienthome.model.WolTarget
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Отправка Wake-on-LAN Magic Packet (UDP:9, 6x0xFF + 16xMAC) по домашней LAN.
 * Выполняется строго в фоновом IO-потоке.
 */
object WolSender {

    private const val TAG = "AmbientHome"

    data class WolResult(
        val success: Boolean,
        val message: String
    )

    fun send(target: WolTarget, localIp: String = ""): WolResult {
        val packetBytes = CustomShortcuts.buildMagicPacket(target.mac)
            ?: return WolResult(false, "Не задан корректный MAC-адрес ПК")
        val normalizedMac = CustomShortcuts.normalizeMac(target.mac) ?: target.mac
        val destinations = LinkedHashSet<String>()
        destinations.add(CustomShortcuts.normalizeBroadcastIp(target.broadcastIp))
        CustomShortcuts.deriveSubnetBroadcast(localIp)?.let { destinations.add(it) }

        var sentCount = 0
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (ip in destinations) {
                    try {
                        val addr = InetAddress.getByName(ip)
                        val dp = DatagramPacket(packetBytes, packetBytes.size, addr, CustomShortcuts.WOL_PORT)
                        socket.send(dp)
                        sentCount++
                    } catch (t: Throwable) {
                        Log.w(TAG, "WoL send failed to $ip", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WoL DatagramSocket failed", t)
            return WolResult(false, "Ошибка сети при отправке WOL")
        }
        return if (sentCount > 0) {
            WolResult(true, "WOL отправлен на $normalizedMac")
        } else {
            WolResult(false, "Не удалось отправить WOL-пакет")
        }
    }
}
