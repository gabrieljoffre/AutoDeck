package com.autodeck.headunit.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.view.Surface
import androidx.core.content.ContextCompat
import com.autodeck.protocol.carplay.Cpc200Codec
import com.autodeck.protocol.carplay.CpcCommandId
import com.autodeck.protocol.carplay.CpcMessageType
import com.autodeck.protocol.carplay.CpcPhoneType
import com.autodeck.protocol.carplay.MultiTouchPoint
import com.autodeck.protocol.carplay.ParsedAudioPacket
import com.autodeck.protocol.carplay.TouchAction
import com.autodeck.protocol.coordinator.LinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

object HurFallbackLauncher {
    private val HUR_PACKAGES = listOf("gb.xxy.hr", "gb.xxy.trial")

    fun findInstalledPackage(context: Context): String? {
        val pm = context.packageManager
        return HUR_PACKAGES.firstOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg) != null
        }
    }

    fun launchHur(context: Context): Boolean {
        val pkg = findInstalledPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false
        context.startActivity(intent)
        return true
    }
}

private data class AudioTrackKey(
    val audioType: Int,
    val sampleRate: Int,
    val channels: Int
)

private data class QueuedAudioPcm(
    val key: AudioTrackKey,
    val bytes: ByteArray
)

/**
 * Motor USB nativo para dongles Carlinkit CPC200-CCPA (CarPlay + Android Auto),
 * alinhado à arquitetura de referência lvalen91/carlink:
 * - Handshake completo (SEND_FILE, BOX_SETTINGS, Wi-Fi 5 GHz, /etc/android_work_mode=1)
 * - Auto-reconexão zero-cliques (WIFI_CONNECT automático)
 * - Canal único de escrita USB (preserva ordem DOWN/MOVE/UP e multitouch)
 * - MediaCodec confinado em thread única com IDR forçado ao recriar Surface
 * - Áudio PCM roteado por decodeType em USAGE_MEDIA (compatível com BT A2DP Taranis) + Microfone
 */
class CarlinkitUsbEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPhonePlugged: (CpcPhoneType) -> Unit = {},
    private val onRequestHostUi: () -> Unit = {}
) {
    private val _status = MutableStateFlow(LinkStatus.DISCONNECTED)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _detailMessage = MutableStateFlow("Aguardando dongle Carlinkit CPC200-CCPA na USB")
    val detailMessage: StateFlow<String> = _detailMessage.asStateFlow()

    private val videoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarlinkitVideo").apply { priority = Thread.MAX_PRIORITY }
    }
    private val videoDispatcher = videoExecutor.asCoroutineDispatcher()

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarlinkitAudio").apply { priority = Thread.MAX_PRIORITY }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    private var sessionJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var micJob: Job? = null

    private var writeChannel = Channel<ByteArray>(capacity = 128)
    private var videoChannel = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var audioChannel = Channel<QueuedAudioPcm>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    private var videoCodec: MediaCodec? = null
    private val audioTracks = LinkedHashMap<AudioTrackKey, AudioTrack>()

    @Volatile
    private var outputSurface: Surface? = null

    @Volatile
    private var renderPausedForReverse = false

    @Volatile
    private var audioDucked = false

    @Volatile
    private var useBoxMic: Boolean = false

    private var hasSurfaceBounds = false
    private var targetWidth = 1280
    private var targetHeight = 720
    private var activeOpenWidth = 0
    private var activeOpenHeight = 0
    private var initSequenceSent = false

    fun setUseBoxMic(boxMic: Boolean) {
        useBoxMic = boxMic
    }

    fun attachSurface(surface: Surface?, width: Int, height: Int) {
        outputSurface = surface
        if (surface != null && width >= 480 && height >= 240) {
            val (normW, normH) = Cpc200Codec.normalizeResolution(width, height)
            targetWidth = normW
            targetHeight = normH
            hasSurfaceBounds = true

            scope.launch(videoDispatcher) {
                setupDecoder(surface)
                if (connection != null) {
                    if (!initSequenceSent) {
                        enqueueFullInitAndAutoConnect()
                    } else if (normW != activeOpenWidth || normH != activeOpenHeight) {
                        activeOpenWidth = normW
                        activeOpenHeight = normH
                        enqueuePacket(Cpc200Codec.encodePacket(CpcMessageType.OPEN.id, Cpc200Codec.buildOpenPayload(normW, normH)))
                        enqueuePacket(Cpc200Codec.buildBoxSettingsPacket(normW, normH, System.currentTimeMillis() / 1_000L))
                    }
                    enqueuePacket(Cpc200Codec.buildCommandPacket(CpcCommandId.FRAME))
                }
            }
        } else if (surface == null) {
            scope.launch(videoDispatcher) {
                releaseDecoder()
            }
        }
    }

    fun setRenderPaused(paused: Boolean) {
        val wasPaused = renderPausedForReverse
        renderPausedForReverse = paused
        if (wasPaused && !paused && connection != null) {
            enqueuePacket(Cpc200Codec.buildCommandPacket(CpcCommandId.FRAME))
        }
    }

    fun setAudioDucking(ducked: Boolean) {
        audioDucked = ducked
        val gain = if (ducked) 0.25f else 1.0f
        scope.launch(audioDispatcher) {
            audioTracks.values.forEach { track ->
                runCatching { track.setVolume(gain) }
            }
        }
    }

    fun startSession(usbManager: UsbManager, device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            _status.value = LinkStatus.CONNECTING
            _detailMessage.value = "Aguardando permissão USB para Carlinkit"
            return
        }
        if (sessionJob?.isActive == true) return

        _status.value = LinkStatus.CONNECTING
        _detailMessage.value = "Inicializando Carlinkit (${device.productName ?: "CPC200"})..."

        writeChannel = Channel(capacity = 128)
        videoChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        audioChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        initSequenceSent = false

        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                val intf = findBulkInterface(device)
                    ?: throw IllegalStateException("Interface USB Bulk não encontrada no dongle")
                val conn = usbManager.openDevice(device)
                    ?: throw IllegalStateException("Falha ao abrir dispositivo USB Carlinkit")

                if (!conn.claimInterface(intf, true)) {
                    conn.close()
                    throw IllegalStateException("Não foi possível reivindicar interface USB do Carlinkit")
                }

                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (i in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    }
                }

                if (epIn == null || epOut == null) {
                    conn.releaseInterface(intf)
                    conn.close()
                    throw IllegalStateException("Endpoints Bulk IN/OUT ausentes no Carlinkit")
                }

                connection = conn
                usbInterface = intf
                bulkIn = epIn
                bulkOut = epOut

                startWriterLoop(conn, epOut)
                startVideoLoop()
                startAudioLoop()

                val currentSurface = outputSurface
                if (currentSurface != null) {
                    scope.launch(videoDispatcher) { setupDecoder(currentSurface) }
                }

                if (hasSurfaceBounds || currentSurface != null) {
                    enqueueFullInitAndAutoConnect()
                }

                startHeartbeatAndAutoConnectLoop()

                _status.value = LinkStatus.STANDBY
                _detailMessage.value = "Dongle pronto — conectando automaticamente ao último celular..."

                readUsbLoop(conn, epIn)
            } catch (cancel: CancellationException) {
                stopSessionInternal()
                throw cancel
            } catch (ex: Exception) {
                _status.value = LinkStatus.ERROR
                _detailMessage.value = ex.message ?: "Erro na sessão Carlinkit USB"
                stopSessionInternal()
            }
        }
    }

    fun sendTouch(action: TouchAction, normalizedX: Float, normalizedY: Float) {
        if (connection == null) return
        val payload = Cpc200Codec.buildTouchPayload(action, normalizedX, normalizedY)
        enqueuePacket(Cpc200Codec.encodePacket(CpcMessageType.TOUCH.id, payload))
    }

    fun sendMultiTouch(points: List<MultiTouchPoint>) {
        if (connection == null || points.isEmpty()) return
        enqueuePacket(Cpc200Codec.buildMultiTouchPacket(points))
    }

    fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        stopSessionInternal()
        _status.value = LinkStatus.DISCONNECTED
        _detailMessage.value = "Dongle Carlinkit desconectado"
    }

    fun shutdown() {
        stopSession()
        videoExecutor.shutdownNow()
        audioExecutor.shutdownNow()
    }

    private fun enqueuePacket(packet: ByteArray) {
        writeChannel.trySend(packet)
    }

    private fun enqueueFullInitAndAutoConnect() {
        if (initSequenceSent) return
        initSequenceSent = true
        activeOpenWidth = targetWidth
        activeOpenHeight = targetHeight
        val packets = Cpc200Codec.buildFullInitSequence(
            width = targetWidth,
            height = targetHeight,
            syncTimeSec = System.currentTimeMillis() / 1_000L,
            boxName = "AutoDeck",
            nightMode = true,
            useBoxMic = useBoxMic
        )
        packets.forEach { enqueuePacket(it) }
        scope.launch(Dispatchers.IO) {
            delay(1_000L)
            if (connection != null) {
                enqueuePacket(Cpc200Codec.buildAutoConnectPacket())
            }
        }
    }

    private fun startWriterLoop(conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            for (packet in writeChannel) {
                if (!isActive || connection !== conn) break
                runCatching { conn.bulkTransfer(epOut, packet, packet.size, 400) }
            }
        }
    }

    private fun startHeartbeatAndAutoConnectLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val hb = Cpc200Codec.buildHeartbeatPacket()
            var ticks = 0
            while (isActive && connection != null) {
                delay(2_000L)
                enqueuePacket(hb)
                ticks++
                // Se em standby sem celular conectado, reenvia WIFI_CONNECT a cada ~8s (zero cliques)
                if (_status.value == LinkStatus.STANDBY && ticks % 4 == 0) {
                    if (!initSequenceSent) {
                        enqueueFullInitAndAutoConnect()
                    } else {
                        enqueuePacket(Cpc200Codec.buildAutoConnectPacket())
                    }
                }
            }
        }
    }

    private fun startVideoLoop() {
        videoJob?.cancel()
        videoJob = scope.launch(videoDispatcher) {
            for (h264 in videoChannel) {
                if (!isActive) break
                feedH264Frame(h264)
            }
        }
    }

    private fun startAudioLoop() {
        audioJob?.cancel()
        audioJob = scope.launch(audioDispatcher) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            for (frame in audioChannel) {
                if (!isActive) break
                playPcmFrame(frame)
            }
        }
    }

    private suspend fun readUsbLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint) {
        val headerBuf = ByteArray(Cpc200Codec.HEADER_SIZE)
        val scratchChunk = ByteArray(16_384)
        var payloadBuf = ByteArray(65_536)

        while (scope.isActive && sessionJob?.isActive == true && connection === conn) {
            val headerRead = conn.bulkTransfer(epIn, headerBuf, headerBuf.size, 500)
            if (headerRead < 0) {
                delay(15)
                continue
            }
            if (headerRead != Cpc200Codec.HEADER_SIZE) {
                val magicPos = Cpc200Codec.findMagicOffset(headerBuf, headerRead)
                if (magicPos > 0) {
                    val remaining = Cpc200Codec.HEADER_SIZE - (headerRead - magicPos)
                    System.arraycopy(headerBuf, magicPos, headerBuf, 0, headerRead - magicPos)
                    if (remaining > 0) {
                        val tail = ByteArray(remaining)
                        val r = conn.bulkTransfer(epIn, tail, remaining, 300)
                        if (r != remaining) continue
                        System.arraycopy(tail, 0, headerBuf, headerRead - magicPos, remaining)
                    }
                } else {
                    continue
                }
            }

            val header = Cpc200Codec.decodeHeader(headerBuf) ?: continue
            if (header.length > payloadBuf.size) {
                payloadBuf = ByteArray(header.length)
            }

            var offset = 0
            while (offset < header.length && scope.isActive) {
                val want = minOf(scratchChunk.size, header.length - offset)
                val read = conn.bulkTransfer(epIn, scratchChunk, want, 500)
                if (read <= 0) break
                System.arraycopy(scratchChunk, 0, payloadBuf, offset, read)
                offset += read
            }
            if (offset != header.length) continue

            handlePacket(header.type, payloadBuf, header.length)
        }
    }

    private fun handlePacket(type: Int, payload: ByteArray, length: Int) {
        when (type) {
            CpcMessageType.PLUGGED.id -> {
                val phoneType = Cpc200Codec.parsePluggedPhoneType(payload.copyOf(length))
                onPhonePlugged(phoneType)
                _status.value = LinkStatus.CONNECTED
                _detailMessage.value = "${phoneType.labelPt} conectado automaticamente"
            }

            CpcMessageType.UNPLUGGED.id -> {
                stopMicCapture()
                _status.value = LinkStatus.STANDBY
                _detailMessage.value = "Telefone desconectado — aguardando próximo aparelho"
            }

            CpcMessageType.VIDEO_DATA.id -> {
                if (_status.value != LinkStatus.CONNECTED) {
                    _status.value = LinkStatus.CONNECTED
                    _detailMessage.value = "Projeção ativa (${activeOpenWidth}×${activeOpenHeight})"
                }
                if (length > 20) {
                    val h264Size = length - 20
                    val frame = ByteArray(h264Size)
                    System.arraycopy(payload, 20, frame, 0, h264Size)
                    val res = videoChannel.trySend(frame)
                    if (res.isFailure) {
                        enqueuePacket(Cpc200Codec.buildCommandPacket(CpcCommandId.FRAME))
                    }
                }
            }

            CpcMessageType.AUDIO_DATA.id -> {
                val copy = payload.copyOf(length)
                when (val parsed = Cpc200Codec.parseAudioPacket(copy)) {
                    is ParsedAudioPacket.PcmFrame -> {
                        val pcm = ByteArray(parsed.pcmLength)
                        System.arraycopy(copy, parsed.pcmOffset, pcm, 0, parsed.pcmLength)
                        audioChannel.trySend(
                            QueuedAudioPcm(
                                key = AudioTrackKey(
                                    audioType = parsed.audioType,
                                    sampleRate = parsed.format.sampleRate,
                                    channels = parsed.format.channels
                                ),
                                bytes = pcm
                            )
                        )
                    }
                    is ParsedAudioPacket.ControlCommand,
                    is ParsedAudioPacket.VolumeChange,
                    null -> Unit
                }
            }

            CpcMessageType.COMMAND.id -> {
                if (length >= 4) {
                    val cmdId = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                    when (cmdId) {
                        CpcCommandId.START_RECORD_AUDIO -> startMicCapture()
                        CpcCommandId.STOP_RECORD_AUDIO -> stopMicCapture()
                        CpcCommandId.REQUEST_HOST_UI -> onRequestHostUi()
                    }
                }
            }
        }
    }

    private fun setupDecoder(surface: Surface) {
        releaseDecoder()
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_048_576)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            videoCodec = codec
        }
    }

    private fun feedH264Frame(h264: ByteArray) {
        val codec = videoCodec ?: return
        runCatching {
            val inputIndex = codec.dequeueInputBuffer(5_000L)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(h264)
                codec.queueInputBuffer(inputIndex, 0, h264.size, System.nanoTime() / 1_000L, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0L)
            while (outputIndex >= 0) {
                // Durante a ré continuamos decodificando (render=false) para não quebrar P-frames na volta!
                codec.releaseOutputBuffer(outputIndex, !renderPausedForReverse)
                outputIndex = codec.dequeueOutputBuffer(info, 0L)
            }
        }
    }

    private fun playPcmFrame(frame: QueuedAudioPcm) {
        val key = frame.key
        val track = audioTracks[key] ?: run {
            val channelMask = if (key.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(
                key.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Mínimo ~250ms de buffer para evitar underrun sobre Bluetooth A2DP do Taranis
            val safe250MsBytes = (key.sampleRate * key.channels * 2) / 4
            val bufferSize = maxOf(minBuf, safe250MsBytes).coerceAtLeast(8_192)

            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Sempre USAGE_MEDIA para rotear inclusive ligações/Siri pelo A2DP do Taranis
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(key.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            newTrack.setVolume(if (audioDucked) 0.25f else 1.0f)
            newTrack.play()
            audioTracks[key] = newTrack
            newTrack
        }
        runCatching { track.write(frame.bytes, 0, frame.bytes.size) }
    }

    @SuppressLint("MissingPermission")
    private fun startMicCapture() {
        if (micJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        micJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1_280)

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                return@launch
            }

            val chunk = ByteArray(1_280) // ~40 ms @ 16 kHz mono 16-bit
            try {
                recorder.startRecording()
                while (isActive && connection != null) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val data = if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read)
                        enqueuePacket(Cpc200Codec.buildMicAudioPacket(data))
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    private fun stopMicCapture() {
        micJob?.cancel()
        micJob = null
    }

    private fun findBulkInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var hasIn = false
            var hasOut = false
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                    if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                }
            }
            if (hasIn && hasOut) return intf
        }
        return null
    }

    private fun releaseDecoder() {
        runCatching {
            videoCodec?.stop()
            videoCodec?.release()
        }
        videoCodec = null
    }

    private fun stopSessionInternal() {
        stopMicCapture()
        heartbeatJob?.cancel()
        heartbeatJob = null
        writerJob?.cancel()
        writerJob = null
        videoJob?.cancel()
        videoJob = null
        audioJob?.cancel()
        audioJob = null

        scope.launch(videoDispatcher) { releaseDecoder() }
        scope.launch(audioDispatcher) {
            audioTracks.values.forEach { track ->
                runCatching {
                    track.stop()
                    track.release()
                }
            }
            audioTracks.clear()
        }

        runCatching {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        }
        usbInterface = null
        connection = null
        bulkIn = null
        bulkOut = null
        initSequenceSent = false
        activeOpenWidth = 0
        activeOpenHeight = 0
    }
}
