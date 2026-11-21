package com.autodeck.headunit.ui.screens

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodeck.headunit.hardware.UsbDeviceRole
import com.autodeck.headunit.service.CameraDebugState
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.HeadUnitViewModel
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.carplay.MultiTouchAction
import com.autodeck.protocol.carplay.MultiTouchPoint
import com.autodeck.protocol.carplay.TouchAction
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.coordinator.ProjectionMode
import com.autodeck.protocol.obd2.AlertSeverity
import com.autodeck.protocol.obd2.ObdAlert
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HeadUnitScreen(
    uiState: HeadUnitUiState,
    viewModel: HeadUnitViewModel
) {
    val headUnit = uiState.headUnit
    var showClearDtcDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AutoDeckColors.BgCarbon)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            LeftAutomotiveDock(
                uiState = uiState,
                onSelectTab = { viewModel.selectTab(it) },
                onToggleCamera = { viewModel.toggleManualCameraPreview() }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp)
            ) {
                // C6: Mantém a SurfaceView do CarPlay/AA sempre montada para nunca destruir o MediaCodec ao trocar de aba!
                ProjectionPanel(
                    uiState = uiState,
                    onAttachSurface = { surface, w, h ->
                        viewModel.attachProjectionSurface(surface, w, h)
                    },
                    onTouch = { action, x, y ->
                        viewModel.sendProjectionTouch(action, x, y)
                    },
                    onMultiTouch = { points ->
                        viewModel.sendProjectionMultiTouch(points)
                    },
                    onLaunchHur = { viewModel.launchHurFallback() },
                    onOpenSettings = { viewModel.selectTab(MainTab.SETTINGS) },
                    onTestReverse = { viewModel.toggleSimulatedReverse() }
                )

                if (headUnit.activeTab == MainTab.OBD_SCANNER) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AutoDeckColors.BgCarbon)
                    ) {
                        ObdScannerPanel(
                            uiState = uiState,
                            onConnect = { viewModel.connectObdNow() },
                            onDisconnect = { viewModel.disconnectObd() },
                            onReadDtc = { viewModel.readDtcCodes() },
                            onRequestClearDtc = { showClearDtcDialog = true },
                            onResetTrip = { viewModel.resetTripConsumption() },
                            onToggleSim = { enabled ->
                                viewModel.updateConfig { it.copy(useSimulatedObd = enabled) }
                            }
                        )
                    }
                } else if (headUnit.activeTab == MainTab.SETTINGS) {
                    val camDebug by viewModel.cameraDebug.collectAsStateWithLifecycle()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AutoDeckColors.BgCarbon)
                    ) {
                        SettingsAndInspectorPanel(
                            uiState = uiState,
                            cameraDebug = camDebug,
                            onRefreshUsb = { viewModel.refreshUsbDevices() },
                            onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                            onCalibrateNoSignal = { viewModel.calibrateCameraNoSignal() },
                            onClearNoSignal = { viewModel.clearNoSignalCalibration() },
                            onToggleSimObd = { enabled ->
                                viewModel.updateConfig { it.copy(useSimulatedObd = enabled) }
                            },
                            onToggleSimCam = { enabled ->
                                viewModel.updateConfig { it.copy(useSimulatedCamera = enabled) }
                            },
                            onTestReverse = { viewModel.toggleSimulatedReverse() },
                            onUpdateGuidelines = { updated ->
                                viewModel.updateConfig { it.copy(guidelines = updated.clamped()) }
                            },
                            onUpdateThresholds = { updated ->
                                viewModel.updateConfig { it.copy(alertThresholds = updated) }
                            },
                            onUpdateFuelCalibration = { factor ->
                                viewModel.updateConfig { it.copy(fuelCalibrationFactor = factor) }
                            },
                            onToggleLauncherMode = { enabled ->
                                viewModel.setLauncherModeEnabled(enabled)
                            },
                            onSetProjectionMode = { mode ->
                                viewModel.updateConfig { it.copy(projectionMode = mode) }
                            },
                            onReopenWizard = { viewModel.reopenSetup() }
                        )
                    }
                }

                // Alertas críticos com botão dispensar (M2)
                val visibleAlert = headUnit.obdAlerts.firstOrNull { it.title != headUnit.dismissedAlertTitle }
                if (visibleAlert != null && !headUnit.isReverseOverlayVisible) {
                    CriticalAlertBanner(
                        alert = visibleAlert,
                        onDismiss = { viewModel.dismissCurrentAlert(visibleAlert.title) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    )
                }
            }
        }

        // Overlay prioritário 100% tela cheia da Câmera de Ré (cobre até CarPlay e Dock)
        if (headUnit.isReverseOverlayVisible) {
            ReverseCameraOverlay(
                uiState = uiState,
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
        }

        if (showClearDtcDialog) {
            AlertDialog(
                onDismissRequest = { showClearDtcDialog = false },
                title = { Text("Limpar códigos de falha (Mode 04)?") },
                text = {
                    Text("Isso apagará as falhas armazenadas e desligará a luz de injeção. Use com a chave ligada e o motor desligado.")
                },
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
                    TextButton(onClick = { showClearDtcDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
private fun LeftAutomotiveDock(
    uiState: HeadUnitUiState,
    onSelectTab: (MainTab) -> Unit,
    onToggleCamera: () -> Unit
) {
    val headUnit = uiState.headUnit
    var clockText by remember { mutableStateOf("--:--") }

    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            clockText = formatter.format(Date())
            delay(15_000L)
        }
    }

    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(AutoDeckColors.SurfaceDock)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clockText,
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = headUnit.connectedPhoneType?.labelPt ?: "AutoDeck",
                    color = AutoDeckColors.AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusDot("BT", headUnit.audioBtStatus)
                StatusDot("OBD", headUnit.obdStatus)
                StatusDot(
                    "CAM",
                    when {
                        headUnit.cameraState == ReverseCameraState.ACTIVE -> LinkStatus.CONNECTED
                        uiState.cameraHardware.hasValidFrameStream -> LinkStatus.STANDBY
                        else -> LinkStatus.DISCONNECTED
                    }
                )
                StatusDot("CP", headUnit.projectionStatus)
            }

            // Mini-card OBD-II fixo com Temperatura, Consumo e Bateria (clique abre Scanner Detalhado)
            val snap = headUnit.obdSnapshot
            val staleTint = AutoDeckColors.TextMuted
            val coolantVal = snap.coolantTempC
            val battVal = snap.batteryVoltageV

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTab(MainTab.OBD_SCANNER) },
                colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
                border = BorderStroke(
                    1.dp,
                    if (headUnit.obdAlerts.any { it.severity == AlertSeverity.CRITICAL }) AutoDeckColors.StatusRed
                    else AutoDeckColors.BorderSubtle
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (snap.isStale) "OBD (desatualizado)" else "MOTOR / OBD-II",
                            color = if (snap.isStale) AutoDeckColors.StatusAmber else AutoDeckColors.AccentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Detalhes →",
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 10.sp
                        )
                    }

                    DockMetricRow(
                        icon = Icons.Rounded.Thermostat,
                        label = "Temp.",
                        value = coolantVal?.let { "${it.toInt()} °C" } ?: "-- °C",
                        tint = when {
                            snap.isStale -> staleTint
                            coolantVal != null && coolantVal >= uiState.config.alertThresholds.coolantCriticalC -> AutoDeckColors.StatusRed
                            coolantVal != null && coolantVal >= uiState.config.alertThresholds.coolantWarningC -> AutoDeckColors.StatusAmber
                            else -> AutoDeckColors.StatusGreen
                        }
                    )

                    val kmPerL = snap.instantKmPerL ?: snap.averageKmPerL
                    val consumptionText = when {
                        kmPerL != null -> "${"%.1f".format(kmPerL)} km/L"
                        snap.litersPerHour != null -> "${"%.1f".format(snap.litersPerHour)} L/h"
                        else -> "-- km/L"
                    }
                    DockMetricRow(
                        icon = Icons.Rounded.LocalGasStation,
                        label = "Consumo",
                        value = consumptionText,
                        tint = if (snap.isStale) staleTint else AutoDeckColors.AccentCyan
                    )

                    // C1 fix: uso seguro sem smart cast entre módulos (:core-protocol -> :app)
                    val batteryLow = battVal?.let { it <= uiState.config.alertThresholds.batteryLowV } == true
                    DockMetricRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        label = "Bateria",
                        value = battVal?.let { "${"%.1f".format(it)} V" } ?: "-- V",
                        tint = when {
                            snap.isStale -> staleTint
                            batteryLow -> AutoDeckColors.StatusAmber
                            else -> AutoDeckColors.TextPrimary
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DockNavButton(
                label = "CarPlay / AA",
                icon = Icons.Rounded.DirectionsCar,
                selected = headUnit.activeTab == MainTab.PROJECTION,
                onClick = { onSelectTab(MainTab.PROJECTION) }
            )
            DockNavButton(
                label = "Scanner OBD2",
                icon = Icons.Rounded.Speed,
                selected = headUnit.activeTab == MainTab.OBD_SCANNER,
                onClick = { onSelectTab(MainTab.OBD_SCANNER) }
            )
            DockNavButton(
                label = "Câmera de Ré",
                icon = Icons.Rounded.Videocam,
                selected = headUnit.isReverseOverlayVisible,
                onClick = onToggleCamera
            )
            DockNavButton(
                label = "Config / USB",
                icon = Icons.Rounded.Settings,
                selected = headUnit.activeTab == MainTab.SETTINGS,
                onClick = { onSelectTab(MainTab.SETTINGS) }
            )
        }
    }
}

