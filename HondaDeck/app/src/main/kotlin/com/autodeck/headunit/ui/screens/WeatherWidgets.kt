package com.autodeck.headunit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.headunit.weather.WeatherKind
import com.autodeck.headunit.weather.WeatherState

private val SunColor = Color(0xFFFFC94D)
private val RainColor = Color(0xFF6FB6FF)
private val CloudColor = Color(0xFFB8C7D9)

private fun weatherIcon(kind: WeatherKind, isDay: Boolean): Pair<ImageVector, Color> = when (kind) {
    WeatherKind.CLEAR -> if (isDay) Icons.Rounded.WbSunny to SunColor else Icons.Rounded.NightsStay to CloudColor
    WeatherKind.PARTLY_CLOUDY -> if (isDay) Icons.Rounded.WbCloudy to SunColor else Icons.Rounded.NightsStay to CloudColor
    WeatherKind.CLOUDY -> Icons.Rounded.Cloud to CloudColor
    WeatherKind.FOG -> Icons.Rounded.Dehaze to CloudColor
    WeatherKind.DRIZZLE -> Icons.Rounded.Grain to RainColor
    WeatherKind.RAIN, WeatherKind.SHOWERS -> Icons.Rounded.Umbrella to RainColor
    WeatherKind.THUNDER -> Icons.Rounded.FlashOn to SunColor
    WeatherKind.SNOW -> Icons.Rounded.AcUnit to Color.White
    WeatherKind.UNKNOWN -> Icons.Rounded.Cloud to AutoDeckColors.TextMuted
}

/**
 * Linha compacta abaixo do relógio: ícone + temperatura + mín/máx.
 * Toque abre a previsão das próximas horas e dos próximos dias.
 */
@Composable
fun WeatherChip(weather: WeatherState, modifier: Modifier = Modifier) {
    var showForecast by remember { mutableStateOf(false) }
    val now = weather.now

    Row(
        modifier = modifier
            .clickable(enabled = now != null) { showForecast = true }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (now == null) {
            Text(weather.statusText, color = AutoDeckColors.TextMuted, fontSize = 12.sp)
        } else {
            val (icon, tint) = weatherIcon(now.kind, now.isDay)
            Icon(icon, contentDescription = now.kind.labelPt, tint = tint, modifier = Modifier.size(22.dp))
            Text("${now.tempC}°", color = AutoDeckColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (weather.todayMinC != null && weather.todayMaxC != null) {
                Text(
                    "${weather.todayMaxC}° / ${weather.todayMinC}°",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showForecast && now != null) {
        WeatherForecastDialog(weather = weather, onDismiss = { showForecast = false })
    }
}

@Composable
private fun WeatherForecastDialog(weather: WeatherState, onDismiss: () -> Unit) {
    val now = weather.now ?: return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 640.dp)
                .background(AutoDeckColors.SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(18.dp))
                .clickable(onClick = onDismiss)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (icon, tint) = weatherIcon(now.kind, now.isDay)
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
                Column {
                    Text("${now.tempC}°C", color = AutoDeckColors.TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Light)
                    Text(
                        "${now.kind.labelPt} · sensação ${now.feelsLikeC}°",
                        color = AutoDeckColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }

            if (weather.nextHours.isNotEmpty()) {
                Text("PRÓXIMAS HORAS", color = AutoDeckColors.TextMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weather.nextHours.forEach { h ->
                        ForecastCell(
                            top = h.hourLabel,
                            kind = h.kind,
                            isDay = true,
                            main = "${h.tempC}°",
                            rainPct = h.rainChancePct
                        )
                    }
                }
            }

            if (weather.nextDays.isNotEmpty()) {
                Text("PRÓXIMOS DIAS", color = AutoDeckColors.TextMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weather.nextDays.forEach { d ->
                        ForecastCell(
                            top = d.dayLabel,
                            kind = d.kind,
                            isDay = true,
                            main = "${d.maxC}° / ${d.minC}°",
                            rainPct = d.rainChancePct
                        )
                    }
                }
            }

            Text(
                "Fonte: Open-Meteo · toque para fechar",
                color = AutoDeckColors.TextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ForecastCell(top: String, kind: WeatherKind, isDay: Boolean, main: String, rainPct: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Text(top, color = AutoDeckColors.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        val (icon, tint) = weatherIcon(kind, isDay)
        Icon(icon, contentDescription = kind.labelPt, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(main, color = AutoDeckColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (rainPct >= 20) {
            Text("$rainPct%", color = RainColor, fontSize = 12.sp)
        }
    }
}
