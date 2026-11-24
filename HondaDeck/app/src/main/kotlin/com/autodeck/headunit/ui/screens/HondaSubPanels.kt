package com.autodeck.headunit.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.hardware.CameraHardwareStatus
import com.autodeck.headunit.hardware.UsbDeviceRole
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.service.CameraDebugState
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.obd2.AlertThresholds
import com.autodeck.protocol.obd2.TransmissionType
import org.osmdroid.views.MapView
import kotlin.math.roundToInt

private data class SensorTile(
    val title: String,
    val value: String,
    val unit: String,
    val accent: Color = AutoDeckColors.TextPrimary
)

@Composable
fun NavigationFullPanel(
    uiState: HeadUnitUiState,
    onOpenPreferredMap: () -> Unit,
    onNavigateDestination: (String) -> Unit,
    onDownloadOfflineRegion: (MapView) -> Unit
) {
    var followGps by rememberSaveable { mutableStateOf(true) }
    var destinationText by remember { mutableStateOf("") }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mapa OSMDroid em destaque
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A1420))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(16.dp))
        ) {
            OsmDarkMapSurface(
                gpsState = uiState.gpsState,
                followGps = followGps,
                interactive = true,
                initialZoom = 16.0,
                onUserDragMap = { followGps = false },
                onMapViewReady = { mapViewRef = it },
                modifier = Modifier.fillMaxSize()
            )

            // Controles de Zoom + Status GPS no canto superior
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD9071019))
                    .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Navigation,
                    contentDescription = null,
                    tint = AutoDeckColors.AccentCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (uiState.gpsState.hasFix) {
                        "${uiState.gpsState.providerLabel} • ${"%.4f".format(uiState.gpsState.latitude)}, ${"%.4f".format(uiState.gpsState.longitude)}"
                    } else {
                        "Aguardando sinal GPS (${uiState.gpsState.providerLabel})..."
                    },
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 12.sp
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!followGps) {
                    Surface(
                        onClick = { followGps = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xE80A1624),
                        border = BorderStroke(1.dp, AutoDeckColors.AccentCyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MyLocation,
                                contentDescription = "Centralizar GPS",
                                tint = AutoDeckColors.AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Centralizar GPS",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Surface(
                    onClick = { mapViewRef?.controller?.zoomIn() },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = CircleShape,
                    color = Color(0xE00A1624),
                    border = BorderStroke(1.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Rounded.Add, "Zoom +", tint = Color.White, modifier = Modifier.padding(12.dp))
                }
                Surface(
                    onClick = { mapViewRef?.controller?.zoomOut() },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = CircleShape,
                    color = Color(0xE00A1624),
                    border = BorderStroke(1.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Rounded.Remove, "Zoom -", tint = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // Coluna de ações rápidas + Cache Offline
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Navegação & Mapa Offline",
                    color = AutoDeckColors.AccentCyan,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = destinationText,
                    onValueChange = { destinationText = it },
                    label = { Text("Destino (ex: Av. Paulista, Casa)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (destinationText.isNotBlank()) {
                            onNavigateDestination(destinationText)
                        } else {
                            onOpenPreferredMap()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Traçar Rota no ${uiState.config.preferredMapLabel}",
                        color = Color(0xFF03151B),
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onNavigateDestination("Posto de gasolina próximo") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Postos", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { onNavigateDestination("Oficina Honda próxima") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Oficina", fontSize = 12.sp) }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Uso Sem Internet (Cache SM-T500)",
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = uiState.gpsState.offlineDownloadStatus,
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { mapViewRef?.let(onDownloadOfflineRegion) },
                    enabled = !uiState.gpsState.offlineDownloading && mapViewRef != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Baixar Mapa Offline (8 km)")
                }
            }
        }
    }
}

