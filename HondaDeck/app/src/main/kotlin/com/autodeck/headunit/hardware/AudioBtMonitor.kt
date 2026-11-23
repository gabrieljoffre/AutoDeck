package com.autodeck.headunit.hardware

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.autodeck.protocol.coordinator.LinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRouteStatus(
    val linkStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val routeLabel: String = "Processador Taramps (Aguardando)",
    val isDucking: Boolean = false
)

/**
 * Monitora passivamente a saída Bluetooth A2DP / USB para o processador de áudio Taramps
 * sem nunca disparar scans Bluetooth, e aplica ducking suave durante a marcha ré.
 */
class AudioBtMonitor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _status = MutableStateFlow(AudioRouteStatus())
    val status: StateFlow<AudioRouteStatus> = _status.asStateFlow()

    private var registered = false
    private var duckFocusRequest: AudioFocusRequest? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                    val deviceName = runCatching {
                        if (BluetoothDirectory.hasConnectPermission(context)) device?.name else null
                    }.getOrNull()
                    refreshRoute(overrideName = deviceName, a2dpState = state)
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> refreshRoute()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        refreshRoute()
    }

    fun stop() {
        releaseReverseDucking()
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refreshRoute(overrideName: String? = null, a2dpState: Int? = null) {
        val manager = audioManager
        if (manager == null) {
            _status.value = AudioRouteStatus()
            return
        }

        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val usbAudio = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val status = when {
            a2dpState == BluetoothProfile.STATE_CONNECTING -> LinkStatus.CONNECTING
            btDevice != null || usbAudio != null -> LinkStatus.CONNECTED
            else -> LinkStatus.DISCONNECTED
        }

        val label = when {
            !overrideName.isNullOrBlank() && status == LinkStatus.CONNECTED -> overrideName
            btDevice != null -> btDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth A2DP (Taramps)"
            usbAudio != null -> usbAudio.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Transceptor 2.4 / USB"
            else -> "Alto-falante do tablet (Sem BT/2.4)"
        }

        _status.value = _status.value.copy(linkStatus = status, routeLabel = label)
    }

    fun requestReverseDucking() {
        val manager = audioManager ?: return
        if (duckFocusRequest != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()

        if (manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            duckFocusRequest = req
            _status.value = _status.value.copy(isDucking = true)
        }
    }

    fun releaseReverseDucking() {
        val manager = audioManager ?: return
        val req = duckFocusRequest ?: return
        manager.abandonAudioFocusRequest(req)
        duckFocusRequest = null
        _status.value = _status.value.copy(isDucking = false)
    }
}
