package com.autodeck.headunit.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.view.Surface
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.HeadUnitPreferences
import com.autodeck.headunit.hardware.AudioBtMonitor
import com.autodeck.headunit.hardware.AudioRouteStatus
import com.autodeck.headunit.hardware.BluetoothClassicObdTransport
import com.autodeck.headunit.hardware.BluetoothDirectory
import com.autodeck.headunit.hardware.CameraHardwareStatus
import com.autodeck.headunit.hardware.CarlinkitUsbEngine
import com.autodeck.headunit.hardware.HurFallbackLauncher
import com.autodeck.headunit.hardware.ObdTransport
import com.autodeck.headunit.hardware.PairedBluetoothDevice
import com.autodeck.headunit.hardware.SimulatedCameraSource
import com.autodeck.headunit.hardware.SimulatedObdTransport
import com.autodeck.headunit.hardware.UsbDeviceSummary
import com.autodeck.headunit.hardware.UsbHubCoordinator
import com.autodeck.headunit.hardware.UvcUsbCameraSource
import com.autodeck.protocol.camera.LumaGrid
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.ReverseTimingConfig
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.camera.VideoSignalDetector
import com.autodeck.protocol.carplay.MultiTouchPoint
import com.autodeck.protocol.carplay.TouchAction
import com.autodeck.protocol.coordinator.HeadUnitCoordinator
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.DtcCategory
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.FuelConsumptionCalculator
import com.autodeck.protocol.obd2.ObdPid
import com.autodeck.protocol.obd2.ObdPollingPlanner
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdResponseParser
import com.autodeck.protocol.obd2.ObdSnapshot
import com.autodeck.protocol.obd2.SupportedPidCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CameraDebugState(
    val analysis: SignalAnalysis? = null,
    val lastGrid: LumaGrid? = null
)

data class HeadUnitUiState(
    val headUnit: HeadUnitState = HeadUnitState(),
    val config: HeadUnitConfig = HeadUnitConfig(),
    val usbDevices: List<UsbDeviceSummary> = emptyList(),
    val pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList(),
    val audioRoute: AudioRouteStatus = AudioRouteStatus(),
    val cameraHardware: CameraHardwareStatus = CameraHardwareStatus(),
    val carlinkitDetail: String = "Aguardando dongle Carlinkit CPC200-CCPA na USB",
    val hurPackageInstalled: String? = null,
    val obdMessage: String = "OBD-II aguardando conexão",
    val simulatedReverseActive: Boolean = false,
    val dtcBusy: Boolean = false,
    val dtcActionFeedback: String? = null,
    val isAppInForeground: Boolean = true
)

/**
 * Núcleo de execução persistente vinculado ao Application Context + ForegroundService.
 * Mantém Carlinkit USB, Câmera de Ré e OBD-II vivos mesmo se o usuário abrir outro app (ex: HUR).
 */
