package com.autodeck.headunit.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodeck.headunit.R
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.HeadUnitViewModel
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.AlertSeverity
import com.autodeck.protocol.obd2.ObdAlert

@Composable
fun HeadUnitScreen(
    uiState: HeadUnitUiState,
    viewModel: HeadUnitViewModel
) {
    val headUnit = uiState.headUnit
    var showClearDtcDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        HondaOuterFrameBackground {
            Column(modifier = Modifier.fillMaxSize()) {
                // Cabeçalho Superior ([H] HONDA + Micro Pontos de Status)
                HondaTopHeader(
                    uiState = uiState,
                    onOpenSettings = { viewModel.selectTab(MainTab.SETTINGS) }
                )

                // Área Central das Telas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    when (headUnit.activeTab) {
                        MainTab.HOME -> HomeDashboardPanel(
                            uiState = uiState,
                            onSelectTab = { viewModel.selectTab(it) },
                            onLaunchMapApp = { viewModel.openPreferredMapApp() },
                            onTogglePlayPause = { viewModel.toggleMusicPlayPause() },
                            onPreviousTrack = { viewModel.skipMusicPrevious() },
                            onNextTrack = { viewModel.skipMusicNext() },
                            onSeekMusic = { viewModel.seekMusicTo(it) },
                            onLaunchMusicApp = { viewModel.openPreferredMusicApp() }
                        )

                        MainTab.NAVIGATION -> NavigationFullPanel(
                            uiState = uiState,
                            onOpenPreferredMap = { viewModel.openPreferredMapApp() },
                            onNavigateDestination = { viewModel.navigateToDestination(it) },
                            onDownloadOfflineRegion = { mapView -> viewModel.downloadOfflineMap(mapView) }
                        )

                        MainTab.MUSIC -> MusicCenterPanel(
                            uiState = uiState,
                            onTogglePlayPause = { viewModel.toggleMusicPlayPause() },
                            onPrevious = { viewModel.skipMusicPrevious() },
                            onNext = { viewModel.skipMusicNext() },
                            onSeekTo = { viewModel.seekMusicTo(it) },
                            onLaunchMusicApp = { viewModel.openPreferredMusicApp() },
                            onSetVolumePct = { viewModel.setMasterVolumePct(it) }
                        )

                        MainTab.AGENT_VOICE -> GeminiAgentPanel(
                            uiState = uiState,
                            onTriggerVoice = { viewModel.triggerVoiceAgent() },
                            onStopVoice = { viewModel.stopVoiceAgent() },
                            onSendPrompt = { viewModel.sendAgentPrompt(it) },
                            onReadLatestWhatsApp = { viewModel.readLatestWhatsAppAloud() },
                            onReplyWhatsApp = { item, text -> viewModel.replyToWhatsApp(item, text) },
                            onDismissWhatsApp = { viewModel.dismissWhatsApp(it) },
                            onLaunchWhatsApp = { viewModel.launchWhatsApp() },
                            onToggleWakeWord = { enabled ->
                                viewModel.updateConfig { it.copy(wakeWordEnabled = enabled) }
                            }
                        )

                        MainTab.OBD_SCANNER -> ObdScannerPanel(
                            uiState = uiState,
                            onConnect = { viewModel.connectObdNow() },
                            onDisconnect = { viewModel.disconnectObd() },
                            onReadDtc = { viewModel.readDtcCodes() },
                            onRequestClearDtc = { showClearDtcDialog = true },
                            onResetTrip = { viewModel.resetTripConsumption() },
                            onMarkTankFull = { viewModel.markTankFull() },
                            onAddFuelLiters = { viewModel.addFuelLiters(it) },
                            onToggleSim = { enabled ->
                                viewModel.updateConfig { it.copy(useSimulatedObd = enabled) }
                            }
                        )

                        MainTab.SETTINGS -> {
                            val camDebug by viewModel.cameraDebug.collectAsStateWithLifecycle()
                            HondaSettingsPanel(
                                uiState = uiState,
                                cameraDebug = camDebug,
                                onRefreshDevices = {
                                    viewModel.onPermissionsUpdated()
                                    viewModel.refreshUsbDevices()
                                },
                                onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                                onUpdateConfig = { transform -> viewModel.updateConfig(transform) },
                                onToggleLauncherMode = { viewModel.setLauncherModeEnabled(it) },
                                onEnterStandbyNow = { viewModel.enterStandbyManually() },
                                onSelectCameraChannel = { viewModel.selectCameraChannel(it) },
                                onSetCameraStandardNtsc = { viewModel.setCameraStandardNtsc(it) },
                                onCalibrateNoSignal = { viewModel.calibrateCameraNoSignal() },
                                onClearNoSignal = { viewModel.clearNoSignalCalibration() },
                                onTestReverse = { viewModel.toggleSimulatedReverse() },
                                onTestManualCamera = { viewModel.toggleManualCameraPreview() },
                                onReopenWizard = { viewModel.reopenSetup() }
                            )
                        }
                    }

                    // Banner de Alerta Crítico OBD-II (prioridade sobre banner de mensagem)
                    val visibleAlert = headUnit.obdAlerts.firstOrNull { it.title != headUnit.dismissedAlertTitle }
                    if (visibleAlert != null && !headUnit.isReverseOverlayVisible) {
                        CriticalAlertBanner(
                            alert = visibleAlert,
                            onDismiss = { viewModel.dismissCurrentAlert(visibleAlert.title) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp)
                        )
                    } else if (uiState.config.whatsappVoiceAlertsEnabled && !headUnit.isReverseOverlayVisible) {
                        val unreadMsg = uiState.whatsAppMessages.firstOrNull { !it.isReadByAgent }
                        if (unreadMsg != null) {
                            WhatsAppTopBanner(
                                item = unreadMsg,
                                onReadAloud = { viewModel.readLatestWhatsAppAloud() },
                                onOpenAgent = { viewModel.selectTab(MainTab.AGENT_VOICE) },
                                onDismiss = { viewModel.dismissWhatsApp(unreadMsg.key) },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Dock Inferior Outlined Neon
                HondaBottomDock(
                    activeTab = headUnit.activeTab,
                    unreadMessages = uiState.whatsAppMessages.count { !it.isReadByAgent },
                    onSelectTab = { viewModel.selectTab(it) },
                    onLongPressVoice = { viewModel.triggerVoiceAgent() },
                    onLongPressCamera = { viewModel.toggleManualCameraPreview() }
                )
            }
        }

        if (uiState.config.debugReferenceOverlay &&
            headUnit.activeTab == MainTab.HOME &&
            !headUnit.isReverseOverlayVisible
        ) {
            Image(
                painter = painterResource(id = R.drawable.ref_home),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.38f)
            )
        }

        // Overlay 100% tela cheia da Câmera de Ré (prioridade máxima)
        if (headUnit.isReverseOverlayVisible) {
            ReverseCameraOverlay(
                uiState = uiState,
                onAttachCameraSurface = { viewModel.attachCameraSurface(it) },
                onSelectCameraChannel = { viewModel.selectCameraChannel(it) },
                onSetCameraStandardNtsc = { viewModel.setCameraStandardNtsc(it) },
                onToggleMirror = {
                    viewModel.updateConfig {
                        it.copy(guidelines = it.guidelines.copy(mirrorHorizontal = !it.guidelines.mirrorHorizontal))
                    }
                },
                onToggleGuidelines = {
                    viewModel.updateConfig {
                        it.copy(guidelines = it.guidelines.copy(enabled = !it.guidelines.enabled))
                    }
                },
                onToggleSimulatedReverse = { viewModel.toggleSimulatedReverse() },
                onCloseManual = { viewModel.exitManualCameraPreview() }
            )
        } else if (uiState.ignitionStatus.standbyActive) {
            StandbyBlackOverlay(
                statusLabel = uiState.ignitionStatus.triggerDeviceLabel,
                onWakeUp = { viewModel.wakeFromStandbyManually() }
            )
        }

        if (showClearDtcDialog && !headUnit.isReverseOverlayVisible) {
            AlertDialog(
                onDismissRequest = { showClearDtcDialog = false },
                title = { Text("Limpar Códigos de Falha (DTC)?") },
                text = { Text("Isso apaga os códigos armazenados e a luz de injeção da ECU.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearDtcDialog = false
                            viewModel.clearDtcCodes()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusRed)
                    ) {
                        Text("Confirmar Limpeza", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDtcDialog = false }) { Text("Cancelar") }
                }
            )
        }
    }
}

