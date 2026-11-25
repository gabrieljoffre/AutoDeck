package com.autodeck.protocol.phone

/**
 * Constantes e contratos do protocolo de comunicação local entre o tablet (`:app`)
 * e o celular Samsung (`:ponte`).
 *
 * Transporte: TCP JSON por linha (UTF-8, terminada em `\n`) na porta 5290.
 * Descoberta: NSD/mDNS (`_hondadeck._tcp.`) + broadcast UDP na porta 5291.
 */
object PhoneLinkProtocol {
    const val PROTOCOL_VERSION: Int = 1
    const val TCP_PORT: Int = 5290
    const val UDP_DISCOVERY_PORT: Int = 5291
    const val NSD_SERVICE_TYPE: String = "_hondadeck._tcp."
    const val NSD_SERVICE_NAME: String = "HondaDeckPonte"

    const val UDP_PROBE_MAGIC: String = "HONDADECK_PONTE_DISCOVER"
    const val UDP_ANNOUNCE_MAGIC: String = "HONDADECK_PONTE_HERE"

    // Métodos RPC (tablet -> celular)
    const val METHOD_PAIR = "pair"
    const val METHOD_AUTH = "auth"
    const val METHOD_PING = "ping"
    const val METHOD_STATUS_GET = "status.get"
    const val METHOD_CONTACTS_SEARCH = "contacts.search"
    const val METHOD_CALL_DIAL = "call.dial"
    const val METHOD_CALL_ANSWER = "call.answer"
    const val METHOD_CALL_END = "call.end"
    const val METHOD_WHATSAPP_SEND_TEXT = "whatsapp.sendText"
    const val METHOD_WHATSAPP_SEND_VOICE = "whatsapp.sendVoice"
    const val METHOD_WHATSAPP_CALL = "whatsapp.call"
    const val METHOD_MESSAGES_LIST = "messages.list"
    const val METHOD_MESSAGES_REPLY = "messages.reply"
    const val METHOD_MESSAGES_DISMISS = "messages.dismiss"

    // Eventos assíncronos (celular -> tablet)
    const val EVENT_CALL_STATE = "call.state"
    const val EVENT_MESSAGE_RECEIVED = "message.received"
    const val EVENT_MESSAGE_REMOVED = "message.removed"
    const val EVENT_PHONE_STATUS = "phone.status"

    // Códigos de erro padronizados
    const val ERR_UNAUTHORIZED = "UNAUTHORIZED"
    const val ERR_INVALID_PIN = "INVALID_PIN"
    const val ERR_PHONE_LOCKED = "PHONE_LOCKED"
    const val ERR_ACCESSIBILITY_DISABLED = "ACCESSIBILITY_DISABLED"
    const val ERR_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val ERR_WHATSAPP_NOT_INSTALLED = "WHATSAPP_NOT_INSTALLED"
    const val ERR_NOT_FOUND = "NOT_FOUND"
    const val ERR_INVALID_PARAMS = "INVALID_PARAMS"
    const val ERR_INTERNAL = "INTERNAL"
}

enum class PhoneCallStatus {
    IDLE,
    RINGING,
    DIALING,
    ACTIVE,
    DISCONNECTED;

    companion object {
        fun fromWire(raw: String?): PhoneCallStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: IDLE
    }
}

data class PhoneCallInfo(
    val status: PhoneCallStatus = PhoneCallStatus.IDLE,
    val number: String = "",
    val displayName: String = "",
    val isWhatsApp: Boolean = false,
    val startedAtMs: Long = 0L
) {
    val isInCall: Boolean
        get() = status == PhoneCallStatus.RINGING ||
            status == PhoneCallStatus.DIALING ||
            status == PhoneCallStatus.ACTIVE
}

data class PhoneContact(
    val id: String,
    val name: String,
    val phone: String,
    val normalizedPhone: String,
    val hasWhatsApp: Boolean = false,
    val whatsAppVoipDataId: Long? = null
)

data class PhoneMessageNotification(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val timestampMs: Long,
    val canReply: Boolean = true,
    val isRead: Boolean = false
)

data class UdpDiscoveryAnnouncement(
    val name: String,
    val port: Int = PhoneLinkProtocol.TCP_PORT,
    val deviceId: String = "",
    val protocolVersion: Int = PhoneLinkProtocol.PROTOCOL_VERSION
)

sealed interface PhoneLinkMessage {
    data class Request(
        val id: String,
        val method: String,
        val params: Map<String, Any?> = emptyMap()
    ) : PhoneLinkMessage

    data class Response(
        val id: String,
        val ok: Boolean,
        val result: Map<String, Any?> = emptyMap(),
        val error: String? = null,
        val errorCode: String? = null
    ) : PhoneLinkMessage

