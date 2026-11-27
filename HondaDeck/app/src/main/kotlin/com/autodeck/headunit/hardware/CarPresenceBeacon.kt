package com.autodeck.headunit.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Sinal Bluetooth LE "o painel do carro está aqui".
 *
 * Só anuncia presença (não pareia nem conecta), então não interfere no áudio do carro.
 * A Ponte no celular procura este UUID para ligar o roteador quando chega perto do carro.
 */
class CarPresenceBeacon(private val context: Context) {

    private var callback: AdvertiseCallback? = null
    private var watching = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** O Bluetooth reiniciando derruba o anúncio: refaz ao religar e a cada 10 min. */
    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> handler.postDelayed({ restart() }, 1_500L)
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> callback = null
            }
        }
    }
    private val refresh = object : Runnable {
        override fun run() { restart(); handler.postDelayed(this, 10 * 60_000L) }
    }

    /** Começa a anunciar e mantém o anúncio vivo (reinícios do Bluetooth). */
    fun startAndWatch() {
        if (!watching) {
            watching = true
            runCatching {
                context.registerReceiver(btReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            }
            handler.postDelayed(refresh, 10 * 60_000L)
        }
        start()
    }

    fun stopWatching() {
        if (watching) {
            watching = false
            runCatching { context.unregisterReceiver(btReceiver) }
            handler.removeCallbacks(refresh)
        }
        stop()
    }

    private fun restart() { stop(); start() }

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth desligado: sinal de presença não iniciado.")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "Aparelho sem suporte a anúncio BLE.")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Sinal de presença do carro ativo.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Falha ao anunciar presença (código $errorCode).")
                callback = null
            }
        }
        callback = cb
        runCatching { advertiser.startAdvertising(settings, data, cb) }
            .onFailure { Log.w(TAG, "startAdvertising: ${it.message}"); callback = null }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    companion object {
        private const val TAG = "CarPresenceBeacon"

        /** Mesmo UUID usado pela Ponte (PonteCarProximity). */
        val SERVICE_UUID: UUID = UUID.fromString("6d0e0c41-4f8a-4c7b-9d1a-48444b434152")
    }
}
