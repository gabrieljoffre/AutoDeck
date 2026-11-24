package com.autodeck.headunit.ui.screens

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.navigation.GpsLocationState
import com.autodeck.headunit.navigation.GpsNavigationTracker
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.TransmissionType
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fundo texturizado escuro Honda com padrão sutil + moldura chanfrada em Ciano Neon.
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
                        Color(0xFF0B2136),
                        Color(0xFF050D17),
                        Color(0xFF03060B)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 1f || size.height <= 1f) return@Canvas
            val w = size.width
            val h = size.height

            // Textura geométrica diagonal sutil (estilo grade automotiva)
            val step = 52f
            val texColor = Color(0xFF39D7FF).copy(alpha = 0.032f)
            var x = -h
            while (x < w + h) {
                drawLine(texColor, Offset(x, 0f), Offset(x + h * 0.55f, h), strokeWidth = 1f)
                x += step
            }

            // Brilho sutil atrás do velocímetro central
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AutoDeckColors.AccentCyan.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.49f),
                    radius = h * 0.46f
                ),
                radius = h * 0.46f,
                center = Offset(w * 0.5f, h * 0.49f)
            )

            // Linha técnica superior chanfrada sob o logo HONDA + diagonais sutis
            val topY = h * 0.068f
            val topNotchY = h * 0.110f
            val topPath = Path().apply {
                moveTo(w * 0.18f, topY)
                lineTo(w * 0.255f, topY)
                lineTo(w * 0.295f, topNotchY)
                lineTo(w * 0.705f, topNotchY)
                lineTo(w * 0.745f, topY)
                lineTo(w * 0.82f, topY)
            }
            drawPath(
                path = topPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.22f),
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
            drawPath(
                path = topPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.85f),
                style = Stroke(width = 2.0f, cap = StrokeCap.Round)
            )

            // Linhas laterais suaves até a base
            drawLine(
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.25f),
                start = Offset(w * 0.26f, topY),
                end = Offset(w * 0.21f, h * 0.23f),
                strokeWidth = 1.4f
            )
            drawLine(
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.25f),
                start = Offset(w * 0.74f, topY),
                end = Offset(w * 0.79f, h * 0.23f),
                strokeWidth = 1.4f
            )

            // Moldura inferior envolvendo o dock
            val botOuterY = h * 0.905f
            val botInnerY = h * 0.868f
            val botPath = Path().apply {
                moveTo(w * 0.045f, botOuterY)
                lineTo(w * 0.315f, botOuterY)
                lineTo(w * 0.355f, botInnerY)
                lineTo(w * 0.645f, botInnerY)
                lineTo(w * 0.685f, botOuterY)
                lineTo(w * 0.955f, botOuterY)
            }
            drawPath(
                path = botPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.20f),
                style = Stroke(width = 9f, cap = StrokeCap.Round)
            )
            drawPath(
                path = botPath,
                color = AutoDeckColors.AccentCyan.copy(alpha = 0.88f),
                style = Stroke(width = 2.2f, cap = StrokeCap.Round)
            )
        }

        content()
    }
}

/**
 * Cabeçalho limpo fiel à referência: emblema [H] HONDA centralizado + 3 pontos discretos no canto superior direito.
 */
@Composable
fun HondaTopHeader(
    uiState: HeadUnitUiState,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        // Centro: Emblema [H] + HONDA sans bold largo ciano
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HondaEmblemLogo(
                modifier = Modifier
                    .width(50.dp)
                    .height(42.dp)
            )
            Text(
                text = "HONDA",
                style = TextStyle(
                    color = Color(0xFF7CF5FF),
                    fontSize = 31.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 5.5.sp,
                    shadow = Shadow(
                        color = AutoDeckColors.AccentCyan.copy(alpha = 0.85f),
                        blurRadius = 22f
                    )
                )
            )
        }

        // Canto superior direito: 3 pontos discretos (Taramps, OBD, GPS)
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenSettings)
                .semantics { contentDescription = "Status e Configurações" }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusMicroDot(
                active = uiState.audioRoute.linkStatus == LinkStatus.CONNECTED,
                activeColor = AutoDeckColors.StatusGreen
            )
            StatusMicroDot(
                active = uiState.headUnit.obdStatus == LinkStatus.CONNECTED,
                activeColor = AutoDeckColors.AccentCyan
            )
            StatusMicroDot(
                active = uiState.gpsState.hasFix,
                activeColor = AutoDeckColors.AccentBlue
            )
        }
    }
}