    data class Event(
        val event: String,
        val data: Map<String, Any?> = emptyMap()
    ) : PhoneLinkMessage
}

/**
 * Serializador/desserializador JSON puro (sem dependências Android ou bibliotecas externas)
 * para as mensagens linha-a-linha do protocolo Ponte.
 */
object PhoneLinkCodec {

    fun encode(message: PhoneLinkMessage): String = when (message) {
        is PhoneLinkMessage.Request -> encodeMap(
            linkedMapOf(
                "v" to PhoneLinkProtocol.PROTOCOL_VERSION,
                "type" to "req",
                "id" to message.id,
                "method" to message.method,
                "params" to message.params
            )
        )

        is PhoneLinkMessage.Response -> {
            val map = linkedMapOf<String, Any?>(
                "v" to PhoneLinkProtocol.PROTOCOL_VERSION,
                "type" to "res",
                "id" to message.id,
                "ok" to message.ok,
                "result" to message.result
            )
            if (!message.error.isNullOrBlank()) map["error"] = message.error
            if (!message.errorCode.isNullOrBlank()) map["errorCode"] = message.errorCode
            encodeMap(map)
        }

        is PhoneLinkMessage.Event -> encodeMap(
            linkedMapOf(
                "v" to PhoneLinkProtocol.PROTOCOL_VERSION,
                "type" to "event",
                "event" to message.event,
                "data" to message.data
            )
        )
    }

    fun decode(line: String): PhoneLinkMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val root = parseJsonValue(trimmed) as? Map<*, *> ?: return null
        return when (root["type"] as? String) {
            "req" -> {
                val id = root["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
                val method = root["method"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
                val params = asStringMap(root["params"])
                PhoneLinkMessage.Request(id = id, method = method, params = params)
            }

            "res" -> {
                val id = root["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
                val ok = root["ok"] as? Boolean ?: false
                val result = asStringMap(root["result"])
                val error = root["error"]?.toString()
                val errorCode = root["errorCode"]?.toString()
                PhoneLinkMessage.Response(
                    id = id,
                    ok = ok,
                    result = result,
                    error = error,
                    errorCode = errorCode
                )
            }

            "event" -> {
                val event = root["event"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
                val data = asStringMap(root["data"])
                PhoneLinkMessage.Event(event = event, data = data)
            }

            else -> null
        }
    }

    fun encodeUdpProbe(clientName: String = "HondaDeck"): String =
        encodeMap(
            linkedMapOf(
                "magic" to PhoneLinkProtocol.UDP_PROBE_MAGIC,
                "v" to PhoneLinkProtocol.PROTOCOL_VERSION,
                "clientName" to clientName
            )
        )

    fun isUdpProbe(payload: String): Boolean {
        val trimmed = payload.trim()
        if (trimmed == PhoneLinkProtocol.UDP_PROBE_MAGIC) return true
        val map = parseJsonValue(trimmed) as? Map<*, *> ?: return false
        return map["magic"] == PhoneLinkProtocol.UDP_PROBE_MAGIC
    }

    fun encodeUdpAnnouncement(info: UdpDiscoveryAnnouncement): String =
        encodeMap(
            linkedMapOf(
                "magic" to PhoneLinkProtocol.UDP_ANNOUNCE_MAGIC,
                "v" to info.protocolVersion,
                "name" to info.name,
                "port" to info.port,
                "deviceId" to info.deviceId
            )
        )

    fun decodeUdpAnnouncement(payload: String): UdpDiscoveryAnnouncement? {
        val map = parseJsonValue(payload.trim()) as? Map<*, *> ?: return null
        if (map["magic"] != PhoneLinkProtocol.UDP_ANNOUNCE_MAGIC) return null
        val name = map["name"]?.toString()?.ifBlank { PhoneLinkProtocol.NSD_SERVICE_NAME }
            ?: PhoneLinkProtocol.NSD_SERVICE_NAME
        val port = (map["port"] as? Number)?.toInt() ?: PhoneLinkProtocol.TCP_PORT
        val deviceId = map["deviceId"]?.toString().orEmpty()
        val version = (map["v"] as? Number)?.toInt() ?: PhoneLinkProtocol.PROTOCOL_VERSION
        return UdpDiscoveryAnnouncement(name = name, port = port, deviceId = deviceId, protocolVersion = version)
    }

    fun contactToMap(c: PhoneContact): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "name" to c.name,
        "phone" to c.phone,
        "normalizedPhone" to c.normalizedPhone,
        "hasWhatsApp" to c.hasWhatsApp,
        "whatsAppVoipDataId" to c.whatsAppVoipDataId
    )

    fun mapToContact(raw: Any?): PhoneContact? {
        val m = raw as? Map<*, *> ?: return null
        val name = m["name"]?.toString().orEmpty()
        val phone = m["phone"]?.toString().orEmpty()
        val norm = m["normalizedPhone"]?.toString().orEmpty().ifBlank { phone }
        if (name.isBlank() && norm.isBlank()) return null
        return PhoneContact(
            id = m["id"]?.toString().orEmpty(),
            name = name,
            phone = phone,
            normalizedPhone = norm,
            hasWhatsApp = m["hasWhatsApp"] as? Boolean ?: false,
            whatsAppVoipDataId = (m["whatsAppVoipDataId"] as? Number)?.toLong()
        )
    }

    fun callInfoToMap(info: PhoneCallInfo): Map<String, Any?> = linkedMapOf(
        "status" to info.status.name,
        "number" to info.number,
        "displayName" to info.displayName,
        "isWhatsApp" to info.isWhatsApp,
        "startedAtMs" to info.startedAtMs
    )

    fun mapToCallInfo(raw: Any?): PhoneCallInfo {
        val m = raw as? Map<*, *> ?: return PhoneCallInfo()
        return PhoneCallInfo(
            status = PhoneCallStatus.fromWire(m["status"]?.toString()),
            number = m["number"]?.toString().orEmpty(),
            displayName = m["displayName"]?.toString().orEmpty(),
            isWhatsApp = m["isWhatsApp"] as? Boolean ?: false,
            startedAtMs = (m["startedAtMs"] as? Number)?.toLong() ?: 0L
        )
    }

    fun messageToMap(msg: PhoneMessageNotification): Map<String, Any?> = linkedMapOf(
        "id" to msg.id,
        "packageName" to msg.packageName,
        "appLabel" to msg.appLabel,
        "sender" to msg.sender,
        "text" to msg.text,
        "timestampMs" to msg.timestampMs,
        "canReply" to msg.canReply,
        "isRead" to msg.isRead
    )

    fun mapToMessage(raw: Any?): PhoneMessageNotification? {
        val m = raw as? Map<*, *> ?: return null
        val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return PhoneMessageNotification(
            id = id,
            packageName = m["packageName"]?.toString().orEmpty(),
            appLabel = m["appLabel"]?.toString().orEmpty().ifBlank { "Mensagem" },
            sender = m["sender"]?.toString().orEmpty(),
            text = m["text"]?.toString().orEmpty(),
            timestampMs = (m["timestampMs"] as? Number)?.toLong() ?: 0L,
            canReply = m["canReply"] as? Boolean ?: true,
            isRead = m["isRead"] as? Boolean ?: false
        )
    }

    // -------------------------------------------------------------------------
    // JSON encoder / parser minimalista (RFC 8259)
    // -------------------------------------------------------------------------

    fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            val d = value.toDouble()
            if (!d.isFinite()) "0" else value.toString()
        }
        is String -> encodeString(value)
        is Map<*, *> -> encodeMap(value)
        is Iterable<*> -> buildString {
            append('[')
            var first = true
            for (item in value) {
                if (!first) append(',')
                first = false
                append(encodeValue(item))
            }
            append(']')
        }
        is Array<*> -> encodeValue(value.asList())
        else -> encodeString(value.toString())
    }

