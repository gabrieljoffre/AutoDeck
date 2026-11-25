package com.autodeck.hondadeck.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val BgDeep = Color(0xFF06090F)
private val BgCard = Color(0xFF0C121C)
private val AccentCyan = Color(0xFF22D3EE)
private val StatusGreen = Color(0xFF22C55E)
private val StatusRed = Color(0xFFF43F5E)
private val TextPrimary = Color(0xFFE8EEF6)
private val TextMuted = Color(0xFF8A97A8)

class MainActivity : ComponentActivity() {

    private lateinit var storage: PonteStorage

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        if (PonteStateHub.uiState.value.serverRunning) {
            PonteServerService.start(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = PonteStorage(applicationContext)
        refreshPermissions()
        if (storage.isServiceEnabled()) {
            PonteServerService.start(applicationContext)
        }

        setContent {
            val state by PonteStateHub.uiState.collectAsState()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDeep,
                    surface = BgCard,
                    primary = AccentCyan,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    PonteScreen(
                        state = state,
                        onToggleService = {
                            if (state.serverRunning) {
                                PonteServerService.stop(applicationContext)
                            } else {
                                PonteServerService.start(applicationContext)
                            }
                        },
                        onRegeneratePin = {
                            if (state.serverRunning) {
                                PonteServerService.sendAction(applicationContext, PonteServerService.ACTION_REGENERATE_PIN)
                            } else {
                                val next = storage.regeneratePin()
                                PonteStateHub.update { it.copy(pin = next) }
                            }
                        },
                        onUnpairAll = {
                            if (state.serverRunning) {
                                PonteServerService.sendAction(applicationContext, PonteServerService.ACTION_UNPAIR_ALL)
                            } else {
                                storage.clearPairedTokens()
                                PonteStateHub.update { it.copy(pairedCount = 0) }
                            }
                        },
                        onRequestContacts = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                        },
                        onRequestCalendar = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR
                                )
                            )
                        },
                        onRequestPhone = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.ANSWER_PHONE_CALLS
                                )
                            )
                        },
                        onRequestPostNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            }
                        },
                        onOpenNotificationAccess = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        onOpenAccessibilitySettings = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        onRequestBatteryExemption = {
                            openBatteryOptimizationSettings()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val contacts = hasPerm(Manifest.permission.READ_CONTACTS)
        val calendar = hasPerm(Manifest.permission.READ_CALENDAR) && hasPerm(Manifest.permission.WRITE_CALENDAR)
        val phone = hasPerm(Manifest.permission.CALL_PHONE) &&
            hasPerm(Manifest.permission.READ_PHONE_STATE) &&
            hasPerm(Manifest.permission.ANSWER_PHONE_CALLS)
        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val notifListener = PonteNotificationListenerService.isPermissionGranted(this)
        val accessibility = PonteAccessibilityController.isEnabledInSettings(this)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(packageName) == true

        PonteStateHub.update {
            it.copy(
                pin = storage.getOrCreatePin(),
                pairedCount = storage.pairedCount(),
                permissions = PontePermissionStatus(
                    contacts = contacts,
                    calendar = calendar,
                    phone = phone,
                    postNotifications = postNotif,
                    notificationListener = notifListener,
                    accessibility = accessibility,
                    ignoringBatteryOptimizations = batteryOk
                )
            )
        }
    }

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