@Composable
private fun StatusMicroDot(
    active: Boolean,
    activeColor: Color
) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (active) activeColor else Color(0xFF334155))
    )
}

@Composable
fun HondaClockBlock(
    modifier: Modifier = Modifier,
    weather: com.autodeck.headunit.weather.WeatherState? = null
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val timeStr = remember(nowMs / 60_000L) {
        SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(nowMs))
    }
    val dateStr = remember(nowMs / 60_000L) {
        SimpleDateFormat("EEE, dd MMM", Locale.forLanguageTag("pt-BR"))
            .format(Date(nowMs))
            .replace(".", "")
            .uppercase(Locale.forLanguageTag("pt-BR"))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = timeStr,
            style = TextStyle(
                color = Color(0xFF53F6FF),
                fontSize = 54.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.sp,
                shadow = Shadow(
                    color = AutoDeckColors.AccentCyan.copy(alpha = 0.7f),
                    blurRadius = 18f
                )
            ),
            textAlign = TextAlign.Center
        )
        Text(
            text = dateStr,
            color = Color(0xFF8BE8F5),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )
        if (weather != null) {
            WeatherChip(weather = weather)
        }
    }
}

@Composable
private fun HondaEmblemLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val rectPath = Path().apply {
            moveTo(w * 0.14f, h * 0.12f)
            lineTo(w * 0.86f, h * 0.12f)
            quadraticTo(w * 0.94f, h * 0.50f, w * 0.84f, h * 0.88f)
            lineTo(w * 0.16f, h * 0.88f)
            quadraticTo(w * 0.06f, h * 0.50f, w * 0.14f, h * 0.12f)
            close()
        }
        drawPath(
            path = rectPath,
            color = AutoDeckColors.AccentCyan.copy(alpha = 0.28f),
            style = Stroke(width = 8f)
        )
        drawPath(
            path = rectPath,
            color = Color(0xFF6CF4FF),
            style = Stroke(width = 2.8f)
        )

        val hPath = Path().apply {
            moveTo(w * 0.27f, h * 0.22f)
            lineTo(w * 0.37f, h * 0.22f)
            lineTo(w * 0.40f, h * 0.49f)
            lineTo(w * 0.60f, h * 0.49f)
            lineTo(w * 0.63f, h * 0.22f)
            lineTo(w * 0.73f, h * 0.22f)
            lineTo(w * 0.68f, h * 0.78f)
            lineTo(w * 0.58f, h * 0.78f)
            lineTo(w * 0.58f, h * 0.59f)
            lineTo(w * 0.42f, h * 0.59f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.32f, h * 0.78f)
            close()
        }
        drawPath(path = hPath, color = Color(0xFF9DF8FF))
    }
}