    private fun encodeMap(map: Map<*, *>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in map) {
            if (k == null) continue
            if (!first) append(',')
            first = false
            append(encodeString(k.toString()))
            append(':')
            append(encodeValue(v))
        }
        append('}')
    }

    private fun encodeString(str: String): String = buildString(str.length + 2) {
        append('"')
        for (ch in str) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private fun asStringMap(raw: Any?): Map<String, Any?> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(m.size)
        for ((k, v) in m) {
            if (k != null) out[k.toString()] = v
        }
        return out
    }

    internal fun parseJsonValue(src: String): Any? = runCatching {
        val p = MiniJsonParser(src)
        val v = p.parseValue()
        p.skipWhitespace()
        if (p.hasMore()) null else v
    }.getOrNull()

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun hasMore(): Boolean = pos < src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) error("EOF inesperado")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Caractere inválido '${src[pos]}' em $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> error("Esperado ',' ou '}' em $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> error("Esperado ',' ou ']' em $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("Escape truncado")
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
                                if (pos + 4 > src.length) error("Unicode escape truncado")
                                val code = src.substring(pos, pos + 4).toInt(16)
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> error("Escape inválido '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            error("String não fechada")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (peek() == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = true
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!src.startsWith(expected, pos)) error("Esperado '$expected' em $pos")
            pos += expected.length
            return value
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun expect(ch: Char) {
            if (pos >= src.length || src[pos] != ch) error("Esperado '$ch' em $pos")
            pos++
        }
    }
}
