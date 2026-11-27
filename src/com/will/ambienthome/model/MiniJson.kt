package com.will.ambienthome.model

/**
 * Компактный RFC-8259 JSON парсер и сериализатор на чистом Kotlin (0 зависимостей).
 * Работает идентично на Android 8+ и в автономных JVM unit-тестах (без Stub! в android.jar).
 */
object MiniJson {

    private const val MAX_DEPTH = 32

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? {
        val value = parseAny(text)
        return value as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?>? {
        val value = parseAny(text)
        return value as? List<Any?>
    }

    fun parseAny(text: String): Any? {
        val p = Parser(text)
        return try {
            p.skipWs()
            val v = p.readValue(0)
            p.skipWs()
            if (p.hasMore()) null else v
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder(128)
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    fun escapeString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        appendEscaped(sb, s)
        return sb.toString()
    }

    fun asDouble(v: Any?): Double? {
        val d = when (v) {
            is Double -> v
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is Float -> v.toDouble()
            is Number -> v.toDouble()
            else -> return null
        }
        return if (d.isFinite()) d else null
    }

    fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
        is Double -> if (v.isFinite() && v >= Int.MIN_VALUE.toDouble() && v <= Int.MAX_VALUE.toDouble()) v.toInt() else null
        is Float -> if (v.isFinite() && v >= Int.MIN_VALUE.toFloat() && v <= Int.MAX_VALUE.toFloat()) v.toInt() else null
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()) d.toInt() else null
        }
        else -> null
    }

    private fun writeValue(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(value.toString())
            }
            is String -> appendEscaped(sb, value)
            is Map<*, *> -> writeObject(sb, value, pretty, depth)
            is Iterable<*> -> writeArray(sb, value, pretty, depth)
            else -> appendEscaped(sb, value.toString())
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, pretty: Boolean, depth: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            if (pretty) appendIndent(sb, depth + 1)
            appendEscaped(sb, k.toString())
            sb.append(if (pretty) ": " else ":")
            writeValue(sb, v, pretty, depth + 1)
        }
        if (pretty) appendIndent(sb, depth)
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, items: Iterable<*>, pretty: Boolean, depth: Int) {
        val list = items as? List<*> ?: items.toList()
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        for (i in list.indices) {
            if (i > 0) sb.append(',')
            if (pretty) appendIndent(sb, depth + 1)
            writeValue(sb, list[i], pretty, depth + 1)
        }
        if (pretty) appendIndent(sb, depth)
        sb.append(']')
    }

    private fun appendIndent(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth * 2) { sb.append(' ') }
    }

    private fun appendEscaped(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun hasMore(): Boolean = pos < s.length

        fun skipWs() {
            while (pos < s.length && isWs(s[pos])) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH || pos >= s.length) fail()
            return when (s[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> fail()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail()
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail()
                pos++
                skipWs()
                map[key] = readValue(depth)
                skipWs()
                if (pos >= s.length) fail()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue(depth))
                skipWs()
                if (pos >= s.length) fail()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail()
                }
            }
        }

        private fun readString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> sb.append(readEscape())
                    ch.code < 0x20 -> fail()
                    else -> sb.append(ch)
                }
            }
            fail()
        }

        private fun readEscape(): Char {
            if (pos >= s.length) fail()
            return when (val e = s[pos++]) {
                '"', '\\', '/' -> e
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> fail()
            }
        }

        private fun readUnicodeEscape(): Char {
            if (pos + 4 > s.length) fail()
            var code = 0
            for (i in 0 until 4) {
                val ch = s[pos + i]
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> fail()
                }
                code = (code shl 4) or digit
            }
            pos += 4
            return code.toChar()
        }

        private fun readLiteral(expected: String, result: Any?): Any? {
            if (!s.startsWith(expected, pos)) fail()
            pos += expected.length
            return result
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length || s[pos] !in '0'..'9') fail()
            if (s[pos] == '0') pos++ else while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail()
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail()
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            if (isFloat) {
                val d = token.toDoubleOrNull() ?: fail()
                if (!d.isFinite()) fail()
                return d
            }
            token.toLongOrNull()?.let { return it }
            val d = token.toDoubleOrNull() ?: fail()
            if (!d.isFinite()) fail()
            return d
        }

        private fun isWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'
        private fun fail(): Nothing = throw IllegalArgumentException("Invalid JSON at $pos")
    }
}
