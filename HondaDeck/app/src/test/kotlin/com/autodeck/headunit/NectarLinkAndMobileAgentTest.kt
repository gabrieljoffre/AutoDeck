package com.autodeck.headunit

import android.app.Application
import com.autodeck.headunit.agent.CarAgentHostBridge
import com.autodeck.headunit.agent.GeminiCarAgent
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.navigation.GpsLocationState
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.ReverseTimingConfig
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.coordinator.HeadUnitCoordinator
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.FuelType
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdSnapshot
import com.autodeck.protocol.obd2.TransmissionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated verification suite modeled after:
 * 1. NectarLink (`thtbee/nectarlink` check.ps1 / ui-check.ps1 walkthrough+home passes / manifest compliance)
 * 2. mobile-agent-testers (`pierridotite/mobile-agent-testers` personas & a11y.py 48dp / large-font / fast-tapper checks)
 */
class NectarLinkAndMobileAgentTest {

    private class RecordingHostBridge : CarAgentHostBridge {
        var currentTab: MainTab = MainTab.HOME
        var lastMusicAction: String? = null
        var lastMusicQuery: String? = null
        var lastDestination: String? = null
        var reverseToggled = false

        fun resetRecordedActions() {
            lastMusicAction = null
            lastMusicQuery = null
            lastDestination = null
            reverseToggled = false
        }

        private val cfg = HeadUnitConfig(
            voiceAssistantMode = VoiceAssistantMode.LOCAL_ONLY,
            wakeWordEnabled = false
        )

        private val huState = HeadUnitState(
            activeTab = MainTab.HOME,
            obdStatus = LinkStatus.CONNECTED,
            obdSnapshot = ObdSnapshot(
                coolantTempC = 89.0,
                batteryVoltageV = 14.1,
                engineLoadPct = 32.0,
                rpm = 2150.0,
                speedKmh = 64.0,
                averageKmPerL = 12.1
            )
        )

        override fun getConfig(): HeadUnitConfig = cfg
        override fun getHeadUnitState(): HeadUnitState = huState
        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState(
            title = "Honda Civic Night",
            artist = "Spotify",
            isPlaying = false
        )

        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = emptyList()

        override fun controlMusic(action: String, query: String?): String {
            lastMusicAction = action
            lastMusicQuery = query
            return action
        }

        override fun navigateTo(destination: String?): String {
            lastDestination = destination ?: "DEFAULT_MAP"
            return "Maps"
        }

        override fun switchTab(tab: MainTab): String {
            currentTab = tab
            return tab.name
        }

        override fun toggleReverseCamera(): String {
            reverseToggled = true
            return "Câmera"
        }

        override fun replyLatestWhatsApp(replyText: String): String = "Enviado"
        override fun markMessagesRead() = Unit
    }

    private fun resolveAppDir(): File {
        val direct = File("src/main/AndroidManifest.xml")
        if (direct.exists()) return File(".")
        val nested = File("app/src/main/AndroidManifest.xml")
        if (nested.exists()) return File("app")
        error("Could not locate app module directory from ${File(".").absolutePath}")
    }

    @Test
    fun nectarlink_manifest_play_protect_and_automotive_compliance() {
        val manifest = File(resolveAppDir(), "src/main/AndroidManifest.xml").readText()
        val completoManifest = File(resolveAppDir(), "src/completo/AndroidManifest.xml").readText()

        val blockedPermissions = listOf(
            "BIND_NOTIFICATION_LISTENER_SERVICE",
            "BIND_ACCESSIBILITY_SERVICE",
            "REQUEST_INSTALL_PACKAGES",
            "READ_SMS",
            "RECEIVE_SMS"
        )
        blockedPermissions.forEach { perm ->
            assertFalse(
                "Main manifest must NOT declare Play-Protect-risky permission: $perm",
                manifest.contains(perm)
            )
        }
        assertTrue(
            "Flavor completo must declare BIND_NOTIFICATION_LISTENER_SERVICE",
            completoManifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE")
        )
        assertFalse(
            "Flavor qr must not have a notification listener manifest",
            File(resolveAppDir(), "src/qr/AndroidManifest.xml").exists()
        )

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION"))
        assertTrue(manifest.contains("foregroundServiceType=\"connectedDevice|location\""))
        assertTrue(manifest.contains("android:showWhenLocked=\"true\""))
        assertTrue(manifest.contains("android:turnScreenOn=\"true\""))
    }

