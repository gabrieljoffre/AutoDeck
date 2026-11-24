package com.autodeck.headunit.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.autodeck.protocol.camera.IsoAltSettingCandidate
import com.autodeck.protocol.camera.LumaGrid
import com.autodeck.protocol.camera.Saa7113SignalStatus
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.camera.Stk1160Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt

enum class CameraSourceMode {
    SIMULATED,
    USB_CONVERTER
}

data class CameraHardwareStatus(
    val mode: CameraSourceMode = CameraSourceMode.SIMULATED,
    val connectedDeviceLabel: String = "Modo Simulador",
    val isUvcCompatible: Boolean = true,
    val needsNativeEasyCapDriver: Boolean = false,
    val needsCameraPermission: Boolean = false,
    val hasValidFrameStream: Boolean = true,
    val isStk1160Ready: Boolean = false,
    val isStreamingVideo: Boolean = false,
    val syncLockDetected: Boolean = false,
    val lastStatusRegHex: String? = null,
    val activeChannel: Int = 0,
    val isNtsc: Boolean = true
)

data class CameraSignalFrame(
    val grid: LumaGrid,
    val hardwareSignalOverride: SignalAnalysis? = null
)

interface CameraFrameSource {
    val hardwareStatus: Flow<CameraHardwareStatus>
    fun lumaSamples(intervalMs: Long = 140L): Flow<LumaGrid>
    fun signalEvents(intervalMs: Long = 140L): Flow<CameraSignalFrame> =
        lumaSamples(intervalMs).map { CameraSignalFrame(grid = it) }
}

/**
 * Simulador de conversor multi-RCA -> USB para testar standby estático (tela cinza/preta
 * igual USB Camera Pro) e cena real com ruído temporal ao engatar a ré.
 */
class SimulatedCameraSource : CameraFrameSource {

    @Volatile
    var simulatedReverseActive: Boolean = false

    private val _hardwareStatus = MutableStateFlow(
        CameraHardwareStatus(
            mode = CameraSourceMode.SIMULATED,
            connectedDeviceLabel = "Simulador Conversor RCA->USB",
            isUvcCompatible = true,
            needsNativeEasyCapDriver = false,
            needsCameraPermission = false,
            hasValidFrameStream = true
        )
    )
    override val hardwareStatus: Flow<CameraHardwareStatus> = _hardwareStatus.asStateFlow()

    override fun lumaSamples(intervalMs: Long): Flow<LumaGrid> = flow {
        var tick = 0
        while (true) {
            tick++
            val grid = if (!simulatedReverseActive) {
                LumaGrid(width = 16, height = 12, luma = IntArray(192) { 42 })
            } else {
                LumaGrid(
                    width = 16,
                    height = 12,
                    luma = IntArray(192) { idx ->
                        val row = idx / 16
                        val col = idx % 16
                        ((row * 18 + (col + tick) * 13) % 190) + 30
                    }
                )
            }
            emit(grid)
            delay(intervalMs)
        }
    }
}

/**
 * Gerenciador exclusivo do conversor EasyCap Syntek STK1160 + SAA7113/GM7113 (`05E1:0408`).
 * Mantém AltSetting 0 (0% de banda USB) fora da ré fazendo apenas leitura I2C do registrador 0x1F,
 * e ativa captura isócrona direta no ANativeWindow apenas quando a ré/preview estiver visível.
 * A interface de áudio USB nunca é aberta para não saturar o Hub nem disparar erro de transferência.
 */
class Stk1160CameraController {

    private val lock = Any()
    private var activeDeviceName: String? = null
    private var connection: UsbDeviceConnection? = null
    private var videoInterface: UsbInterface? = null
    private var selectedAltSetting: Int = 5
    private var selectedIsoPacketSize: Int = Stk1160Protocol.MIN_ISO_PACKET_SIZE
    private var currentChannel: Int = 0
    private var currentIsNtsc: Boolean = true
    private var attachedSurface: Surface? = null
    private var wantStreaming: Boolean = false
    private var streamingActive: Boolean = false
    private var lastStartAttemptMs: Long = 0L
    private var consecutiveStartFailures: Int = 0