@Composable
fun MusicCenterPanel(
    uiState: HeadUnitUiState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onLaunchMusicApp: () -> Unit,
    onSetVolumePct: (Int) -> Unit
) {
    val context = LocalContext.current
    val media = uiState.mediaState

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Player Expandido
        Card(
            modifier = Modifier.weight(1.4f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AutoDeckColors.SurfaceElevated)
                            .border(1.5.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = media.coverBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = media.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = AutoDeckColors.AccentCyan,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = media.appLabel.uppercase(),
                            color = AutoDeckColors.AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = media.title,
                            color = AutoDeckColors.TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = media.artist,
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (media.album.isNotBlank()) {
                            Text(
                                text = media.album,
                                color = AutoDeckColors.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        if (!media.hasActiveTrack) {
                            Text(
                                text = "Toque em Play ou abra o ${uiState.config.preferredMusicLabel}. No Spotify, ative 'Status de transmissão de dispositivo' nas Configurações para exibir faixa e capa.",
                                color = AutoDeckColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val dur = media.durationMs.coerceAtLeast(1L)
                    val frac = if (media.durationMs > 0L) (media.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    HondaMusicProgressBar(
                        progress = frac,
                        enabled = media.canSeek && media.durationMs > 0L,
                        onSeekFraction = { f ->
                            if (media.durationMs > 0L) onSeekTo((f * media.durationMs).toLong())
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDurationMs(media.positionMs), color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                        Text(formatDurationMs(media.durationMs), color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AutoDeckColors.AccentCyan)
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Tocar/Pausar",
                            tint = Color(0xFF03151B),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Próxima", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }

        // Coluna Lateral: Volume Taramps + Acesso Spotify
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Áudio & Saída Taramps", color = AutoDeckColors.AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Rota atual: ${uiState.audioRoute.routeLabel}",
                    color = if (uiState.audioRoute.linkStatus == LinkStatus.CONNECTED) AutoDeckColors.StatusGreen else AutoDeckColors.TextSecondary,
                    fontSize = 13.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = AutoDeckColors.AccentCyan)
                    Text("Volume: ${uiState.masterVolumePct}%", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = uiState.masterVolumePct.toFloat(),
                    onValueChange = { onSetVolumePct(it.roundToInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics { contentDescription = "Volume ${uiState.masterVolumePct}%" }
                )

                Button(
                    onClick = onLaunchMusicApp,
                    colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abrir ${uiState.config.preferredMusicLabel} (com Cápsula)", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                if (!media.hasNotificationAccess) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AutoDeckColors.AccentCyan.copy(alpha = 0.10f)),
                        border = BorderStroke(1.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.45f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Modo compatível com Play Protect: o HondaDeck recebe música e capa automaticamente pelo Spotify Broadcast. No Spotify, ative 'Status de transmissão de dispositivo'.",
                                color = AutoDeckColors.TextPrimary,
                                fontSize = 12.sp
                            )
                            OutlinedButton(onClick = onLaunchMusicApp) {
                                Text("Abrir ${uiState.config.preferredMusicLabel}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiAgentPanel(
    uiState: HeadUnitUiState,
    onTriggerVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onReadLatestWhatsApp: () -> Unit,
    onReplyWhatsApp: (WhatsAppNotificationItem, String) -> Boolean,
    onDismissWhatsApp: (String) -> Unit,
    onLaunchWhatsApp: () -> Unit,
    onToggleWakeWord: (Boolean) -> Unit
) {
    val agent = uiState.agentState
    var inputPrompt by remember { mutableStateOf("") }
    var replyTextByKey by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val pulseTransition = rememberInfiniteTransition(label = "OrbPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (agent.isListening || agent.isSpeaking) 1.15f else 1.03f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "OrbScale"
    )

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Coluna Esquerda: Orbe + Chat Gemini
        Card(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            if (agent.isListening) AutoDeckColors.StatusGreen else AutoDeckColors.AccentCyan,
                                            Color(0xFF0A2438)
                                        )
                                    )
                                )
                                .border(1.5.dp, AutoDeckColors.AccentCyan, CircleShape)
                                .clickable(onClick = onTriggerVoice),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Mic, "Falar", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Copiloto HondaDeck (${agent.engineBadge})", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = agent.partialTranscript.ifBlank { agent.statusText },
                                color = AutoDeckColors.AccentCyan,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = uiState.config.wakeWordEnabled,
                                    role = Role.Switch,
                                    onValueChange = onToggleWakeWord
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("\"${uiState.config.wakeWordPhrase}\"", color = AutoDeckColors.TextSecondary, fontSize = 11.sp)
                            Switch(checked = uiState.config.wakeWordEnabled, onCheckedChange = null)
                        }
                        if (agent.isListening || agent.isSpeaking) {
                            IconButton(onClick = onStopVoice) {
                                Icon(Icons.Rounded.Stop, "Parar", tint = AutoDeckColors.StatusRed)
                            }
                        }
                    }
                }

                // Chips rápidos
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Como está o motor?" to "Motor agora",
                        "Qual meu consumo e autonomia?" to "Consumo",
                        "Próxima música" to "Próx. música",
                        "Ler mensagens do WhatsApp" to "Ler WhatsApp"
                    ).forEach { (prompt, label) ->
                        FilterChip(
                            selected = false,
                            onClick = { onSendPrompt(prompt) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                // Histórico de mensagens
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(agent.messages, key = { it.id }) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.84f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (msg.isUser) AutoDeckColors.AccentBlue.copy(alpha = 0.25f)
                                        else AutoDeckColors.SurfaceElevated
                                    )
                                    .border(
                                        1.dp,
                                        if (msg.isUser) AutoDeckColors.AccentBlue.copy(alpha = 0.5f) else AutoDeckColors.BorderSubtle,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Text(msg.text, color = AutoDeckColors.TextPrimary, fontSize = 13.sp)
                                if (msg.actionBadge != null || (!msg.isUser && msg.isOfflineEngine)) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = buildString {
                                            if (msg.isOfflineEngine) append("Motor Local")
                                            if (msg.actionBadge != null) {
                                                if (isNotEmpty()) append(" • ")
                                                append("Ação: ${msg.actionBadge}")
                                            }
                                        },
                                        color = AutoDeckColors.AccentCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Input texto (quando parado)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputPrompt,
                        onValueChange = { inputPrompt = it },
                        placeholder = { Text("Pergunte ao Gemini ou peça uma ação...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (inputPrompt.isNotBlank()) {
                                onSendPrompt(inputPrompt)
                                inputPrompt = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Enviar", tint = Color(0xFF03151B))
                    }
                }
            }
        }

        // Coluna Direita: Central WhatsApp & Notificações
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Message, null, tint = AutoDeckColors.StatusGreen, modifier = Modifier.size(18.dp))
                        Text("WhatsApp & Mensagens", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    TextButton(onClick = onLaunchWhatsApp) { Text("Abrir App") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onReadLatestWhatsApp,
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusGreen),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Ler Última em Voz Alta", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.whatsAppMessages.isEmpty()) {
                        Text(
                            text = if (uiState.mediaState.hasNotificationAccess) {
                                "Nenhuma mensagem recebida ainda. Quando chegar WhatsApp no tablet, aparecerá aqui."
                            } else {
                                "Modo compatível com Play Protect: a captura automática de notificações fica desabilitada neste APK para evitar bloqueio na instalação. Use 'Abrir App' acima para abrir o WhatsApp."
                            },
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        uiState.whatsAppMessages.forEach { msg ->
                            val draftReply = replyTextByKey[msg.key].orEmpty()
                            Card(
                                colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceElevated),
                                border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${msg.sender} (${msg.appLabel})",
                                            color = AutoDeckColors.AccentCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                .clickable { onDismissWhatsApp(msg.key) },
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Text(
                                                text = "Dispensar",
                                                color = AutoDeckColors.TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Text(msg.message, color = AutoDeckColors.TextPrimary, fontSize = 13.sp)
                                    if (msg.canReply) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = draftReply,
                                                onValueChange = { replyTextByKey = replyTextByKey + (msg.key to it) },
                                                placeholder = { Text("Responder...", fontSize = 11.sp) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    if (draftReply.isNotBlank() && onReplyWhatsApp(msg, draftReply)) {
                                                        replyTextByKey = replyTextByKey - msg.key
                                                    }
                                                }
                                            ) { Text("Enviar", fontSize = 11.sp) }
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
}

@Composable
fun ObdScannerPanel(
    uiState: HeadUnitUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReadDtc: () -> Unit,
    onRequestClearDtc: () -> Unit,
    onResetTrip: () -> Unit,
    onMarkTankFull: () -> Unit,
    onAddFuelLiters: (Double) -> Unit,
    onToggleSim: (Boolean) -> Unit
) {
    val snap = uiState.headUnit.obdSnapshot
    val derived = snap.derived
    val dtcs = uiState.headUnit.dtcCodes

    val tiles = listOf(
        SensorTile("Rotação", snap.rpm?.toInt()?.toString() ?: "--", "RPM", AutoDeckColors.AccentCyan),
        SensorTile("Velocidade GPS/Pneu", (derived.correctedSpeedKmh ?: snap.speedKmh)?.toInt()?.toString() ?: "--", "km/h", AutoDeckColors.AccentCyan),
        SensorTile("Marcha Est.", derived.estimatedGearLabel, derived.shiftAdvice.labelPt, AutoDeckColors.StatusGreen),
        SensorTile("Temp. Motor", snap.coolantTempC?.toInt()?.toString() ?: "--", "°C", AutoDeckColors.StatusGreen),
        SensorTile("Bateria", snap.batteryVoltageV?.let { "%.1f".format(it) } ?: "--", "V", AutoDeckColors.StatusAmber),
        SensorTile("Uso do Motor", snap.engineLoadPct?.toInt()?.toString() ?: "--", "%", AutoDeckColors.AccentCyan),
        SensorTile("Consumo Inst.", snap.instantKmPerL?.let { "%.1f".format(it) } ?: "--", "km/L", AutoDeckColors.AccentCyan),
        SensorTile("Consumo Médio", snap.averageKmPerL?.let { "%.1f".format(it) } ?: "--", "km/L"),
        SensorTile("Vazão Comb.", snap.litersPerHour?.let { "%.1f".format(it) } ?: "--", "L/h"),
        SensorTile("Autonomia Est.", derived.estimatedRangeKm?.toInt()?.toString() ?: "--", "km", AutoDeckColors.StatusGreen),
        SensorTile("Borboleta (TPS)", snap.throttlePct?.toInt()?.toString() ?: "--", "%"),
        SensorTile("Pressão MAP", snap.intakeMapKpa?.toInt()?.toString() ?: "--", "kPa"),
        SensorTile("Temp. Admissão", snap.intakeTempC?.toInt()?.toString() ?: "--", "°C"),
        SensorTile("Avanço Ignição", snap.timingAdvanceDeg?.let { "%.1f".format(it) } ?: "--", "°"),
        SensorTile("STFT Curto", snap.stftPct?.let { "%+.1f".format(it) } ?: "--", "%"),
        SensorTile("LTFT Longo", snap.ltftPct?.let { "%+.1f".format(it) } ?: "--", "%")
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Telemetria Honda Civic & Scanner OBD-II (${uiState.headUnit.obdStatus.labelPt})",
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${uiState.obdMessage} | MIL: ${if (snap.milOn) "ACESA (${snap.milDtcCount})" else "Apagada"} | Viagem: ${"%.1f".format(derived.tripDistanceKm)} km",
                        color = if (snap.milOn) AutoDeckColors.StatusAmber else AutoDeckColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = uiState.config.useSimulatedObd,
                                role = Role.Switch,
                                onValueChange = onToggleSim
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Simulador", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                        Switch(checked = uiState.config.useSimulatedObd, onCheckedChange = null)
                    }
                    OutlinedButton(onClick = onResetTrip) { Text("Zerar Viagem", fontSize = 12.sp) }
                    if (uiState.headUnit.obdStatus == LinkStatus.CONNECTED) {
                        OutlinedButton(onClick = onDisconnect) { Text("Desconectar", fontSize = 12.sp) }
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
                        colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.90f)),
                        border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(11.dp)) {
                            Text(tile.title, color = AutoDeckColors.TextSecondary, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = tile.value,
                                    color = if (snap.isStale) AutoDeckColors.TextMuted else tile.accent,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(tile.unit, color = AutoDeckColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Tanque Virtual + Falhas DTC
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
                border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.LocalGasStation, null, tint = AutoDeckColors.AccentCyan, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Tanque Virtual: ${"%.1f".format(derived.virtualTankRemainingLiters)}L (${derived.virtualTankPercent.toInt()}%)",
                            color = AutoDeckColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onAddFuelLiters(10.0) }, modifier = Modifier.weight(1f)) {
                            Text("+10L", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onAddFuelLiters(20.0) }, modifier = Modifier.weight(1f)) {
                            Text("+20L", fontSize = 11.sp)
                        }
                        Button(onClick = onMarkTankFull, modifier = Modifier.weight(1f)) {
                            Text("Cheio", fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = "Códigos de Falha (03 / 07 / 0A)",
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onReadDtc,
                            enabled = !uiState.dtcBusy && uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                            modifier = Modifier.weight(1f)
                        ) { Text("Ler Falhas", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = onRequestClearDtc,
                            enabled = !uiState.dtcBusy && uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                            modifier = Modifier.weight(1f)
                        ) { Text("Limpar", fontSize = 12.sp) }
                    }

                    uiState.dtcActionFeedback?.let { feedback ->
                        Text(feedback, color = AutoDeckColors.AccentCyan, fontSize = 12.sp)
                    }

                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (dtcs.isEmpty()) {
                            Text("Nenhuma falha DTC armazenada.", color = AutoDeckColors.StatusGreen, fontSize = 13.sp)
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
                                            fontSize = 12.sp
                                        )
                                        Text(dtc.descriptionPt, color = AutoDeckColors.TextPrimary, fontSize = 12.sp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HondaSettingsPanel(
    uiState: HeadUnitUiState,
    cameraDebug: CameraDebugState,
    onRefreshDevices: () -> Unit,
    onRequestUsbPermission: (String) -> Unit,
    onUpdateConfig: ((HeadUnitConfig) -> HeadUnitConfig) -> Unit,
    onToggleLauncherMode: (Boolean) -> Unit,
    onEnterStandbyNow: () -> Unit,
    onSelectCameraChannel: (Int) -> Unit,
    onSetCameraStandardNtsc: (Boolean) -> Unit,
    onCalibrateNoSignal: () -> Boolean,
    onClearNoSignal: () -> Unit,
    onTestReverse: () -> Unit,
    onReopenWizard: () -> Unit,
    onTestManualCamera: () -> Unit = onTestReverse
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val canDrawOverlays = remember(resumeTick, context) { Settings.canDrawOverlays(context) }

    val cfg = uiState.config
    var calibrationMsg by remember { mutableStateOf<String?>(null) }
    var showApiKey by remember { mutableStateOf(false) }
    var apiKeyDraft by remember(cfg.geminiApiKey) { mutableStateOf(cfg.geminiApiKey) }
    var wakeWordDraft by remember(cfg.wakeWordPhrase) { mutableStateOf(cfg.wakeWordPhrase) }
    val apiKeyDirty = apiKeyDraft.trim() != cfg.geminiApiKey
    val wakeWordDirty = wakeWordDraft.trim() != cfg.wakeWordPhrase

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Aplicativos Padrão & Cápsula Flutuante
        SettingsSectionCard(title = "1. Aplicativos Padrão & Cápsula Flutuante") {
            Text("App padrão de Música (card MÚSICA da Home):", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.installedMusicApps.forEach { app ->
                    FilterChip(
                        selected = cfg.preferredMusicPackage == app.packageName,
                        onClick = {
                            onUpdateConfig {
                                it.copy(preferredMusicPackage = app.packageName, preferredMusicLabel = app.label)
                            }
                        },
                        label = { Text(app.label) }
                    )
                }
            }

            Text("App padrão de Mapa (card NAVEGAÇÃO da Home):", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.installedMapApps.forEach { app ->
                    FilterChip(
                        selected = cfg.preferredMapPackage == app.packageName,
                        onClick = {
                            onUpdateConfig {
                                it.copy(preferredMapPackage = app.packageName, preferredMapLabel = app.label)
                            }
                        },
                        label = { Text(app.label) }
                    )
                }
            }

            SettingsSwitchRow(
                title = "Mostrar Mini-Cápsula Flutuante Honda sobre Maps e Spotify",
                subtitle = if (canDrawOverlays) {
                    "Permissão de sobreposição concedida (mostra km/h, marcha, Gemini e voltar)"
                } else {
                    "Permissão pendente — toque no botão abaixo para liberar"
                },
                checked = cfg.showFloatingHudOnExternalApps,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(showFloatingHudOnExternalApps = enabled) } }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Dica Spotify: ative 'Status de transmissão de dispositivo' nas configs do Spotify para exibir faixa/tempo sem permissões bloqueadas pelo Play Protect.",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (!canDrawOverlays) {
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                            runCatching { context.startActivity(intent) }
                        }
                    ) { Text("Liberar Cápsula Flutuante") }
                }
            }
        }

        // 2. Gatilho de Ignição Bluetooth (Taramps) & Standby
        SettingsSectionCard(title = "2. Gatilho de Ignição Bluetooth (Taramps) & Energia") {
            SettingsSwitchRow(
                title = "Ligar/Apagar tela automaticamente pelo Bluetooth do carro",
                subtitle = "Quando o Taramps conecta (1ª fase da chave), acende o painel; quando desliga, entra em Standby.",
                checked = cfg.ignitionTriggerEnabled,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(ignitionTriggerEnabled = enabled) } }
            )

            SettingsSwitchRow(
                title = "Preparar/aquecer ${cfg.preferredMusicLabel} ao ligar a chave",
                subtitle = "Deixa o player pronto automaticamente quando o Taramps conectar",
                checked = cfg.autoWarmupMusicOnIgnition,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(autoWarmupMusicOnIgnition = enabled) } }
            )

            SettingsSwitchRow(
                title = "Dar Play automático na música ao ligar a chave",
                checked = cfg.autoPlayMusicOnIgnition,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(autoPlayMusicOnIgnition = enabled) } }
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Escolha o dispositivo Bluetooth que aciona o painel:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                OutlinedButton(onClick = onRefreshDevices) { Text("Atualizar Pareados", fontSize = 11.sp) }
            }

            if (uiState.pairedBluetoothDevices.isEmpty()) {
                Text("Nenhum dispositivo Bluetooth pareado encontrado.", color = AutoDeckColors.StatusAmber, fontSize = 12.sp)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.pairedBluetoothDevices.take(8).forEach { dev ->
                        val isMatch = cfg.ignitionBtMac.equals(dev.macAddress, ignoreCase = true) ||
                            (cfg.ignitionBtMac.isBlank() && dev.name.contains(cfg.ignitionBtName, ignoreCase = true))
                        FilterChip(
                            selected = isMatch,
                            onClick = {
                                onUpdateConfig { it.copy(ignitionBtMac = dev.macAddress, ignitionBtName = dev.name) }
                            },
                            leadingIcon = { Icon(Icons.Rounded.Bluetooth, null, modifier = Modifier.size(16.dp)) },
                            label = { Text(dev.name) }
                        )
                    }
                }
            }

            Text("Atraso para entrar em Standby ao desligar a chave:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(5, 8, 15, 30).forEach { sec ->
                    FilterChip(
                        selected = cfg.ignitionOffStandbyDelaySec == sec,
                        onClick = { onUpdateConfig { it.copy(ignitionOffStandbyDelaySec = sec) } },
                        label = { Text("${sec}s") }
                    )
                }
                OutlinedButton(onClick = onEnterStandbyNow) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Testar Standby")
                }
            }
        }

        // 3. Agente Gemini & Assistente de Voz
        SettingsSectionCard(title = "3. Agente Gemini, Voz & WhatsApp") {
            Text("Modo do Assistente:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VoiceAssistantMode.entries.forEach { mode ->
                    FilterChip(
                        selected = cfg.voiceAssistantMode == mode,
                        onClick = { onUpdateConfig { it.copy(voiceAssistantMode = mode) } },
                        label = { Text(mode.labelPt.substringBefore(" (")) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("Google Gemini API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { showApiKey = !showApiKey }) {
                    Text(if (showApiKey) "Ocultar" else "Mostrar")
                }
                Button(
                    onClick = { onUpdateConfig { it.copy(geminiApiKey = apiKeyDraft.trim()) } },
                    enabled = apiKeyDirty
                ) {
                    Text(if (apiKeyDirty) "Salvar" else "Salvo")
                }
            }

            Text("Modelo Gemini:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash").forEach { m ->
                    FilterChip(
                        selected = cfg.geminiModel == m,
                        onClick = { onUpdateConfig { it.copy(geminiModel = m) } },
                        label = { Text(m) }
                    )
                }
            }

            SettingsSwitchRow(
                title = "Ativar frase de chamada por voz (Wake Word)",
                subtitle = "Escuta continuamente a frase configurada enquanto o painel está aberto",
                checked = cfg.wakeWordEnabled,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(wakeWordEnabled = enabled) } }
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = wakeWordDraft,
                    onValueChange = { wakeWordDraft = it },
                    label = { Text("Frase de ativação (ex: ok honda, ei civic)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val cleaned = wakeWordDraft.trim().ifBlank { "ok honda" }
                        wakeWordDraft = cleaned
                        onUpdateConfig { it.copy(wakeWordPhrase = cleaned) }
                    },
                    enabled = wakeWordDirty
                ) {
                    Text(if (wakeWordDirty) "Salvar" else "Salvo")
                }
            }

            SettingsSwitchRow(
                title = "Conversa contínua após resposta do Gemini",
                checked = cfg.continuousConversationEnabled,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(continuousConversationEnabled = enabled) } }
            )
            SettingsSwitchRow(
                title = "Alertas de WhatsApp no topo com leitura/resposta por voz",
                checked = cfg.whatsappVoiceAlertsEnabled,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(whatsappVoiceAlertsEnabled = enabled) } }
            )
        }

        // 4. OBD-II Turbo & Painel Honda Civic
        SettingsSectionCard(title = "4. OBD-II Turbo & Painel Honda Civic") {
            SettingsSwitchRow(
                title = "Modo HOME_TURBO (Velocidade e RPM em alta frequência)",
                checked = cfg.obdFastTurboMode,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(obdFastTurboMode = enabled) } }
            )
            SettingsSwitchRow(
                title = "Modo Simulador OBD-II (Bancada sem carro)",
                checked = cfg.useSimulatedObd,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(useSimulatedObd = enabled) } }
            )

            Text("Escala máxima do velocímetro na Home:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(160, 180, 200, 220).forEach { maxKmh ->
                    FilterChip(
                        selected = cfg.speedometerMaxKmh == maxKmh,
                        onClick = { onUpdateConfig { it.copy(speedometerMaxKmh = maxKmh) } },
                        label = { Text("$maxKmh km/h") }
                    )
                }
            }

            Text("Câmbio do Civic (cálculo de marchas):", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransmissionType.entries.forEach { t ->
                    FilterChip(
                        selected = cfg.transmissionType == t,
                        onClick = { onUpdateConfig { it.copy(transmissionType = t) } },
                        label = { Text(t.labelPt) }
                    )
                }
            }

            Text("Adaptador Bluetooth ELM327:", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.pairedBluetoothDevices.take(8).forEach { dev ->
                    FilterChip(
                        selected = cfg.obdDeviceMac == dev.macAddress,
                        onClick = {
                            onUpdateConfig { it.copy(obdDeviceMac = dev.macAddress, obdDeviceName = dev.name) }
                        },
                        label = { Text(dev.name) }
                    )
                }
            }
        }

        // 5. Câmera de Ré (USB) & Sistema
        SettingsSectionCard(title = "5. Câmera de Ré EasyCap USB & Sistema") {
            Text(
                text = buildString {
                    append("GPS: ${if (uiState.gpsState.hasFix) "FIX (${uiState.gpsState.providerLabel})" else uiState.gpsState.providerLabel}")
                    append("  •  Câmera: ${uiState.cameraHardware.connectedDeviceLabel}${if (uiState.cameraHardware.isStreamingVideo) " (Ativa)" else ""}")
                    append("  •  Spotify: ${if (uiState.mediaState.hasActiveTrack) "Ativa (${uiState.mediaState.title})" else "Aguardando broadcast"}")
                },
                color = AutoDeckColors.AccentCyan,
                fontSize = 11.sp
            )

            SettingsSwitchRow(
                title = "Simulador de Câmera (Bancada)",
                checked = cfg.useSimulatedCamera,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(useSimulatedCamera = enabled) } }
            )
            SettingsSwitchRow(
                title = "HondaDeck como Tela Inicial (Launcher HOME do tablet)",
                checked = cfg.launcherModeEnabled,
                onCheckedChange = onToggleLauncherMode
            )
            SettingsSwitchRow(
                title = "Sobrepor imagem de referência na Home (40%)",
                subtitle = "Ajuda a conferir o alinhamento visual da Home com a imagem-alvo",
                checked = cfg.debugReferenceOverlay,
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(debugReferenceOverlay = enabled) } }
            )

            CameraInputControlRow(
                selectedChannel = cfg.cameraInputChannel,
                isNtsc = cfg.cameraIsNtsc,
                statusRegHex = uiState.cameraHardware.lastStatusRegHex,
                showDiagnostic = true,
                onSelectCameraChannel = onSelectCameraChannel,
                onSetCameraStandardNtsc = onSetCameraStandardNtsc
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    val ok = onCalibrateNoSignal()
                    calibrationMsg = if (ok) "Frame 'Sem Sinal' calibrado!" else "Conecte a câmera ou ative o simulador antes."
                }) { Text("Calibrar 'Sem Sinal'") }
                OutlinedButton(onClick = onClearNoSignal) { Text("Limpar Calibração") }
                OutlinedButton(onClick = onTestManualCamera) { Text("Testar Câmera Traseira") }
                OutlinedButton(onClick = onTestReverse) { Text("Testar Ré (Sim)") }
                OutlinedButton(onClick = onReopenWizard) { Text("Reabrir Assistente") }
            }

            calibrationMsg?.let { Text(it, color = AutoDeckColors.AccentCyan, fontSize = 12.sp) }

            uiState.usbDevices.forEach { dev ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "• ${dev.productName ?: "USB"} [${dev.vidPidHex}] (${dev.role.labelPt})",
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 12.sp
                    )
                    if (!dev.hasPermission && dev.role != UsbDeviceRole.OTHER) {
                        OutlinedButton(onClick = { onRequestUsbPermission(dev.deviceName) }) {
                            Text("Autorizar USB", fontSize = 11.sp)
                        }
                    }
                }
            }

            cameraDebug.analysis?.let {
                Text(
                    text = "Sinal Câmera: ${it.reason} (luma=${it.meanLuma.toInt()}, std=${"%.1f".format(it.stdDev)})",
                    color = AutoDeckColors.TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = AutoDeckColors.AccentCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = AutoDeckColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = AutoDeckColors.TextSecondary, fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
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