@Composable
private fun HomeDashboardPanel(
    uiState: HeadUnitUiState,
    onSelectTab: (MainTab) -> Unit,
    onLaunchMapApp: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekMusic: (Long) -> Unit,
    onLaunchMusicApp: () -> Unit
) {
    val snap = uiState.headUnit.obdSnapshot
    val derived = snap.derived
    val speed = (
        derived.correctedSpeedKmh
            ?: snap.speedKmh
            ?: if (uiState.gpsState.hasFix) uiState.gpsState.gpsSpeedKmh.toDouble() else 0.0
        ).toFloat()
    val rpm = (snap.rpm ?: 0.0).toFloat()
    val fuelPct = (snap.fuelLevelPct ?: derived.virtualTankPercent).toFloat()

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Coluna Esquerda: Relógio Digital + Card NAVEGAÇÃO >
        Column(
            modifier = Modifier
                .weight(0.26f)
                .fillMaxSize()
        ) {
            HondaClockBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.27f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OsmDarkMiniMapCard(
                gpsState = uiState.gpsState,
                preferredMapLabel = uiState.config.preferredMapLabel,
                onExpandMap = { onSelectTab(MainTab.NAVIGATION) },
                onLaunchPreferredMap = onLaunchMapApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.73f)
            )
        }

        // Coluna Central: Velocímetro Neon + Marchas + Combustível
        HondaCenterGaugeCluster(
            speedKmh = speed,
            rpm = rpm,
            maxSpeedKmh = uiState.config.speedometerMaxKmh,
            transmission = uiState.config.transmissionType,
            estimatedGear = derived.estimatedGear,
            isReverseActive = uiState.headUnit.isHardwareReverseActive,
            fuelPercent = fuelPct,
            modifier = Modifier.weight(0.47f)
        )

        // Coluna Direita: MÚSICA (em cima) + MONITORAMENTO (embaixo)
        Column(
            modifier = Modifier
                .weight(0.27f)
                .fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(30.dp))
            HondaMusicCard(
                mediaState = uiState.mediaState,
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPreviousTrack,
                onNext = onNextTrack,
                onSeekTo = onSeekMusic,
                onOpenMusicTab = { onSelectTab(MainTab.MUSIC) },
                onLaunchMusicApp = onLaunchMusicApp,
                modifier = Modifier.weight(1.48f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            HondaMonitoringCard(
                batteryVoltageV = snap.batteryVoltageV,
                coolantTempC = snap.coolantTempC,
                engineLoadPct = snap.engineLoadPct,
                isStale = snap.isStale,
                onOpenObdScanner = { onSelectTab(MainTab.OBD_SCANNER) },
                modifier = Modifier.weight(1.0f)
            )
        }
    }
}