    /** Momento em que o vídeo começou de fato (0 = parado). Usado como carência do sinal. */
    @Volatile
    var streamStartedAtMs: Long = 0L
        private set

    @Volatile
    var lastStreamError: String? = null
        private set

    val isInitialized: Boolean
        get() = synchronized(lock) { connection != null }

    val isStreamingActive: Boolean
        get() = synchronized(lock) { streamingActive }

    /** Quer vídeo agora (ré/preview visível com superfície pronta). */
    val wantsVideo: Boolean
        get() = synchronized(lock) { wantStreaming && attachedSurface != null }

    /**
     * Chamado a cada ciclo do monitor de sinal. Detecta a thread ISO nativa que morreu sozinha
     * e tenta reiniciar a captura enquanto a ré/preview estiver visível. Antes, uma falha no
     * início (ex.: banda do hub USB) deixava a tela preta até sair e entrar de novo na ré.
     */
    fun superviseStreaming(nowMs: Long) {
        synchronized(lock) {
            if (streamingActive && !Stk1160NativeBridge.nativeIsStreaming()) {
                Log.w(TAG, "Thread ISO encerrou sozinha; reiniciando captura")
                lastStreamError = "Vídeo interrompido; reiniciando"
                Stk1160NativeBridge.nativeStopStreaming()
                streamingActive = false
                streamStartedAtMs = 0L
            }
            val needsStart = wantStreaming && attachedSurface != null && !streamingActive && connection != null
            if (!needsStart) return
            if (nowMs - lastStartAttemptMs < RETRY_INTERVAL_MS) return
            if (consecutiveStartFailures >= MAX_START_FAILURES_BEFORE_REINIT) {
                // Várias falhas seguidas: reinicia o chip do zero no próximo refreshFromUsb().
                Log.w(TAG, "Falhas seguidas ao iniciar vídeo; reinicializando STK1160")
                consecutiveStartFailures = 0
                releaseLocked()
                return
            }
            syncStreamingLocked()
        }
    }

    fun ensureInitialized(
        manager: UsbManager,
        device: UsbDevice,
        channel: Int,
        isNtsc: Boolean
    ): Result<Unit> = synchronized(lock) {
        if (connection != null && activeDeviceName == device.deviceName) {
            return Result.success(Unit)
        }

        releaseLocked()

        if (!Stk1160NativeBridge.isLibraryLoaded) {
            return Result.failure(IllegalStateException("Biblioteca nativa libstk1160_native.so indisponível"))
        }
        if (!manager.hasPermission(device)) {
            return Result.failure(SecurityException("Sem permissão USB para STK1160"))
        }

        val videoIntf = findVideoInterfaceZero(device)
            ?: return Result.failure(IllegalStateException("Interface de vídeo 0 ausente no STK1160"))

        val conn = manager.openDevice(device)
            ?: return Result.failure(IllegalStateException("Falha ao abrir dispositivo USB STK1160"))

        if (!conn.claimInterface(videoIntf, true)) {
            conn.close()
            return Result.failure(IllegalStateException("Falha ao reivindicar interface de vídeo 0"))
        }

        val candidates = resolveIsoCandidates(conn, device)
        val bestAlt = Stk1160Protocol.selectBestIsoAltSetting(candidates)
        selectedAltSetting = bestAlt?.altSetting ?: 5
        selectedIsoPacketSize = bestAlt?.let {
            Stk1160Protocol.decodeHighBandwidthPacketSize(it.rawMaxPacketSize)
        }?.takeIf { it > 4 } ?: Stk1160Protocol.MIN_ISO_PACKET_SIZE

        val clampedChannel = channel.coerceIn(0, 3)
        val initRc = Stk1160NativeBridge.nativeInitChip(conn.fileDescriptor, clampedChannel, isNtsc)
        if (initRc != 0) {
            runCatching { conn.releaseInterface(videoIntf) }
            runCatching { conn.close() }
            return Result.failure(IllegalStateException("Erro ao inicializar chip STK1160/SAA7113 (rc=$initRc)"))
        }

        connection = conn
        videoInterface = videoIntf
        activeDeviceName = device.deviceName
        currentChannel = clampedChannel
        currentIsNtsc = isNtsc
        streamingActive = false

        syncStreamingLocked()
        return Result.success(Unit)
    }