@Composable
private fun StatusDot(label: String, status: LinkStatus) {
    val color = when (status) {
        LinkStatus.CONNECTED -> AutoDeckColors.StatusGreen
        LinkStatus.STANDBY -> AutoDeckColors.AccentCyan
        LinkStatus.CONNECTING -> AutoDeckColors.StatusAmber
        LinkStatus.ERROR -> AutoDeckColors.StatusRed
        LinkStatus.DISCONNECTED -> AutoDeckColors.TextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, color = AutoDeckColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DockMetricRow(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
            Text(text = label, color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Text(text = value, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DockNavButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) AutoDeckColors.AccentCyan.copy(alpha = 0.20f) else AutoDeckColors.SurfaceCard
    val border = if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.BorderSubtle
    val contentColor = if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.TextPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ProjectionPanel(
    uiState: HeadUnitUiState,
    onAttachSurface: (android.view.Surface?, Int, Int) -> Unit,
    onTouch: (TouchAction, Float, Float) -> Unit,
    onMultiTouch: (List<MultiTouchPoint>) -> Unit,
    onLaunchHur: () -> Boolean,
    onOpenSettings: () -> Unit,
    onTestReverse: () -> Unit
) {
    val headUnit = uiState.headUnit
    val isCarlinkitStreaming = headUnit.projectionStatus == LinkStatus.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(18.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onAttachSurface(holder.surface, width, height)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            onAttachSurface(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onAttachSurface(null, 0, 0)
                        }
                    })
                    setOnTouchListener { v, event ->
                        if (v.width <= 0 || v.height <= 0) return@setOnTouchListener false
                        if (event.pointerCount > 1) {
                            val masked = event.actionMasked
                            val actionIdx = event.actionIndex
                            val points = (0 until event.pointerCount).map { i ->
                                val nx = (event.getX(i) / v.width.toFloat()).coerceIn(0f, 1f)
                                val ny = (event.getY(i) / v.height.toFloat()).coerceIn(0f, 1f)
                                val act = when {
                                    i == actionIdx && masked == MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
                                    i == actionIdx && (masked == MotionEvent.ACTION_POINTER_UP || masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) -> MultiTouchAction.UP
                                    else -> MultiTouchAction.MOVE
                                }
                                MultiTouchPoint(
                                    id = event.getPointerId(i),
                                    normalizedX = nx,
                                    normalizedY = ny,
                                    action = act
                                )
                            }
                            onMultiTouch(points)
                        } else {
                            val normX = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
                            val normY = (event.y / v.height.toFloat()).coerceIn(0f, 1f)
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> onTouch(TouchAction.DOWN, normX, normY)
                                MotionEvent.ACTION_MOVE -> onTouch(TouchAction.MOVE, normX, normY)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    onTouch(TouchAction.UP, normX, normY)
                            }
                        }
                        true
                    }
                }
            }
        )

        if (!isCarlinkitStreaming) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AutoDeckColors.BgCarbon.copy(alpha = 0.92f))
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.DirectionsCar,
                    contentDescription = null,
                    tint = AutoDeckColors.AccentCyan,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (headUnit.projectionMode == ProjectionMode.CARLINKIT_NATIVE) {
                        "Apple CarPlay & Android Auto"
                    } else {
                        "Modo Transição — HUR (Android Auto)"
                    },
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.carlinkitDetail,
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Áudio BT (Taranis): ${uiState.audioRoute.routeLabel} | Câmera: ${headUnit.cameraState.name}",
                    color = AutoDeckColors.AccentCyan,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(22.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (uiState.hurPackageInstalled != null) {
                        Button(
                            onClick = { onLaunchHur() },
                            colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan)
                        ) {
                            Text("Abrir HUR (Android Auto)", color = Color(0xFF03151B), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (uiState.config.useSimulatedCamera) {
                        OutlinedButton(onClick = onTestReverse) {
                            Text(
                                if (uiState.simulatedReverseActive) "Desengatar Ré (Sim)"
                                else "Simular Engate de Ré"
                            )
                        }
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("Inspetor USB / Config")
                    }
                }
            }
        }
    }
}

