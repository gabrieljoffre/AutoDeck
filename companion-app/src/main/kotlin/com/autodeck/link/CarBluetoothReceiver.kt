package com.autodeck.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Escuta passivamente quando o celular se conecta ao Bluetooth do carro (Taramps / Tablet)
 * e anuncia o IP do servidor Android Auto para o AutoDeck no tablet via UDP 5278.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!AutoDeckLinkPrefs.isAutoBluetoothEnabled(context)) return

        val hasBtPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasBtPerm) return

        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        ) ?: return

        val name = runCatching { device.name?.uppercase() ?: "" }.getOrDefault("")
        val triggers = AutoDeckLinkPrefs.getTriggerKeywords(context)
        if (name.isBlank() || triggers.none { name.contains(it) }) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repeat(3) { attempt ->
                    val res = AutoDeckLinkNetwork.announceToTablet()
                    if (res.tabletEndpoint != null) return@repeat
                    if (attempt < 2) delay(2_000L)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
