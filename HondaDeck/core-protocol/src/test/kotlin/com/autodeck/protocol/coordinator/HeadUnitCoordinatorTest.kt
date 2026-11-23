package com.autodeck.protocol.coordinator

import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.ReverseTimingConfig
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.obd2.AlertSeverity
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitCoordinatorTest {

    private fun signalSample(active: Boolean) = SignalAnalysis(
        hasActiveSignal = active,
        reason = if (active) "Ativo" else "Sem sinal",
        meanLuma = if (active) 110.0 else 15.0,
        stdDev = if (active) 35.0 else 0.0,
        diffFromReference = 50.0
    )

    @Test
    fun `defaults to HOME tab and HOME_TURBO polling profile`() {
        val coordinator = HeadUnitCoordinator()
        assertEquals(MainTab.HOME, coordinator.state.activeTab)
        assertEquals(ObdPollingProfile.HOME_TURBO, coordinator.state.activeObdProfile)
    }

    @Test
    fun `blocks bluetooth discovery once setup is done or audio A2DP is connected`() {
        val coordinator = HeadUnitCoordinator()
        assertTrue(coordinator.state.btDiscoveryAllowed)

        coordinator.updateAudioBluetooth(LinkStatus.CONNECTED, "Taramps BT")
        assertFalse(coordinator.state.btDiscoveryAllowed)

        coordinator.updateAudioBluetooth(LinkStatus.DISCONNECTED)
        coordinator.configure(setupCompleted = true, duckAudioOnReverse = true, obdFastTurboMode = true)
        assertFalse(coordinator.state.btDiscoveryAllowed)
    }

    @Test
    fun `reverse camera takes priority, ducks audio and reduces OBD cadence`() {
        val coordinator = HeadUnitCoordinator(
            reverseStateMachine = ReverseStateMachine(ReverseTimingConfig(activateAfterFrames = 2, deactivateAfterMs = 1_000L))
        )
        coordinator.selectTab(MainTab.OBD_SCANNER)
        assertEquals(ObdPollingProfile.SCANNER_DETAILED, coordinator.state.activeObdProfile)

        coordinator.onCameraSample(signalSample(true), 100L)
        val activeState = coordinator.onCameraSample(signalSample(true), 150L)

        assertEquals(ReverseCameraState.ACTIVE, activeState.cameraState)
        assertTrue(activeState.isReverseOverlayVisible)
        assertTrue(activeState.shouldDuckAudio)
        assertEquals(ObdPollingProfile.DOCK_BASIC, activeState.activeObdProfile)
    }

    @Test
    fun `ignores short cranking voltage drop and triggers critical coolant alert`() {
        val coordinator = HeadUnitCoordinator()

        val crankingDrop = coordinator.updateObdSnapshot(
            snapshot = ObdSnapshot(coolantTempC = 90.0, batteryVoltageV = 10.4, rpm = 900.0),
            lowVoltageDurationMs = 2_000L
        )
        assertTrue(crankingDrop.obdAlerts.isEmpty())

        val overheat = coordinator.updateObdSnapshot(
            snapshot = ObdSnapshot(coolantTempC = 114.0, batteryVoltageV = 14.0, rpm = 2_000.0)
        )
        assertTrue(overheat.obdAlerts.any { it.severity == AlertSeverity.CRITICAL })

        val alertTitle = overheat.obdAlerts.first().title
        val dismissed = coordinator.dismissAlert(alertTitle)
        assertEquals(alertTitle, dismissed.dismissedAlertTitle)
    }
}
