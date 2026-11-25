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
import com.autodeck.protocol.camera.AnalogVideoStandard
import com.autodeck.protocol.camera.CameraImageAdjustments
import com.autodeck.protocol.camera.CameraVideoStandardMode
import com.autodeck.protocol.camera.IsoAltSettingCandidate
import com.autodeck.protocol.camera.LumaGrid
import com.autodeck.protocol.camera.Saa7113ColorSystem
import com.autodeck.protocol.camera.Saa7113SignalStatus
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.camera.Stk1160Protocol
import com.autodeck.protocol.camera.VideoStandardAutoDetector
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
    val isNtsc: Boolean = true,
    val standardMode: CameraVideoStandardMode = CameraVideoStandardMode.AUTO,
    val detectedStandard: AnalogVideoStandard = AnalogVideoStandard.NTSC,
    val selectedAltSetting: Int = 5,
    val isoPacketSize: Int = Stk1160Protocol.MIN_ISO_PACKET_SIZE,
    val framesRendered: Long = 0L,
    val framesDropped: Long = 0L,
    val isoPacketErrors: Long = 0L,
    val streamRestartCount: Int = 0
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

data class Stk1160StreamStats(
    val framesRendered: Long = 0L,
    val framesDropped: Long = 0L,
    val isoPacketErrors: Long = 0L,
    val lastFrameAgeMs: Long = -1L,
    val stalled: Boolean = false
)

/**
 * Gerenciador exclusivo do conversor EasyCap Syntek STK1160 + SAA7113/GM7113 (`05E1:0408`).
 * - Mantém AltSetting 0 (0% de banda USB) fora da ré fazendo apenas leitura I2C de status (`0x1F`/`0x1E`).
 * - Ativa captura isócrona direta no ANativeWindow apenas quando a ré/preview estiver visível.
 * - Escolhe a melhor AltSetting disponível com fallback automático em caso de falta de banda (-ENOSPC).
 * - Watchdog reinicia automaticamente streams travados sem piscar a tela (mantendo o último quadro bom).
 * - Nunca abre a interface de áudio USB para não saturar o Hub.
 */
class Stk1160CameraController {

    private val lock = Any()
    private var activeDeviceName: String? = null
    private var connection: UsbDeviceConnection? = null
    private var videoInterface: UsbInterface? = null
    private var rankedAltSettings: List<IsoAltSettingCandidate> = emptyList()
    private var altCandidateIndex: Int = 0
    private var selectedAltSetting: Int = 5
    private var selectedIsoPacketSize: Int = Stk1160Protocol.MIN_ISO_PACKET_SIZE
    private var currentChannel: Int = 0
    private var currentMode: CameraVideoStandardMode = CameraVideoStandardMode.AUTO
    private var currentStandard: AnalogVideoStandard = AnalogVideoStandard.NTSC
    private var currentAdjustments: CameraImageAdjustments = CameraImageAdjustments()
    private var attachedSurface: Surface? = null
    private var wantStreaming: Boolean = false
    private var streamingActive: Boolean = false
    private var lastStartAttemptMs: Long = 0L
    private var consecutiveStartFailures: Int = 0
    private var streamRestartCountInternal: Int = 0

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

    val activeStandard: AnalogVideoStandard
        get() = synchronized(lock) { currentStandard }

    val activeAltSetting: Int
        get() = synchronized(lock) { selectedAltSetting }

    val activeIsoPacketSize: Int
        get() = synchronized(lock) { selectedIsoPacketSize }

    val streamRestartCount: Int
        get() = synchronized(lock) { streamRestartCountInternal }

    /** Quer vídeo agora (ré/preview visível com superfície pronta). */
    val wantsVideo: Boolean
        get() = synchronized(lock) { wantStreaming && attachedSurface != null }

