package com.autodeck.protocol.carplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

enum class CpcMessageType(val id: Int) {
    OPEN(0x01),
    PLUGGED(0x02),
    PHASE(0x03),
    UNPLUGGED(0x04),
    TOUCH(0x05),
    VIDEO_DATA(0x06),
    AUDIO_DATA(0x07),
    COMMAND(0x08),
    MANUFACTURER_INFO(0x14),
    MULTI_TOUCH(0x17),
    BOX_SETTINGS(0x19),
    SEND_FILE(0x99),
    HEARTBEAT(0xAA)
}

object CpcCommandId {
    const val START_RECORD_AUDIO = 1
    const val STOP_RECORD_AUDIO = 2
    const val REQUEST_HOST_UI = 3
    const val MIC = 7
    const val FRAME = 12
    const val NIGHT_MODE_ON = 16
    const val NIGHT_MODE_OFF = 17
    const val BOX_MIC = 15
    const val AUDIO_TRANSFER_ON = 22
    const val AUDIO_TRANSFER_OFF = 23
    const val WIFI_24G = 24
    const val WIFI_5G = 25
    const val WIFI_ENABLE = 1000
    const val AUTO_CONNECT_ENABLE = 1001
    const val WIFI_CONNECT = 1002
}

enum class CpcPhoneType(val id: Int, val labelPt: String) {
    CARPLAY(3, "Apple CarPlay"),
    ANDROID_AUTO(5, "Android Auto"),
    UNKNOWN(0, "Telefone Conectado")
}

enum class TouchAction(val code: Int) {
    DOWN(14),
    MOVE(15),
    UP(16)
}

enum class MultiTouchAction(val code: Int) {
    UP(0),
    DOWN(1),
    MOVE(2)
}

data class MultiTouchPoint(
    val id: Int,
    val normalizedX: Float,
    val normalizedY: Float,
    val action: MultiTouchAction
)

data class CpcHeader(
    val length: Int,
    val type: Int
)

data class CpcPacket(
    val type: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CpcPacket) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class AudioDecodeFormat(
    val sampleRate: Int,
    val channels: Int
)

sealed interface ParsedAudioPacket {
    data class ControlCommand(
        val decodeType: Int,
        val audioType: Int,
        val command: Int
    ) : ParsedAudioPacket

    data class VolumeChange(
        val decodeType: Int,
        val volume: Float,
        val audioType: Int
    ) : ParsedAudioPacket

    data class PcmFrame(
        val decodeType: Int,
        val format: AudioDecodeFormat,
        val volume: Float,
        val audioType: Int,
        val pcmOffset: Int,
        val pcmLength: Int
    ) : ParsedAudioPacket
}

/**
 * Codec binário completo do protocolo USB Carlinkit CPC200-CCPA
 * alinhado às implementações de referência (lvalen91/carlink e node-carplay).
 */
object Cpc200Codec {
    const val VENDOR_ID: Int = 0x1314
    val PRODUCT_IDS: Set<Int> = setOf(0x1520, 0x1521)

    const val HEADER_MAGIC: Int = 0x55AA55AA
    const val HEADER_SIZE: Int = 16
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    fun isCarlinkitDevice(vendorId: Int, productId: Int): Boolean {
        return vendorId == VENDOR_ID && productId in PRODUCT_IDS
    }

    fun normalizeResolution(width: Int, height: Int): Pair<Int, Int> {
        var w = width.coerceAtLeast(480).toDouble()
        var h = height.coerceAtLeast(240).toDouble()
        if (w > 1920.0 || h > 1080.0) {
            val scale = minOf(1920.0 / w, 1080.0 / h)
            w *= scale
            h *= scale
        }
        val alignedW = (((w / 16.0).roundToInt()) * 16).coerceIn(480, 1920)
        val alignedH = (((h / 16.0).roundToInt()) * 16).coerceIn(240, 1072)
        return alignedW to alignedH
    }

