package com.autodeck.headunit.ui.overlay

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.camera.ReverseCameraState
import com.autodeck.protocol.obd2.AlertSeverity
import com.autodeck.protocol.obd2.ObdAlert

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
fun ReverseCameraOverlay(
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
                        val surface = Surface(surfaceTexture!!)
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
fun CriticalAlertBanner(
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

@Composable
fun StandbyBlackOverlay(
    statusLabel: String,
    onWakeUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onWakeUp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = AutoDeckColors.AccentCyan.copy(alpha = 0.35f),
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = "HondaDeck em Standby ($statusLabel)",
                color = Color(0xFF475569),
                fontSize = 14.sp
            )
            Text(
                text = "Ligue a chave do carro (Taramps) ou toque na tela para acordar",
                color = Color(0xFF334155),
                fontSize = 12.sp
            )
        }
    }
}