    /**
     * Watchdog chamado a cada ciclo do monitor de sinal:
     * - Detecta thread ISO nativa que encerrou sozinha ou reportou stall.
     * - Detecta stream "vivo" porém congelado (sem novos quadros há > 2,5 s enquanto há sinal travado).
     * - Reinicia a captura sem ocultar o overlay e, se necessário, faz fallback para outro AltSetting
     *   ou libera/reabre o dispositivo USB de forma limpa.
     */
    fun superviseStreaming(nowMs: Long, hasSyncLock: Boolean): Stk1160StreamStats {
        val stats = readStreamStats()
        synchronized(lock) {
            if (streamingActive) {
                val threadDead = !Stk1160NativeBridge.nativeIsStreaming()
                val streamAgeMs = if (streamStartedAtMs > 0L) nowMs - streamStartedAtMs else 0L
                val frozenWithSignal = hasSyncLock &&
                    streamAgeMs >= WATCHDOG_STARTUP_GRACE_MS &&
                    (stats.lastFrameAgeMs < 0L || stats.lastFrameAgeMs >= WATCHDOG_NO_FRAME_TIMEOUT_MS)

                if (threadDead || stats.stalled || frozenWithSignal) {
                    val reason = when {
                        threadDead -> "thread ISO encerrou"
                        stats.stalled -> "stall USB detectado"
                        else -> "sem quadros há ${stats.lastFrameAgeMs}ms (rendered=${stats.framesRendered}, drops=${stats.framesDropped}, err=${stats.isoPacketErrors})"
                    }
                    Log.w(TAG, "Watchdog câmera: $reason — reiniciando stream (alt=$selectedAltSetting)")
                    lastStreamError = "Vídeo estabilizando…"
                    Stk1160NativeBridge.nativeStopStreaming()
                    streamingActive = false
                    streamStartedAtMs = 0L
                    streamRestartCountInternal++
                    consecutiveStartFailures++
                    if (consecutiveStartFailures >= ALT_FALLBACK_AFTER_FAILURES) {
                        advanceAltSettingFallbackLocked()
                    }
                }
            }

            val needsStart = wantStreaming && attachedSurface != null && !streamingActive && connection != null
            if (!needsStart) return stats
            if (nowMs - lastStartAttemptMs < RETRY_INTERVAL_MS) return stats

            if (consecutiveStartFailures >= MAX_START_FAILURES_BEFORE_REINIT) {
                Log.w(TAG, "Falhas seguidas ($consecutiveStartFailures) ao iniciar vídeo; liberando e reabrindo USB STK1160")
                consecutiveStartFailures = 0
                altCandidateIndex = 0
                releaseLocked()
                return stats
            }
            syncStreamingLocked()
        }
        return stats
    }

    fun ensureInitialized(
        manager: UsbManager,
        device: UsbDevice,
        channel: Int,
        mode: CameraVideoStandardMode,
        initialStandard: AnalogVideoStandard,
        adjustments: CameraImageAdjustments
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
            runCatching { conn.close() }
            return Result.failure(IllegalStateException("Falha ao reivindicar interface de vídeo 0"))
        }

        val candidates = resolveIsoCandidates(conn, device)
        rankedAltSettings = Stk1160Protocol.rankIsoAltSettings(candidates)
        altCandidateIndex = 0
        applySelectedAltCandidateLocked()

        val clampedChannel = channel.coerceIn(0, 3)
        val clampedAdj = adjustments.clamped()
        val effectiveStd = mode.forcedStandard ?: initialStandard
        val autoFreq = (mode == CameraVideoStandardMode.AUTO)

        var initRc = -1
        for (attempt in 1..2) {
            initRc = Stk1160NativeBridge.nativeInitChip(
                fd = conn.fileDescriptor,
                channel = clampedChannel,
                standardId = effectiveStd.nativeId,
                autoDetectFreq = autoFreq,
                regBrightness = Stk1160Protocol.brightnessToSaa7113Reg0A(clampedAdj.brightness),
                regContrast = Stk1160Protocol.contrastToSaa7113Reg0B(clampedAdj.contrast),
                regSaturation = Stk1160Protocol.saturationToSaa7113Reg0C(clampedAdj.saturation)
            )
            if (initRc == 0) break
            Log.w(TAG, "nativeInitChip tentativa $attempt falhou (rc=$initRc)")
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (initRc != 0) {
            runCatching { conn.releaseInterface(videoIntf) }
            runCatching { conn.close() }
            return Result.failure(IllegalStateException("Erro ao inicializar chip STK1160/SAA7113 (rc=$initRc)"))
        }

        connection = conn
        videoInterface = videoIntf
        activeDeviceName = device.deviceName
        currentChannel = clampedChannel
        currentMode = mode
        currentStandard = effectiveStd
        currentAdjustments = clampedAdj
        streamingActive = false
        consecutiveStartFailures = 0

        Log.i(
            TAG,
            "STK1160 pronto: ch=$clampedChannel mode=$mode std=$effectiveStd alt=$selectedAltSetting pkt=$selectedIsoPacketSize candidatos=${rankedAltSettings.map { "${it.altSetting}:${Stk1160Protocol.decodeHighBandwidthPacketSize(it.rawMaxPacketSize)}" }}"
        )

        syncStreamingLocked()
        return Result.success(Unit)
    }