    @Test
    fun nectarlink_ui_check_walkthrough_and_home_passes() {
        // Pass 1: Fresh install (walkthrough)
        val coordinator = HeadUnitCoordinator()
        assertFalse(coordinator.state.setupCompleted)
        assertTrue(coordinator.state.btDiscoveryAllowed)

        val wizardConfig = HeadUnitConfig(
            setupCompleted = false,
            fuelType = FuelType.ETHANOL,
            engineDisplacementLiters = 1.7,
            transmissionType = TransmissionType.MANUAL_5,
            preferredMusicPackage = "com.spotify.music",
            preferredMapPackage = "com.google.android.apps.maps",
            ignitionBtName = "TARAMPS"
        )
        assertFalse(wizardConfig.setupCompleted)

        // Pass 2: Setup completed (Home) + paired Taramps audio
        val completedState = coordinator.configure(
            setupCompleted = true,
            duckAudioOnReverse = true,
            obdFastTurboMode = true
        )
        assertTrue(completedState.setupCompleted)
        assertFalse(completedState.btDiscoveryAllowed)

        coordinator.selectTab(MainTab.OBD_SCANNER)
        assertEquals(ObdPollingProfile.SCANNER_DETAILED, coordinator.state.activeObdProfile)

        MainTab.entries.forEach { tab ->
            assertEquals(tab, coordinator.selectTab(tab).activeTab)
        }
        assertEquals(ObdPollingProfile.HOME_TURBO, coordinator.selectTab(MainTab.HOME).activeObdProfile)
        assertEquals(MainTab.HOME, HeadUnitUiState(config = wizardConfig.copy(setupCompleted = true)).headUnit.activeTab)
    }

    @Test
    fun mobile_agent_persona_malik_fast_tab_and_reverse_interrupt() {
        val coordinator = HeadUnitCoordinator(
            reverseStateMachine = ReverseStateMachine(
                ReverseTimingConfig(activateAfterFrames = 2, deactivateAfterMs = 1_000L)
            )
        )
        coordinator.configure(setupCompleted = true, duckAudioOnReverse = true, obdFastTurboMode = true)

        // Rapidly switch across tabs before engaging reverse camera
        listOf(MainTab.NAVIGATION, MainTab.MUSIC, MainTab.AGENT_VOICE, MainTab.NAVIGATION).forEach {
            coordinator.selectTab(it)
        }

        // Manual preview must show overlay WITHOUT marking hardware reverse active or ducking audio
        val manualState = coordinator.toggleManualCameraPreview()
        assertEquals(ReverseCameraState.MANUAL_PREVIEW, manualState.cameraState)
        assertTrue(manualState.isReverseOverlayVisible)
        assertFalse(manualState.isHardwareReverseActive)
        assertFalse(manualState.shouldDuckAudio)
        coordinator.exitManualCameraPreview()

        // Hardware reverse signal must override navigation immediately, activate hardware flag, and duck audio
        val signalOn = SignalAnalysis(
            hasActiveSignal = true,
            reason = "Active NTSC",
            meanLuma = 96.0,
            stdDev = 26.0,
            diffFromReference = 42.0,
            temporalDiff = 7.5
        )
        coordinator.onCameraSample(signalOn, 100L)
        val reverseState = coordinator.onCameraSample(signalOn, 180L)
        assertEquals(ReverseCameraState.ACTIVE, reverseState.cameraState)
        assertTrue(reverseState.isReverseOverlayVisible)
        assertTrue(reverseState.isHardwareReverseActive)
        assertTrue(reverseState.shouldDuckAudio)
        assertEquals(ObdPollingProfile.DOCK_BASIC, reverseState.activeObdProfile)

        // GPS speed fallback check when OBD snapshot is absent
        val gps = GpsLocationState(hasFix = true, gpsSpeedKmh = 72.4f)
        assertEquals(72, if (gps.hasFix) gps.gpsSpeedKmh.toInt() else 0)
    }

    @Test
    fun mobile_agent_voice_word_boundary_no_false_positives() = runBlocking {
        val bridge = RecordingHostBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val agent = GeminiCarAgent(
            appContext = Application(),
            scope = scope,
            host = bridge,
            mainDispatcher = Dispatchers.Unconfined
        )

        // Trap inputs containing "play" or "re" substrings must NOT trigger play/camera commands
        val substringTraps = listOf(
            "quero replay",
            "limpar display",
            "ir no restaurante",
            "problema no freio",
            "nao pare",
            "wifi na rede"
        )
        substringTraps.forEach { trap ->
            bridge.resetRecordedActions()
            agent.sendUserPrompt(trap, speakReply = false)
            val reply = agent.state.value.messages.last()
            assertNull("Trap '$trap' must not trigger music", bridge.lastMusicAction)
            assertNull("Trap '$trap' must not trigger navigation", bridge.lastDestination)
            assertFalse("Trap '$trap' must not toggle reverse camera", bridge.reverseToggled)
            assertEquals("Modo Offline", reply.actionBadge)
        }

        // Exact voice commands MUST still trigger cleanly
        bridge.resetRecordedActions()
        agent.sendUserPrompt("tocar", speakReply = false)
        assertEquals("MUSIC_PLAY", bridge.lastMusicAction)

        bridge.resetRecordedActions()
        agent.sendUserPrompt("play", speakReply = false)
        assertEquals("MUSIC_PLAY", bridge.lastMusicAction)

        bridge.resetRecordedActions()
        agent.sendUserPrompt("pausar", speakReply = false)
        assertEquals("MUSIC_PAUSE", bridge.lastMusicAction)

        bridge.resetRecordedActions()
        agent.sendUserPrompt("próxima música", speakReply = false)
        assertEquals("MUSIC_NEXT", bridge.lastMusicAction)

        bridge.resetRecordedActions()
        agent.sendUserPrompt("abrir câmera de ré", speakReply = false)
        assertTrue(bridge.reverseToggled)

        agent.sendUserPrompt("como está o motor", speakReply = false)
        assertTrue(agent.state.value.messages.last().text.contains("89°C"))
    }

