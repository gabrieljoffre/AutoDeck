package com.autodeck.protocol.coordinator

import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.obd2.AlertThresholds
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.ObdAlert
import com.autodeck.protocol.obd2.ObdPollingPlanner
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdSnapshot

enum class MainTab {
    HOME,
    NAVIGATION,
    MUSIC,
    AGENT_VOICE,
    OBD_SCANNER,
    SETTINGS
}

enum class LinkStatus(val labelPt: String) {
    DISCONNECTED("Desconectado"),
    CONNECTING("Conectando"),
    STANDBY("Standby"),
    CONNECTED("Conectado"),
    ERROR("Erro")
}

data class HeadUnitState(
    val setupCompleted: Boolean = false,
    val activeTab: MainTab = MainTab.HOME,
    val audioBtStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val audioBtDeviceName: String = "Processador Taramps",
    val obdStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val obdSnapshot: ObdSnapshot = ObdSnapshot(),
    val obdAlerts: List<ObdAlert> = emptyList(),
    val dismissedAlertTitle: String? = null,
    val dtcCodes: List<DtcCode> = emptyList(),
    val cameraState: ReverseCameraState = ReverseCameraState.STANDBY,
    val cameraSignalDebug: SignalAnalysis? = null,
    val powerConnected: Boolean = true,
    val duckAudioOnReverse: Boolean = true,
    val obdFastTurboMode: Boolean = true,
    val isReverseOverlayVisible: Boolean = false,
    val isHardwareReverseActive: Boolean = false,
    val shouldDuckAudio: Boolean = false,
    val btDiscoveryAllowed: Boolean = true,
    val activeObdProfile: ObdPollingProfile = ObdPollingProfile.HOME_TURBO
)

/**
 * Árbitro determinístico thread-safe do HondaDeck que coordena Câmera de Ré USB,
 * OBD-II BT SPP em modo HOME_TURBO e Áudio/Ignição BT (Taramps), garantindo
 * prioridade total da câmera de ré e zero interferência no áudio A2DP.
 */
class HeadUnitCoordinator(
    private val reverseStateMachine: ReverseStateMachine = ReverseStateMachine(),
    @Volatile var alertThresholds: AlertThresholds = AlertThresholds()
) {
    private val stateLock = Any()

    @Volatile
    private var currentState = HeadUnitState()

    val state: HeadUnitState
        get() = currentState

    fun configure(
        setupCompleted: Boolean,
        duckAudioOnReverse: Boolean,
        obdFastTurboMode: Boolean = true
    ): HeadUnitState {
        return updateDerived {
            it.copy(
                setupCompleted = setupCompleted,
                duckAudioOnReverse = duckAudioOnReverse,
                obdFastTurboMode = obdFastTurboMode
            )
        }
    }

    fun selectTab(tab: MainTab): HeadUnitState {
        return updateDerived { it.copy(activeTab = tab) }
    }

    fun updateAudioBluetooth(status: LinkStatus, deviceName: String? = null): HeadUnitState {
        return updateDerived {
            it.copy(
                audioBtStatus = status,
                audioBtDeviceName = deviceName ?: it.audioBtDeviceName
            )
        }
    }

    fun updateObdStatus(status: LinkStatus): HeadUnitState {
        return updateDerived { it.copy(obdStatus = status) }
    }

    fun updateObdSnapshot(
        snapshot: ObdSnapshot,
        lowVoltageDurationMs: Long = Long.MAX_VALUE
    ): HeadUnitState {
        val alerts = ObdPollingPlanner.evaluateAlerts(
            snapshot = snapshot,
            thresholds = alertThresholds,
            lowVoltageDurationMs = lowVoltageDurationMs
        )
        return updateDerived { prev ->
            val keepDismissed = alerts.any { it.title == prev.dismissedAlertTitle }
            prev.copy(
                obdSnapshot = snapshot,
                obdAlerts = alerts,
                dismissedAlertTitle = if (keepDismissed) prev.dismissedAlertTitle else null
            )
        }
    }

    fun dismissAlert(title: String): HeadUnitState {
        return updateDerived { it.copy(dismissedAlertTitle = title) }
    }

    fun updateDtcCodes(codes: List<DtcCode>): HeadUnitState {
        return updateDerived { it.copy(dtcCodes = codes) }
    }

    fun updatePowerConnected(connected: Boolean): HeadUnitState {
        return updateDerived { it.copy(powerConnected = connected) }
    }

    fun onCameraSample(analysis: SignalAnalysis, nowMs: Long): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.onVideoSignalSample(
            hasSignal = analysis.hasActiveSignal,
            nowMs = nowMs
        )
        updateDerivedLocked {
            it.copy(
                cameraState = nextCameraState,
                cameraSignalDebug = analysis
            )
        }
    }

    fun onUsbCameraAttached(nowMs: Long): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.onUsbCameraAttached(nowMs)
        updateDerivedLocked { it.copy(cameraState = nextCameraState) }
    }

    fun onUsbCameraDetached(): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.onUsbCameraDetached()
        updateDerivedLocked { it.copy(cameraState = nextCameraState) }
    }

    fun toggleManualCameraPreview(): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.toggleManualPreview()
        updateDerivedLocked { it.copy(cameraState = nextCameraState) }
    }

    fun enterManualCameraPreview(): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.enterManualPreview()
        updateDerivedLocked { it.copy(cameraState = nextCameraState) }
    }

    fun exitManualCameraPreview(): HeadUnitState = synchronized(stateLock) {
        val nextCameraState = reverseStateMachine.exitManualPreview()
        updateDerivedLocked { it.copy(cameraState = nextCameraState) }
    }

    private fun updateDerived(transform: (HeadUnitState) -> HeadUnitState): HeadUnitState =
        synchronized(stateLock) {
            updateDerivedLocked(transform)
        }

    private fun updateDerivedLocked(transform: (HeadUnitState) -> HeadUnitState): HeadUnitState {
        val updated = transform(currentState)
        val hardwareReverse = updated.cameraState == ReverseCameraState.ACTIVE ||
            updated.cameraState == ReverseCameraState.CONFIRMING_LOSS
        val reverseVisible = hardwareReverse ||
            updated.cameraState == ReverseCameraState.MANUAL_PREVIEW

        val duckAudio = updated.duckAudioOnReverse && hardwareReverse

        // Nunca permite BT discovery após a 1ª configuração ou com o áudio conectado
        val discoveryAllowed = !updated.setupCompleted && updated.audioBtStatus != LinkStatus.CONNECTED

        val pollingProfile = when {
            updated.activeTab == MainTab.OBD_SCANNER && !reverseVisible -> ObdPollingProfile.SCANNER_DETAILED
            updated.obdFastTurboMode && !reverseVisible -> ObdPollingProfile.HOME_TURBO
            else -> ObdPollingProfile.DOCK_BASIC
        }

        currentState = updated.copy(
            isReverseOverlayVisible = reverseVisible,
            isHardwareReverseActive = hardwareReverse,
            shouldDuckAudio = duckAudio,
            btDiscoveryAllowed = discoveryAllowed,
            activeObdProfile = pollingProfile
        )
        return currentState
    }
}
