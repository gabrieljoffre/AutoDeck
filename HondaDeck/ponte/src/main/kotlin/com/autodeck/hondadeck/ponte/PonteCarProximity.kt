package com.autodeck.hondadeck.ponte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Liga o roteador do celular quando o sinal BLE do painel do carro aparece por perto
 * e desliga quando ele some por [LOST_AFTER_MS]. Não pareia nem conecta: só escuta.
 *
 * Ligar/desligar o roteador é feito pela acessibilidade (Configurações rápidas),
 * porque o Android não permite que apps comuns liguem o roteador diretamente.
 */
class PonteCarProximity(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var scanning = false

    @Volatile var lastSeenMs: Long = 0L
        private set
    @Volatile var lastRssi: Int = 0
        private set

    /** O roteador foi ligado por nós (só desligamos o que ligamos). */
    private var hotspotOnByUs = false
    private var carPresent = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onSeen(result.rssi)
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.maxByOrNull { it.rssi }?.let { onSeen(it.rssi) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Busca BLE falhou (código $errorCode).")
            scanning = false
        }
    }

    private var watching = false
    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_ON) {
                main.postDelayed({ restartScan() }, 1_500L)
            }
        }
    }
    /** O Android rebaixa buscas longas: refaz a cada 10 min. */
    private val refresh = object : Runnable {
        override fun run() { restartScan(); main.postDelayed(this, 10 * 60_000L) }
    }

    fun startAndWatch() {
        if (!watching) {
            watching = true
            runCatching {
                context.registerReceiver(btReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            }
            main.postDelayed(refresh, 10 * 60_000L)
        }
        start()
    }

    fun stopWatching() {
        if (watching) {
            watching = false
            runCatching { context.unregisterReceiver(btReceiver) }
            main.removeCallbacks(refresh)
        }
        stop()
    }

    private fun restartScan() { stop(); start() }

    private val lostCheck = object : Runnable {
        override fun run() {
            if (carPresent && System.currentTimeMillis() - lastSeenMs > LOST_AFTER_MS) {
                carPresent = false
                Log.i(TAG, "Painel do carro fora de alcance.")
                PonteStateHub.logActivity("Carro longe: desligando o roteador")
                if (hotspotOnByUs) worker.execute { setHotspot(false) }
            }
            main.postDelayed(this, 15_000L)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "Bluetooth desligado: sem detecção do carro.")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onSuccess {
                scanning = true
                Log.i(TAG, "Procurando o painel do carro por BLE.")
                main.removeCallbacks(lostCheck)
                main.postDelayed(lostCheck, 15_000L)
            }
            .onFailure { Log.w(TAG, "startScan: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacks(lostCheck)
        if (!scanning) return
        scanning = false
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(callback) }
    }

    private fun onSeen(rssi: Int) {
        lastSeenMs = System.currentTimeMillis()
        lastRssi = rssi
        if (!carPresent && rssi >= RSSI_NEAR) {
            carPresent = true
            Log.i(TAG, "Painel do carro detectado (RSSI $rssi): ligando o roteador.")
            PonteStateHub.logActivity("Carro por perto: ligando o roteador")
            worker.execute { setHotspot(true) }
        }
    }

    /**
     * Com o celular bloqueado a Samsung pede o PIN para mexer no roteador:
     * nesse caso espera o próximo desbloqueio e liga na hora.
     */
    @Volatile private var pendingOnUnlock: Boolean? = null
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val want = pendingOnUnlock ?: return
            pendingOnUnlock = null
            if (want && !carPresent) return
            Log.i(TAG, "Celular desbloqueado: aplicando roteador=${want}.")
            main.postDelayed({ worker.execute { setHotspot(want) } }, 1_200L)
        }
    }
    private var unlockReceiverRegistered = false

    private fun isLocked(): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun setHotspot(on: Boolean, attempt: Int = 0) {
        if (isLocked()) {
            pendingOnUnlock = on
            if (!unlockReceiverRegistered) {
                runCatching {
                    context.registerReceiver(unlockReceiver, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
                    unlockReceiverRegistered = true
                }
            }
            Log.i(TAG, "Celular bloqueado: roteador=${on} fica para o próximo desbloqueio.")
            PonteStateHub.logActivity(if (on) "Carro por perto: o roteador liga ao desbloquear" else "Carro longe: o roteador desliga ao desbloquear")
            return
        }
        val svc = PonteAccessibilityService.instance
        val result = svc?.setQuickSettingsToggles(on) ?: PonteAccessibilityService.ToggleResult.UNAVAILABLE
        Log.i(TAG, "Roteador ${if (on) "ligar" else "desligar"}: $result (tentativa ${attempt + 1})")
        when (result) {
            PonteAccessibilityService.ToggleResult.DONE -> hotspotOnByUs = on
            PonteAccessibilityService.ToggleResult.ALREADY -> if (!on) hotspotOnByUs = false
            else -> if (attempt < 3) main.postDelayed({ worker.execute { setHotspot(on, attempt + 1) } }, 20_000L)
        }
    }

    companion object {
        private const val TAG = "PonteCarProximity"
        /** Mesmo UUID do CarPresenceBeacon do tablet. */
        val SERVICE_UUID: UUID = UUID.fromString("6d0e0c41-4f8a-4c7b-9d1a-48444b434152")
        /** Sinal mínimo para considerar "perto" (~5–10 m em ambiente aberto). */
        const val RSSI_NEAR = -85
        const val LOST_AFTER_MS = 120_000L
    }
}
