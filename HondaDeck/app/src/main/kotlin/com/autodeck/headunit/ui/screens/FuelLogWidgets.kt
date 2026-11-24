package com.autodeck.headunit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.obd2.FuelEntry
import com.autodeck.protocol.obd2.FuelLogCalculator
import com.autodeck.protocol.obd2.FuelLogSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma linha de resumo para o cartão do tanque (null sem registros). */
fun fuelSummaryLine(s: FuelLogSummary): String? {
    if (s.entryCount == 0) return null
    val real = s.averageKmPerL?.let { "Real: ${"%.1f".format(it)} km/L" } ?: "Real: falta 2º tanque cheio"
    val cost = s.costPerKm?.let { " · R$ ${"%.2f".format(it)}/km" } ?: ""
    return "$real$cost · 30 dias: R$ ${"%.0f".format(s.spentLast30Days)}"
}

/**
 * Diário de abastecimentos: resumo do consumo real, histórico e formulário.
 * Pela voz: "Ok Honda, abasteci 40 litros a 6,29 com 123.456 km, completei".
 */
@Composable
fun FuelLogDialog(
    entries: List<FuelEntry>,
    summary: FuelLogSummary,
    onSave: (liters: Double, price: Double?, odometerKm: Double?, full: Boolean) -> String,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var odo by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR")) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 760.dp)
                .background(AutoDeckColors.SurfaceCard, RoundedCornerShape(18.dp))
                .border(1.dp, AutoDeckColors.BorderSubtle, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Abastecimentos", color = AutoDeckColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            // Resumo
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SummaryCell("Consumo real", summary.averageKmPerL?.let { "%.1f km/L".format(it) } ?: "--")
                SummaryCell("Último tanque", summary.lastKmPerL?.let { "%.1f km/L".format(it) } ?: "--")
                SummaryCell("Custo por km", summary.costPerKm?.let { "R$ %.2f".format(it) } ?: "--")
                SummaryCell("Últimos 30 dias", "R$ %.2f".format(summary.spentLast30Days))
            }
            Text(
                "O consumo real é medido entre dois tanques cheios, pelo hodômetro. A cada tanque cheio o app também recalibra o cálculo do OBD.",
                color = AutoDeckColors.TextMuted,
                fontSize = 12.sp
            )

            // Formulário
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = odo, onValueChange = { odo = it }, label = { Text("Hodômetro (km)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.3f)
                )
                OutlinedTextField(
                    value = liters, onValueChange = { liters = it }, label = { Text("Litros") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it }, label = { Text("R$/litro") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Completei", color = AutoDeckColors.TextSecondary, fontSize = 11.sp)
                    Switch(checked = full, onCheckedChange = { full = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val l = liters.toBrNumber()
                    feedback = if (l == null || l <= 0) {
                        "Informe os litros."
                    } else {
                        onSave(l, price.toBrNumber(), odo.toBrNumber(), full).also {
                            odo = ""; liters = ""; price = ""
                        }
                    }
                }) { Text("Salvar abastecimento") }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
            feedback?.let { Text(it, color = AutoDeckColors.AccentCyan, fontSize = 13.sp) }

            // Histórico
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(entries, key = { it.timestampMs }) { e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append(dateFmt.format(Date(e.timestampMs)))
                                append(" · ${"%.1f".format(e.liters)} L")
                                e.pricePerLiter?.let { append(" · R$ ${"%.2f".format(it)}/L") }
                                e.totalCost?.let { append(" · R$ ${"%.2f".format(it)}") }
                                e.odometerKm?.let { append(" · ${"%.0f".format(it)} km") }
                                append(if (e.fullTank) " · cheio" else " · parcial")
                            },
                            color = AutoDeckColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { onDelete(e.timestampMs) }) { Text("Apagar", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column {
        Text(label, color = AutoDeckColors.TextMuted, fontSize = 11.sp)
        Text(value, color = AutoDeckColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun String.toBrNumber(): Double? = trim().takeIf { it.isNotEmpty() }?.let { FuelLogCalculator.toNumber(it) }