    fun pollStatus1F(): Saa7113SignalStatus? = synchronized(lock) {
        val conn = connection ?: return null
        val reg = Stk1160NativeBridge.nativeReadSignalStatus(conn.fileDescriptor)
        if (reg < 0) return null
        return Stk1160Protocol.parseSaa7113Status1F(reg)
    }

    fun setWantStreaming(want: Boolean) {
        synchronized(lock) {
            wantStreaming = want
            syncStreamingLocked()
        }
    }

    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            attachedSurface = surface
            if (streamingActive) {
                if (surface != null) {
                    Stk1160NativeBridge.nativeUpdateSurface(surface)
                } else {
                    Stk1160NativeBridge.nativeStopStreaming()
                    streamingActive = false
                    streamStartedAtMs = 0L
                }
            } else {
                // Superfície nova = tentativa nova, sem esperar o intervalo de repetição.
                lastStartAttemptMs = 0L
                syncStreamingLocked()
            }
        }
    }

    fun selectChannel(channel: Int): Int {
        val clamped = channel.coerceIn(0, 3)
        synchronized(lock) {
            currentChannel = clamped
            val conn = connection ?: return 0
            return Stk1160NativeBridge.nativeSelectChannel(conn.fileDescriptor, clamped)
        }
    }

    fun setStandard(isNtsc: Boolean): Int {
        synchronized(lock) {
            if (currentIsNtsc == isNtsc) return 0
            currentIsNtsc = isNtsc
            val conn = connection ?: return 0
            val wasStreaming = streamingActive
            if (wasStreaming) {
                Stk1160NativeBridge.nativeStopStreaming()
                streamingActive = false
            }
            val rc = Stk1160NativeBridge.nativeSetStandard(conn.fileDescriptor, isNtsc)
            if (wasStreaming && wantStreaming && attachedSurface != null) {
                syncStreamingLocked()
            }
            return rc
        }
    }

    fun copyLastLumaGrid(): LumaGrid? {
        val buf = IntArray(192)
        if (!Stk1160NativeBridge.nativeCopyLastLumaGrid(buf)) return null
        return LumaGrid(width = 16, height = 12, luma = buf)
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun syncStreamingLocked() {
        val conn = connection ?: return
        val surface = attachedSurface
        val shouldStream = wantStreaming && surface != null

        if (shouldStream && !streamingActive) {
            lastStartAttemptMs = System.currentTimeMillis()
            val rc = Stk1160NativeBridge.nativeStartStreaming(
                fd = conn.fileDescriptor,
                altSetting = selectedAltSetting,
                isoPacketSize = selectedIsoPacketSize,
                isNtsc = currentIsNtsc,
                surface = surface
            )
            streamingActive = (rc == 0)
            if (streamingActive) {
                streamStartedAtMs = lastStartAttemptMs
                consecutiveStartFailures = 0
                lastStreamError = null
            } else {
                consecutiveStartFailures++
                lastStreamError = "Falha ao iniciar vídeo (rc=$rc); tentando de novo"
                Log.w(TAG, "nativeStartStreaming rc=$rc (falha $consecutiveStartFailures)")
            }
        } else if (!shouldStream && streamingActive) {
            Stk1160NativeBridge.nativeStopStreaming()
            streamingActive = false
            streamStartedAtMs = 0L
        }
    }

    private fun releaseLocked() {
        val conn = connection
        val intf = videoInterface
        if (streamingActive || conn != null) {
            Stk1160NativeBridge.nativeReleaseInterface(conn?.fileDescriptor ?: -1)
        }
        if (conn != null && intf != null) {
            runCatching { conn.releaseInterface(intf) }
        }
        runCatching { conn?.close() }
        connection = null
        videoInterface = null
        activeDeviceName = null
        streamingActive = false
        streamStartedAtMs = 0L
    }

    private companion object {
        const val TAG = "Stk1160Camera"
        const val RETRY_INTERVAL_MS = 800L
        const val MAX_START_FAILURES_BEFORE_REINIT = 4
    }

    private fun findVideoInterfaceZero(device: UsbDevice): UsbInterface? {
        var firstZero: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == Stk1160Protocol.VIDEO_INTERFACE_INDEX) {
                if (intf.alternateSetting == 0) return intf
                if (firstZero == null) firstZero = intf
            }
        }
        return firstZero
    }

    private fun resolveIsoCandidates(conn: UsbDeviceConnection, device: UsbDevice): List<IsoAltSettingCandidate> {
        val raw = runCatching { conn.rawDescriptors }.getOrNull()
        val parsed = if (raw != null && raw.isNotEmpty()) {
            Stk1160Protocol.parseRawIsoCandidates(raw, Stk1160Protocol.VIDEO_INTERFACE_INDEX)
        } else {
            emptyList()
        }
        if (parsed.isNotEmpty()) return parsed

        val fallback = mutableListOf<IsoAltSettingCandidate>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == Stk1160Protocol.VIDEO_INTERFACE_INDEX && intf.alternateSetting > 0) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    fallback += IsoAltSettingCandidate(
                        altSetting = intf.alternateSetting,
                        endpointAddress = ep.address,
                        rawMaxPacketSize = ep.maxPacketSize
                    )
                }
            }
        }
        return fallback
    }
}

