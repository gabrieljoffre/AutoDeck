package com.autodeck.headunit.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.service.HeadUnitRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IgnitionStatus(
    val ignitionOn: Boolean = true,
    val standbyActive: Boolean = false,
    val standbyCountdownSec: Int? = null,
    val triggerDeviceLabel: String = "Taramps (Em espera)"
)

/**
 * Receiver estático registrado no AndroidManifest para acordar o HondaDeck
 * imediatamente quando o Bluetooth do processador Taramps conecta (1ª fase da chave).
 */
class IgnitionBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        ) ?: return

        val mac = device.address.orEmpty()
        val name = runCatching {
            if (BluetoothDirectory.hasConnectPermission(context)) device.name else null
        }.getOrNull().orEmpty()

        val connected: Boolean? = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED
                )
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> true
                    BluetoothProfile.STATE_DISCONNECTED -> false
                    else -> null
                }
            }
            else -> null
        }

        if (connected != null) {
            HeadUnitRuntime.get(context.applicationContext)
                .onIgnitionBluetoothDeviceEvent(mac = mac, name = name, connected = connected)
        }
    }
}

/**
 * Gerencia o gatilho automático de ignição pelo Bluetooth do carro (ex: Taramps)
 * e controla o modo Standby OLED ao desligar a chave após o delay configurado.
 */
class IgnitionBluetoothMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val onIgnitionTurnedOn: (deviceLabel: String) -> Unit,
    private val onIgnitionStandbyEntered: () -> Unit
) {
    private val _status = MutableStateFlow(IgnitionStatus())
    val status: StateFlow<IgnitionStatus> = _status.asStateFlow()

    private var standbyTimerJob: Job? = null
    private var lastIgnitionMacOrKey: String = ""
    private var lastIgnitionConnected: Boolean? = null
    private var lastIgnitionEventMs: Long = 0L

    fun start() = Unit

    fun stop() {
        standbyTimerJob?.cancel()
    }

    fun handleBluetoothEvent(
        mac: String,
        name: String,
        connected: Boolean,
        config: HeadUnitConfig
    ) {
        if (!config.ignitionTriggerEnabled) return
        if (!matchesConfiguredDevice(mac, name, config)) return

        val key = mac.ifBlank { name }.uppercase()
        val now = System.currentTimeMillis()
        if (lastIgnitionConnected == connected &&
            lastIgnitionMacOrKey == key &&
            now - lastIgnitionEventMs < 3_000L
        ) {
            return
        }
        lastIgnitionConnected = connected
        lastIgnitionMacOrKey = key
        lastIgnitionEventMs = now

        val label = name.ifBlank { mac.ifBlank { config.ignitionBtName } }
        if (connected) {
            standbyTimerJob?.cancel()
            standbyTimerJob = null
            wakeTabletScreen()
            _status.value = IgnitionStatus(
                ignitionOn = true,
                standbyActive = false,
                standbyCountdownSec = null,
                triggerDeviceLabel = "$label (Chave Ligada)"
            )
            onIgnitionTurnedOn(label)
        } else {
            scheduleStandby(label, config.ignitionOffStandbyDelaySec)
        }
    }

    fun syncFromAudioRoute(routeConnected: Boolean, routeLabel: String, config: HeadUnitConfig) {
        if (!config.ignitionTriggerEnabled) return
        val keyword = config.ignitionBtName.trim()
        val matches = keyword.isNotBlank() && routeLabel.contains(keyword, ignoreCase = true)
        if (routeConnected && matches && _status.value.standbyActive) {
            handleBluetoothEvent(config.ignitionBtMac, routeLabel, true, config)
        } else if (routeConnected && matches) {
            _status.value = _status.value.copy(
                ignitionOn = true,
                standbyActive = false,
                triggerDeviceLabel = "$routeLabel (Conectado)"
            )
        }
    }

    fun enterStandbyNow() {
        standbyTimerJob?.cancel()
        _status.value = _status.value.copy(
            ignitionOn = false,
            standbyActive = true,
            standbyCountdownSec = null,
            triggerDeviceLabel = "Standby (Toque ou ligue o carro)"
        )
        onIgnitionStandbyEntered()
    }

    fun wakeFromStandbyManually() {
        standbyTimerJob?.cancel()
        wakeTabletScreen()
        _status.value = _status.value.copy(
            ignitionOn = true,
            standbyActive = false,
            standbyCountdownSec = null,
            triggerDeviceLabel = "Ativo manualmente"
        )
        onIgnitionTurnedOn("Despertar manual")
    }

    private fun scheduleStandby(label: String, delaySec: Int) {
        standbyTimerJob?.cancel()
        standbyTimerJob = scope.launch {
            for (remaining in delaySec downTo 1) {
                _status.value = IgnitionStatus(
                    ignitionOn = false,
                    standbyActive = false,
                    standbyCountdownSec = remaining,
                    triggerDeviceLabel = "$label desligou (${remaining}s)"
                )
                delay(1_000L)
            }
            enterStandbyNow()
        }
    }

    private fun matchesConfiguredDevice(mac: String, name: String, config: HeadUnitConfig): Boolean {
        val targetMac = config.ignitionBtMac.trim()
        if (targetMac.isNotBlank() && mac.equals(targetMac, ignoreCase = true)) {
            return true
        }
        val keyword = config.ignitionBtName.trim()
        return keyword.isNotBlank() && name.contains(keyword, ignoreCase = true)
    }

    @SuppressLint("InvalidWakeLockTag")
    @Suppress("DEPRECATION")
    private fun wakeTabletScreen() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HondaDeck:IgnitionWake"
            )
            wl.acquire(2_500L)
        }
    }
}