@Composable
private fun PonteScreen(
    state: PonteUiState,
    onToggleService: () -> Unit,
    onRegeneratePin: () -> Unit,
    onUnpairAll: () -> Unit,
    onRequestContacts: () -> Unit,
    onRequestCalendar: () -> Unit,
    onRequestPhone: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabeçalho
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Phonelink,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "HondaDeck Ponte",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Ponte de WhatsApp, SMS e ligações para o tablet",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        }

        // Cartão de Status
        PonteCard {
            val statusColor = when {
                state.isTabletConnected -> StatusGreen
                state.serverRunning -> AccentCyan
                else -> StatusRed
            }
            val statusLabel = when {
                state.isTabletConnected -> "Conectado ao tablet (${state.connectedTabletName})"
                state.serverRunning -> "Serviço ativo · aguardando tablet"
                else -> "Serviço parado"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = statusLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (state.localIp.isNotBlank()) {
                    "Endereço local: ${state.localIp}:${state.port} · Tablets pareados: ${state.pairedCount}"
                } else {
                    "Porta ${state.port} · Tablets pareados: ${state.pairedCount}"
                },
                fontSize = 13.sp,
                color = TextMuted
            )
            if (state.lastActivity.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = state.lastActivity, fontSize = 13.sp, color = AccentCyan)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.serverRunning) StatusRed else AccentCyan,
                        contentColor = if (state.serverRunning) Color.White else BgDeep
                    )
                ) {
                    Text(if (state.serverRunning) "Parar serviço" else "Iniciar serviço")
                }
                if (state.pairedCount > 0) {
                    OutlinedButton(onClick = onUnpairAll) {
                        Text("Desparear todos", color = TextPrimary)
                    }
                }
            }
        }

        // Cartão do PIN de 6 dígitos
        PonteCard {
            Text(
                text = "PIN DE PAREAMENTO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.pin.chunked(3).joinToString(" "),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = AccentCyan
                )
                OutlinedButton(onClick = onRegeneratePin) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Novo PIN")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "No tablet HondaDeck, abra Ajustes › Celular e digite este PIN uma única vez. Depois a reconexão será automática.",
                fontSize = 13.sp,
                color = TextMuted
            )
        }

        // Checklist de permissões e configurações
        PonteCard {
            Text(
                text = "CHECKLIST DE PERMISSÕES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(10.dp))
            ChecklistRow(
                title = "Contatos (agenda e WhatsApp)",
                subtitle = if (state.contactsCount > 0) {
                    "${state.contactsCount} contatos sincronizados com o copiloto."
                } else {
                    "Sincroniza nomes, apelidos e números com o copiloto."
                },
                granted = state.permissions.contacts,
                actionLabel = "Permitir",
                onAction = onRequestContacts
            )
            ChecklistRow(
                title = "Agenda / Calendário Google",
                subtitle = "Mostra e cria compromissos do celular no painel do carro.",
                granted = state.permissions.calendar,
                actionLabel = "Permitir",
                onAction = onRequestCalendar
            )
            ChecklistRow(
                title = "Telefone e chamadas",
                subtitle = "Fazer, atender, desligar e mostrar chamadas no painel.",
                granted = state.permissions.phone,
                actionLabel = "Permitir",
                onAction = onRequestPhone
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ChecklistRow(
                    title = "Notificação do serviço",
                    subtitle = "Mantém a Ponte ativa em primeiro plano.",
                    granted = state.permissions.postNotifications,
                    actionLabel = "Permitir",
                    onAction = onRequestPostNotifications
                )
            }
            ChecklistRow(
                title = "Acesso a notificações (WhatsApp e SMS)",
                subtitle = "Ative uma vez para repassar mensagens e respostas rápidas ao tablet.",
                granted = state.permissions.notificationListener,
                actionLabel = "Abrir",
                onAction = onOpenNotificationAccess
            )
            ChecklistRow(
                title = "Acessibilidade (envio automático WhatsApp)",
                subtitle = "Ative uma vez. Só toca em Enviar na janela de 15 s após comando do tablet.",
                granted = state.permissions.accessibility,
                actionLabel = "Abrir",
                onAction = onOpenAccessibilitySettings
            )
            ChecklistRow(
                title = "Sem restrição de bateria",
                subtitle = "Evita que o Samsung encerre a Ponte em segundo plano.",
                granted = state.permissions.ignoringBatteryOptimizations,
                actionLabel = "Configurar",
                onAction = onRequestBatteryExemption
            )
        }

        // Dica Smart Lock
        PonteCard {
            Text(
                text = "USO NO CARRO (IMPORTANTE)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
                color = AccentCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "1. Ative o Acesso a Notificações e o Serviço de Acessibilidade acima uma única vez.\n" +
                    "2. Recomendação: em Configurações do Samsung › Tela de bloqueio › Estender desbloqueio (Smart Lock) › Dispositivos confiáveis, adicione o Bluetooth do carro (ex.: Taramps) para o celular permanecer desbloqueado enquanto você dirige.\n" +
                    "3. Se o celular estiver bloqueado ao enviar WhatsApp, o copiloto avisará no painel.",
                fontSize = 13.sp,
                color = TextPrimary,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun PonteCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgCard)
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = if (granted) "Ativo" else "Pendente",
                tint = if (granted) StatusGreen else StatusRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(text = subtitle, fontSize = 12.sp, color = TextMuted)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onAction) {
            Text(if (granted) "OK" else actionLabel, fontSize = 12.sp, color = if (granted) StatusGreen else AccentCyan)
        }
    }
}
