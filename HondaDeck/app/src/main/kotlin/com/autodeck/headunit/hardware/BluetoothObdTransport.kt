package com.autodeck.headunit.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sin

data class PairedBluetoothDevice(
    val name: String,
    val macAddress: String
)

interface ObdTransport {
    val isConnected: Boolean
    suspend fun connect(): Result<Unit>
    suspend fun sendCommand(command: String, timeoutMs: Long = 1_500L): String
    suspend fun disconnect()
}

object BluetoothDirectory {
    fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Lista apenas dispositivos JÁ PAREADOS no Android.
     * Nunca dispara startDiscovery() para não interferir no Bluetooth A2DP de áudio.
     */
    @SuppressLint("MissingPermission")
    fun listBondedDevices(context: Context): List<PairedBluetoothDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter = manager?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .map { PairedBluetoothDevice(name = it.name ?: "Dispositivo Bluetooth", macAddress = it.address) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }
}

/**
 * Transporte RFCOMM/SPP direto no MAC salvo do ELM327 azulzinho (v1.5 / v2.1).
 * Usa Mutex para serializar polling e leitura/limpeza DTC, drena lixo residual antes
 * de enviar comandos e tenta 3 métodos de abertura RFCOMM para clones v2.1.
 */
class BluetoothClassicObdTransport(
    private val context: Context,
    private val macAddress: String
) : ObdTransport {

    private val ioMutex = Mutex()
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BluetoothDirectory.hasConnectPermission(context)) {
            return@withContext Result.failure(SecurityException("Permissão BLUETOOTH_CONNECT necessária"))
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth indisponível neste tablet"))

        runCatching {
            disconnect()
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val device = adapter.getRemoteDevice(macAddress)
            val rfcomm = openRfcommWithFallback(device)
            socket = rfcomm
            input = rfcomm.inputStream
            output = rfcomm.outputStream
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRfcommWithFallback(device: BluetoothDevice): BluetoothSocket {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                @Suppress("DiscouragedPrivateApi")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        )

        var lastError: Throwable? = null
        for (creator in attempts) {
            var candidate: BluetoothSocket? = null
            try {
                candidate = creator()
                candidate.connect()
                return candidate
            } catch (t: Throwable) {
                lastError = t
                runCatching { candidate?.close() }
            }
        }
        throw lastError ?: IOException("Falha ao abrir socket RFCOMM com ELM327")
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = ioMutex.withLock {
        withContext(Dispatchers.IO) {
            val outStream = output ?: throw IOException("OBD-II desconectado")
            val inStream = input ?: throw IOException("OBD-II desconectado")

            // Drena resíduos de respostas atrasadas antes de enviar o próximo comando
            val drainBuf = ByteArray(128)
            while (inStream.available() > 0) {
                inStream.read(drainBuf)
            }

            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            outStream.write(payload)
            outStream.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(128)

            while (System.currentTimeMillis() < deadline) {
                if (inStream.available() > 0) {
                    val read = inStream.read(buffer)
                    if (read > 0) {
                        val chunk = String(buffer, 0, read, Charsets.US_ASCII)
                        sb.append(chunk)
                        if (chunk.contains('>')) break
                    }
                } else {
                    delay(3)
                }
            }
            sb.toString()
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Simulador realista de ELM327 em formato hexadecimal SAE J1979 para testes de bancada.
 */
class SimulatedObdTransport : ObdTransport {
    private val ioMutex = Mutex()
    private var connected = false
    private var startTimeMs = 0L
    private var dtcActive = true

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect(): Result<Unit> {
        delay(250)
        startTimeMs = System.currentTimeMillis()
        connected = true
        return Result.success(Unit)
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = ioMutex.withLock {
        delay(12)
        val rawCmd = command.trim().uppercase()
        val cmd = if (rawCmd.length == 5 && rawCmd.startsWith("01") && rawCmd.endsWith("1")) {
            rawCmd.substring(0, 4)
        } else {
            rawCmd
        }
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000.0).coerceAtLeast(0.0)
        val wave = (sin(elapsedSec * 0.45) + 1.0) / 2.0

        when {
            cmd == "ATZ" -> "ELM327 v1.5 (SIM)\r>"
            cmd == "ATE0" || cmd == "ATL0" || cmd == "ATS0" || cmd == "ATH0" || cmd == "ATAT1" || cmd.startsWith("ATSP") -> "OK\r>"
            cmd == "ATDPN" -> "A6\r>"
            cmd == "ATRV" -> {
                val volts = 13.9 + wave * 0.4
                "${"%.1f".format(volts).replace(',', '.')}V\r>"
            }
            cmd == "0100" -> "41 00 BE 3F A8 13\r>" // Suporta MAP (0B) + MAF (10)
            cmd == "0101" -> if (dtcActive) "41 01 82 07 65 04\r>" else "41 01 00 07 65 04\r>"
            cmd == "0105" -> {
                val tempC = (65 + (elapsedSec * 0.8).coerceAtMost(26.0) + wave * 3.0).toInt()
                "41 05 %02X\r>".format(tempC + 40)
            }
            cmd == "010C" -> {
                val rpm = (820 + wave * 2350).toInt()
                val raw = rpm * 4
                "41 0C %02X %02X\r>".format((raw ushr 8) and 0xFF, raw and 0xFF)
            }
            cmd == "010D" -> {
                val speed = (28 + wave * 64).toInt()
                "41 0D %02X\r>".format(speed)
            }
            cmd == "010B" -> {
                val map = (34 + wave * 52).toInt()
                "41 0B %02X\r>".format(map)
            }
            cmd == "0110" -> {
                val maf = ((4.5 + wave * 16.0) * 100).toInt()
                "41 10 %02X %02X\r>".format((maf ushr 8) and 0xFF, maf and 0xFF)
            }
            cmd == "0111" -> {
                val tps = ((14.0 + wave * 42.0) * 255.0 / 100.0).toInt()
                "41 11 %02X\r>".format(tps)
            }
            cmd == "0104" -> {
                val load = ((22.0 + wave * 50.0) * 255.0 / 100.0).toInt()
                "41 04 %02X\r>".format(load)
            }
            cmd == "010F" -> "41 0F %02X\r>".format(34 + 40)
            cmd == "010E" -> "41 0E %02X\r>".format(((14.0 + 64.0) * 2.0).toInt())
            cmd == "0106" -> "41 06 %02X\r>".format(130)
            cmd == "0107" -> "41 07 %02X\r>".format(129)
            cmd == "012F" -> "41 2F %02X\r>".format((68.0 * 255.0 / 100.0).toInt())
            cmd == "0142" -> "41 42 37 14\r>"
            cmd == "03" -> if (dtcActive) "43 04 43 01 71 00 00\r>" else "43 00 00\r>"
            cmd == "07" -> if (dtcActive) "47 03 00 00 00\r>" else "47 00 00\r>"
            cmd == "0A" -> "4A 00 00\r>"
            cmd == "04" -> {
                dtcActive = false
                "44\r>"
            }
            else -> "NO DATA\r>"
        }
    }

    override suspend fun disconnect() {
        connected = false
    }
}