/**
 * Dock inferior com 6 ícones contornados fiéis à referência (sem pílula de fundo).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HondaBottomDock(
    activeTab: MainTab,
    unreadMessages: Int,
    onSelectTab: (MainTab) -> Unit,
    onLongPressVoice: () -> Unit,
    onLongPressCamera: () -> Unit
) {
    val items = listOf(
        Triple(MainTab.HOME, Icons.Outlined.Home, "Início"),
        Triple(MainTab.NAVIGATION, Icons.Outlined.Map, "Mapa"),
        Triple(MainTab.MUSIC, Icons.Outlined.MusicNote, "Música"),
        Triple(MainTab.AGENT_VOICE, Icons.Outlined.Phone, "Voz & WhatsApp"),
        Triple(MainTab.OBD_SCANNER, Icons.Outlined.DirectionsCar, "OBD2"),
        Triple(MainTab.SETTINGS, Icons.Outlined.Settings, "Configurações")
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, icon, label) ->
                val isSelected = activeTab == tab
                val longPressDesc = when (tab) {
                    MainTab.AGENT_VOICE -> "Ativar comando de voz"
                    MainTab.OBD_SCANNER -> "Alternar câmera traseira"
                    else -> null
                }
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .semantics { selected = isSelected }
                        .combinedClickable(
                            onClickLabel = label,
                            onLongClickLabel = longPressDesc,
                            onClick = { onSelectTab(tab) },
                            onLongClick = {
                                when (tab) {
                                    MainTab.AGENT_VOICE -> onLongPressVoice()
                                    MainTab.OBD_SCANNER -> onLongPressCamera()
                                    else -> onSelectTab(tab)
                                }
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(AutoDeckColors.AccentCyan.copy(alpha = 0.16f))
                        )
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) AutoDeckColors.AccentCyan else Color(0xFF7D93AC),
                        modifier = Modifier.size(26.dp)
                    )
                    if (tab == MainTab.AGENT_VOICE && unreadMessages > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
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
 * Card NAVEGAÇÃO (esquerda): título em cápsula cinza-azulada + mapa CartoDark real + Marker GPS + rodapé Google Map.
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
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A2433).copy(alpha = 0.92f))
                .border(1.dp, Color(0xFF28394F), RoundedCornerShape(8.dp))
                .clickable(onClick = onExpandMap)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NAVEGAÇÃO",
                color = Color(0xFFCBD5E1),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.8.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Expandir Mapa",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF0A1420))
                .border(1.dp, Color(0xFF23384D), RoundedCornerShape(11.dp))
        ) {
            OsmDarkMapSurface(
                gpsState = gpsState,
                followGps = true,
                interactive = false,
                initialZoom = 16.0,
                onTapNonInteractive = onExpandMap,
                modifier = Modifier.fillMaxSize()
            )

            // Rodapé translúcido para abrir o Google Maps / Waze
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xEB071019))
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
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = preferredMapLabel,
                    color = AutoDeckColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Superfície MapView real do OSMDroid com CartoDark nativo, ciclo de vida completo,
 * Marker GPS real rotacionado pelo bearing, Polyline de trilha e controle sem resetar zoom/pan.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmDarkMapSurface(
    gpsState: GpsLocationState,
    followGps: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    initialZoom: Double = 16.0,
    onUserDragMap: () -> Unit = {},
    onTapNonInteractive: () -> Unit = {},
    onMapViewReady: (MapView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val arrowDrawable = remember(context) { GpsNavigationTracker.createNavigationArrowDrawable(context) }

    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }
    var trailRef by remember { mutableStateOf<Polyline?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { mapRef?.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapRef?.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                mapRef?.onPause()
                mapRef?.onDetach()
            }
            mapRef = null
            markerRef = null
            trailRef = null
        }
    }

    // Move câmera apenas quando a posição GPS mudar E followGps estiver ativo (nunca trava o zoom/arraste do usuário)
    LaunchedEffect(gpsState.latitude, gpsState.longitude, followGps, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        val geo = GeoPoint(gpsState.latitude, gpsState.longitude)
        runCatching {
            markerRef?.let { marker ->
                marker.position = geo
                if (gpsState.hasFix) {
                    marker.rotation = gpsState.bearingDeg
                }
            }
            trailRef?.let { poly ->
                if (gpsState.trailPoints.size >= 2) {
                    poly.setPoints(gpsState.trailPoints)
                }
            }
            if (followGps) {
                map.controller.animateTo(geo)
            }
            map.invalidate()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            runCatching {
                org.osmdroid.config.Configuration.getInstance().userAgentValue =
                    "HondaDeck/2.0 (Android SM-T500)"
            }
            MapView(ctx).apply {
                runCatching {
                    setTileSource(GpsNavigationTracker.CARTO_DARK_TILE_SOURCE)
                    setUseDataConnection(true)
                    setMultiTouchControls(interactive)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isClickable = true
                    isFocusable = interactive

                    val trail = Polyline(this).apply {
                        outlinePaint.color = android.graphics.Color.argb(210, 41, 231, 255)
                        outlinePaint.strokeWidth = 7f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    overlays.add(trail)
                    trailRef = trail

                    val marker = Marker(this).apply {
                        position = GeoPoint(gpsState.latitude, gpsState.longitude)
                        icon = arrowDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
                        if (gpsState.hasFix) rotation = gpsState.bearingDeg
                    }
                    overlays.add(marker)
                    markerRef = marker

                    controller.setZoom(initialZoom)
                    controller.setCenter(GeoPoint(gpsState.latitude, gpsState.longitude))

                    if (interactive) {
                        setOnTouchListener { _, ev ->
                            if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
                                onUserDragMap()
                            }
                            false
                        }
                    } else {
                        setOnTouchListener { _, ev ->
                            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                                onTapNonInteractive()
                            }
                            true
                        }
                    }
                    onResume()
                }
                mapRef = this
                onMapViewReady(this)
            }
        },
        update = { map ->
            runCatching {
                map.setMultiTouchControls(interactive)
            }
        }
    )
}

/** Fundo de escala do arco de giro: corte do Civic 1.7 (D17) fica perto de 6.800 rpm. */
private const val RPM_ARC_FULL_SCALE = 7000f
private const val RPM_ARC_START = 148f
private const val RPM_ARC_SWEEP = 244f
private const val RPM_SEGMENTS = 28 // 250 rpm por segmento
private const val RPM_YELLOW_FROM = 4500f
private const val RPM_RED_FROM = 6000f
/** A partir daqui os LEDs acesos piscam (hora de trocar de marcha). */
private const val RPM_SHIFT_ALERT = 6300f

