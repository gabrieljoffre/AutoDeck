package com.autodeck.headunit.ui.screens

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.navigation.GpsLocationState
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.TransmissionType
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fundo texturizado escuro Honda com moldura chanfrada superior e inferior em Ciano Neon.
 */
@Composable
fun HondaOuterFrameBackground(
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0A1B2E),
                        Color(0xFF050B14),
                        Color(0xFF03060B)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 1f || size.height <= 1f) return@Canvas
            val w = size.width
            val h = size.height

            // Linha técnica superior com recorte central do emblema HONDA
            val topY = h * 0.092f
            val topDeepY = h * 0.115f
            val topPath = Path().apply {
                moveTo(w * 0.02f, topY)
                lineTo(w * 0.25f, topY)
                lineTo(w * 0.285f, topDeepY)
                lineTo(w * 0.715f, topDeepY)
                lineTo(w * 0.75f, topY)
                lineTo(w * 0.98f, topY)
            }
            drawPath(
                path = topPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.22f),
                style = Stroke(width = 9f, cap = StrokeCap.Round)
            )
            drawPath(
                path = topPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.88f),
                style = Stroke(width = 2.4f, cap = StrokeCap.Round)
            )

            // Linha técnica inferior trapezoidal envolvendo o dock
            val botBaseY = h * 0.915f
            val botTopY = h * 0.868f
            val botPath = Path().apply {
                moveTo(w * 0.02f, botBaseY)
                lineTo(w * 0.34f, botBaseY)
                lineTo(w * 0.375f, botTopY)
                lineTo(w * 0.625f, botTopY)
                lineTo(w * 0.66f, botBaseY)
                lineTo(w * 0.98f, botBaseY)
            }
            drawPath(
                path = botPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.22f),
                style = Stroke(width = 10f, cap = StrokeCap.Round)
            )
            drawPath(
                path = botPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.92f),
                style = Stroke(width = 2.6f, cap = StrokeCap.Round)
            )
        }

        content()
    }
}

