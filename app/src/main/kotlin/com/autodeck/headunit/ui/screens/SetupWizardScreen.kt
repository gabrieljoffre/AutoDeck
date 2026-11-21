package com.autodeck.headunit.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.hardware.PairedBluetoothDevice
import com.autodeck.headunit.hardware.UsbDeviceRole
import com.autodeck.headunit.hardware.UsbDeviceSummary
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.ui.theme.AutoDeckColors
import com.autodeck.protocol.coordinator.ProjectionMode
import com.autodeck.protocol.obd2.FuelType

@Composable
fun SetupWizardScreen(
    uiState: HeadUnitUiState,
    onRefreshBluetooth: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefreshUsb: () -> Unit,
    onCalibrateNoSignal: () -> Boolean,
    onToggleLauncherMode: (Boolean) -> Unit,
    onFinishSetup: (HeadUnitConfig) -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Não usa remember(uiState.config) para não apagar escolhas dos passos anteriores ao calibrar!
    var draft by remember { mutableStateOf(uiState.config) }
    var calibrationFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.config.noSignalCalibration) {
        if (uiState.config.noSignalCalibration != null) {
            draft = draft.copy(noSignalCalibration = uiState.config.noSignalCalibration)
        }
    }

    val stepTitles = listOf(
        "1. OBD-II & Motor",
        "2. Câmera de Ré (USB)",
        "3. CarPlay & Android Auto",
        "4. Permissões & Concluir"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AutoDeckColors.BgCarbon)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AutoDeck — 1ª Configuração",
                    color = AutoDeckColors.AccentCyan,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure uma vez para tudo conectar sozinho ao ligar o carro.",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "Passo ${step + 1} de 4",
                color = AutoDeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stepTitles.forEachIndexed { index, title ->
                val selected = index == step
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) AutoDeckColors.AccentCyan.copy(alpha = 0.18f)
                            else AutoDeckColors.SurfaceCard,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { step = index }
                        .padding(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Text(
                        text = title,
                        color = if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AutoDeckColors.SurfaceCard),
            border = BorderStroke(1.dp, AutoDeckColors.BorderSubtle),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (step) {
                    0 -> StepObdConfig(
                        draft = draft,
                        pairedDevices = uiState.pairedBluetoothDevices,
                        onUpdate = { draft = it },
                        onRefreshBluetooth = onRefreshBluetooth
                    )
                    1 -> StepCameraConfig(
                        draft = draft,
                        hasValidStream = uiState.cameraHardware.hasValidFrameStream,
                        calibrationFeedback = calibrationFeedback,
                        usbDevices = uiState.usbDevices,
                        onUpdate = { draft = it },
                        onRefreshUsb = onRefreshUsb,
                        onCalibrateNoSignal = {
                            val ok = onCalibrateNoSignal()
                            calibrationFeedback = if (ok) {
                                "Frame 'Sem Sinal' capturado com sucesso!"
                            } else {
                                "Ative o Simulador de Câmera ou conecte um stream USB válido antes de calibrar."
                            }
                        }
                    )
                    2 -> StepProjectionConfig(
                        draft = draft,
                        hurPackage = uiState.hurPackageInstalled,
                        usbDevices = uiState.usbDevices,
                        onUpdate = { draft = it }
                    )
                    else -> StepAutomationAndChecklist(
                        draft = draft,
                        onUpdate = { draft = it },
                        onRequestPermissions = onRequestPermissions,
                        onToggleLauncherMode = {
                            draft = draft.copy(launcherModeEnabled = it)
                            onToggleLauncherMode(it)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = { if (step > 0) step-- },
                enabled = step > 0
            ) {
                Text("Voltar")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step < 3) {
                    Button(
                        onClick = { step++ },
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentCyan)
                    ) {
                        Text("Próximo Passo", color = Color(0xFF03151B), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { onFinishSetup(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.StatusGreen)
                    ) {
                        Text("Concluir e Iniciar Central", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepObdConfig(
    draft: HeadUnitConfig,
    pairedDevices: List<PairedBluetoothDevice>,
    onUpdate: (HeadUnitConfig) -> Unit,
    onRefreshBluetooth: () -> Unit
) {
    Text(
        text = "Conexão OBD-II (ELM327 Bluetooth) + Áudio Taranis",
        color = AutoDeckColors.TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Para nunca picotar a música enviada ao processador Taranis, o AutoDeck não faz varredura Bluetooth após configurado: escolha o OBD-II na lista de dispositivos já pareados.",
        color = AutoDeckColors.TextSecondary,
        fontSize = 13.sp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Usar Modo Simulador OBD-II (Bancada)", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Gera RPM, temperatura, km/L e bateria simulados sem precisar do carro", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = draft.useSimulatedObd,
            onCheckedChange = { onUpdate(draft.copy(useSimulatedObd = it)) }
        )
    }

    if (!draft.useSimulatedObd) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dispositivos Bluetooth pareados:", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onRefreshBluetooth) { Text("Atualizar lista") }
        }
        if (pairedDevices.isEmpty()) {
            Text(
                text = "Nenhum dispositivo pareado encontrado (conceda permissão Bluetooth ou pareie o OBDII nas Configurações do Android).",
                color = AutoDeckColors.StatusAmber,
                fontSize = 13.sp
            )
        } else {
            pairedDevices.forEach { dev ->
                val selected = draft.obdDeviceMac == dev.macAddress
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdate(draft.copy(obdDeviceMac = dev.macAddress, obdDeviceName = dev.name))
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) AutoDeckColors.AccentCyan.copy(alpha = 0.16f) else AutoDeckColors.SurfaceElevated
                    ),
                    border = BorderStroke(1.dp, if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.BorderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dev.name, color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(dev.macAddress, color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    Text("Combustível para cálculo de consumo (km/L):", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FuelType.entries.forEach { fuel ->
            FilterChip(
                selected = draft.fuelType == fuel,
                onClick = { onUpdate(draft.copy(fuelType = fuel)) },
                label = { Text(fuel.labelPt.substringBefore(" (")) }
            )
        }
    }

    Text("Cilindrada do motor (fallback Speed-Density se o carro não tiver MAF):", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1.0, 1.4, 1.6, 1.8, 2.0, 2.4).forEach { liters ->
            FilterChip(
                selected = draft.engineDisplacementLiters == liters,
                onClick = { onUpdate(draft.copy(engineDisplacementLiters = liters)) },
                label = { Text("${liters}L") }
            )
        }
    }
}

@Composable
private fun StepCameraConfig(
    draft: HeadUnitConfig,
    hasValidStream: Boolean,
    calibrationFeedback: String?,
    usbDevices: List<UsbDeviceSummary>,
    onUpdate: (HeadUnitConfig) -> Unit,
    onRefreshUsb: () -> Unit,
    onCalibrateNoSignal: () -> Unit
) {
    Text(
        text = "Câmera de Ré Automática (Conversor Multi-RCA → USB)",
        color = AutoDeckColors.TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Igual ao USB Camera Pro, o AutoDeck mantém a câmera em standby e sobe em 100% tela cheia ao detectar sinal real (inclusive à noite por variação analógica entre frames).",
        color = AutoDeckColors.TextSecondary,
        fontSize = 13.sp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Usar Modo Simulador de Câmera (Bancada)", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Permite simular tela cinza em standby e subida automática ao engatar a ré", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = draft.useSimulatedCamera,
            onCheckedChange = { onUpdate(draft.copy(useSimulatedCamera = it)) }
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRefreshUsb) { Text("Verificar Conversor no Hub USB") }
        Button(
            onClick = onCalibrateNoSignal,
            enabled = hasValidStream,
            colors = ButtonDefaults.buttonColors(containerColor = AutoDeckColors.AccentBlue)
        ) {
            Text("Calibrar Frame 'Sem Sinal'")
        }
    }

    calibrationFeedback?.let {
        Text(text = it, color = AutoDeckColors.AccentCyan, fontSize = 13.sp)
    }

    Text(
        text = if (draft.noSignalCalibration != null) {
            "Referência 'Sem Sinal' calibrada (luma médio=${draft.noSignalCalibration.meanLuma.toInt()}, desvio=${"%.1f".format(draft.noSignalCalibration.lumaStdDev)})."
        } else {
            "Sem calibração manual ainda — usando detecção automática de tela estática uniforme."
        },
        color = AutoDeckColors.StatusGreen,
        fontSize = 13.sp
    )

    Text("Dispositivos USB detectados no Hub:", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
    if (usbDevices.isEmpty()) {
        Text("Nenhum periférico USB conectado no momento.", color = AutoDeckColors.TextSecondary, fontSize = 13.sp)
    } else {
        usbDevices.forEach { dev ->
            Text(
                text = "• ${dev.productName ?: "USB"} [${dev.vidPidHex}] — ${dev.role.labelPt} (${if (dev.hasPermission) "Permissão OK" else "Sem permissão"})",
                color = AutoDeckColors.TextPrimary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun StepProjectionConfig(
    draft: HeadUnitConfig,
    hurPackage: String?,
    usbDevices: List<UsbDeviceSummary>,
    onUpdate: (HeadUnitConfig) -> Unit
) {
    Text(
        text = "Projeção: Apple CarPlay + Android Auto (Zero Cliques)",
        color = AutoDeckColors.TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "Com o dongle Carlinkit CPC200-CCPA, o AutoDeck envia Wi-Fi 5 GHz (não colide com o Bluetooth do Taranis), habilita Android Auto + CarPlay e dispara reconexão automática sem você precisar abrir nada no celular.",
        color = AutoDeckColors.TextSecondary,
        fontSize = 13.sp
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val modes = listOf(
            ProjectionMode.CARLINKIT_NATIVE to "Carlinkit CPC200-CCPA Embutido (CarPlay + Android Auto automáticos no mesmo app)",
            ProjectionMode.HUR_FALLBACK to "HUR Transição (Android Auto via app HUR externo + Painel OBD-II/Ré aqui)"
        )
        modes.forEach { (mode, label) ->
            val selected = draft.projectionMode == mode
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(draft.copy(projectionMode = mode)) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) AutoDeckColors.AccentCyan.copy(alpha = 0.16f) else AutoDeckColors.SurfaceElevated
                ),
                border = BorderStroke(1.dp, if (selected) AutoDeckColors.AccentCyan else AutoDeckColors.BorderSubtle)
            ) {
                Text(
                    text = label,
                    color = AutoDeckColors.TextPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Usar microfone do dongle (se existir) em vez do tablet", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Desligado = usa microfone do tablet Samsung para Siri / Google / chamadas", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = draft.useBoxMic,
            onCheckedChange = { onUpdate(draft.copy(useBoxMic = it)) }
        )
    }

    val carlinkitFound = usbDevices.any { it.role == UsbDeviceRole.CARLINKIT_DONGLE }
    Text(
        text = "Status detectado: Carlinkit USB = ${if (carlinkitFound) "Conectado" else "Não conectado"} | HUR instalado = ${hurPackage ?: "Não encontrado"}",
        color = AutoDeckColors.TextSecondary,
        fontSize = 13.sp
    )
}

@SuppressLint("BatteryLife")
@Composable
private fun StepAutomationAndChecklist(
    draft: HeadUnitConfig,
    onUpdate: (HeadUnitConfig) -> Unit,
    onRequestPermissions: () -> Unit,
    onToggleLauncherMode: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPerm(context, Manifest.permission.BLUETOOTH_CONNECT)
    } else true
    val camGranted = hasPerm(context, Manifest.permission.CAMERA)
    val micGranted = hasPerm(context, Manifest.permission.RECORD_AUDIO)
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasPerm(context, Manifest.permission.POST_NOTIFICATIONS)
    } else true
    val overlayGranted = Settings.canDrawOverlays(context)
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

    Text(
        text = "Checklist 'Funcionar 100% Sozinho' (Android 10–16)",
        color = AutoDeckColors.TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onRequestPermissions) {
            Text("Pedir Permissões do App")
        }
        if (!overlayGranted) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Text("Liberar Sobreposição (Ré s/ HUR)")
            }
        }
        if (!batteryUnrestricted) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Text("Remover Otimização Bateria")
            }
        }
    }

    PermissionStatusRow("Bluetooth OBD-II (BLUETOOTH_CONNECT)", btGranted)
    PermissionStatusRow("Câmera USB (CAMERA — obrigatória no Android 10+)", camGranted)
    PermissionStatusRow("Microfone Siri / Ligações (RECORD_AUDIO)", micGranted)
    PermissionStatusRow("Notificação Serviço Ativo (POST_NOTIFICATIONS)", notifGranted)
    PermissionStatusRow("Sobreposição de Tela (câmera sobre outros apps)", overlayGranted)
    PermissionStatusRow("Sem suspensão Samsung / Otimização Bateria", batteryUnrestricted)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Modo Launcher (Tela Inicial Automática do Tablet)", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Permite definir o AutoDeck como Home padrão do tablet Samsung", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = draft.launcherModeEnabled,
            onCheckedChange = onToggleLauncherMode
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Auto-conectar tudo ao abrir", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Pula este assistente nas próximas inicializações", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = draft.autoConnectOnLaunch,
            onCheckedChange = { onUpdate(draft.copy(autoConnectOnLaunch = it)) }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Abaixar som suavemente na ré (Ducking)", color = AutoDeckColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Sem desconectar o áudio Bluetooth do processador Taranis", color = AutoDeckColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = draft.duckAudioOnReverse,
            onCheckedChange = { onUpdate(draft.copy(duckAudioOnReverse = it)) }
        )
    }

    Text(
        text = "Dica Samsung: nas Configurações do tablet, ative 'Proteger bateria (85%)' e desligue 'Busca de dispositivos próximos' para zero interferência no BT.",
        color = AutoDeckColors.StatusAmber,
        fontSize = 12.sp
    )
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "• $label", color = AutoDeckColors.TextPrimary, fontSize = 13.sp)
        Text(
            text = if (granted) "OK" else "PENDENTE",
            color = if (granted) AutoDeckColors.StatusGreen else AutoDeckColors.StatusAmber,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

private fun hasPerm(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