/**
 * Contrato de captura USB para conversores multi-RCA -> USB.
 * Suporta EasyCap Syntek STK1160 (`05E1:0408`) nativamente via user-space usbdevfs C/JNI
 * e identifica outros chips UVC/EasyCap pelo Hub USB.
 */
class UvcUsbCameraSource(
    private val context: Context,
    private val usbHubCoordinator: UsbHubCoordinator
) : CameraFrameSource {

    private val stk1160Controller = Stk1160CameraController()

    @Volatile
    private var preferredChannel: Int = 0

    @Volatile
    private var preferredIsNtsc: Boolean = true

    @Volatile
    private var initErrorMessage: String? = null

    private var lastSyncedStatus: Saa7113SignalStatus? = null
    private var consecutiveI2cErrors: Int = 0

    private val _hardwareStatus = MutableStateFlow(
        CameraHardwareStatus(
            mode = CameraSourceMode.USB_CONVERTER,
            connectedDeviceLabel = "Nenhum conversor USB conectado",
            isUvcCompatible = false,
            needsNativeEasyCapDriver = false,
            needsCameraPermission = false,
            hasValidFrameStream = false
        )
    )
    override val hardwareStatus: Flow<CameraHardwareStatus> = _hardwareStatus.asStateFlow()

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun attachSurface(surface: Surface?) {
        stk1160Controller.attachSurface(surface)
    }

    fun setReverseOverlayVisible(visible: Boolean) {
        stk1160Controller.setWantStreaming(visible)
    }

    fun selectChannel(channel: Int) {
        val clamped = channel.coerceIn(0, 3)
        if (preferredChannel == clamped && stk1160Controller.isInitialized) return
        preferredChannel = clamped
        stk1160Controller.selectChannel(clamped)
        refreshFromUsb()
    }

    fun setStandard(isNtsc: Boolean) {
        if (preferredIsNtsc == isNtsc && stk1160Controller.isInitialized) return
        preferredIsNtsc = isNtsc
        stk1160Controller.setStandard(isNtsc)
        refreshFromUsb()
    }

    fun onCameraDetached() {
        lastSyncedStatus = null
        consecutiveI2cErrors = 0
        initErrorMessage = null
        stk1160Controller.release()
        refreshFromUsb()
    }

    fun refreshFromUsb(): UsbDevice? {
        val camPermOk = hasCameraPermission()
        val device = usbHubCoordinator.findCameraDevice()
        if (device == null) {
            if (stk1160Controller.isInitialized) {
                stk1160Controller.release()
            }
            lastSyncedStatus = null
            consecutiveI2cErrors = 0
            initErrorMessage = null
            _hardwareStatus.value = CameraHardwareStatus(
                mode = CameraSourceMode.USB_CONVERTER,
                connectedDeviceLabel = "Aguardando conversor RCA->USB no Hub",
                isUvcCompatible = false,
                needsNativeEasyCapDriver = false,
                needsCameraPermission = !camPermOk,
                hasValidFrameStream = false,
                activeChannel = preferredChannel,
                isNtsc = preferredIsNtsc
            )
            return null
        }

        val summary = usbHubCoordinator.devices.value.firstOrNull { it.deviceName == device.deviceName }
        val isStk1160 = Stk1160Protocol.isStk1160Device(device.vendorId, device.productId)

        if (isStk1160) {
            val manager = usbHubCoordinator.getManager()
            val hasUsbPerm = manager?.hasPermission(device) == true
            if (manager != null && hasUsbPerm) {
                val res = stk1160Controller.ensureInitialized(
                    manager = manager,
                    device = device,
                    channel = preferredChannel,
                    isNtsc = preferredIsNtsc
                )
                initErrorMessage = res.exceptionOrNull()?.message
            } else {
                initErrorMessage = if (!hasUsbPerm) "Aguardando permissão USB no tablet" else null
            }

            val ready = stk1160Controller.isInitialized
            val stdLabel = if (preferredIsNtsc) "NTSC" else "PAL"
            val chLabel = "Cam ${preferredChannel + 1}"
            val statusLabel = when {
                ready -> "EasyCap STK1160 (05E1:0408) • $chLabel • $stdLabel"
                initErrorMessage != null -> "STK1160 (05E1:0408): $initErrorMessage"
                else -> "STK1160 (05E1:0408) conectada"
            }

            _hardwareStatus.value = CameraHardwareStatus(
                mode = CameraSourceMode.USB_CONVERTER,
                connectedDeviceLabel = statusLabel,
                isUvcCompatible = false,
                needsNativeEasyCapDriver = false,
                needsCameraPermission = false,
                hasValidFrameStream = ready,
                isStk1160Ready = ready,
                isStreamingVideo = stk1160Controller.isStreamingActive,
                syncLockDetected = lastSyncedStatus?.hasSyncLock == true,
                lastStatusRegHex = lastSyncedStatus?.let { "0x%02X".format(it.rawReg1F) },
                activeChannel = preferredChannel,
                isNtsc = preferredIsNtsc
            )
            return device
        }

        val isUvc = summary?.isUvcCompliant == true || summary?.role == UsbDeviceRole.CAMERA_UVC
        val isLegacy = summary?.role == UsbDeviceRole.CAMERA_EASYCAP_LEGACY

        _hardwareStatus.value = CameraHardwareStatus(
            mode = CameraSourceMode.USB_CONVERTER,
            connectedDeviceLabel = "${device.productName ?: "Conversor"} (${summary?.vidPidHex ?: ""})",
            isUvcCompatible = isUvc,
            needsNativeEasyCapDriver = isLegacy,
            needsCameraPermission = !camPermOk,
            hasValidFrameStream = false,
            activeChannel = preferredChannel,
            isNtsc = preferredIsNtsc
        )
        return device
    }

    override fun lumaSamples(intervalMs: Long): Flow<LumaGrid> =
        signalEvents(intervalMs).map { it.grid }

    override fun signalEvents(intervalMs: Long): Flow<CameraSignalFrame> = flow {
        while (true) {
            refreshFromUsb()
            if (stk1160Controller.isInitialized) {
                val nowMs = System.currentTimeMillis()
                stk1160Controller.superviseStreaming(nowMs)
                val videoWanted = stk1160Controller.wantsVideo

                val polled = stk1160Controller.pollStatus1F()
                if (polled != null) {
                    consecutiveI2cErrors = 0
                    lastSyncedStatus = polled
                } else {
                    consecutiveI2cErrors++
                    // Com o vídeo ISO rodando, leituras I2C podem falhar por disputa no USB.
                    // Derrubar o controlador aqui matava o vídeo no meio da ré; só reinicia
                    // por erro de I2C quando não há vídeo sendo exibido.
                    val limit = if (videoWanted) I2C_ERRORS_LIMIT_WHILE_STREAMING else I2C_ERRORS_LIMIT
                    if (consecutiveI2cErrors >= limit) {
                        lastSyncedStatus = null
                        stk1160Controller.release()
                    }
                }

                val grid = stk1160Controller.copyLastLumaGrid()
                    ?: LumaGrid(width = 0, height = 0, luma = IntArray(0))
                val st = lastSyncedStatus
                // Ao iniciar o vídeo, o decodificador é reprogramado e pode perder o
                // sincronismo por instantes; sem esta carência a ré "piscava" e a tela de
                // vídeo era destruída antes da primeira imagem.
                val startedAt = stk1160Controller.streamStartedAtMs
                val inStartupGrace = videoWanted && (startedAt == 0L || nowMs - startedAt < STREAM_STARTUP_GRACE_MS)
                val hasLock = st?.hasSyncLock == true || (inStartupGrace && lastLockSeenRecently(nowMs))
                if (st?.hasSyncLock == true) lastLockSeenMs = nowMs
                val hex = st?.let { "0x%02X".format(it.rawReg1F) } ?: "--"
                val freq = st?.let { if (it.is60Hz) "60Hz" else "50Hz" } ?: ""

                _hardwareStatus.value = _hardwareStatus.value.copy(
                    isStreamingVideo = stk1160Controller.isStreamingActive,
                    syncLockDetected = hasLock,
                    lastStatusRegHex = st?.let { "0x%02X".format(it.rawReg1F) }
                )

                val (mean, stdDev) = computeGridStats(grid)
                val hwAnalysis = SignalAnalysis(
                    hasActiveSignal = hasLock,
                    reason = if (hasLock) {
                        stk1160Controller.lastStreamError ?: "STK1160 SAA7113 HSYNC travado ($hex $freq)"
                    } else {
                        "STK1160 standby Alt 0 sem sinal ($hex)"
                    },
                    meanLuma = mean,
                    stdDev = stdDev,
                    diffFromReference = null,
                    temporalDiff = null
                )
                emit(CameraSignalFrame(grid = grid, hardwareSignalOverride = hwAnalysis))
            } else {
                emit(CameraSignalFrame(grid = LumaGrid(width = 0, height = 0, luma = IntArray(0))))
            }
            delay(intervalMs)
        }
    }

    @Volatile
    private var lastLockSeenMs: Long = 0L

    /** Só segura o sinal na carência se houve travamento real há pouco (evita ré fantasma). */
    private fun lastLockSeenRecently(nowMs: Long): Boolean =
        lastLockSeenMs != 0L && nowMs - lastLockSeenMs < STREAM_STARTUP_GRACE_MS

    private companion object {
        const val I2C_ERRORS_LIMIT = 4
        const val I2C_ERRORS_LIMIT_WHILE_STREAMING = 25 // ~3,5 s a 140 ms por ciclo
        const val STREAM_STARTUP_GRACE_MS = 2_500L
    }

    private fun computeGridStats(grid: LumaGrid): Pair<Double, Double> {
        if (grid.luma.isEmpty()) return 0.0 to 0.0
        var sum = 0.0
        for (v in grid.luma) sum += v
        val mean = sum / grid.luma.size
        var sq = 0.0
        for (v in grid.luma) {
            val d = v - mean
            sq += d * d
        }
        return mean to sqrt(sq / grid.luma.size)
    }
}
