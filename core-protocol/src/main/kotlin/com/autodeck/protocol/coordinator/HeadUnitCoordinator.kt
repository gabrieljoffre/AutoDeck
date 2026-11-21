package com.autodeck.protocol.coordinator

import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.carplay.CpcPhoneType
import com.autodeck.protocol.obd2.AlertThresholds
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.ObdAlert
import com.autodeck.protocol.obd2.ObdPollingPlanner
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdSnapshot

enum class MainTab {
    PROJECTION,
    OBD_SCANNER,
    SETTINGS
}

enum class ProjectionMode {
    CARLINKIT_NATIVE,
    HUR_FALLBACK
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
    val activeTab: MainTab = MainTab.PROJECTION,
    val projectionMode: ProjectionMode = ProjectionMode.CARLINKIT_NATIVE,
    val audioBtStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val audioBtDeviceName: String = "Processador Taranis",
    val obdStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val obdSnapshot: ObdSnapshot = ObdSnapshot(),
    val obdAlerts: List<ObdAlert> = emptyList(),
    val dismissedAlertTitle: String? = null,
    val dtcCodes: List<DtcCode> = emptyList(),
    val cameraState: ReverseCameraState = ReverseCameraState.STANDBY,
    val cameraSignalDebug: SignalAnalysis? = null,
    val projectionStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val connectedPhoneType: CpcPhoneType? = null,
    val powerConnected: Boolean = true,
    val duckAudioOnReverse: Boolean = true,
    val isReverseOverlayVisible: Boolean = false,
    val shouldDuckAudio: Boolean = false,
    val shouldSuspendProjectionRender: Boolean = false,
    val btDiscoveryAllowed: Boolean = true,
    val activeObdProfile: ObdPollingProfile = ObdPollingProfile.DOCK_BASIC
)

/**
 * Árbitro determinístico central que coordena as 4 conexões (Projeção USB,
 * Câmera USB, OBD-II BT SPP e Áudio BT A2DP) garantindo prioridade da câmera
 * de ré e zero interferência do OBD-II sobre o áudio do processador.
 */
class HeadUnitCoordinator(
    private val reverseStateMachine: ReverseStateMachine = ReverseStateMachine(),
    var alertThresholds: AlertThresholds = AlertThresholds()
) {
    private var currentState = HeadUnitState()

    val state: HeadUnitState
        get() = currentState

    fun configure(
        setupCompleted: Boolean,
        projectionMode: ProjectionMode,
        duckAudioOnReverse: Boolean
    ): HeadUnitState {
        return updateDerived {
            it.copy(
                setupCompleted = setupCompleted,
                projectionMode = projectionMode,
                duckAudioOnReverse = duckAudioOnReverse
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

    fun updateProjectionStatus(status: LinkStatus): HeadUnitState {
        return updateDerived {
            it.copy(
                projectionStatus = status,
                connectedPhoneType = if (status == LinkStatus.CONNECTED) it.connectedPhoneType else null
            )
        }
    }

    fun updateConnectedPhone(phoneType: CpcPhoneType?): HeadUnitState {
        return updateDerived { it.copy(connectedPhoneType = phoneType) }
    }

    fun updatePowerConnected(connected: Boolean): HeadUnitState {
        return updateDerived { it.copy(powerConnected = connected) }
    }

    fun onCameraSample(analysis: SignalAnalysis, nowMs: Long): HeadUnitState {
        val nextCameraState = reverseStateMachine.onVideoSignalSample(
            hasSignal = analysis.hasActiveSignal,
            nowMs = nowMs
        )
        return updateDerived {
            it.copy(
                cameraState = nextCameraState,
                cameraSignalDebug = analysis
            )
        }
    }

    fun onUsbCameraAttached(nowMs: Long): HeadUnitState {
        val nextCameraState = reverseStateMachine.onUsbCameraAttached(nowMs)
        return updateDerived { it.copy(cameraState = nextCameraState) }
    }

    fun onUsbCameraDetached(): HeadUnitState {
        val nextCameraState = reverseStateMachine.onUsbCameraDetached()
        return updateDerived { it.copy(cameraState = nextCameraState) }
    }

    fun toggleManualCameraPreview(): HeadUnitState {
        val nextCameraState = reverseStateMachine.toggleManualPreview()
        return updateDerived { it.copy(cameraState = nextCameraState) }
    }

    fun exitManualCameraPreview(): HeadUnitState {
        val nextCameraState = reverseStateMachine.exitManualPreview()
        return updateDerived { it.copy(cameraState = nextCameraState) }
    }

    private fun updateDerived(transform: (HeadUnitState) -> HeadUnitState): HeadUnitState {
        val updated = transform(currentState)
        val reverseVisible = updated.cameraState == ReverseCameraState.ACTIVE ||
            updated.cameraState == ReverseCameraState.CONFIRMING_LOSS ||
            updated.cameraState == ReverseCameraState.MANUAL_PREVIEW

        val duckAudio = updated.duckAudioOnReverse &&
            (updated.cameraState == ReverseCameraState.ACTIVE ||
                updated.cameraState == ReverseCameraState.CONFIRMING_LOSS)

        // Nunca permite BT discovery após a 1ª configuração ou com o áudio conectado
        val discoveryAllowed = !updated.setupCompleted && updated.audioBtStatus != LinkStatus.CONNECTED

        val pollingProfile = if (updated.activeTab == MainTab.OBD_SCANNER && !reverseVisible) {
            ObdPollingProfile.SCANNER_DETAILED
        } else {
            ObdPollingProfile.DOCK_BASIC
        }

        currentState = updated.copy(
            isReverseOverlayVisible = reverseVisible,
            shouldDuckAudio = duckAudio,
            shouldSuspendProjectionRender = reverseVisible,
            btDiscoveryAllowed = discoveryAllowed,
            activeObdProfile = pollingProfile
        )
        return currentState
    }
}