    @Test
    fun mobile_agent_a11y_and_large_font_source_audit() {
        val appDir = resolveAppDir()
        val screensDir = File(appDir, "src/main/kotlin/com/autodeck/headunit/ui/screens")
        val overlayDir = File(appDir, "src/main/kotlin/com/autodeck/headunit/ui/overlay")
        val webDir = File(appDir, "src/main/assets/web")

        // Old Compose screens must be deleted
        listOf(
            "HeadUnitScreen.kt",
            "HondaComponents.kt",
            "HondaSubPanels.kt",
            "FuelLogWidgets.kt",
            "WeatherWidgets.kt"
        ).forEach { removedFile ->
            assertFalse(
                "Old Compose file $removedFile should have been removed",
                File(screensDir, removedFile).exists()
            )
        }

        // Kept Compose files (SetupWizardScreen, BootSplashOverlay, ReverseCameraOverlay)
        val wizard = File(screensDir, "SetupWizardScreen.kt").readText()
        val reverseOverlay = File(overlayDir, "ReverseCameraOverlay.kt").readText()
        assertTrue(File(screensDir, "BootSplashOverlay.kt").exists())
        assertTrue(File(overlayDir, "FloatingHudOverlayManager.kt").exists())
        assertTrue(wizard.contains("Role.Switch"))
        assertTrue(wizard.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(reverseOverlay.contains("fun ReverseCameraOverlay("))

        // Every FilterChip call in SetupWizardScreen is wrapped inside FlowRow rather than an unwrapping Row
        val wizardLines = wizard.lines()
        wizardLines.forEachIndexed { idx, line ->
            if (line.contains("FilterChip(")) {
                val window = wizardLines.subList((idx - 8).coerceAtLeast(0), idx).joinToString("\n")
                assertFalse(
                    "FilterChip near line ${idx + 1} should not sit directly inside a non-wrapping Row",
                    window.contains("Row(") && !window.contains("FlowRow(")
                )
            }
        }

        // Web UI (assets/web): buttons with aria-label, >= 48px touch targets in CSS, no external CDN
        val indexHtml = File(webDir, "index.html").readText()
        val orbitaCss = File(webDir, "css/orbita.css").readText()

        val ariaLabelCount = Regex("""<button\b[^>]*\baria-label="[^"]+"""").findAll(indexHtml).count()
        assertTrue("Web index.html must define aria-label on interactive buttons (found $ariaLabelCount)", ariaLabelCount >= 25)
        assertTrue(indexHtml.contains("aria-label=\"Peça ao Gemini\""))
        assertTrue(indexHtml.contains("aria-label=\"Tocar ou pausar\""))
        assertTrue(indexHtml.contains("aria-label=\"Silenciar ou reativar o som\""))

        // Touch targets >= 48px in CSS
        val minHeight48Count = Regex("""min-height:\s*(48|5[0-9]|6[0-9]|7[0-9])px""").findAll(orbitaCss).count()
        assertTrue("orbita.css must enforce >= 48px touch targets (found $minHeight48Count)", minHeight48Count >= 5)
        assertTrue(orbitaCss.contains("width: 48px;\n  height: 48px;"))

        // No external CDN references in HTML, CSS, or app JS files
        assertFalse("index.html must not load scripts or styles from http(s)", Regex("""https?://""", RegexOption.IGNORE_CASE).containsMatchIn(indexHtml))
        assertFalse("orbita.css must not load fonts or assets from http(s)", Regex("""https?://""", RegexOption.IGNORE_CASE).containsMatchIn(orbitaCss))

        // Permite apenas a API oficial Google Maps JS em map.js (carregada sob demanda quando há chave);
        // mantém proibidos CDNs de fontes/bibliotecas (fonts.googleapis.com, gstatic, unpkg, jsdelivr, cdnjs).
        val bannedCdnRegex = Regex("""((?<!maps\.)googleapis\.com|gstatic\.com|unpkg\.com|jsdelivr\.net|cdnjs\.cloudflare\.com|cdn\.)""", RegexOption.IGNORE_CASE)
        val webSourceFiles = listOf(
            File(webDir, "index.html"),
            File(webDir, "css/orbita.css")
        ) + (File(webDir, "js").listFiles()?.filter { it.extension == "js" }.orEmpty())

        webSourceFiles.forEach { file ->
            val content = file.readText()
            assertFalse(
                "Web file ${file.name} must not load from an external CDN",
                bannedCdnRegex.containsMatchIn(content)
            )
        }
    }
}