class HeadUnitRuntime private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences = HeadUnitPreferences(appContext)
    private val fuelCalculator = FuelConsumptionCalculator()
    private val signalDetector = VideoSignalDetector()
    private val coordinator = HeadUnitCoordinator(
        reverseStateMachine = ReverseStateMachine(
            ReverseTimingConfig(activateAfterFrames = 3, deactivateAfterMs = 1_200L)
        )
    )

    private val audioMonitor = AudioBtMonitor(appContext)
    private val carlinkitEngine = CarlinkitUsbEngine(
        context = appContext,
        scope = scope,
        onPhonePlugged = { phoneType ->
            coordinator.updateConnectedPhone(phoneType)
            publishState()
        },
        onRequestHostUi = {
            coordinator.selectTab(MainTab.SETTINGS)
            publishState()
        }
    )

    private val usbHubCoordinator: UsbHubCoordinator = UsbHubCoordinator(
        context = appContext,
        onCameraAttached = {
            signalDetector.resetHistory()
            coordinator.onUsbCameraAttached(System.currentTimeMillis())
            publishState()
        },
        onCameraDetached = {
            signalDetector.resetHistory()
            coordinator.onUsbCameraDetached()
            publishState()
        },
        onCarlinkitReady = { device ->
            val manager = appContext.getSystemService(UsbManager::class.java)
            if (manager != null) {
                carlinkitEngine.startSession(manager, device)
            }
        },
        onCarlinkitDetached = {
            carlinkitEngine.stopSession()
        }
    )

    private val simulatedCamera = SimulatedCameraSource()
    private val usbCamera = UvcUsbCameraSource(appContext, usbHubCoordinator)

    private var obdTransport: ObdTransport? = null
    private var obdJob: Job? = null
    private var cameraJob: Job? = null
    private var latestValidGrid: LumaGrid? = null
    private var lowVoltageSinceMs: Long? = null

    private val _uiState = MutableStateFlow(HeadUnitUiState())
    val uiState: StateFlow<HeadUnitUiState> = _uiState.asStateFlow()

    private val _cameraDebug = MutableStateFlow(CameraDebugState())
    val cameraDebug: StateFlow<CameraDebugState> = _cameraDebug.asStateFlow()

    init {
        val loadedConfig = preferences.load()
        fuelCalculator.restoreTripState(loadedConfig.tripDistanceKm, loadedConfig.tripFuelLiters)
        applyConfigToEngines(loadedConfig)

        _uiState.update {
            it.copy(
                config = loadedConfig,
                headUnit = coordinator.state,
                pairedBluetoothDevices = BluetoothDirectory.listBondedDevices(appContext),
                hurPackageInstalled = HurFallbackLauncher.findInstalledPackage(appContext)
            )
        }

        usbHubCoordinator.start()
        audioMonitor.start()
        observeHardwareFlows()
        startCameraSignalMonitoring(loadedConfig.useSimulatedCamera)

        if (loadedConfig.setupCompleted && loadedConfig.autoConnectOnLaunch) {
            startObdLoop()
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        _uiState.update { it.copy(isAppInForeground = inForeground) }
    }

    fun onPermissionsUpdated() {
        usbCamera.refreshFromUsb()
        _uiState.update {
            it.copy(
                pairedBluetoothDevices = BluetoothDirectory.listBondedDevices(appContext),
                hurPackageInstalled = HurFallbackLauncher.findInstalledPackage(appContext)
            )
        }
        audioMonitor.refreshRoute()
        val cfg = _uiState.value.config
        if (cfg.setupCompleted && cfg.autoConnectOnLaunch && obdJob?.isActive != true) {
            startObdLoop()
        }
    }

    fun completeSetup(newConfig: HeadUnitConfig) {
        val finalized = newConfig.copy(setupCompleted = true)
        preferences.save(finalized)
        applyConfigToEngines(finalized)
        _uiState.update { it.copy(config = finalized) }
        publishState()

        startCameraSignalMonitoring(finalized.useSimulatedCamera)
        restartObdLoop()
    }

    fun reopenSetup() {
        val updated = _uiState.value.config.copy(setupCompleted = false)
        preferences.save(updated)
        applyConfigToEngines(updated)
        _uiState.update { it.copy(config = updated) }
        publishState()
    }

    fun updateConfig(transform: (HeadUnitConfig) -> HeadUnitConfig) {
        val prev = _uiState.value.config
        val updated = transform(prev)
        preferences.save(updated)
        applyConfigToEngines(updated)
        _uiState.update { it.copy(config = updated) }
        publishState()

        if (prev.useSimulatedCamera != updated.useSimulatedCamera) {
            startCameraSignalMonitoring(updated.useSimulatedCamera)
        }
        if (prev.useSimulatedObd != updated.useSimulatedObd || prev.obdDeviceMac != updated.obdDeviceMac) {
            restartObdLoop()
        }
    }

    fun setLauncherModeEnabled(enabled: Boolean) {
        val pm = appContext.packageManager
        val alias = ComponentName(appContext, "com.autodeck.headunit.LauncherHomeAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }
        updateConfig { it.copy(launcherModeEnabled = enabled) }
    }

    fun selectTab(tab: MainTab) {
        coordinator.selectTab(tab)
        publishState()
    }

    fun dismissCurrentAlert(title: String) {
        coordinator.dismissAlert(title)
        publishState()
    }

    fun onPowerConnectionChanged(connected: Boolean) {
        coordinator.updatePowerConnected(connected)
        publishState()
    }

    fun toggleManualCameraPreview() {
        coordinator.toggleManualCameraPreview()
        publishState()
    }

    fun exitManualCameraPreview() {
        coordinator.exitManualCameraPreview()
        publishState()
    }

    fun toggleSimulatedReverse() {
        val next = !simulatedCamera.simulatedReverseActive
        simulatedCamera.simulatedReverseActive = next
        _uiState.update { it.copy(simulatedReverseActive = next) }
    }

    fun calibrateCameraNoSignal(): Boolean {
        val grid = latestValidGrid ?: return false
        if (grid.luma.isEmpty()) return false
        val cal = signalDetector.captureCalibration(grid, toleranceMad = 12.0)
        updateConfig { it.copy(noSignalCalibration = cal) }
        return true
    }

    fun clearNoSignalCalibration() {
        signalDetector.calibration = null
        updateConfig { it.copy(noSignalCalibration = null) }
    }

    fun connectObdNow() {
        if (obdJob?.isActive != true) {
            startObdLoop()
        }
    }

    fun disconnectObd() {
        obdJob?.cancel()
        obdJob = null
        scope.launch {
            obdTransport?.disconnect()
            obdTransport = null
            coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
            coordinator.updateObdSnapshot(coordinator.state.obdSnapshot.copy(isStale = true))
            _uiState.update { it.copy(obdMessage = "OBD-II desconectado manualmente") }
            publishState()
        }
    }

    fun readDtcCodes() {
        val transport = obdTransport ?: return
        if (!transport.isConnected) return
        scope.launch {
            _uiState.update { it.copy(dtcBusy = true, dtcActionFeedback = null) }
            runCatching {
                val collected = mutableListOf<DtcCode>()
                for (category in DtcCategory.entries) {
                    val raw = transport.sendCommand(category.modeCommand, timeoutMs = 2_500L)
                    collected += ObdResponseParser.parseDtcCodes(raw, category)
                }
                val milRaw = transport.sendCommand("0101", timeoutMs = 1_500L)
                val mil = ObdResponseParser.parseMilStatus0101(milRaw)
                if (mil != null) {
                    coordinator.updateObdSnapshot(
                        coordinator.state.obdSnapshot.copy(milOn = mil.first, milDtcCount = mil.second)
                    )
                }
                coordinator.updateDtcCodes(collected)
            }
            _uiState.update { it.copy(dtcBusy = false) }
            publishState()
        }
    }

    fun clearDtcCodes() {
        val transport = obdTransport ?: return
        if (!transport.isConnected) return
        scope.launch {
            _uiState.update { it.copy(dtcBusy = true, dtcActionFeedback = null) }
            val result = runCatching {
                val raw = transport.sendCommand("04", timeoutMs = 3_000L)
                if (ObdResponseParser.isClearDtcConfirmed(raw)) {
                    coordinator.updateDtcCodes(emptyList())
                    coordinator.updateObdSnapshot(
                        coordinator.state.obdSnapshot.copy(milOn = false, milDtcCount = 0)
                    )
                    "Falhas limpas com sucesso."
                } else {
                    "ECU não confirmou limpeza. Deixe a chave ligada com o motor desligado."
                }
            }
            val msg = result.getOrElse { "Erro ao limpar falhas: ${it.message}" }
            _uiState.update { it.copy(dtcBusy = false, dtcActionFeedback = msg) }
            publishState()
        }
    }

    fun resetTripConsumption() {
        fuelCalculator.resetTrip()
        val snap = coordinator.state.obdSnapshot.copy(averageKmPerL = null)
        coordinator.updateObdSnapshot(snap)
        updateConfig { it.copy(tripDistanceKm = 0.0, tripFuelLiters = 0.0) }
    }

    fun refreshUsbDevices() {
        usbHubCoordinator.refreshDevices(requestMissingPermissions = true)
        usbCamera.refreshFromUsb()
    }

    fun requestUsbPermission(deviceName: String) {
        usbHubCoordinator.requestPermissionForDevice(deviceName)
    }

    fun attachProjectionSurface(surface: Surface?, width: Int, height: Int) {
        carlinkitEngine.attachSurface(surface, width, height)
    }

    fun attachCameraSurface(surface: Surface?) {
        usbCamera.attachSurface(surface)
    }

    fun selectCameraChannel(channel: Int) {
        val clamped = channel.coerceIn(0, 3)
        updateConfig { it.copy(cameraInputChannel = clamped) }
    }

    fun setCameraStandardNtsc(isNtsc: Boolean) {
        updateConfig { it.copy(cameraIsNtsc = isNtsc) }
    }

    fun sendProjectionTouch(action: TouchAction, normalizedX: Float, normalizedY: Float) {
        carlinkitEngine.sendTouch(action, normalizedX, normalizedY)
    }

    fun sendProjectionMultiTouch(points: List<MultiTouchPoint>) {
        carlinkitEngine.sendMultiTouch(points)
    }

    fun launchHurFallback(): Boolean {
        return HurFallbackLauncher.launchHur(appContext)
    }

    private fun observeHardwareFlows() {
        scope.launch {
            usbHubCoordinator.devices.collect { list ->
                _uiState.update { it.copy(usbDevices = list) }
                usbCamera.refreshFromUsb()
            }
        }
        scope.launch {
            audioMonitor.status.collect { route ->
                coordinator.updateAudioBluetooth(route.linkStatus, route.routeLabel)
                _uiState.update { it.copy(audioRoute = route) }
                publishState()
            }
        }
        scope.launch {
            carlinkitEngine.status.collect { status ->
                coordinator.updateProjectionStatus(status)
                publishState()
            }
        }
        scope.launch {
            carlinkitEngine.detailMessage.collect { detail ->
                _uiState.update { it.copy(carlinkitDetail = detail) }
            }
        }
    }

    private fun startCameraSignalMonitoring(useSimulated: Boolean) {
        cameraJob?.cancel()
        signalDetector.resetHistory()
        val source = if (useSimulated) simulatedCamera else usbCamera

        cameraJob = scope.launch {
            launch {
                source.hardwareStatus.collect { hw ->
                    _uiState.update { it.copy(cameraHardware = hw) }
                }
            }
            source.signalEvents(intervalMs = 140L).collect { sample ->
                val grid = sample.grid
                latestValidGrid = grid.takeIf { it.luma.isNotEmpty() }
                val prevCamState = coordinator.state.cameraState
                val analysis = sample.hardwareSignalOverride ?: signalDetector.analyze(grid)
                coordinator.onCameraSample(analysis, System.currentTimeMillis())
                _cameraDebug.value = CameraDebugState(analysis = analysis, lastGrid = grid)
                if (coordinator.state.cameraState != prevCamState) {
                    publishState()
                }
            }
        }
    }

    private fun restartObdLoop() {
        obdJob?.cancel()
        obdJob = null
        scope.launch {
            obdTransport?.disconnect()
            obdTransport = null
            startObdLoop()
        }
    }

    private fun startObdLoop() {
        obdJob?.cancel()
        obdJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val cfg = _uiState.value.config
                val transport: ObdTransport = when {
                    cfg.useSimulatedObd -> SimulatedObdTransport()
                    !cfg.obdDeviceMac.isNullOrBlank() -> BluetoothClassicObdTransport(appContext, cfg.obdDeviceMac)
                    else -> {
                        coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
                        _uiState.update { it.copy(obdMessage = "Selecione o OBD-II na Config ou ative o Simulador") }
                        publishState()
                        delay(2_500L)
                        continue
                    }
                }

                obdTransport = transport
                coordinator.updateObdStatus(LinkStatus.CONNECTING)
                _uiState.update {
                    it.copy(
                        obdMessage = if (cfg.useSimulatedObd) {
                            "Iniciando Simulador OBD-II..."
                        } else {
                            "Conectando a ${cfg.obdDeviceName ?: cfg.obdDeviceMac}..."
                        }
                    )
                }
                publishState()

                val connectResult = transport.connect()
                if (connectResult.isFailure) {
                    val err = connectResult.exceptionOrNull()?.message ?: "Falha na conexão OBD-II"
                    coordinator.updateObdStatus(LinkStatus.ERROR)
                    _uiState.update { it.copy(obdMessage = "$err (nova tentativa automática)") }
                    publishState()
                    delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
                    continue
                }

                // Inicialização tolerante ELM327 com validação real e descoberta de PIDs 0100
                var initOk = true
                var capabilities = SupportedPidCapabilities(
                    supportsMap = cfg.preferSpeedDensity,
                    supportsMaf = !cfg.preferSpeedDensity
                )
                for (initCmd in ObdPollingPlanner.initSequence(cfg.savedObdProtocol)) {
                    val resp = runCatching {
                        transport.sendCommand(initCmd.command, initCmd.timeoutMs)
                    }.getOrNull()

                    if (initCmd.command == "0100" && resp != null) {
                        ObdResponseParser.parseSupportedPids0100(resp)?.let { capabilities = it }
                    }

                    val valid = when {
                        resp == null -> false
                        initCmd.optional -> true
                        initCmd.command == "ATZ" || initCmd.command == "ATE0" ->
                            ObdResponseParser.isValidElmInitResponse(resp)
                        else -> !ObdResponseParser.isErrorResponse(ObdResponseParser.sanitize(resp))
                    }
                    if (!valid && !initCmd.optional) {
                        initOk = false
                        break
                    }
                    delay(80L)
                }

                if (!initOk) {
                    transport.disconnect()
                    coordinator.updateObdStatus(LinkStatus.ERROR)
                    _uiState.update { it.copy(obdMessage = "ELM327 não respondeu à inicialização") }
                    publishState()
                    delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
                    continue
                }

                // Salva protocolo CAN/ISO detectado para acelerar próximas reconexões
                if (cfg.savedObdProtocol.isNullOrBlank()) {
                    runCatching {
                        val dpn = ObdResponseParser.sanitize(transport.sendCommand("ATDPN", 1_000L))
                        val protoChar = dpn.removePrefix("A").firstOrNull { it in '1'..'9' || it in 'A'..'C' }
                        if (protoChar != null) {
                            updateConfig { it.copy(savedObdProtocol = protoChar.toString()) }
                        }
                    }
                }

                attempt = 0
                coordinator.updateObdStatus(LinkStatus.CONNECTED)
                _uiState.update {
                    it.copy(
                        obdMessage = if (cfg.useSimulatedObd) {
                            "Simulador OBD-II ativo (telemetria em tempo real)"
                        } else {
                            "Conectado a ${cfg.obdDeviceName ?: "ELM327"}"
                        }
                    )
                }
                publishState()

                // Lê MIL e falhas iniciais
                runCatching {
                    val milRaw = transport.sendCommand("0101", timeoutMs = 1_500L)
                    val mil = ObdResponseParser.parseMilStatus0101(milRaw)
                    val stored = ObdResponseParser.parseDtcCodes(
                        transport.sendCommand("03", timeoutMs = 2_000L),
                        DtcCategory.STORED
                    )
                    coordinator.updateDtcCodes(stored)
                    if (mil != null) {
                        coordinator.updateObdSnapshot(
                            coordinator.state.obdSnapshot.copy(milOn = mil.first, milDtcCount = mil.second)
                        )
                    }
                    publishState()
                }

                var cycle = 0L
                var emptyCycles = 0
                var currentSnap = coordinator.state.obdSnapshot.copy(isStale = false)

                while (isActive && transport.isConnected) {
                    val profile = coordinator.state.activeObdProfile
                    val commands = ObdPollingPlanner.nextCommands(
                        profile = profile,
                        cycleIndex = cycle++,
                        capabilities = capabilities
                    )

                    try {
                        var validResponses = 0
                        for (cmd in commands) {
                            val raw = transport.sendCommand(cmd)
                            val updated = applyParsedCommand(cmd, raw, currentSnap)
                            if (updated != null) {
                                currentSnap = updated
                                validResponses++
                            }
                            delay(25L)
                        }

                        if (validResponses == 0) {
                            emptyCycles++
                            if (emptyCycles >= 5) {
                                // Watchdog: link bluetooth travou ou chave desligada
                                throw IllegalStateException("Sem resposta da ECU por 5 ciclos")
                            }
                        } else {
                            emptyCycles = 0
                        }

                        val now = System.currentTimeMillis()
                        val fuel = fuelCalculator.recordSampleDetailed(
                            speedKmh = currentSnap.speedKmh,
                            mafSensor = currentSnap.mafGramsPerSec,
                            intakeMapKpa = currentSnap.intakeMapKpa,
                            intakeTempC = currentSnap.intakeTempC,
                            rpm = currentSnap.rpm,
                            throttlePct = currentSnap.throttlePct,
                            stftPct = currentSnap.stftPct,
                            ltftPct = currentSnap.ltftPct,
                            timestampMs = now
                        )

                        currentSnap = currentSnap.copy(
                            instantKmPerL = fuel.instantKmPerL,
                            averageKmPerL = fuel.averageKmPerL ?: currentSnap.averageKmPerL,
                            litersPerHour = fuel.litersPerHour,
                            isStale = false,
                            timestampMs = now
                        )

                        val volts = currentSnap.batteryVoltageV
                        val engineRunning = (currentSnap.rpm ?: 0.0) > 400.0
                        val lowLimit = _uiState.value.config.alertThresholds.batteryLowV
                        val isLow = volts != null && ((engineRunning && volts <= lowLimit) || (!engineRunning && volts < 11.7))
                        val lowDuration = if (isLow) {
                            val start = lowVoltageSinceMs ?: now.also { lowVoltageSinceMs = it }
                            now - start
                        } else {
                            lowVoltageSinceMs = null
                            0L
                        }

                        coordinator.updateObdSnapshot(currentSnap, lowVoltageDurationMs = lowDuration)
                        publishState()

                        if (cycle % 20L == 0L) {
                            val trip = fuelCalculator.exportTripState()
                            val prev = _uiState.value.config
                            preferences.save(prev.copy(tripDistanceKm = trip.distanceKm, tripFuelLiters = trip.fuelLiters))
                        }

                        delay(if (profile == ObdPollingProfile.DOCK_BASIC) 450L else 180L)
                    } catch (_: Exception) {
                        transport.disconnect()
                        break
                    }
                }

                coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
                coordinator.updateObdSnapshot(coordinator.state.obdSnapshot.copy(isStale = true))
                publishState()
                delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
            }
        }
    }

    private fun applyParsedCommand(cmd: String, raw: String, snap: ObdSnapshot): ObdSnapshot? {
        if (cmd == "ATRV") {
            val v = ObdResponseParser.parseBatteryVoltage(raw) ?: return null
            return snap.copy(batteryVoltageV = v)
        }
        val pid = ObdPid.entries.firstOrNull { it.command == cmd } ?: return null
        val value = ObdResponseParser.parsePid(pid, raw) ?: return null
        return when (pid) {
            ObdPid.COOLANT_TEMP -> snap.copy(coolantTempC = value)
            ObdPid.RPM -> snap.copy(rpm = value)
            ObdPid.SPEED -> snap.copy(speedKmh = value)
            ObdPid.ENGINE_LOAD -> snap.copy(engineLoadPct = value)
            ObdPid.THROTTLE_POS -> snap.copy(throttlePct = value)
            ObdPid.INTAKE_MAP -> snap.copy(intakeMapKpa = value)
            ObdPid.MAF -> snap.copy(mafGramsPerSec = value)
            ObdPid.INTAKE_TEMP -> snap.copy(intakeTempC = value)
            ObdPid.TIMING_ADVANCE -> snap.copy(timingAdvanceDeg = value)
            ObdPid.STFT_BANK1 -> snap.copy(stftPct = value)
            ObdPid.LTFT_BANK1 -> snap.copy(ltftPct = value)
            ObdPid.FUEL_LEVEL -> snap.copy(fuelLevelPct = value)
            ObdPid.CONTROL_MODULE_VOLTAGE -> snap.copy(batteryVoltageV = snap.batteryVoltageV ?: value)
        }
    }

    private fun applyConfigToEngines(config: HeadUnitConfig) {
        fuelCalculator.fuelType = config.fuelType
        fuelCalculator.engineDisplacementLiters = config.engineDisplacementLiters
        fuelCalculator.calibrationFactor = config.fuelCalibrationFactor
        signalDetector.calibration = config.noSignalCalibration
        carlinkitEngine.setUseBoxMic(config.useBoxMic)
        usbCamera.selectChannel(config.cameraInputChannel)
        usbCamera.setStandard(config.cameraIsNtsc)
        coordinator.alertThresholds = config.alertThresholds
        coordinator.configure(
            setupCompleted = config.setupCompleted,
            projectionMode = config.projectionMode,
            duckAudioOnReverse = config.duckAudioOnReverse
        )
    }

    private fun publishState() {
        val state = coordinator.state
        val simCam = _uiState.value.config.useSimulatedCamera
        usbCamera.setReverseOverlayVisible(state.isReverseOverlayVisible && !simCam)
        carlinkitEngine.setRenderPaused(state.shouldSuspendProjectionRender)
        carlinkitEngine.setAudioDucking(state.shouldDuckAudio)
        if (state.shouldDuckAudio) {
            audioMonitor.requestReverseDucking()
        } else {
            audioMonitor.releaseReverseDucking()
        }
        _uiState.update { it.copy(headUnit = state) }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: HeadUnitRuntime? = null

        fun get(context: Context): HeadUnitRuntime {
            return instance ?: synchronized(this) {
                instance ?: HeadUnitRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
