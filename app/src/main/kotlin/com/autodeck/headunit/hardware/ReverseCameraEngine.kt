package com.autodeck.headunit.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import androidx.core.content.ContextCompat
import com.autodeck.protocol.camera.LumaGrid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

enum class CameraSourceMode {
    SIMULATED,
    USB_CONVERTER
}

data class CameraHardwareStatus(
    val mode: CameraSourceMode = CameraSourceMode.SIMULATED,
    val connectedDeviceLabel: String = "Modo Simulador",
    val isUvcCompatible: Boolean = true,
    val needsNativeEasyCapDriver: Boolean = false,
    val needsCameraPermission: Boolean = false,
    val hasValidFrameStream: Boolean = true
)

interface CameraFrameSource {
    val hardwareStatus: Flow<CameraHardwareStatus>
    fun lumaSamples(intervalMs: Long = 140L): Flow<LumaGrid>
}

/**
 * Simulador de conversor multi-RCA -> USB para testar standby estático (tela cinza/preta
 * igual USB Camera Pro) e cena real com ruído temporal ao engatar a ré.
 */
class SimulatedCameraSource : CameraFrameSource {

    @Volatile
    var simulatedReverseActive: Boolean = false

    private val _hardwareStatus = MutableStateFlow(
        CameraHardwareStatus(
            mode = CameraSourceMode.SIMULATED,
            connectedDeviceLabel = "Simulador Conversor RCA->USB",
            isUvcCompatible = true,
            needsNativeEasyCapDriver = false,
            needsCameraPermission = false,
            hasValidFrameStream = true
        )
    )
    override val hardwareStatus: Flow<CameraHardwareStatus> = _hardwareStatus.asStateFlow()

    override fun lumaSamples(intervalMs: Long): Flow<LumaGrid> = flow {
        var tick = 0
        while (true) {
            tick++
            val grid = if (!simulatedReverseActive) {
                // Tela cinza uniforme 100% estática (standby igual USB Camera Pro)
                LumaGrid(width = 16, height = 12, luma = IntArray(192) { 42 })
            } else {
                // Cena dinâmica de câmera real com variação espacial e temporal
                LumaGrid(
                    width = 16,
                    height = 12,
                    luma = IntArray(192) { idx ->
                        val row = idx / 16
                        val col = idx % 16
                        ((row * 18 + (col + tick) * 13) % 190) + 30
                    }
                )
            }
            emit(grid)
            delay(intervalMs)
        }
    }
}

/**
 * Contrato de captura USB para conversores multi-RCA -> USB.
 * Verifica permissão CAMERA (obrigatória no Android 10+ para USB class 0x0E) e
 * identifica UVC (MS2109) vs EasyCap proprietário (UTV007/STK1160) via UsbHubCoordinator.
 */
class UvcUsbCameraSource(
    private val context: Context,
    private val usbHubCoordinator: UsbHubCoordinator
) : CameraFrameSource {

    private val _hardwareStatus = MutableStateFlow(
        CameraHardwareStatus(
            mode = CameraSourceMode.USB_CONVERTER,
            connectedDeviceLabel = "Nenhum conversor USB conectado",
            isUvcCompatible = false,
            needsNativeEasyCapDriver = false,
            needsCameraPermission = false,
            hasValidFrameStream = false
        )
    )
    override val hardwareStatus: Flow<CameraHardwareStatus> = _hardwareStatus.asStateFlow()

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshFromUsb(): UsbDevice? {
        val camPermOk = hasCameraPermission()
        val device = usbHubCoordinator.findCameraDevice()
        if (device == null) {
            _hardwareStatus.value = CameraHardwareStatus(
                mode = CameraSourceMode.USB_CONVERTER,
                connectedDeviceLabel = "Aguardando conversor RCA->USB no Hub",
                isUvcCompatible = false,
                needsNativeEasyCapDriver = false,
                needsCameraPermission = !camPermOk,
                hasValidFrameStream = false
            )
            return null
        }

        val summary = usbHubCoordinator.devices.value.firstOrNull { it.deviceName == device.deviceName }
        val isUvc = summary?.isUvcCompliant == true || summary?.role == UsbDeviceRole.CAMERA_UVC
        val isLegacy = summary?.role == UsbDeviceRole.CAMERA_EASYCAP_LEGACY

        _hardwareStatus.value = CameraHardwareStatus(
            mode = CameraSourceMode.USB_CONVERTER,
            connectedDeviceLabel = "${device.productName ?: "Conversor"} (${summary?.vidPidHex ?: ""})",
            isUvcCompatible = isUvc,
            needsNativeEasyCapDriver = isLegacy,
            needsCameraPermission = !camPermOk,
            hasValidFrameStream = false
        )
        return device
    }

    override fun lumaSamples(intervalMs: Long): Flow<LumaGrid> = flow {
        while (true) {
            refreshFromUsb()
            // Grade vazia enquanto o stream nativo libuvc/usbtv007 ainda não está acoplado:
            // impede falso disparo de ré e impede calibrar em cima de um frame fictício (I10).
            emit(LumaGrid(width = 0, height = 0, luma = IntArray(0)))
            delay(intervalMs)
        }
    }
}