private data class SensorTile(
    val title: String,
    val value: String,
    val unit: String,
    val accent: Color = AutoDeckColors.TextPrimary
)

@Composable
private fun ObdScannerPanel(
    uiState: HeadUnitUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReadDtc: () -> Unit,
    onRequestClearDtc: () -> Unit,
    onResetTrip: () -> Unit,
    onToggleSim: (Boolean) -> Unit
) {
    val snap = uiState.headUnit.obdSnapshot
    val dtcs = uiState.headUnit.dtcCodes

    val tiles = listOf(
        SensorTile("Rotação", snap.rpm?.toInt()?.toString() ?: "--", "RPM", AutoDeckColors.AccentCyan),
        SensorTile("Velocidade", snap.speedKmh?.toInt()?.toString() ?: "--", "km/h", AutoDeckColors.AccentCyan),
        SensorTile("Temp. Motor", snap.coolantTempC?.toInt()?.toString() ?: "--", "°C", AutoDeckColors.StatusGreen),
        SensorTile("Consumo Inst.", snap.instantKmPerL?.let { "%.1f".format(it) } ?: "--", "km/L", AutoDeckColors.AccentCyan),
        SensorTile("Consumo Médio", snap.averageKmPerL?.let { "%.1f".format(it) } ?: "--", "km/L"),
        SensorTile("Vazão Comb.", snap.litersPerHour?.let { "%.1f".format(it) } ?: "--", "L/h"),
        SensorTile("Bateria", snap.batteryVoltageV?.let { "%.1f".format(it) } ?: "--", "V", AutoDeckColors.StatusAmber),
        SensorTile("Carga Motor", snap.engineLoadPct?.toInt()?.toString() ?: "--", "%"),
        SensorTile("Borboleta (TPS)", snap.throttlePct?.toInt()?.toString() ?: "--", "%"),
        SensorTile("Pressão MAP", snap.intakeMapKpa?.toInt()?.toString() ?: "--", "kPa"),
        SensorTile("Fluxo MAF", snap.mafGramsPerSec?.let { "%.1f".format(it) } ?: "--", "g/s"),
        SensorTile("Temp. Admissão", snap.intakeTempC?.toInt()?.toString() ?: "--", "°C"),
        SensorTile("Avanço Ignição", snap.timingAdvanceDeg?.let { "%.1f".format(it) } ?: "--", "°"),
        SensorTile("STFT Curto", snap.stftPct?.let { "%+.1f".format(it) } ?: "--", "%"),
        SensorTile("LTFT Longo", snap.ltftPct?.let { "%+.1f".format(it) } ?: "--", "%"),
        SensorTile("Nível Tanque", snap.fuelLevelPct?.toInt()?.toString() ?: "--", "%")
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scanner OBD-II Detalhado (${uiState.headUnit.obdStatus.labelPt})",
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${uiState.obdMessage} | Luz Injeção (MIL): ${if (snap.milOn) "ACESA (${snap.milDtcCount})" else "Apagada"}",
                        color = if (snap.milOn) AutoDeckColors.StatusAmber else AutoDeckColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Simulador", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                    Switch(checked = uiState.config.useSimulatedObd, onCheckedChange = onToggleSim)
                    OutlinedButton(onClick = onResetTrip) { Text("Zerar Média") }
                    if (uiState.headUnit.obdStatus == LinkStatus.CONNECTED) {
                        OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
                    } else {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan)
                        ) {
                            Text("Conectar", color = Color(0xFF03151B), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 142.dp),
                modifier = Modifier.weight(2.2f).fillMaxHeight(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tiles) { tile ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
                        border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(tile.title, color = AutoDeckColors.TextSecondary, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = tile.value,
                                    color = if (snap.isStale) AutoDeckColors.TextMuted else tile.accent,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(tile.unit, color = AutoDeckColors.TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
                border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Códigos de Falha (03 / 07 / 0A)",
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onReadDtc,
                            enabled = !uiState.dtcBusy && uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Ler Falhas")
                        }
                        OutlinedButton(
                            onClick = onRequestClearDtc,
                            enabled = !uiState.dtcBusy && uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Limpar")
                        }
                    }

                    uiState.dtcActionFeedback?.let { feedback ->
                        Text(feedback, color = AutoDeckColors.AccentCyan, fontSize = 12.sp)
                    }

                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (dtcs.isEmpty()) {
                            Text(
                                text = "Nenhuma falha DTC armazenada.",
                                color = AutoDeckColors.StatusGreen,
                                fontSize = 13.sp
                            )
                        } else {
                            dtcs.forEach { dtc ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceElevated),
                                    border = BorderStroke(1.dp, AutoDeckColors.StatusAmber.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "${dtc.code} [${dtc.category.labelPt}] — ${dtc.system.labelPt}",
                                            color = AutoDeckColors.StatusAmber,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = dtc.descriptionPt,
                                            color = AutoDeckColors.TextPrimary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAndInspectorPanel(
    uiState: HeadUnitUiState,
    cameraDebug: CameraDebugState,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: (String) -> Unit,
    onCalibrateNoSignal: () -> Boolean,
    onClearNoSignal: () -> Unit,
    onToggleSimObd: (Boolean) -> Unit,
    onToggleSimCam: (Boolean) -> Unit,
    onTestReverse: () -> Unit,
    onUpdateGuidelines: (GuidelineCalibration) -> Unit,
    onUpdateThresholds: (com.autodeck.protocol.obd2.AlertThresholds) -> Unit,
    onUpdateFuelCalibration: (Double) -> Unit,
    onToggleLauncherMode: (Boolean) -> Unit,
    onSetProjectionMode: (ProjectionMode) -> Unit,
    onReopenWizard: () -> Unit
) {
    val guide = uiState.config.guidelines
    val thresholds = uiState.config.alertThresholds
    var calMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Inspetor USB ao vivo
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Inspetor USB-C Hub ao Vivo",
                            color = AutoDeckColors.AccentCyan,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Identifica o chip exato (VID:PID) do seu conversor EasyCap/UVC e do dongle Carlinkit.",
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = onRefreshUsb) { Text("Reescanear Hub USB") }
                }

                if (uiState.cameraHardware.needsCameraPermission) {
                    Text(
                        text = "Atenção: permissão CAMERA pendente — o Android 10+ bloqueia dispositivos UVC sem ela.",
                        color = AutoDeckColors.StatusRed,
                        fontSize = 12.sp
                    )
                }

                if (uiState.usbDevices.isEmpty()) {
                    Text(
                        text = "Nenhum dispositivo USB detectado no tablet/hub no momento.",
                        color = AutoDeckColors.StatusAmber,
                        fontSize = 13.sp
                    )
                } else {
                    uiState.usbDevices.forEach { dev ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceElevated),
                            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${dev.productName ?: "Dispositivo USB"} [${dev.vidPidHex}]",
                                        color = AutoDeckColors.TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Função: ${dev.role.labelPt} | Interfaces: ${dev.interfaceSummary}",
                                        color = AutoDeckColors.TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    if (dev.role == UsbDeviceRole.CAMERA_EASYCAP_LEGACY) {
                                        Text(
                                            text = "Chip EasyCap proprietário detectado — na Fase 2 plugaremos o driver específico deste VID:PID.",
                                            color = AutoDeckColors.StatusAmber,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                if (!dev.hasPermission) {
                                    Button(onClick = { onRequestUsbPermission(dev.deviceName) }) {
                                        Text("Permitir USB")
                                    }
                                } else {
                                    Text("Permissão OK", color = AutoDeckColors.StatusGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Câmera de Ré & Calibração "Sem Sinal" + Simulador
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Câmera de Ré & Detecção Automática",
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                cameraDebug.analysis?.let { sig ->
                    Text(
                        text = "Análise ao vivo: ${sig.reason} | Média=${"%.1f".format(sig.meanLuma)} Desvio=${"%.1f".format(sig.stdDev)} Δt=${sig.temporalDiff?.let { "%.1f".format(it) } ?: "--"}",
                        color = AutoDeckColors.AccentCyan,
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Simulador Câmera", color = AutoDeckColors.TextPrimary)
                    Switch(checked = uiState.config.useSimulatedCamera, onCheckedChange = onToggleSimCam)
                    if (uiState.config.useSimulatedCamera) {
                        Button(onClick = onTestReverse) {
                            Text(if (uiState.simulatedReverseActive) "Desengatar Ré (Sim)" else "Simular Engate de Ré")
                        }
                    }
                    Button(
                        onClick = {
                            calMessage = if (onCalibrateNoSignal()) {
                                "Calibração 'Sem Sinal' gravada!"
                            } else {
                                "Sem frame válido para calibrar (ative o Simulador ou conecte vídeo)."
                            }
                        },
                        enabled = uiState.cameraHardware.hasValidFrameStream
                    ) { Text("Calibrar 'Sem Sinal'") }
                    OutlinedButton(onClick = onClearNoSignal) { Text("Limpar Calibração") }
                }
                calMessage?.let { Text(it, color = AutoDeckColors.StatusGreen, fontSize = 12.sp) }

                Text("Linhas Guia — Largura Topo (${"%.0f".format(guide.topWidthRatio * 100)}%)", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                Slider(
                    value = guide.topWidthRatio,
                    onValueChange = { onUpdateGuidelines(guide.copy(topWidthRatio = it)) },
                    valueRange = 0.20f..0.80f
                )
                Text("Linhas Guia — Alcance Superior (${"%.0f".format(guide.topYRatio * 100)}%)", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                Slider(
                    value = guide.topYRatio,
                    onValueChange = { onUpdateGuidelines(guide.copy(topYRatio = it)) },
                    valueRange = 0.25f..0.75f
                )
            }
        }

        // 3. Limites de Alerta e Fator de Consumo (M1 + I7)
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Alertas do Motor & Calibração de Consumo",
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Aviso Temperatura: ${thresholds.coolantWarningC.toInt()} °C | Crítico: ${thresholds.coolantCriticalC.toInt()} °C",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 12.sp
                )
                Slider(
                    value = thresholds.coolantWarningC.toFloat(),
                    onValueChange = { w ->
                        val crit = maxOf(thresholds.coolantCriticalC, w.toDouble() + 4.0)
                        onUpdateThresholds(thresholds.copy(coolantWarningC = w.toDouble(), coolantCriticalC = crit))
                    },
                    valueRange = 95f..115f
                )
                Text(
                    text = "Sobrecarga Alternador: ${"%.1f".format(thresholds.batteryHighV)} V | Fator Consumo: ${"%.2f".format(uiState.config.fuelCalibrationFactor)}x",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 12.sp
                )
                Slider(
                    value = uiState.config.fuelCalibrationFactor.toFloat(),
                    onValueChange = { onUpdateFuelCalibration(it.toDouble()) },
                    valueRange = 0.80f..1.25f
                )
            }
        }

        // 4. Bluetooth, Projeção, Launcher e Assistente
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Áudio Taranis, OBD-II, Projeção e Automação",
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Rota de áudio: ${uiState.audioRoute.routeLabel} | Discovery BT bloqueado: ${!uiState.headUnit.btDiscoveryAllowed}",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ativar AutoDeck como Launcher (Home do Tablet)", color = AutoDeckColors.TextPrimary)
                    Switch(checked = uiState.config.launcherModeEnabled, onCheckedChange = onToggleLauncherMode)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Simulador OBD-II", color = AutoDeckColors.TextPrimary)
                    Switch(checked = uiState.config.useSimulatedObd, onCheckedChange = onToggleSimObd)
                    OutlinedButton(onClick = { onSetProjectionMode(ProjectionMode.CARLINKIT_NATIVE) }) {
                        Text("Modo Carlinkit")
                    }
                    OutlinedButton(onClick = { onSetProjectionMode(ProjectionMode.HUR_FALLBACK) }) {
                        Text("Modo HUR")
                    }
                    Button(onClick = onReopenWizard) {
                        Text("Reabrir Assistente 1ª Config")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReverseCameraOverlay(
    uiState: HeadUnitUiState,
    onToggleMirror: () -> Unit,
    onToggleGuidelines: () -> Unit,
    onToggleSimulatedReverse: () -> Unit,
    onCloseManual: () -> Unit
) {
    val guide = uiState.config.guidelines
    val isManual = uiState.headUnit.cameraState == ReverseCameraState.MANUAL_PREVIEW

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06090F))
    ) {
        // C2: TextureView real respeitando espelhamento sem brigar com o SurfaceView do CarPlay por baixo
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> TextureView(ctx) },
            update = { tv ->
                tv.scaleX = if (guide.mirrorHorizontal) -1f else 1f
            }
        )

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(AutoDeckColors.StatusRed))
                Text(
                    text = if (isManual) "CÂMERA TRASEIRA (MANUAL)" else "MARCHA RÉ DETECTADA — PRIORIDADE MÁXIMA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