    fun encodePacket(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(HEADER_MAGIC)
        buffer.putInt(payload.size)
        buffer.putInt(type)
        buffer.putInt(type.inv())
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    fun decodeHeader(bytes: ByteArray, offset: Int = 0): CpcHeader? {
        if (offset < 0 || bytes.size - offset < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.getInt()
        if (magic != HEADER_MAGIC) return null

        val length = buffer.getInt()
        val type = buffer.getInt()
        val typeCheck = buffer.getInt()

        if (typeCheck != type.inv()) return null
        if (length < 0 || length > MAX_PAYLOAD_BYTES) return null

        return CpcHeader(length = length, type = type)
    }

    fun findMagicOffset(buffer: ByteArray, validLength: Int): Int {
        if (validLength < 4) return -1
        val limit = validLength - 4
        for (i in 0..limit) {
            if (buffer[i] == 0xAA.toByte() &&
                buffer[i + 1] == 0x55.toByte() &&
                buffer[i + 2] == 0xAA.toByte() &&
                buffer[i + 3] == 0x55.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    fun buildOpenPayload(
        width: Int,
        height: Int,
        fps: Int = 60,
        format: Int = 5,
        packetMax: Int = 49_152,
        iBoxVersion: Int = 2,
        phoneWorkMode: Int = 2
    ): ByteArray {
        val (safeWidth, safeHeight) = normalizeResolution(width, height)
        val buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(safeWidth)
        buffer.putInt(safeHeight)
        buffer.putInt(fps)
        buffer.putInt(format)
        buffer.putInt(packetMax)
        buffer.putInt(iBoxVersion)
        buffer.putInt(phoneWorkMode)
        return buffer.array()
    }

    /**
     * Ordem CPC200 corrigida (SendTouch): (action, x, y, flags=0).
     */
    fun buildTouchPayload(
        action: TouchAction,
        normalizedX: Float,
        normalizedY: Float
    ): ByteArray {
        val xScaled = (normalizedX.coerceIn(0f, 1f) * 10_000f).toInt()
        val yScaled = (normalizedY.coerceIn(0f, 1f) * 10_000f).toInt()
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(action.code)
        buffer.putInt(xScaled)
        buffer.putInt(yScaled)
        buffer.putInt(0)
        return buffer.array()
    }

    fun buildMultiTouchPacket(points: List<MultiTouchPoint>): ByteArray {
        val payload = ByteBuffer.allocate(points.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (pt in points) {
            payload.putFloat(pt.normalizedX.coerceIn(0f, 1f))
            payload.putFloat(pt.normalizedY.coerceIn(0f, 1f))
            payload.putInt(pt.action.code)
            payload.putInt(pt.id)
        }
        return encodePacket(CpcMessageType.MULTI_TOUCH.id, payload.array())
    }

    fun buildSendFilePacket(path: String, content: ByteArray): ByteArray {
        val pathBytes = path.toByteArray(Charsets.US_ASCII)
        val pathWithNull = ByteArray(pathBytes.size + 1)
        System.arraycopy(pathBytes, 0, pathWithNull, 0, pathBytes.size)
        pathWithNull[pathBytes.size] = 0

        val payload = ByteBuffer.allocate(8 + pathWithNull.size + content.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(pathWithNull.size)
        payload.put(pathWithNull)
        payload.putInt(content.size)
        payload.put(content)
        return encodePacket(CpcMessageType.SEND_FILE.id, payload.array())
    }

    fun buildSendFileIntPacket(path: String, value: Int): ByteArray {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return buildSendFilePacket(path, bytes)
    }

    fun buildSendFileStringPacket(path: String, value: String): ByteArray {
        return buildSendFilePacket(path, value.toByteArray(Charsets.UTF_8))
    }

    fun buildBoxSettingsPacket(
        width: Int,
        height: Int,
        syncTimeSec: Long,
        mediaDelayMs: Int = 300
    ): ByteArray {
        val (safeW, safeH) = normalizeResolution(width, height)
        val json = """{"mediaDelay":$mediaDelayMs,"syncTime":$syncTimeSec,"androidAutoSizeW":$safeW,"androidAutoSizeH":$safeH}"""
        return encodePacket(CpcMessageType.BOX_SETTINGS.id, json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Sequência completa de inicialização do Carlinkit CPC200-CCPA para habilitar
     * Apple CarPlay + Android Auto (via /etc/android_work_mode), Wi-Fi 5 GHz (sem
     * interferir no BT 2.4 GHz do processador Taranis) e microfone/áudio corretos.
     */
    fun buildFullInitSequence(
        width: Int,
        height: Int,
        syncTimeSec: Long,
        boxName: String = "AutoDeck",
        nightMode: Boolean = true,
        useBoxMic: Boolean = false
    ): List<ByteArray> {
        val (normW, normH) = normalizeResolution(width, height)
        return listOf(
            buildSendFileIntPacket("/tmp/screen_dpi", 160),
            encodePacket(CpcMessageType.OPEN.id, buildOpenPayload(normW, normH)),
            buildSendFileIntPacket("/tmp/night_mode", if (nightMode) 1 else 0),
            buildSendFileIntPacket("/tmp/hand_drive_mode", 0),
            buildSendFileIntPacket("/tmp/charge_mode", 0),
            buildSendFileStringPacket("/etc/box_name", boxName),
            buildBoxSettingsPacket(normW, normH, syncTimeSec),
            buildCommandPacket(CpcCommandId.WIFI_ENABLE),
            buildCommandPacket(CpcCommandId.WIFI_5G),
            buildCommandPacket(if (useBoxMic) CpcCommandId.BOX_MIC else CpcCommandId.MIC),
            buildCommandPacket(CpcCommandId.AUDIO_TRANSFER_OFF),
            buildSendFileIntPacket("/etc/android_work_mode", 1)
        )
    }

    fun buildAutoConnectPacket(): ByteArray = buildCommandPacket(CpcCommandId.WIFI_CONNECT)

    fun buildHeartbeatPacket(): ByteArray = encodePacket(CpcMessageType.HEARTBEAT.id)

    fun buildCommandPacket(commandId: Int): ByteArray {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(commandId)
            .array()
        return encodePacket(CpcMessageType.COMMAND.id, payload)
    }

    fun audioFormatForDecodeType(decodeType: Int): AudioDecodeFormat {
        return when (decodeType) {
            1, 2 -> AudioDecodeFormat(sampleRate = 44_100, channels = 2)
            3 -> AudioDecodeFormat(sampleRate = 8_000, channels = 1)
            4 -> AudioDecodeFormat(sampleRate = 48_000, channels = 2)
            5 -> AudioDecodeFormat(sampleRate = 16_000, channels = 1)
            6 -> AudioDecodeFormat(sampleRate = 24_000, channels = 1)
            7 -> AudioDecodeFormat(sampleRate = 16_000, channels = 2)
            else -> AudioDecodeFormat(sampleRate = 44_100, channels = 2)
        }
    }

    fun parseAudioPacket(payload: ByteArray): ParsedAudioPacket? {
        if (payload.size < 12) return null
        val buf = ByteBuffer.wrap(payload, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val decodeType = buf.getInt()
        val volume = buf.getFloat()
        val audioType = buf.getInt()

        return when {
            payload.size == 13 -> ParsedAudioPacket.ControlCommand(
                decodeType = decodeType,
                audioType = audioType,
                command = payload[12].toInt() and 0xFF
            )
            payload.size == 16 -> ParsedAudioPacket.VolumeChange(
                decodeType = decodeType,
                volume = volume,
                audioType = audioType
            )
            payload.size > 16 -> ParsedAudioPacket.PcmFrame(
                decodeType = decodeType,
                format = audioFormatForDecodeType(decodeType),
                volume = volume,
                audioType = audioType,
                pcmOffset = 12,
                pcmLength = payload.size - 12
            )
            else -> null
        }
    }

    fun buildMicAudioPacket(pcm16kMono: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(12 + pcm16kMono.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(5) // decodeType = 5 (16 kHz mono)
        payload.putFloat(0f)
        payload.putInt(3) // audioType = 3 (microfone)
        payload.put(pcm16kMono)
        return encodePacket(CpcMessageType.AUDIO_DATA.id, payload.array())
    }

    fun parsePluggedPhoneType(payload: ByteArray): CpcPhoneType {
        if (payload.size < 4) return CpcPhoneType.UNKNOWN
        val phoneTypeId = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
        return CpcPhoneType.entries.firstOrNull { it.id == phoneTypeId } ?: CpcPhoneType.UNKNOWN
    }
}
