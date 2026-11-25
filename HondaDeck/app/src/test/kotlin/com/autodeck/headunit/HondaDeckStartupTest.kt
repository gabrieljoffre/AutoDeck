package com.autodeck.headunit

import android.app.Application
import com.autodeck.headunit.agent.AgentUiState
import com.autodeck.headunit.agent.CarAgentHostBridge
import com.autodeck.headunit.agent.GeminiCarAgent
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.protocol.coordinator.HeadUnitCoordinator
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.CivicTelemetryCalculator
import com.autodeck.protocol.obd2.FuelConsumptionCalculator
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdSnapshot
import com.autodeck.protocol.obd2.TireSpec
import com.autodeck.protocol.obd2.TransmissionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HondaDeckStartupTest {

    private class FakeCarHostBridge : CarAgentHostBridge {
        var currentTab: MainTab = MainTab.HOME
        var lastMusicAction: String? = null
        var lastMusicQuery: String? = null
        var lastDestination: String? = null
        var reverseToggled = false

        private val cfg = HeadUnitConfig(
            voiceAssistantMode = VoiceAssistantMode.LOCAL_ONLY,
            wakeWordEnabled = false
        )

        private val huState = HeadUnitState(
            activeTab = MainTab.HOME,
            obdStatus = LinkStatus.CONNECTED,
            obdSnapshot = ObdSnapshot(
                coolantTempC = 92.0,
                batteryVoltageV = 14.2,
                engineLoadPct = 25.0,
                rpm = 2400.0,
                speedKmh = 80.0,
                averageKmPerL = 11.4
            )
        )

        override fun getConfig(): HeadUnitConfig = cfg
        override fun getHeadUnitState(): HeadUnitState = huState
        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState(
            title = "Teste Honda",
            artist = "Spotify",
            isPlaying = true
        )
        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = emptyList()

        override fun controlMusic(action: String, query: String?): String {
            lastMusicAction = action
            lastMusicQuery = query
            return action
        }

        override fun navigateTo(destination: String?): String {
            lastDestination = destination ?: "DEFAULT"
            return "Maps"
        }

        override fun switchTab(tab: MainTab): String {
            currentTab = tab
            return tab.name
        }

        override fun toggleReverseCamera(): String {
            reverseToggled = true
            return "Ré"
        }

        override fun replyLatestWhatsApp(replyText: String): String = "Enviado"
        override fun markMessagesRead() = Unit
    }

    @Test
    fun `initial UI state and config defaults match HondaDeck requirements`() {
        val state = HeadUnitUiState()
        assertEquals(MainTab.HOME, state.headUnit.activeTab)
        assertEquals(ObdPollingProfile.HOME_TURBO, state.headUnit.activeObdProfile)
        assertEquals("com.spotify.music", state.config.preferredMusicPackage)
        assertEquals("com.google.android.apps.maps", state.config.preferredMapPackage)
        assertEquals(160, state.config.speedometerMaxKmh)
        assertEquals("ok honda", state.config.wakeWordPhrase)
        assertEquals("TARAMPS", state.config.ignitionBtName)
        assertNotNull(state.agentState)
    }

    @Test
    fun `coordinator switches cleanly across all 6 HondaDeck tabs and keeps camera priority`() {
        val coordinator = HeadUnitCoordinator()
        MainTab.entries.forEach { tab ->
            val updated = coordinator.selectTab(tab)
            assertEquals(tab, updated.activeTab)
        }

        coordinator.selectTab(MainTab.HOME)
        assertEquals(ObdPollingProfile.HOME_TURBO, coordinator.state.activeObdProfile)

        coordinator.selectTab(MainTab.OBD_SCANNER)
        assertEquals(ObdPollingProfile.SCANNER_DETAILED, coordinator.state.activeObdProfile)

        val manualCam = coordinator.toggleManualCameraPreview()
        assertTrue(manualCam.isReverseOverlayVisible)
        assertFalse(coordinator.exitManualCameraPreview().isReverseOverlayVisible)
    }

    @Test
    fun `GeminiCarAgent initializes safely and executes offline vehicle, music and map commands`() = runBlocking {
        val bridge = FakeCarHostBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val agent = GeminiCarAgent(
            appContext = Application(),
            scope = scope,
            host = bridge,
            mainDispatcher = Dispatchers.Unconfined
        )

        agent.sendUserPrompt("Como está o motor agora?", speakReply = false)
        val motorReply = agent.state.value.messages.last().text
        assertTrue(motorReply.contains("92°C"))
        assertTrue(motorReply.contains("14") && motorReply.contains("V"))

        agent.sendUserPrompt("Próxima música", speakReply = false)
        assertEquals("MUSIC_NEXT", bridge.lastMusicAction)

        agent.sendUserPrompt("Ir para Avenida Paulista", speakReply = false)
        assertTrue(bridge.lastDestination.equals("Avenida Paulista", ignoreCase = true))
    }

    @Test
    fun `telemetry and fuel calculations remain stable at zero, highway and reverse states`() {
        val fuelCalc = FuelConsumptionCalculator()
        val idle = ObdSnapshot(rpm = 750.0, speedKmh = 0.0, coolantTempC = 90.0, engineLoadPct = 20.0)
        val idleDerived = CivicTelemetryCalculator.compute(
            snapshot = idle,
            tire = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15,
            transmission = TransmissionType.MANUAL_5,
            virtualTankCapacityLiters = 50.0,
            virtualTankRemainingLiters = 35.0,
            tripDistanceKm = 0.0,
            tripFuelUsedLiters = 0.0,
            peakRpm = 750.0,
            peakSpeedKmh = 0.0,
            peakCoolantTempC = 90.0,
            pendingDtcCount = 0,
            isReverseActive = false
        )
        assertEquals("N", idleDerived.estimatedGearLabel)

        val reverseDerived = CivicTelemetryCalculator.compute(
            snapshot = idle,
            tire = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15,
            transmission = TransmissionType.MANUAL_5,
            virtualTankCapacityLiters = 50.0,
            virtualTankRemainingLiters = 35.0,
            tripDistanceKm = 0.0,
            tripFuelUsedLiters = 0.0,
            peakRpm = 750.0,
            peakSpeedKmh = 0.0,
            peakCoolantTempC = 90.0,
            pendingDtcCount = 0,
            isReverseActive = true
        )
        assertEquals("R", reverseDerived.estimatedGearLabel)

        val sample = fuelCalc.recordSampleDetailed(
            speedKmh = 80.0,
            mafSensor = null,
            intakeMapKpa = 48.0,
            intakeTempC = 32.0,
            rpm = 2400.0,
            throttlePct = 25.0,
            stftPct = 0.0,
            ltftPct = 0.0,
            timestampMs = 1_000L
        )
        assertTrue((sample.instantKmPerL ?: 0.0) > 0.0)
        assertNotNull(AgentUiState())
    }
}