@Composable
fun HondaTopHeader(
    uiState: HeadUnitUiState,
    onTriggerVoice: () -> Unit,
    onToggleCamera: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val timeStr = remember(nowMs / 60_000L) {
        SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(nowMs))
    }
    val dateStr = remember(nowMs / 60_000L) {
        SimpleDateFormat("EEE, dd MMM", Locale("pt", "BR"))
            .format(Date(nowMs))
            .replace(".", "")
            .uppercase(Locale("pt", "BR"))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        // Esquerda: Relógio + Data em ciano neon
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = timeStr,
                style = TextStyle(
                    color = Color(0xFF4FF5FF),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                    shadow = Shadow(
                        color = AutoDeckColors.AccentCyan.copy(alpha = 0.65f),
                        blurRadius = 16f
                    )
                )
            )
            Text(
                text = dateStr,
                color = AutoDeckColors.HondaCyanSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp
            )
        }

        // Centro: Emblema H + HONDA com glow
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HondaEmblemLogo(modifier = Modifier.size(40.dp))
            Text(
                text = "HONDA",
                style = TextStyle(
                    color = Color(0xFFE0FCFF),
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 4.sp,
                    shadow = Shadow(
                        color = AutoDeckColors.AccentCyan.copy(alpha = 0.85f),
                        blurRadius = 20f
                    )
                )
            )
        }

        // Direita: Chips de status + botão rápido Gemini + Câmera de ré
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderStatusChip(
                label = "TARAMPS",
                active = uiState.audioRoute.linkStatus == LinkStatus.CONNECTED,
                activeColor = AutoDeckColors.StatusGreen,
                onClick = onOpenSettings
            )
            val obdHz = if (uiState.obdCycleHz > 0.5) " ${uiState.obdCycleHz.toInt()}Hz" else ""
            HeaderStatusChip(
                label = "OBD$obdHz",
                active = uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                activeColor = AutoDeckColors.AccentCyan,
                onClick = onOpenSettings
            )
            HeaderStatusChip(
                label = if (uiState.gpsState.hasFix) "GPS" else "GPS...",
                active = uiState.gpsState.hasFix,
                activeColor = AutoDeckColors.AccentCyan,
                onClick = onOpenSettings
            )

            Surface(
                onClick = onTriggerVoice,
                shape = RoundedCornerShape(20.dp),
                color = if (uiState.agentState.isListening) {
                    AutoDeckColors.AccentCyan.copy(alpha = 0.25f)
                } else {
                    AutoDeckColors.SurfaceCard.copy(alpha = 0.9f)
                },
                border = BorderStroke(
                    1.dp,
                    if (uiState.agentState.isListening) AutoDeckColors.AccentCyan else AutoDeckColors.BorderSubtle
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Assistente Gemini",
                        tint = if (uiState.agentState.isListening) AutoDeckColors.StatusGreen else AutoDeckColors.AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (uiState.agentState.isListening) "Ouvindo" else uiState.config.wakeWordPhrase,
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Surface(
                onClick = onToggleCamera,
                shape = RoundedCornerShape(20.dp),
                color = AutoDeckColors.SurfaceCard.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = "Câmera Traseira",
                    tint = AutoDeckColors.AccentCyan,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HondaEmblemLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.6f
        val rectPath = Path().apply {
            moveTo(w * 0.14f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.14f)
            quadraticTo(w * 0.94f, h * 0.52f, w * 0.84f, h * 0.86f)
            lineTo(w * 0.16f, h * 0.86f)
            quadraticTo(w * 0.06f, h * 0.52f, w * 0.14f, h * 0.14f)
            close()
        }
        drawPath(
            path = rectPath,
            color = AutoDeckColors.AccentCyan.copy(alpha = 0.25f),
            style = Stroke(width = 7f)
        )
        drawPath(
            path = rectPath,
            color = Color(0xFF70F5FF),
            style = Stroke(width = stroke)
        )

        val hPath = Path().apply {
            // Perna esquerda
            moveTo(w * 0.28f, h * 0.22f)
            lineTo(w * 0.38f, h * 0.22f)
            lineTo(w * 0.40f, h * 0.50f)
            lineTo(w * 0.60f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.22f)
            lineTo(w * 0.72f, h * 0.22f)
            lineTo(w * 0.68f, h * 0.78f)
            lineTo(w * 0.58f, h * 0.78f)
            lineTo(w * 0.58f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.32f, h * 0.78f)
            close()
        }
        drawPath(path = hPath, color = Color(0xFFA2FAFF))
    }
}

@Composable
private fun HeaderStatusChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AutoDeckColors.SurfaceCard.copy(alpha = 0.85f))
            .border(1.dp, AutoDeckColors.BorderSubtle.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else AutoDeckColors.TextMuted)
        )
        Text(
            text = label,
            color = if (active) AutoDeckColors.TextPrimary else AutoDeckColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun HondaBottomDock(
    activeTab: MainTab,
    unreadMessages: Int,
    onSelectTab: (MainTab) -> Unit
) {
    val items = listOf(
        Triple(MainTab.HOME, Icons.Rounded.Home, "Início"),
        Triple(MainTab.NAVIGATION, Icons.Rounded.Map, "Mapa"),
        Triple(MainTab.MUSIC, Icons.Rounded.MusicNote, "Música"),
        Triple(MainTab.AGENT_VOICE, Icons.Rounded.Mic, "Gemini"),
        Triple(MainTab.OBD_SCANNER, Icons.Rounded.DirectionsCar, "OBD2"),
        Triple(MainTab.SETTINGS, Icons.Rounded.Settings, "Config")
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = activeTab == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) AutoDeckColors.AccentCyan.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.TextSecondary,
                        modifier = Modifier.size(25.dp)
                    )
                    if (tab == MainTab.AGENT_VOICE && unreadMessages > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(AutoDeckColors.StatusGreen)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card de Navegação (esquerda) com Mini-Mapa Dark Navy em tempo real via OSMDroid + GPS do SM-T500.
 */
@Composable
fun OsmDarkMiniMapCard(
    gpsState: GpsLocationState,
    preferredMapLabel: String,
    onExpandMap: () -> Unit,
    onLaunchPreferredMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cabeçalho estilo cápsula "NAVEGAÇÃO >"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AutoDeckColors.HondaCardHeader.copy(alpha = 0.92f))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(onClick = onExpandMap)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NAVEGAÇÃO",
                color = AutoDeckColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Expandir Mapa",
                tint = AutoDeckColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        // Caixa do Mini-Mapa
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0A1420))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(14.dp))
        ) {
            OsmDarkMapSurface(
                gpsState = gpsState,
                zoomLevel = 16.2,
                interactive = false,
                modifier = Modifier.fillMaxSize()
            )

            // Rota neon e cursor seta azul no centro
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 1f || size.height <= 1f) return@Canvas
                val cx = size.width * 0.5f
                val cy = size.height * 0.58f

                val routePath = Path().apply {
                    moveTo(cx, size.height * 0.94f)
                    lineTo(cx, cy)
                    cubicTo(
                        cx, cy - size.height * 0.14f,
                        cx + size.width * 0.06f, cy - size.height * 0.25f,
                        cx + size.width * 0.08f, size.height * 0.04f
                    )
                }
                drawPath(
                    path = routePath,
                    color = AutoDeckColors.AccentCyan.copy(alpha = 0.28f),
                    style = Stroke(width = 16f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = routePath,
                    color = AutoDeckColors.AccentCyan.copy(alpha = 0.85f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                // Halo do cursor
                drawCircle(
                    color = AutoDeckColors.AccentBlue.copy(alpha = 0.28f),
                    radius = 30f,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = AutoDeckColors.AccentCyan.copy(alpha = 0.8f),
                    radius = 30f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.2f)
                )

                // Seta direcional azul
                val arrow = Path().apply {
                    moveTo(cx, cy - 17f)
                    lineTo(cx + 13f, cy + 13f)
                    lineTo(cx, cy + 6f)
                    lineTo(cx - 13f, cy + 13f)
                    close()
                }
                drawPath(path = arrow, color = AutoDeckColors.AccentCyan)
            }

            // Rodapé clicável para abrir Google Maps / Waze
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xEE071019))
                        )
                    )
                    .clickable(onClick = onLaunchPreferredMap)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Navigation,
                    contentDescription = null,
                    tint = AutoDeckColors.AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = preferredMapLabel,
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Superfície MapView do OSMDroid com filtro Dark Navy Automotive + suporte offline em cache.
 */
