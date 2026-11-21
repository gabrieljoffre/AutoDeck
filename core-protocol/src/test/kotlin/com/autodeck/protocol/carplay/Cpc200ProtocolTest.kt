package com.autodeck.protocol.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Cpc200ProtocolTest {

    @Test
    fun `encodes and decodes CPC200 header cleanly with normalized 16-aligned resolution`() {
        val openPayload = Cpc200Codec.buildOpenPayload(width = 2560, height = 1600, fps = 60)
        val packet = Cpc200Codec.encodePacket(CpcMessageType.OPEN.id, openPayload)

        val header = Cpc200Codec.decodeHeader(packet)
        assertNotNull(header)
        assertEquals(28, header!!.length)
        assertEquals(CpcMessageType.OPEN.id, header.type)

        val payloadBuf = ByteBuffer.wrap(packet, Cpc200Codec.HEADER_SIZE, 28).order(ByteOrder.LITTLE_ENDIAN)
        val w = payloadBuf.getInt()
        val h = payloadBuf.getInt()
        assertTrue(w <= 1920 && h <= 1080)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(60, payloadBuf.getInt())
    }

    @Test
    fun `rejects corrupted magic or inverted type check and finds magic offset`() {
        val packet = Cpc200Codec.buildHeartbeatPacket()
        val noisy = ByteArray(4 + packet.size)
        System.arraycopy(packet, 0, noisy, 4, packet.size)
        assertEquals(4, Cpc200Codec.findMagicOffset(noisy, noisy.size))

        packet[8] = (packet[8].toInt() xor 0x01).toByte()
        assertNull(Cpc200Codec.decodeHeader(packet))
    }

    @Test
    fun `buildTouchPayload uses CPC200 order action, x, y, flags`() {
        val payload = Cpc200Codec.buildTouchPayload(TouchAction.DOWN, normalizedX = 0.5f, normalizedY = 1.2f)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(TouchAction.DOWN.code, buf.getInt())
        assertEquals(5_000, buf.getInt())
        assertEquals(10_000, buf.getInt())
        assertEquals(0, buf.getInt())
    }

    @Test
    fun `builds full Carlinkit init sequence with 5GHz wifi, android_work_mode and autoConnect`() {
        val packets = Cpc200Codec.buildFullInitSequence(
            width = 1600,
            height = 1000,
            syncTimeSec = 1_700_000_000L
        )
        assertEquals(12, packets.size)

        val decodedTypes = packets.map { Cpc200Codec.decodeHeader(it)!!.type }
        assertTrue(decodedTypes.contains(CpcMessageType.OPEN.id))
        assertTrue(decodedTypes.contains(CpcMessageType.BOX_SETTINGS.id))
        assertTrue(decodedTypes.contains(CpcMessageType.SEND_FILE.id))
        assertTrue(decodedTypes.contains(CpcMessageType.COMMAND.id))

        val lastSendFileText = String(packets.last(), Cpc200Codec.HEADER_SIZE, packets.last().size - Cpc200Codec.HEADER_SIZE)
        assertTrue(lastSendFileText.contains("/etc/android_work_mode"))

        val autoConn = Cpc200Codec.buildAutoConnectPacket()
        val autoHeader = Cpc200Codec.decodeHeader(autoConn)!!
        assertEquals(CpcMessageType.COMMAND.id, autoHeader.type)
        val cmdId = ByteBuffer.wrap(autoConn, Cpc200Codec.HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
        assertEquals(CpcCommandId.WIFI_CONNECT, cmdId)
    }

    @Test
    fun `parses audio packet distinguishing control, volume and PCM decodeType`() {
        // 13 bytes -> controle (não deve ir para AudioTrack!)
        val ctrl = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putFloat(1.0f).putInt(1).put(1.toByte()).array()
        assertTrue(Cpc200Codec.parseAudioPacket(ctrl) is ParsedAudioPacket.ControlCommand)

        // 16 bytes -> volume
        val vol = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putFloat(0.5f).putInt(1).putInt(0).array()
        assertTrue(Cpc200Codec.parseAudioPacket(vol) is ParsedAudioPacket.VolumeChange)

        // > 16 bytes -> PCM com decodeType 4 (48 kHz estéreo)
        val pcmPayload = ByteBuffer.allocate(12 + 128).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putFloat(1.0f).putInt(1).array()
        val parsedPcm = Cpc200Codec.parseAudioPacket(pcmPayload) as ParsedAudioPacket.PcmFrame
        assertEquals(48_000, parsedPcm.format.sampleRate)
        assertEquals(2, parsedPcm.format.channels)
        assertEquals(128, parsedPcm.pcmLength)
    }

    @Test
    fun `parses plugged phone type for CarPlay and Android Auto`() {
        val cp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array()
        val aa = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(5).array()
        assertEquals(CpcPhoneType.CARPLAY, Cpc200Codec.parsePluggedPhoneType(cp))
        assertEquals(CpcPhoneType.ANDROID_AUTO, Cpc200Codec.parsePluggedPhoneType(aa))
    }
}
