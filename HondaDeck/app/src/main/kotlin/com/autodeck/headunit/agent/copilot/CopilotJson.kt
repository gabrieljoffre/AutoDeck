package com.autodeck.headunit.agent.copilot

/**
 * Parser e serializador JSON leve em Kotlin puro (sem dependência de org.json do Android,
 * que nos testes unitários JVM com isReturnDefaultValues=true é apenas um stub vazio).
 */
sealed interface JsonValue {
    fun toJsonString(): String
}

data object JsonNull : JsonValue {
    override fun toJsonString(): String = "null"
}

data class JsonBool(val value: Boolean) : JsonValue {
    override fun toJsonString(): String = if (value) "true" else "false"
}

data class JsonNum(val raw: String) : JsonValue {
    val asDouble: Double? get() = raw.toDoubleOrNull()
    val asInt: Int? get() = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    val asLong: Long? get() = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()

    constructor(number: Number) : this(number.toString())

    override fun toJsonString(): String = raw
}

data class JsonStr(val value: String) : JsonValue {
    override fun toJsonString(): String = CopilotJson.quote(value)
}

data class JsonArr(val items: List<JsonValue> = emptyList()) : JsonValue {
    val size: Int get() = items.size
    operator fun get(index: Int): JsonValue? = items.getOrNull(index)
    fun optObj(index: Int): JsonObj? = items.getOrNull(index) as? JsonObj
    fun optArr(index: Int): JsonArr? = items.getOrNull(index) as? JsonArr
    fun optString(index: Int): String? = (items.getOrNull(index) as? JsonStr)?.value
    fun optDouble(index: Int): Double? = (items.getOrNull(index) as? JsonNum)?.asDouble
    fun optInt(index: Int): Int? = (items.getOrNull(index) as? JsonNum)?.asInt

    override fun toJsonString(): String =
        items.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJsonString() }
}

data class JsonObj(val entries: Map<String, JsonValue> = emptyMap()) : JsonValue {
    operator fun get(key: String): JsonValue? = entries[key]
    fun has(key: String): Boolean = key in entries && entries[key] !is JsonNull
    fun optObj(key: String): JsonObj? = entries[key] as? JsonObj
    fun optArr(key: String): JsonArr? = entries[key] as? JsonArr

    fun optString(key: String): String? = when (val v = entries[key]) {
        is JsonStr -> v.value
        is JsonNum -> v.raw
        is JsonBool -> v.toJsonString()
        else -> null
    }

    fun optStrictString(key: String): String? = (entries[key] as? JsonStr)?.value
    fun optInt(key: String): Int? = (entries[key] as? JsonNum)?.asInt
    fun optDouble(key: String): Double? = (entries[key] as? JsonNum)?.asDouble
    fun optBoolean(key: String): Boolean? = (entries[key] as? JsonBool)?.value

    fun with(key: String, value: JsonValue): JsonObj {
        val next = LinkedHashMap(entries)
        next[key] = value
        return JsonObj(next)
    }

    override fun toJsonString(): String =
        entries.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
            "${CopilotJson.quote(k)}:${v.toJsonString()}"
        }
}

object CopilotJson {

    fun parseOrNull(input: String): JsonValue? = runCatching {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val reader = Reader(trimmed)
        val value = reader.readValue()
        reader.skipWs()
        if (!reader.isEof()) return null
        value
    }.getOrNull()

    fun parseObjOrNull(input: String): JsonObj? = parseOrNull(input) as? JsonObj

    fun parseArrOrNull(input: String): JsonArr? = parseOrNull(input) as? JsonArr

    fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun isEof(): Boolean = pos >= src.length

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWs()
            if (pos >= src.length) error("JSON inesperado no fim")
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonStr(readString())
                't' -> {
                    expectLiteral("true")
                    JsonBool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonBool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonNull
                }
                '-', in '0'..'9' -> readNumber()
                else -> error("Caractere JSON inválido: '$ch' na posição $pos")
            }
        }

        private fun expectLiteral(lit: String) {
            if (!src.startsWith(lit, pos)) error("Esperado '$lit' na posição $pos")
            pos += lit.length
        }

        private fun readObject(): JsonObj {
            pos++ // '{'
            skipWs()
            val map = LinkedHashMap<String, JsonValue>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return JsonObj(map)
            }
            while (true) {
                skipWs()
                if (pos < src.length && src[pos] == '}') {
                    // Tolera vírgula final (trailing comma) gerada ocasionalmente por LLMs
                    pos++
                    break
                }
                if (pos >= src.length || src[pos] != '"') error("Esperada chave em string na posição $pos")
                val key = readString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') error("Esperado ':' após chave '$key'")
                pos++
                val value = readValue()
                map[key] = value
                skipWs()
                if (pos >= src.length) error("Objeto JSON não fechado")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> error("Esperado ',' ou '}' na posição $pos")
                }
            }
            return JsonObj(map)
        }

        private fun readArray(): JsonArr {
            pos++ // '['
            skipWs()
            val list = ArrayList<JsonValue>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return JsonArr(list)
            }
            while (true) {
                skipWs()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                }
                list.add(readValue())
                skipWs()
                if (pos >= src.length) error("Array JSON não fechado")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        break
                    }
                    else -> error("Esperado ',' ou ']' na posição $pos")
                }
            }
            return JsonArr(list)
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("Escape incompleto no fim da string")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("Escape unicode incompleto")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: error("Hex unicode inválido: $hex")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            error("String JSON não fechada")
        }

        private fun readNumber(): JsonNum {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length || !src[pos].isDigit()) error("Número JSON inválido")
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                if (pos >= src.length || !src[pos].isDigit()) error("Fração inválida em número JSON")
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || !src[pos].isDigit()) error("Expoente inválido em número JSON")
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return JsonNum(src.substring(start, pos))
        }
    }
}