@Composable
fun OsmDarkMapSurface(
    gpsState: GpsLocationState,
    zoomLevel: Double,
    interactive: Boolean,
    onMapViewReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val darkMatrixFilter = remember {
        val invert = ColorMatrix(
            floatArrayOf(
                -0.88f, 0f, 0f, 0f, 232f,
                0f, -0.86f, 0f, 0f, 238f,
                0f, 0f, -0.75f, 0f, 248f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val tint = ColorMatrix(
            floatArrayOf(
                0.70f, 0f, 0f, 0f, 6f,
                0f, 0.88f, 0f, 0f, 14f,
                0f, 0f, 1.15f, 0f, 28f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        invert.postConcat(tint)
        ColorMatrixColorFilter(invert)
    }

    var mapRef: MapView? = remember { null }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { mapRef?.onDetach() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            runCatching {
                org.osmdroid.config.Configuration.getInstance().userAgentValue = ctx.packageName
            }
            MapView(ctx).apply {
                runCatching {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(interactive)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isClickable = interactive
                    isFocusable = interactive
                    overlayManager.tilesOverlay.setColorFilter(darkMatrixFilter)
                    controller.setZoom(zoomLevel)
                    controller.setCenter(GeoPoint(gpsState.latitude, gpsState.longitude))
                }
                mapRef = this
                onMapViewReady(this)
            }
        },
        update = { map ->
            runCatching {
                map.setMultiTouchControls(interactive)
                map.isClickable = interactive
                map.overlayManager.tilesOverlay.setColorFilter(darkMatrixFilter)
                map.controller.setZoom(zoomLevel)
                map.controller.animateTo(GeoPoint(gpsState.latitude, gpsState.longitude))
                map.mapOrientation = if (gpsState.hasFix) -gpsState.bearingDeg else 0f
            }
        }
    )
}

/**
 * Cockpit Central Honda: Velocímetro circular 60 FPS + Arco RPM + Marchas + Combustível.
 */
@Composable
fun HondaCenterGaugeCluster(
    speedKmh: Float,
    rpm: Float,
    maxSpeedKmh: Int,
    transmission: TransmissionType,
    estimatedGear: Int?,
    isReverseActive: Boolean,
    fuelPercent: Float,
    estimatedRangeKm: Double?,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, maxSpeedKmh.toFloat()),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HondaSpeedAnim"
    )
    val animatedRpm by animateFloatAsState(
        targetValue = rpm.coerceIn(0f, 7500f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HondaRpmAnim"
    )
    val animatedFuel by animateFloatAsState(
        targetValue = (fuelPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "HondaFuelAnim"
    )

    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Velocímetro + Arco RPM + Marchas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (size.width <= 1f || size.height <= 1f) return@Canvas
                    val diameter = (minOf(size.width, size.height) * 0.94f).coerceAtLeast(180f)
                    val radius = diameter / 2f
                    val center = Offset(size.width / 2f, size.height * 0.50f)
                    val arcTopLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(diameter, diameter)

                    val startAngle = 135f
                    val sweepTotal = 270f

                    // Glow radial suave de fundo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AutoDeckColors.AccentCyan.copy(alpha = 0.09f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 1.05f
                        ),
                        radius = radius * 1.05f,
                        center = center
                    )

                    // Trilho externo tricolor (Verde -> Amarelo -> Vermelho)
                    val greenSweep = sweepTotal * 0.40f
                    val yellowSweep = sweepTotal * 0.32f
                    val redSweep = sweepTotal * 0.28f

                    // Glow de fundo do arco
                    drawArc(
                        color = AutoDeckColors.StatusGreen.copy(alpha = 0.22f),
                        startAngle = startAngle,
                        sweepAngle = greenSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 18f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = AutoDeckColors.StatusAmber.copy(alpha = 0.22f),
                        startAngle = startAngle + greenSweep,
                        sweepAngle = yellowSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 18f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = AutoDeckColors.StatusRed.copy(alpha = 0.22f),
                        startAngle = startAngle + greenSweep + yellowSweep,
                        sweepAngle = redSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 18f, cap = StrokeCap.Round)
                    )

                    // Arco colorido principal
                    drawArc(
                        color = AutoDeckColors.StatusGreen,
                        startAngle = startAngle,
                        sweepAngle = greenSweep - 3f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = AutoDeckColors.StatusAmber,
                        startAngle = startAngle + greenSweep + 1f,
                        sweepAngle = yellowSweep - 3f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = AutoDeckColors.StatusRed,
                        startAngle = startAngle + greenSweep + yellowSweep + 1f,
                        sweepAngle = redSweep - 2f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )

                    // Halo de brilho na velocidade atual
                    val speedFrac = (animatedSpeed / maxSpeedKmh.toFloat()).coerceIn(0f, 1f)
                    val speedSweep = sweepTotal * speedFrac
                    if (speedSweep > 2f) {
                        val tipColor = when {
                            speedFrac < 0.40f -> AutoDeckColors.StatusGreen
                            speedFrac < 0.72f -> AutoDeckColors.StatusAmber
                            else -> AutoDeckColors.StatusRed
                        }
                        val tipRad = ((startAngle + speedSweep) * PI / 180.0)
                        val tipPt = Offset(
                            x = center.x + (radius * cos(tipRad)).toFloat(),
                            y = center.y + (radius * sin(tipRad)).toFloat()
                        )
                        drawCircle(
                            color = tipColor.copy(alpha = 0.55f),
                            radius = 12f,
                            center = tipPt
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 4.5f,
                            center = tipPt
                        )
                    }

                    // Ticks e números da escala (0 .. maxSpeedKmh)
                    val step = 20
                    val numSteps = maxSpeedKmh / step
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 210, 232, 245)
                        textSize = (radius * 0.135f).coerceIn(18f, 30f)
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    for (i in 0..(numSteps * 2)) {
                        val frac = i / (numSteps * 2f)
                        val deg = startAngle + sweepTotal * frac
                        val rad = deg * PI / 180.0
                        val isMajor = i % 2 == 0
                        val rOuter = radius * 0.90f
                        val rInner = if (isMajor) radius * 0.81f else radius * 0.855f
                        drawLine(
                            color = Color.White.copy(alpha = if (isMajor) 0.80f else 0.42f),
                            start = Offset(
                                center.x + (rInner * cos(rad)).toFloat(),
                                center.y + (rInner * sin(rad)).toFloat()
                            ),
                            end = Offset(
                                center.x + (rOuter * cos(rad)).toFloat(),
                                center.y + (rOuter * sin(rad)).toFloat()
                            ),
                            strokeWidth = if (isMajor) 2.6f else 1.4f,
                            cap = StrokeCap.Round
                        )
                        if (isMajor) {
                            val labelVal = (i / 2) * step
                            val rLabel = radius * 0.69f
                            val lx = center.x + (rLabel * cos(rad)).toFloat()
                            val ly = center.y + (rLabel * sin(rad)).toFloat() + textPaint.textSize * 0.35f
                            drawContext.canvas.nativeCanvas.drawText(
                                labelVal.toString(),
                                lx,
                                ly,
                                textPaint
                            )
                        }
                    }

                    // Arco interno azul neon (RPM)
                    val innerR = radius * 0.48f
                    val innerTopLeft = Offset(center.x - innerR, center.y - innerR)
                    val innerSize = Size(innerR * 2f, innerR * 2f)
                    drawArc(
                        color = Color(0xFF0E2942),
                        startAngle = 140f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 11f, cap = StrokeCap.Round)
                    )
                    val rpmFrac = (animatedRpm / 7000f).coerceIn(0.03f, 1f)
                    val rpmSweep = 260f * rpmFrac
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                AutoDeckColors.AccentBlue,
                                AutoDeckColors.AccentCyan
                            ),
                            center = center
                        ),
                        startAngle = 140f,
                        sweepAngle = rpmSweep,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 11f, cap = StrokeCap.Round)
                    )
                }

                // Número central km/h
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Text(
                        text = animatedSpeed.toInt().toString(),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = Shadow(
                                color = AutoDeckColors.AccentCyan.copy(alpha = 0.55f),
                                blurRadius = 14f
                            )
                        )
                    )
                    Text(
                        text = "km/h",
                        color = AutoDeckColors.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Indicador MARCHA posicionado na abertura inferior do arco
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "MARCHA",
                        color = AutoDeckColors.HondaCyanSoft,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val gears = (1..transmission.forwardGears).map { it.toString() } + "R"
                    val activeGearStr = when {
                        isReverseActive -> "R"
                        estimatedGear != null && estimatedGear in 1..transmission.forwardGears -> estimatedGear.toString()
                        else -> null
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        gears.forEach { g ->
                            val isActive = g == activeGearStr
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isActive) AutoDeckColors.AccentCyan.copy(alpha = 0.22f)
                                        else Color.Transparent
                                    )
                                    .then(
                                        if (isActive) {
                                            Modifier.border(
                                                1.5.dp,
                                                AutoDeckColors.AccentCyan,
                                                RoundedCornerShape(6.dp)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = g,
                                    color = if (isActive) Color.White else AutoDeckColors.TextMuted,
                                    fontSize = if (isActive) 17.sp else 15.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Bloco inferior: COMBUSTÍVEL (E ---- F) com moldura trapezoidal sutil
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(66.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val sideLines = Path().apply {
                        moveTo(w * 0.14f, 0f)
                        lineTo(w * 0.02f, h)
                        moveTo(w * 0.86f, 0f)
                        lineTo(w * 0.98f, h)
                    }
                    drawPath(
                        path = sideLines,
                        color = AutoDeckColors.AccentCyan.copy(alpha = 0.35f),
                        style = Stroke(width = 2f)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "COMBUSTÍVEL",
                        color = AutoDeckColors.HondaCyanSoft,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("E", color = AutoDeckColors.FuelOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                        ) {
                            if (size.width <= 1f || size.height <= 1f) return@Canvas
                            val barH = 7f
                            val top = (size.height - barH) / 2f
                            drawRoundRect(
                                color = Color(0xFF132234),
                                topLeft = Offset(0f, top),
                                size = Size(size.width, barH),
                                cornerRadius = CornerRadius(6f, 6f)
                            )
                            val maxW = size.width.coerceAtLeast(1f)
                            val fillW = (size.width * animatedFuel).coerceIn(0f, maxW)
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        AutoDeckColors.FuelOrange,
                                        AutoDeckColors.StatusAmber,
                                        AutoDeckColors.AccentCyan
                                    )
                                ),
                                topLeft = Offset(0f, top),
                                size = Size(fillW, barH),
                                cornerRadius = CornerRadius(6f, 6f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 5f,
                                center = Offset(fillW, size.height / 2f)
                            )
                        }
                        Text("F", color = AutoDeckColors.AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalGasStation,
                            contentDescription = null,
                            tint = AutoDeckColors.HondaCyanSoft,
                            modifier = Modifier.size(14.dp)
                        )
                        val rangeLabel = estimatedRangeKm?.let { "~${it.toInt()} km" } ?: "${fuelPercent.toInt()}%"
                        Text(
                            text = rangeLabel,
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card MÚSICA (direita superior) ligado em tempo real ao Spotify / MediaSession do tablet.
 */
@Composable
fun HondaMusicCard(
    mediaState: MediaPlaybackState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenMusicTab: () -> Unit,
    onLaunchMusicApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cabeçalho MÚSICA ...
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AutoDeckColors.HondaCardHeader.copy(alpha = 0.92f))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenMusicTab)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = AutoDeckColors.StatusRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "MÚSICA",
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = "Abrir Central de Música",
                tint = AutoDeckColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        // Corpo do Player
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AutoDeckColors.SurfaceCard.copy(alpha = 0.90f))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLaunchMusicApp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1F3B5C), Color(0xFF0E1D2F))
                            )
                        )
                        .border(1.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = mediaState.coverBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = mediaState.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = AutoDeckColors.AccentCyan,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaState.title,
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mediaState.artist,
                        color = AutoDeckColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaState.appLabel,
                        color = AutoDeckColors.AccentCyan.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            // Barra de progresso + tempo
            Column(modifier = Modifier.fillMaxWidth()) {
                val dur = mediaState.durationMs.coerceAtLeast(1L)
                val progress = if (mediaState.durationMs > 0L) {
                    (mediaState.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Slider(
                    value = progress,
                    onValueChange = { frac ->
                        if (mediaState.durationMs > 0L) {
                            onSeekTo((frac * mediaState.durationMs).toLong())
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.9f),
                        inactiveTrackColor = AutoDeckColors.BorderSubtle
                    ),
                    modifier = Modifier.height(22.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDurationMs(mediaState.positionMs), color = AutoDeckColors.TextMuted, fontSize = 10.sp)
                    Text(formatDurationMs(mediaState.durationMs), color = AutoDeckColors.TextMuted, fontSize = 10.sp)
                }
            }

            // Controles Anterior / Play-Pause / Próxima
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = AutoDeckColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AutoDeckColors.SurfaceElevated)
                        .border(1.5.dp, AutoDeckColors.AccentCyan.copy(alpha = 0.8f), CircleShape)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (mediaState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (mediaState.isPlaying) "Pausar" else "Tocar",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Próxima",
                        tint = AutoDeckColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * Card MONITORAMENTO (direita inferior): Voltagem, Temp. Motor e Uso do Motor em 3 colunas.
 */
@Composable
fun HondaMonitoringCard(
    batteryVoltageV: Double?,
    coolantTempC: Double?,
    engineLoadPct: Double?,
    isStale: Boolean,
    onOpenObdScanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AutoDeckColors.HondaCardHeader.copy(alpha = 0.92f))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenObdScanner)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MONITORAMENTO",
                color = AutoDeckColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = "Abrir Scanner OBD-II",
                tint = AutoDeckColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AutoDeckColors.SurfaceCard.copy(alpha = 0.90f))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenObdScanner)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonitoringGaugeItem(
                label = "Voltagem",
                value = batteryVoltageV?.let { "%.1fV".format(it) } ?: "--",
                isStale = isStale,
                valueColor = when {
                    batteryVoltageV != null && batteryVoltageV < 12.0 -> AutoDeckColors.StatusAmber
                    else -> Color(0xFF7DE8FF)
                },
                iconDrawer = { DrawBatteryNeonIcon(Modifier.size(34.dp)) },
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.75f)
                    .background(AutoDeckColors.BorderSubtle.copy(alpha = 0.6f))
            )

            MonitoringGaugeItem(
                label = "Temp. Motor",
                value = coolantTempC?.let { "${it.toInt()}°C" } ?: "--",
                isStale = isStale,
                valueColor = when {
                    coolantTempC != null && coolantTempC >= 105.0 -> AutoDeckColors.StatusRed
                    else -> Color(0xFF7DE8FF)
                },
                iconDrawer = { DrawCoolantTempNeonIcon(Modifier.size(34.dp)) },
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.75f)
                    .background(AutoDeckColors.BorderSubtle.copy(alpha = 0.6f))
            )

            MonitoringGaugeItem(
                label = "Uso do Motor",
                value = engineLoadPct?.let { "${it.toInt()}%" } ?: "--",
                isStale = isStale,
                valueColor = Color(0xFF7DE8FF),
                iconDrawer = { DrawEngineLoadNeonIcon(engineLoadPct ?: 25.0, Modifier.size(34.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MonitoringGaugeItem(
    label: String,
    value: String,
    isStale: Boolean,
    valueColor: Color,
    iconDrawer: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        iconDrawer()
        Text(
            text = label,
            color = AutoDeckColors.TextSecondary,
            fontSize = 11.sp,
            maxLines = 1
        )
        Text(
            text = value,
            color = if (isStale) AutoDeckColors.TextMuted else valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun DrawBatteryNeonIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        // Polos
        drawRect(cyan, Offset(w * 0.22f, h * 0.16f), Size(w * 0.16f, h * 0.10f))
        drawRect(cyan, Offset(w * 0.62f, h * 0.16f), Size(w * 0.16f, h * 0.10f))
        // Corpo da bateria
        drawRoundRect(
            color = cyan,
            topLeft = Offset(w * 0.12f, h * 0.26f),
            size = Size(w * 0.76f, h * 0.56f),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 3f)
        )
        // Sinal - e +
        drawLine(cyan, Offset(w * 0.24f, h * 0.54f), Offset(w * 0.38f, h * 0.54f), 3f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.60f, h * 0.54f), Offset(w * 0.74f, h * 0.54f), 3f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.67f, h * 0.44f), Offset(w * 0.67f, h * 0.64f), 3f, StrokeCap.Round)
    }
}

@Composable
private fun DrawCoolantTempNeonIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        // Haste do termômetro
        drawLine(cyan, Offset(w * 0.44f, h * 0.16f), Offset(w * 0.44f, h * 0.62f), 4f, StrokeCap.Round)
        drawCircle(cyan, radius = w * 0.11f, center = Offset(w * 0.44f, h * 0.66f))
        // Escalas
        drawLine(cyan, Offset(w * 0.56f, h * 0.24f), Offset(w * 0.70f, h * 0.24f), 2.5f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.56f, h * 0.38f), Offset(w * 0.70f, h * 0.38f), 2.5f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.56f, h * 0.52f), Offset(w * 0.70f, h * 0.52f), 2.5f, StrokeCap.Round)
        // Onda de líquido na base
        val wave = Path().apply {
            moveTo(w * 0.14f, h * 0.84f)
            quadraticTo(w * 0.30f, h * 0.76f, w * 0.48f, h * 0.84f)
            quadraticTo(w * 0.66f, h * 0.92f, w * 0.86f, h * 0.82f)
        }
        drawPath(wave, cyan, style = Stroke(width = 2.8f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DrawEngineLoadNeonIcon(loadPct: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        drawArc(
            color = cyan.copy(alpha = 0.35f),
            startAngle = 150f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
        val frac = (loadPct / 100.0).toFloat().coerceIn(0.05f, 1f)
        drawArc(
            color = cyan,
            startAngle = 150f,
            sweepAngle = 240f * frac,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = 4.5f, cap = StrokeCap.Round)
        )
        // Ponteiro
        val angleRad = ((150f + 240f * frac) * PI / 180.0)
        val cx = w * 0.5f
        val cy = h * 0.54f
        drawCircle(cyan, radius = 3.5f, center = Offset(cx, cy))
        drawLine(
            color = Color.White,
            start = Offset(cx, cy),
            end = Offset(
                cx + (w * 0.25f * cos(angleRad)).toFloat(),
                cy + (w * 0.25f * sin(angleRad)).toFloat()
            ),
            strokeWidth = 2.6f,
            cap = StrokeCap.Round
        )
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
