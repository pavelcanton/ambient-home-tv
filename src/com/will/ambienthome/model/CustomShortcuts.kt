package com.will.ambienthome.model

/**
 * Чистая логика Wake-on-LAN и виртуальных ярлыков Дока (Ambient Home 3.0.0).
 * Проверяет/нормализует MAC-адрес и собирает стандартный Magic Packet (6x0xFF + 16xMAC = 102 байта).
 */
object CustomShortcuts {

    const val WOL_PACKAGE = "com.will.ambienthome.shortcut.wol"
    const val WOL_CLASS = "com.will.ambienthome.shortcut.WolAction"
    const val WOL_PORT = 9
    private const val MAGIC_PACKET_LEN = 102

    fun isWolShortcut(packageName: String): Boolean = packageName == WOL_PACKAGE

    fun isValidMac(raw: String): Boolean = normalizeMac(raw) != null

    fun normalizeMac(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hex = extractHexMac(trimmed) ?: return null
        if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
        return buildString(17) {
            for (i in 0 until 12 step 2) {
                if (i > 0) append(':')
                append(hex[i])
                append(hex[i + 1])
            }
        }
    }

    fun buildMagicPacket(mac: String): ByteArray? {
        val normalized = normalizeMac(mac) ?: return null
        val macBytes = ByteArray(6)
        for (i in 0 until 6) {
            val pos = i * 3
            val hi = hexDigit(normalized[pos])
            val lo = hexDigit(normalized[pos + 1])
            if (hi < 0 || lo < 0) return null
            macBytes[i] = ((hi shl 4) or lo).toByte()
        }
        val packet = ByteArray(MAGIC_PACKET_LEN)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
        }
        return packet
    }

    fun normalizeBroadcastIp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return WolTarget.DEFAULT_BROADCAST
        val parts = trimmed.split('.')
        if (parts.size != 4) return WolTarget.DEFAULT_BROADCAST
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return WolTarget.DEFAULT_BROADCAST
            val n = p.toIntOrNull() ?: return WolTarget.DEFAULT_BROADCAST
            if (n !in 0..255) return WolTarget.DEFAULT_BROADCAST
        }
        return trimmed
    }

    fun deriveSubnetBroadcast(localIp: String): String? {
        val parts = localIp.trim().split('.')
        if (parts.size != 4) return null
        for (i in 0 until 3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
        }
        val last = parts[3].toIntOrNull() ?: return null
        if (last !in 1..254) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    private fun extractHexMac(input: String): String? {
        val hasSep = input.indexOf(':') >= 0 || input.indexOf('-') >= 0
        if (hasSep) {
            val sep = if (input.indexOf(':') >= 0) ':' else '-'
            if (input.indexOf(if (sep == ':') '-' else ':') >= 0) return null
            val groups = input.split(sep)
            if (groups.size != 6) return null
            val sb = StringBuilder(12)
            for (g in groups) {
                if (g.length != 2 || hexDigit(g[0]) < 0 || hexDigit(g[1]) < 0) return null
                sb.append(g.uppercase())
            }
            return sb.toString()
        }
        if (input.length != 12) return null
        val sb = StringBuilder(12)
        for (ch in input) {
            if (hexDigit(ch) < 0) return null
            sb.append(ch.uppercaseChar())
        }
        return sb.toString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