/**
 * Cockpit Central Honda fiel à imagem: arco 145°/250°, faixas internas, velocidade Light 58sp,
 * MARCHA dentro da abertura do arco e COMBUSTÍVEL fino com E/F abaixo da barra.
 * O arco azul interno é o conta-giros (RPM).
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
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, maxSpeedKmh.toFloat()),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HondaSpeedAnim"
    )
    val animatedRpm by animateFloatAsState(
        targetValue = rpm.coerceIn(0f, RPM_ARC_FULL_SCALE),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HondaRpmAnim"
    )
    val animatedFuel by animateFloatAsState(
        targetValue = (fuelPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "HondaFuelAnim"
    )
    // Pisca dos LEDs de giro perto do corte (só é usado acima de RPM_SHIFT_ALERT).
    val shiftBlinkAlpha by rememberInfiniteTransition(label = "HondaShiftBlink").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 140),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HondaShiftBlinkAlpha"
    )

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 1f || size.height <= 1f) return@Canvas
                val diameter = (minOf(size.width, size.height) * 0.90f).coerceAtLeast(180f)
                val radius = diameter / 2f
                val center = Offset(size.width / 2f, size.height * 0.50f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(diameter, diameter)

                val startAngle = 145f
                val sweepTotal = 250f

                // Brilho suave atrás do dial
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AutoDeckColors.AccentCyan.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * 1.04f
                    ),
                    radius = radius * 1.04f,
                    center = center
                )

                // Divisão tricolor: verde 38%, amarelo 28%, vermelho 34%
                val greenSweep = sweepTotal * 0.38f
                val yellowSweep = sweepTotal * 0.28f
                val redSweep = sweepTotal * 0.34f

                // Halo neon externo
                drawArc(
                    color = AutoDeckColors.StatusGreen.copy(alpha = 0.24f),
                    startAngle = startAngle,
                    sweepAngle = greenSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 18f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = AutoDeckColors.StatusAmber.copy(alpha = 0.24f),
                    startAngle = startAngle + greenSweep,
                    sweepAngle = yellowSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 18f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = AutoDeckColors.StatusRed.copy(alpha = 0.24f),
                    startAngle = startAngle + greenSweep + yellowSweep,
                    sweepAngle = redSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 18f, cap = StrokeCap.Round)
                )

                // Arco principal tricolor
                drawArc(
                    color = AutoDeckColors.StatusGreen,
                    startAngle = startAngle,
                    sweepAngle = greenSweep - 2f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 6.5f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = AutoDeckColors.StatusAmber,
                    startAngle = startAngle + greenSweep,
                    sweepAngle = yellowSweep - 2f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 6.5f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = AutoDeckColors.StatusRed,
                    startAngle = startAngle + greenSweep + yellowSweep,
                    sweepAngle = redSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 6.5f, cap = StrokeCap.Round)
                )

                // Moldura metálica escura entre o arco externo e a escala
                val bezelR = radius * 0.91f
                drawArc(
                    color = Color(0xFF172738),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = Offset(center.x - bezelR, center.y - bezelR),
                    size = Size(bezelR * 2f, bezelR * 2f),
                    style = Stroke(width = 6f)
                )

                // Faixas internas sutis (verde início, vermelho fim)
                val bandR = radius * 0.77f
                val bandTopLeft = Offset(center.x - bandR, center.y - bandR)
                val bandSize = Size(bandR * 2f, bandR * 2f)
                drawArc(
                    color = AutoDeckColors.StatusGreen.copy(alpha = 0.14f),
                    startAngle = startAngle,
                    sweepAngle = greenSweep,
                    useCenter = false,
                    topLeft = bandTopLeft,
                    size = bandSize,
                    style = Stroke(width = radius * 0.22f)
                )
                drawArc(
                    color = AutoDeckColors.StatusRed.copy(alpha = 0.14f),
                    startAngle = startAngle + greenSweep + yellowSweep,
                    sweepAngle = redSweep,
                    useCenter = false,
                    topLeft = bandTopLeft,
                    size = bandSize,
                    style = Stroke(width = radius * 0.22f)
                )

                // Ticks e números usando sp.toPx()
                val step = 20
                val numSteps = maxSpeedKmh / step
                val labelPx = 15.sp.toPx()
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(235, 226, 238, 248)
                    textSize = labelPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                for (i in 0..(numSteps * 2)) {
                    val frac = i / (numSteps * 2f)
                    val deg = startAngle + sweepTotal * frac
                    val rad = deg * PI / 180.0
                    val isMajor = i % 2 == 0
                    val rOuter = radius * 0.875f
                    val rInner = if (isMajor) radius * 0.79f else radius * 0.835f
                    drawLine(
                        color = Color.White.copy(alpha = if (isMajor) 0.85f else 0.40f),
                        start = Offset(
                            center.x + (rInner * cos(rad)).toFloat(),
                            center.y + (rInner * sin(rad)).toFloat()
                        ),
                        end = Offset(
                            center.x + (rOuter * cos(rad)).toFloat(),
                            center.y + (rOuter * sin(rad)).toFloat()
                        ),
                        strokeWidth = if (isMajor) 2.4f else 1.2f,
                        cap = StrokeCap.Round
                    )
                    if (isMajor) {
                        val labelVal = (i / 2) * step
                        val rLabel = radius * 0.675f
                        val lx = center.x + (rLabel * cos(rad)).toFloat()
                        val ly = center.y + (rLabel * sin(rad)).toFloat() + labelPx * 0.35f
                        drawContext.canvas.nativeCanvas.drawText(
                            labelVal.toString(),
                            lx,
                            ly,
                            textPaint
                        )
                    }
                }

                // CONTA-GIROS estilo pista (shift light) em volta do número da velocidade:
                // segmentos de LED que acendem com o RPM, verdes até 4.500, amarelos até
                // 6.000 e vermelhos acima. Perto do corte, os segmentos acesos piscam.
                // A velocidade fica só no número central e a marcha no seletor.
                val innerR = radius * 0.45f
                val innerTopLeft = Offset(center.x - innerR, center.y - innerR)
                val innerSize = Size(innerR * 2f, innerR * 2f)
                val rpmFrac = (animatedRpm / RPM_ARC_FULL_SCALE).coerceIn(0f, 1f)
                val segSweep = RPM_ARC_SWEEP / RPM_SEGMENTS
                val segGap = 1.8f
                val shiftAlert = animatedRpm >= RPM_SHIFT_ALERT
                for (i in 0 until RPM_SEGMENTS) {
                    val segRpm = (i + 0.5f) / RPM_SEGMENTS * RPM_ARC_FULL_SCALE
                    val zoneColor = when {
                        segRpm < RPM_YELLOW_FROM -> AutoDeckColors.StatusGreen
                        segRpm < RPM_RED_FROM -> AutoDeckColors.StatusAmber
                        else -> AutoDeckColors.StatusRed
                    }
                    val lit = (i + 1f) / RPM_SEGMENTS <= rpmFrac + 0.0001f
                    val alpha = when {
                        !lit -> 0.13f
                        shiftAlert -> shiftBlinkAlpha
                        else -> 1f
                    }
                    val segStart = RPM_ARC_START + i * segSweep + segGap / 2f
                    if (lit) {
                        // Brilho atrás do segmento aceso (efeito LED)
                        drawArc(
                            color = zoneColor.copy(alpha = 0.28f * alpha),
                            startAngle = segStart,
                            sweepAngle = segSweep - segGap,
                            useCenter = false,
                            topLeft = innerTopLeft,
                            size = innerSize,
                            style = Stroke(width = 20f, cap = StrokeCap.Butt)
                        )
                    }
                    drawArc(
                        color = zoneColor.copy(alpha = alpha),
                        startAngle = segStart,
                        sweepAngle = segSweep - segGap,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 11f, cap = StrokeCap.Butt)
                    )
                }
            }

            // Velocidade central 58sp Light + "km/h"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = (-8).dp)
            ) {
                Text(
                    text = animatedSpeed.toInt().toString(),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        shadow = Shadow(
                            color = AutoDeckColors.AccentCyan.copy(alpha = 0.45f),
                            blurRadius = 12f
                        )
                    )
                )
                Text(
                    text = "km/h",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            // MARCHA posicionada na abertura inferior do arco
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MARCHA",
                    color = Color(0xFF83B9CD),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.8.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                val gears = (1..transmission.forwardGears).map { it.toString() } + "R"
                val activeGearStr = when {
                    isReverseActive -> "R"
                    estimatedGear != null && estimatedGear in 1..transmission.forwardGears -> estimatedGear.toString()
                    else -> null
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    gears.forEach { g ->
                        val isActive = g == activeGearStr
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .then(
                                    if (isActive) {
                                        Modifier.border(
                                            1.4.dp,
                                            AutoDeckColors.AccentCyan,
                                            RoundedCornerShape(5.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = g,
                                color = if (isActive) Color.White else Color(0xFF647B92),
                                fontSize = 17.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // COMBUSTÍVEL entre linhas diagonais com E e F abaixo das pontas da barra
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .heightIn(min = 62.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 1f || size.height <= 1f) return@Canvas
                val w = size.width
                val h = size.height
                val sideLines = Path().apply {
                    moveTo(w * 0.16f, 0f)
                    lineTo(w * 0.02f, h)
                    moveTo(w * 0.84f, 0f)
                    lineTo(w * 0.98f, h)
                }
                drawPath(
                    path = sideLines,
                    color = AutoDeckColors.AccentCyan.copy(alpha = 0.35f),
                    style = Stroke(width = 1.8f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "COMBUSTÍVEL",
                    color = Color(0xFF9AC5D8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                ) {
                    if (size.width <= 1f || size.height <= 1f) return@Canvas
                    val barH = 5.5f
                    val top = (size.height - barH) / 2f
                    drawRoundRect(
                        color = Color(0xFF152538),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, barH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    val maxW = size.width.coerceAtLeast(1f)
                    val fillW = (size.width * animatedFuel).coerceIn(0f, maxW)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                AutoDeckColors.StatusRed,
                                AutoDeckColors.FuelOrange,
                                AutoDeckColors.StatusAmber,
                                AutoDeckColors.AccentCyan
                            )
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(fillW, barH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    // Marcador vertical fino na ponta do nível
                    drawLine(
                        color = Color.White,
                        start = Offset(fillW, 0f),
                        end = Offset(fillW, size.height),
                        strokeWidth = 2.4f,
                        cap = StrokeCap.Round
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("E", color = AutoDeckColors.FuelOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Icon(
                        imageVector = Icons.Rounded.LocalGasStation,
                        contentDescription = null,
                        tint = AutoDeckColors.HondaCyanSoft,
                        modifier = Modifier.size(15.dp)
                    )
                    Text("F", color = AutoDeckColors.AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Card MÚSICA fiel à imagem: capa 82dp, sem linha de app extra, barra customizada em Canvas,
 * botões FastRewind / círculo fino Play-Pause / FastForward.
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A2433).copy(alpha = 0.92f))
                .border(1.dp, Color(0xFF28394F), RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenMusicTab)
                .padding(horizontal = 14.dp),
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
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "MÚSICA",
                    color = Color(0xFFCBD5E1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.8.sp
                )
            }
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = "Abrir Central de Música",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AutoDeckColors.SurfaceCard.copy(alpha = 0.90f))
                .border(1.dp, Color(0xFF23384D), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onLaunchMusicApp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF243B55), Color(0xFF101D2C))
                            )
                        )
                        .border(1.dp, Color(0xFF334C66), RoundedCornerShape(8.dp)),
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
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = mediaState.title,
                        color = AutoDeckColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaState.artist,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Barra de progresso customizada em Canvas
            Column(modifier = Modifier.fillMaxWidth()) {
                val dur = mediaState.durationMs.coerceAtLeast(1L)
                val progress = if (mediaState.durationMs > 0L) {
                    (mediaState.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                HondaMusicProgressBar(
                    progress = progress,
                    enabled = mediaState.canSeek && mediaState.durationMs > 0L,
                    onSeekFraction = { frac ->
                        onSeekTo((frac * mediaState.durationMs).toLong())
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDurationMs(mediaState.positionMs), color = Color(0xFF7D93AC), fontSize = 10.sp)
                    Text(formatDurationMs(mediaState.durationMs), color = Color(0xFF7D93AC), fontSize = 10.sp)
                }
            }

            // Controles ⏪ ⏸/▶ ⏩
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Rounded.FastRewind,
                        contentDescription = "Anterior",
                        tint = Color(0xFFE2E8F0),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(1.2.dp, Color(0xFFCBD5E1), CircleShape)
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
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = "Próxima",
                        tint = Color(0xFFE2E8F0),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HondaMusicProgressBar(
    progress: Float,
    enabled: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnSeek by rememberUpdatedState(onSeekFraction)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    val displayProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = "Barra de progresso da música" }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                if (size.width > 0) {
                                    currentOnSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    if (size.width > 0) {
                                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    }
                                },
                                onDragEnd = {
                                    isDragging = false
                                    currentOnSeek(dragProgress)
                                },
                                onDragCancel = {
                                    isDragging = false
                                }
                            ) { change, _ ->
                                change.consume()
                                if (size.width > 0) {
                                    dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                }
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val cy = size.height / 2f
        drawLine(
            color = Color(0xFF283A4E),
            start = Offset(0f, cy),
            end = Offset(size.width, cy),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
        val activeX = (size.width * displayProgress)
        if (activeX > 0f) {
            drawLine(
                color = Color(0xFFE2E8F0),
                start = Offset(0f, cy),
                end = Offset(activeX, cy),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = Color.White,
            radius = 5.5f,
            center = Offset(activeX, cy)
        )
    }
}

/**
 * Card MONITORAMENTO fiel à referência: sem divisórias verticais, ícones ciano 36dp e valores 16sp Regular #6FD8FF.
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A2433).copy(alpha = 0.92f))
                .border(1.dp, Color(0xFF28394F), RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenObdScanner)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MONITORAMENTO",
                color = Color(0xFFCBD5E1),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.8.sp
            )
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = "Abrir Scanner OBD-II",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AutoDeckColors.SurfaceCard.copy(alpha = 0.90f))
                .border(1.dp, Color(0xFF23384D), RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenObdScanner)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonitoringGaugeItem(
                label = "Voltagem",
                value = batteryVoltageV?.let { "%.1fV".format(it) } ?: "--",
                isStale = isStale,
                valueColor = when {
                    batteryVoltageV != null && batteryVoltageV < 12.0 -> AutoDeckColors.StatusAmber
                    else -> Color(0xFF6FD8FF)
                },
                iconDrawer = { DrawBatteryNeonIcon(Modifier.size(36.dp)) },
                modifier = Modifier.weight(1f)
            )

            MonitoringGaugeItem(
                label = "Temp. Motor",
                value = coolantTempC?.let { "${it.toInt()}°C" } ?: "--",
                isStale = isStale,
                valueColor = when {
                    coolantTempC != null && coolantTempC >= 105.0 -> AutoDeckColors.StatusRed
                    else -> Color(0xFF6FD8FF)
                },
                iconDrawer = { DrawCoolantTempNeonIcon(Modifier.size(36.dp)) },
                modifier = Modifier.weight(1f)
            )

            MonitoringGaugeItem(
                label = "Uso do Motor",
                value = engineLoadPct?.let { "${it.toInt()}%" } ?: "--",
                isStale = isStale,
                valueColor = Color(0xFF6FD8FF),
                iconDrawer = { DrawEngineLoadNeonIcon(engineLoadPct ?: 25.0, Modifier.size(36.dp)) },
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
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        iconDrawer()
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = if (isStale) AutoDeckColors.TextMuted else valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DrawBatteryNeonIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        drawRect(cyan, Offset(w * 0.22f, h * 0.18f), Size(w * 0.16f, h * 0.08f))
        drawRect(cyan, Offset(w * 0.62f, h * 0.18f), Size(w * 0.16f, h * 0.08f))
        drawRoundRect(
            color = cyan,
            topLeft = Offset(w * 0.12f, h * 0.26f),
            size = Size(w * 0.76f, h * 0.54f),
            cornerRadius = CornerRadius(5f, 5f),
            style = Stroke(width = 2.6f)
        )
        drawLine(cyan, Offset(w * 0.24f, h * 0.53f), Offset(w * 0.38f, h * 0.53f), 2.5f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.60f, h * 0.53f), Offset(w * 0.74f, h * 0.53f), 2.5f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.67f, h * 0.44f), Offset(w * 0.67f, h * 0.62f), 2.5f, StrokeCap.Round)
    }
}

@Composable
private fun DrawCoolantTempNeonIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        drawLine(cyan, Offset(w * 0.44f, h * 0.16f), Offset(w * 0.44f, h * 0.62f), 3.4f, StrokeCap.Round)
        drawCircle(cyan, radius = w * 0.10f, center = Offset(w * 0.44f, h * 0.66f))
        drawLine(cyan, Offset(w * 0.56f, h * 0.24f), Offset(w * 0.70f, h * 0.24f), 2.2f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.56f, h * 0.38f), Offset(w * 0.70f, h * 0.38f), 2.2f, StrokeCap.Round)
        drawLine(cyan, Offset(w * 0.56f, h * 0.52f), Offset(w * 0.70f, h * 0.52f), 2.2f, StrokeCap.Round)
        val wave = Path().apply {
            moveTo(w * 0.14f, h * 0.84f)
            quadraticTo(w * 0.30f, h * 0.76f, w * 0.48f, h * 0.84f)
            quadraticTo(w * 0.66f, h * 0.92f, w * 0.86f, h * 0.82f)
        }
        drawPath(wave, cyan, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DrawEngineLoadNeonIcon(loadPct: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val w = size.width
        val h = size.height
        val cyan = AutoDeckColors.AccentCyan
        drawArc(
            color = cyan.copy(alpha = 0.38f),
            startAngle = 150f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = 3.2f, cap = StrokeCap.Round)
        )
        val frac = (loadPct / 100.0).toFloat().coerceIn(0.05f, 1f)
        drawArc(
            color = cyan,
            startAngle = 150f,
            sweepAngle = 240f * frac,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = 3.6f, cap = StrokeCap.Round)
        )
        val angleRad = ((150f + 240f * frac) * PI / 180.0)
        val cx = w * 0.5f
        val cy = h * 0.54f
        drawCircle(cyan, radius = 3.0f, center = Offset(cx, cy))
        drawLine(
            color = Color.White,
            start = Offset(cx, cy),
            end = Offset(
                cx + (w * 0.24f * cos(angleRad)).toFloat(),
                cy + (w * 0.24f * sin(angleRad)).toFloat()
            ),
            strokeWidth = 2.4f,
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