@Composable
private fun WhatsAppTopBanner(
    item: WhatsAppNotificationItem,
    onReadAloud: () -> Unit,
    onOpenAgent: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(0.75f),
        colors = CardDefaults.cardColors(containerColor = Color(0xF00B1C2E)),
        border = BorderStroke(1.dp, AutoDeckColors.AccentCyan),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.Message, null, tint = AutoDeckColors.StatusGreen)
            Column(modifier = Modifier.weight(1f)) {
                Text("${item.sender} (${item.appLabel})", color = AutoDeckColors.AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(item.message, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onReadAloud,
                colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("🔊 Ler", color = Color(0xFF03151B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenAgent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Responder", fontSize = 11.sp)
            }
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onDismiss)
                    .clearAndSetSemantics { contentDescription = "Fechar alerta WhatsApp" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    color = AutoDeckColors.TextSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraInputControlRow(
    selectedChannel: Int,
    isNtsc: Boolean,
    statusRegHex: String?,
    showDiagnostic: Boolean,
    onSelectCameraChannel: (Int) -> Unit,
    onSetCameraStandardNtsc: (Boolean) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "RCA:",
            color = AutoDeckColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        (0..3).forEach { ch ->
            val selected = selectedChannel == ch
            if (selected) {
                Button(
                    onClick = { onSelectCameraChannel(ch) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Cam ${ch + 1}", fontSize = 12.sp) }
            } else {
                OutlinedButton(
                    onClick = { onSelectCameraChannel(ch) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Cam ${ch + 1}", fontSize = 12.sp) }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        listOf(true to "NTSC", false to "PAL").forEach { (isNtscOpt, label) ->
            val selected = isNtsc == isNtscOpt
            if (selected) {
                Button(
                    onClick = { onSetCameraStandardNtsc(isNtscOpt) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(label, fontSize = 12.sp) }
            } else {
                OutlinedButton(
                    onClick = { onSetCameraStandardNtsc(isNtscOpt) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(label, fontSize = 12.sp) }
            }
        }
        if (showDiagnostic && statusRegHex != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Reg1F=$statusRegHex",
                color = AutoDeckColors.AccentCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReverseCameraOverlay(
    uiState: HeadUnitUiState,
    onAttachCameraSurface: (Surface?) -> Unit,
    onSelectCameraChannel: (Int) -> Unit,
    onSetCameraStandardNtsc: (Boolean) -> Unit,
    onToggleMirror: () -> Unit,
    onToggleGuidelines: () -> Unit,
    onToggleSimulatedReverse: () -> Unit,
    onCloseManual: () -> Unit
) {
    val guide = uiState.config.guidelines
    val isManual = uiState.headUnit.cameraState == ReverseCameraState.MANUAL_PREVIEW
    var currentSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onAttachCameraSurface(null)
            currentSurface?.release()
            currentSurface = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06090F))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            currentSurface?.release()
                            val surface = Surface(st)
                            currentSurface = surface
                            onAttachCameraSurface(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onAttachCameraSurface(null)
                            currentSurface?.release()
                            currentSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    if (isAvailable && surfaceTexture != null) {
                        val surface = Surface(surfaceTexture)
                        currentSurface = surface
                        onAttachCameraSurface(surface)
                    }
                }
            },
            update = { tv ->
                tv.scaleX = if (guide.mirrorHorizontal) -1f else 1f
            }
        )

        if (!uiState.cameraHardware.isStreamingVideo) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (uiState.config.useSimulatedCamera) {
                        "CÂMERA DE RÉ ATIVA (MODO SIMULADOR)"
                    } else {
                        "CÂMERA DE RÉ ATIVA — ${uiState.cameraHardware.connectedDeviceLabel}"
                    },
                    color = AutoDeckColors.AccentCyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (guide.mirrorHorizontal) "Espelhamento: ATIVO" else "Espelhamento: NORMAL (padrão aftermarket)",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        if (guide.enabled) {
            ParkingGuidelinesCanvas(guide = guide, modifier = Modifier.fillMaxSize())
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(AutoDeckColors.StatusRed))
                Text(
                    text = if (isManual) "CÂMERA TRASEIRA (MANUAL)" else "MARCHA RÉ DETECTADA — PRIORIDADE MÁXIMA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = onToggleMirror) { Text("Espelhar") }
                OutlinedButton(onClick = onToggleGuidelines) {
                    Text(if (guide.enabled) "Ocultar Guias" else "Mostrar Guias")
                }
                if (uiState.config.useSimulatedCamera && !isManual) {
                    Button(
                        onClick = onToggleSimulatedReverse,
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusAmber)
                    ) {
                        Text("Desengatar Ré (Sim)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                if (isManual) {
                    Button(
                        onClick = onCloseManual,
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusRed)
                    ) {
                        Text("Fechar Câmera", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!uiState.config.useSimulatedCamera) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                CameraInputControlRow(
                    selectedChannel = uiState.config.cameraInputChannel,
                    isNtsc = uiState.config.cameraIsNtsc,
                    statusRegHex = uiState.cameraHardware.lastStatusRegHex,
                    showDiagnostic = true,
                    onSelectCameraChannel = onSelectCameraChannel,
                    onSetCameraStandardNtsc = onSetCameraStandardNtsc
                )
            }
        }
    }
}

@Composable
private fun ParkingGuidelinesCanvas(
    guide: GuidelineCalibration,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerX = w * (0.5f + guide.horizontalOffsetRatio)
        val topY = h * guide.topYRatio
        val botY = h * guide.bottomYRatio
        val midY = topY + (botY - topY) * 0.45f
        val nearY = topY + (botY - topY) * 0.78f

        val topHalf = (w * guide.topWidthRatio) / 2f
        val botHalf = (w * guide.bottomWidthRatio) / 2f
        val midHalf = topHalf + (botHalf - topHalf) * 0.45f
        val nearHalf = topHalf + (botHalf - topHalf) * 0.78f

        val stroke = 6f
        val dash = PathEffect.dashPathEffect(floatArrayOf(24f, 16f), 0f)

        drawLine(AutoDeckColors.StatusGreen, Offset(centerX - topHalf, topY), Offset(centerX - midHalf, midY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusGreen, Offset(centerX + topHalf, topY), Offset(centerX + midHalf, midY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusGreen, Offset(centerX - topHalf, topY), Offset(centerX + topHalf, topY), 4f, pathEffect = dash)

        drawLine(AutoDeckColors.StatusAmber, Offset(centerX - midHalf, midY), Offset(centerX - nearHalf, nearY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusAmber, Offset(centerX + midHalf, midY), Offset(centerX + nearHalf, nearY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusAmber, Offset(centerX - midHalf, midY), Offset(centerX + midHalf, midY), 4f, pathEffect = dash)

        drawLine(AutoDeckColors.StatusRed, Offset(centerX - nearHalf, nearY), Offset(centerX - botHalf, botY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusRed, Offset(centerX + nearHalf, nearY), Offset(centerX + botHalf, botY), stroke, StrokeCap.Round)
        drawLine(AutoDeckColors.StatusRed, Offset(centerX - nearHalf, nearY), Offset(centerX + nearHalf, nearY), 4f, pathEffect = dash)
    }
}

@Composable
private fun CriticalAlertBanner(
    alert: ObdAlert,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (alert.severity == AlertSeverity.CRITICAL) AutoDeckColors.StatusRed else AutoDeckColors.StatusAmber
    val textColor = if (alert.severity == AlertSeverity.CRITICAL) Color.White else Color.Black

    Card(
        modifier = modifier.fillMaxWidth(0.82f),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(imageVector = Icons.Rounded.Warning, contentDescription = null, tint = textColor)
                Column {
                    Text(alert.title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(alert.detail, color = textColor.copy(alpha = 0.9f), fontSize = 12.sp)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("Dispensar", color = textColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}