    fun pollExtendedStatus(): Saa7113SignalStatus? = synchronized(lock) {
        val conn = connection ?: return null
        val packed = Stk1160NativeBridge.nativeReadExtendedStatus(conn.fileDescriptor)
        if (packed < 0) return null
        val reg1F = packed and 0xFF
        val reg1E = (packed ushr 8) and 0xFF
        return Stk1160Protocol.parseSaa7113Status(reg1F = reg1F, reg1E = reg1E)
    }

    fun probeColorLock(): Boolean? = synchronized(lock) {
        val conn = connection ?: return null
        val rc = Stk1160NativeBridge.nativeProbeColorLock(conn.fileDescriptor)
        if (rc < 0) return null
        return rc != 0
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
                // Superfície nova = tentativa imediata, sem aguardar intervalo de repetição.
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

    /**
     * Aplica novo modo/padrão de vídeo.
     * - Quando apenas o padrão de cor muda na mesma cadência (ex.: NTSC <-> PAL-M em 60 Hz ou PAL <-> PAL-N em 50 Hz),
     *   atualiza apenas os registradores I2C (`0x08`/`0x0E`) ao vivo **sem reiniciar o stream nem piscar a tela**.
     * - Quando muda a resolução (480 <-> 576 linhas), reinicia o stream mantendo o último quadro no Surface.
     */
    fun applyStandard(mode: CameraVideoStandardMode, standard: AnalogVideoStandard): Int {
        synchronized(lock) {
            val autoFreq = (mode == CameraVideoStandardMode.AUTO)
            val modeChanged = (currentMode != mode)
            val stdChanged = (currentStandard != standard)
            if (!modeChanged && !stdChanged) return 0

            val geometryChanged = (currentStandard.height != standard.height)
            currentMode = mode
            currentStandard = standard
            val conn = connection ?: return 0

            if (!geometryChanged) {
                val rc = Stk1160NativeBridge.nativeSetStandard(
                    fd = conn.fileDescriptor,
                    standardId = standard.nativeId,
                    autoDetectFreq = autoFreq,
                    colorOnly = true
                )
                Log.i(TAG, "Troca de cor ao vivo (sem reinício ISO): mode=$mode std=$standard rc=$rc")
                return rc
            }

            val wasStreaming = streamingActive
            if (wasStreaming) {
                Stk1160NativeBridge.nativeStopStreaming()
                streamingActive = false
                streamStartedAtMs = 0L
            }
            val rc = Stk1160NativeBridge.nativeSetStandard(
                fd = conn.fileDescriptor,
                standardId = standard.nativeId,
                autoDetectFreq = autoFreq,
                colorOnly = false
            )
            Log.i(TAG, "Troca de geometria (${standard.width}x${standard.height}): mode=$mode std=$standard rc=$rc")
            if (wasStreaming && wantStreaming && attachedSurface != null) {
                lastStartAttemptMs = 0L
                syncStreamingLocked()
            }
            return rc
        }
    }

    fun setImageAdjustments(adjustments: CameraImageAdjustments): Int {
        val clamped = adjustments.clamped()
        synchronized(lock) {
            if (currentAdjustments == clamped) return 0
            currentAdjustments = clamped
            val conn = connection ?: return 0
            return Stk1160NativeBridge.nativeSetImageAdjustments(
                fd = conn.fileDescriptor,
                regBrightness = Stk1160Protocol.brightnessToSaa7113Reg0A(clamped.brightness),
                regContrast = Stk1160Protocol.contrastToSaa7113Reg0B(clamped.contrast),
                regSaturation = Stk1160Protocol.saturationToSaa7113Reg0C(clamped.saturation)
            )
        }
    }

    fun copyLastLumaGrid(): LumaGrid? {
        val buf = IntArray(192)
        if (!Stk1160NativeBridge.nativeCopyLastLumaGrid(buf)) return null
        return LumaGrid(width = 16, height = 12, luma = buf)
    }

    fun readStreamStats(): Stk1160StreamStats {
        val out = LongArray(5)
        if (!Stk1160NativeBridge.nativeGetStreamStats(out)) return Stk1160StreamStats()
        return Stk1160StreamStats(
            framesRendered = out[0],
            framesDropped = out[1],
            isoPacketErrors = out[2],
            lastFrameAgeMs = out[3],
            stalled = out[4] != 0L
        )
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun applySelectedAltCandidateLocked() {
        val chosen = rankedAltSettings.getOrNull(altCandidateIndex)
        selectedAltSetting = chosen?.altSetting ?: 5
        selectedIsoPacketSize = chosen?.let {
            Stk1160Protocol.decodeHighBandwidthPacketSize(it.rawMaxPacketSize)
        }?.takeIf { it > 8 } ?: Stk1160Protocol.MIN_ISO_PACKET_SIZE
    }

    private fun advanceAltSettingFallbackLocked() {
        if (rankedAltSettings.size <= 1) return
        val nextIdx = (altCandidateIndex + 1) % rankedAltSettings.size
        altCandidateIndex = nextIdx
        applySelectedAltCandidateLocked()
        Log.w(TAG, "Alternando AltSetting ISO para alt=$selectedAltSetting (pkt=$selectedIsoPacketSize)")
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
                standardId = currentStandard.nativeId,
                autoDetectFreq = (currentMode == CameraVideoStandardMode.AUTO),
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
                Log.w(TAG, "nativeStartStreaming rc=$rc alt=$selectedAltSetting (falha $consecutiveStartFailures)")
                // -ENOSPC (-28) ou falhas repetidas -> tenta próximo AltSetting menor
                if (rc == -28 || consecutiveStartFailures >= ALT_FALLBACK_AFTER_FAILURES) {
                    advanceAltSettingFallbackLocked()
                }
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
        const val WATCHDOG_STARTUP_GRACE_MS = 2_800L
        const val WATCHDOG_NO_FRAME_TIMEOUT_MS = 2_500L
        const val ALT_FALLBACK_AFTER_FAILURES = 2
        const val MAX_START_FAILURES_BEFORE_REINIT = 5
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
 * Suporta EasyCap Syntek STK1160 (`05E1:0408`) nativamente via user-space usbdevfs C/JNI,
 * com detecção automática de padrão (NTSC / PAL-M / PAL-N / PAL), ajustes de imagem no SAA7113
 * e watchdog de recuperação automática.
 */
class UvcUsbCameraSource(
    private val context: Context,
    private val usbHubCoordinator: UsbHubCoordinator
) : CameraFrameSource {

    private val stk1160Controller = Stk1160CameraController()
    private val standardDetector = VideoStandardAutoDetector(
        initialStandard = AnalogVideoStandard.PAL_M,
        confirmSamples = 3
    )

    @Volatile
    private var preferredChannel: Int = 0

    @Volatile
    private var preferredStandardMode: CameraVideoStandardMode = CameraVideoStandardMode.AUTO

    @Volatile
    private var preferredAdjustments: CameraImageAdjustments = CameraImageAdjustments()

    @Volatile
    private var initErrorMessage: String? = null

    private var lastSyncedStatus: Saa7113SignalStatus? = null
    private var consecutiveI2cErrors: Int = 0
    private var lastLockSeenMs: Long = 0L
    private var lockAcquiredAtMs: Long = 0L
    private var colorProbeAttemptedForLock: Boolean = false

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
        colorProbeAttemptedForLock = false
        stk1160Controller.selectChannel(clamped)
        refreshFromUsb()
    }

    fun setStandardMode(mode: CameraVideoStandardMode) {
        if (preferredStandardMode == mode && stk1160Controller.isInitialized) return
        preferredStandardMode = mode
        colorProbeAttemptedForLock = false
        val targetStd = mode.forcedStandard ?: standardDetector.activeStandard
        standardDetector.reset(targetStd)
        stk1160Controller.applyStandard(mode, targetStd)
        refreshFromUsb()
    }

    fun setStandard(isNtsc: Boolean) {
        setStandardMode(if (isNtsc) CameraVideoStandardMode.NTSC else CameraVideoStandardMode.PAL)
    }

    fun setImageAdjustments(adjustments: CameraImageAdjustments) {
        val clamped = adjustments.clamped()
        if (preferredAdjustments == clamped && stk1160Controller.isInitialized) return
        preferredAdjustments = clamped
        stk1160Controller.setImageAdjustments(clamped)
    }

    fun onCameraDetached() {
        lastSyncedStatus = null
        consecutiveI2cErrors = 0
        initErrorMessage = null
        lockAcquiredAtMs = 0L
        colorProbeAttemptedForLock = false
        stk1160Controller.release()
        refreshFromUsb()
    }

    fun refreshFromUsb(): UsbDevice? {
        val camPermOk = hasCameraPermission()
        val device = usbHubCoordinator.findCameraDevice()
        val activeStd = stk1160Controller.activeStandard
        if (device == null) {
            if (stk1160Controller.isInitialized) {
                stk1160Controller.release()
            }
            lastSyncedStatus = null
            consecutiveI2cErrors = 0
            initErrorMessage = null
            lockAcquiredAtMs = 0L
            colorProbeAttemptedForLock = false
            _hardwareStatus.value = CameraHardwareStatus(
                mode = CameraSourceMode.USB_CONVERTER,
                connectedDeviceLabel = "Aguardando conversor RCA->USB no Hub",
                isUvcCompatible = false,
                needsNativeEasyCapDriver = false,
                needsCameraPermission = !camPermOk,
                hasValidFrameStream = false,
                activeChannel = preferredChannel,
                isNtsc = activeStd.is60Hz,
                standardMode = preferredStandardMode,
                detectedStandard = activeStd
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
                    mode = preferredStandardMode,
                    initialStandard = standardDetector.activeStandard,
                    adjustments = preferredAdjustments
                )
                initErrorMessage = res.exceptionOrNull()?.message
            } else {
                initErrorMessage = if (!hasUsbPerm) "Aguardando permissão USB no tablet" else null
            }

            val ready = stk1160Controller.isInitialized
            val currentStd = stk1160Controller.activeStandard
            val chLabel = "Cam ${preferredChannel + 1}"
            val stdLabel = if (preferredStandardMode == CameraVideoStandardMode.AUTO) {
                "Auto (${currentStd.shortLabel})"
            } else {
                currentStd.shortLabel
            }
            val statusLabel = when {
                ready -> "EasyCap STK1160 • $chLabel • $stdLabel (${currentStd.width}×${currentStd.height})"
                initErrorMessage != null -> "STK1160 (05E1:0408): $initErrorMessage"
                else -> "STK1160 (05E1:0408) conectada"
            }

            _hardwareStatus.value = _hardwareStatus.value.copy(
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
                isNtsc = currentStd.is60Hz,
                standardMode = preferredStandardMode,
                detectedStandard = currentStd,
                selectedAltSetting = stk1160Controller.activeAltSetting,
                isoPacketSize = stk1160Controller.activeIsoPacketSize,
                streamRestartCount = stk1160Controller.streamRestartCount
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
            isNtsc = activeStd.is60Hz,
            standardMode = preferredStandardMode,
            detectedStandard = activeStd
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
                val videoWanted = stk1160Controller.wantsVideo

                val polled = stk1160Controller.pollExtendedStatus()
                if (polled != null) {
                    consecutiveI2cErrors = 0
                    lastSyncedStatus = polled
                } else {
                    consecutiveI2cErrors++
                    // Com o vídeo ISO rodando, leituras I2C podem falhar por disputa no USB;
                    // tolera mais falhas antes de derrubar o controlador.
                    val limit = if (videoWanted) I2C_ERRORS_LIMIT_WHILE_STREAMING else I2C_ERRORS_LIMIT
                    if (consecutiveI2cErrors >= limit) {
                        Log.w(TAG, "Erros seguidos de I2C ($consecutiveI2cErrors); liberando STK1160 para reconexão")
                        lastSyncedStatus = null
                        lockAcquiredAtMs = 0L
                        colorProbeAttemptedForLock = false
                        stk1160Controller.release()
                    }
                }

                val st = lastSyncedStatus
                val hasDirectLock = st?.hasSyncLock == true
                if (hasDirectLock) {
                    if (lockAcquiredAtMs == 0L) {
                        lockAcquiredAtMs = nowMs
                        colorProbeAttemptedForLock = false
                    }
                    lastLockSeenMs = nowMs
                } else {
                    lockAcquiredAtMs = 0L
                    colorProbeAttemptedForLock = false
                }

                // Detecção automática de padrão de vídeo (NTSC / PAL-M / PAL-N / PAL) no modo Automático
                if (st != null && hasDirectLock && preferredStandardMode == CameraVideoStandardMode.AUTO) {
                    val prevStd = standardDetector.activeStandard
                    var colorBurstMissing = false
                    val needColorProbe = !colorProbeAttemptedForLock &&
                        st.colorSystem == Saa7113ColorSystem.NONE &&
                        lockAcquiredAtMs > 0L &&
                        nowMs - lockAcquiredAtMs >= COLOR_PROBE_DELAY_MS

                    if (needColorProbe) {
                        colorProbeAttemptedForLock = true
                        val colorLocked = stk1160Controller.probeColorLock()
                        if (colorLocked == false) {
                            colorBurstMissing = true
                            Log.i(TAG, "Sinal ${if (st.is60Hz) "60Hz" else "50Hz"} sem trava de cor em $prevStd; testando padrão alternativo")
                        }
                    }

                    val nextStd = standardDetector.onStatusSample(
                        mode = preferredStandardMode,
                        status = st,
                        colorBurstMissingForCurrent = colorBurstMissing
                    )
                    if (colorBurstMissing && nextStd == prevStd) {
                        // Força avaliação imediata na troca única de sonda de cor (ex.: PAL-M <-> NTSC)
                        val altStd = Stk1160Protocol.detectVideoStandard(
                            mode = preferredStandardMode,
                            status = st,
                            currentStandard = prevStd,
                            colorBurstMissingForCurrent = true
                        )
                        if (altStd != prevStd) {
                            standardDetector.reset(altStd)
                            stk1160Controller.applyStandard(preferredStandardMode, altStd)
                        }
                    } else if (nextStd != prevStd) {
                        Log.i(TAG, "Padrão de vídeo detectado automaticamente: $prevStd -> $nextStd (1F=0x%02X 1E=0x%02X)".format(st.rawReg1F, st.rawReg1E))
                        stk1160Controller.applyStandard(preferredStandardMode, nextStd)
                    }
                }

                val startedAt = stk1160Controller.streamStartedAtMs
                val inStartupGrace = videoWanted && (startedAt == 0L || nowMs - startedAt < STREAM_STARTUP_GRACE_MS)
                val hasLock = hasDirectLock || (inStartupGrace && lastLockSeenRecently(nowMs))

                val stats = stk1160Controller.superviseStreaming(nowMs = nowMs, hasSyncLock = hasDirectLock)

                val grid = stk1160Controller.copyLastLumaGrid()
                    ?: LumaGrid(width = 0, height = 0, luma = IntArray(0))
                val currentStd = stk1160Controller.activeStandard
                val hex = st?.let { "0x%02X".format(it.rawReg1F) } ?: "--"
                val freq = st?.let { if (it.is60Hz) "60Hz" else "50Hz" } ?: ""

                _hardwareStatus.value = _hardwareStatus.value.copy(
                    isStreamingVideo = stk1160Controller.isStreamingActive,
                    syncLockDetected = hasLock,
                    lastStatusRegHex = st?.let { "0x%02X".format(it.rawReg1F) },
                    isNtsc = currentStd.is60Hz,
                    standardMode = preferredStandardMode,
                    detectedStandard = currentStd,
                    selectedAltSetting = stk1160Controller.activeAltSetting,
                    isoPacketSize = stk1160Controller.activeIsoPacketSize,
                    framesRendered = stats.framesRendered,
                    framesDropped = stats.framesDropped,
                    isoPacketErrors = stats.isoPacketErrors,
                    streamRestartCount = stk1160Controller.streamRestartCount
                )

                val (mean, stdDev) = computeGridStats(grid)
                val hwAnalysis = SignalAnalysis(
                    hasActiveSignal = hasLock,
                    reason = if (hasLock) {
                        stk1160Controller.lastStreamError
                            ?: "STK1160 ${currentStd.shortLabel} travado ($hex $freq)"
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

    /** Só segura o sinal na carência se houve travamento real há pouco (evita ré fantasma). */
    private fun lastLockSeenRecently(nowMs: Long): Boolean =
        lastLockSeenMs != 0L && nowMs - lastLockSeenMs < STREAM_STARTUP_GRACE_MS

    private companion object {
        const val TAG = "UvcUsbCamera"
        const val I2C_ERRORS_LIMIT = 4
        const val I2C_ERRORS_LIMIT_WHILE_STREAMING = 25 // ~3,5 s a 140 ms por ciclo
        const val STREAM_STARTUP_GRACE_MS = 2_500L
        const val COLOR_PROBE_DELAY_MS = 700L
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
