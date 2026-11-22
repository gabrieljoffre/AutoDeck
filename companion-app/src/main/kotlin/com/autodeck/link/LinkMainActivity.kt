package com.autodeck.link

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LinkMainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AutoDeckLinkScreen()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
private fun AutoDeckLinkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverActive by remember { mutableStateOf(false) }
    var phoneIp by remember { mutableStateOf<String?>(null) }
    var tabletEndpoint by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Verificando rede e Android Auto...") }
    var autoBtEnabled by remember { mutableStateOf(AutoDeckLinkPrefs.isAutoBluetoothEnabled(context)) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshAndAnnounce() {
        val res = AutoDeckLinkNetwork.announceToTablet()
        serverActive = res.serverRunningOnPhone
        phoneIp = res.phoneIp
        tabletEndpoint = res.tabletEndpoint
        statusMessage = when {
            res.tabletEndpoint != null && res.serverRunningOnPhone ->
                "Tablet encontrado (${res.tabletEndpoint}) e servidor 5277 ativo!"
            res.tabletEndpoint != null && !res.serverRunningOnPhone ->
                "Tablet encontrado (${res.tabletEndpoint}), mas o Servidor de Unidade Principal está desligado."
            res.serverRunningOnPhone ->
                "Servidor 5277 ativo (${res.phoneIp ?: "sem IP"}). Procurando tablet no Wi-Fi..."
            else ->
                "Conecte no Wi-Fi/Hotspot do Civic e ative o Servidor de Unidade Principal."
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshAndAnnounce()
            delay(4_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 42.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AutoDeck Link",
            color = Color(0xFF22D3EE),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Conector rápido para Honda Civic 2005 LX",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141B24)),
            border = BorderStroke(1.dp, Color(0xFF233042)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Status ao vivo", color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "Servidor AA (porta 5277): ${if (serverActive) "ATIVO" else "DESLIGADO"}",
                    color = if (serverActive) Color(0xFF22C55E) else Color(0xFFF59E0B),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "IP deste celular: ${phoneIp ?: "Sem Wi-Fi / Hotspot"}",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )
                Text(
                    text = "Tablet AutoDeck: ${tabletEndpoint ?: "Aguardando resposta UDP 5278"}",
                    color = if (tabletEndpoint != null) Color(0xFF22C55E) else Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = statusMessage, color = Color(0xFF38BDF8), fontSize = 13.sp)
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    refreshAndAnnounce()
                    busy = false
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = if (busy) "Conectando..." else "Conectar no Civic Agora",
                color = Color(0xFF03151B),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        OutlinedButton(
            onClick = { AutoDeckLinkNetwork.launchAndroidAutoSettings(context) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Abrir Configurações do Android Auto", color = Color(0xFFE2E8F0))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141B24)),
            border = BorderStroke(1.dp, Color(0xFF233042)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reconexão automática por Bluetooth",
                            color = Color(0xFFE2E8F0),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Avisa o tablet automaticamente ao conectar em TARAMPS / Galaxy Tab",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = autoBtEnabled,
                        onCheckedChange = {
                            autoBtEnabled = it
                            AutoDeckLinkPrefs.setAutoBluetoothEnabled(context, it)
                        }
                    )
                }

                Text(
                    text = "Dica para o dia a dia (Samsung): crie uma rotina em Modos e Rotinas → 'Se Bluetooth conectado em TARAMPS_BT → Ativar Roteador Wi-Fi'. Ao entrar no carro, tudo conecta sem tirar o celular do bolso.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
