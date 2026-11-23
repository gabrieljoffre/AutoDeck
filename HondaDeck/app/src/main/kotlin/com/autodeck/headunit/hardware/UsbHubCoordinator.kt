package com.autodeck.headunit.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

enum class UsbDeviceRole(val labelPt: String) {
    CAMERA_UVC("Conversor de Vídeo UVC Padrão"),
    CAMERA_EASYCAP_LEGACY("Conversor EasyCap Proprietário"),
    USB_AUDIO("Interface de Áudio USB"),
    OTHER("Outro Dispositivo USB")
}

data class UsbDeviceSummary(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val role: UsbDeviceRole,
    val isUvcCompliant: Boolean,
    val hasPermission: Boolean,
    val interfaceSummary: String
) {
    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)
}

/**
 * Coordena o barramento USB-C do tablet: classifica conversores de câmera (UVC/EasyCap)
 * e interfaces de áudio USB e serializa os diálogos de permissão.
 */
class UsbHubCoordinator(
    private val context: Context,
    private val onCameraAttached: () -> Unit = {},
    private val onCameraDetached: () -> Unit = {}
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _devices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
    val devices: StateFlow<List<UsbDeviceSummary>> = _devices.asStateFlow()

    private val permissionQueue = ArrayDeque<String>()
    private var permissionInFlightDevice: String? = null
    private var registered = false

    private val systemUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices(requestMissingPermissions = true)
                    if (device != null && isCameraRole(classifyRole(device))) {
                        onCameraAttached()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && isCameraRole(classifyRole(device))) {
                        onCameraDetached()
                    }
                    refreshDevices(requestMissingPermissions = false)
                }
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            permissionInFlightDevice = null

            refreshDevices(requestMissingPermissions = false)

            if (granted && device != null && isCameraRole(classifyRole(device))) {
                onCameraAttached()
            }
            processNextPermission()
        }
    }

    fun start() {
        if (registered) return
        val systemFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            systemUsbReceiver,
            systemFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        refreshDevices(requestMissingPermissions = true)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(systemUsbReceiver) }
        runCatching { context.unregisterReceiver(permissionReceiver) }
        registered = false
    }

    fun refreshDevices(requestMissingPermissions: Boolean = false) {
        val manager = usbManager ?: return
        val list = manager.deviceList.values.map { device ->
            val role = classifyRole(device)
            val hasPerm = manager.hasPermission(device)
            val interfaces = buildString {
                for (i in 0 until device.interfaceCount) {
                    if (i > 0) append(", ")
                    val intf = device.getInterface(i)
                    append("cls=${intf.interfaceClass}/sub=${intf.interfaceSubclass}")
                }
            }

            if (requestMissingPermissions && !hasPerm && role != UsbDeviceRole.OTHER) {
                enqueuePermission(device.deviceName)
            }

            UsbDeviceSummary(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName,
                manufacturerName = device.manufacturerName,
                role = role,
                isUvcCompliant = hasUvcInterface(device),
                hasPermission = hasPerm,
                interfaceSummary = interfaces.ifBlank { "sem interfaces" }
            )
        }.sortedBy { it.role.ordinal }

        _devices.value = list
        if (requestMissingPermissions) {
            processNextPermission()
        }
    }

    fun requestPermissionForDevice(deviceName: String) {
        enqueuePermission(deviceName)
        processNextPermission()
    }

    fun findCameraDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull { isCameraRole(classifyRole(it)) }
    }

    fun getManager(): UsbManager? = usbManager

    private fun enqueuePermission(deviceName: String) {
        if (permissionInFlightDevice != deviceName && !permissionQueue.contains(deviceName)) {
            permissionQueue.addLast(deviceName)
        }
    }

    private fun processNextPermission() {
        val manager = usbManager ?: return
        if (permissionInFlightDevice != null) return

        while (permissionQueue.isNotEmpty()) {
            val nextName = permissionQueue.removeFirst()
            val device = manager.deviceList[nextName] ?: continue
            if (manager.hasPermission(device)) continue

            permissionInFlightDevice = nextName
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, nextName.hashCode(), intent, flags)
            manager.requestPermission(device, pendingIntent)
            return
        }
    }

    private fun classifyRole(device: UsbDevice): UsbDeviceRole {
        if (hasUvcInterface(device) || device.vendorId in KNOWN_UVC_CAPTURE_VIDS) {
            return UsbDeviceRole.CAMERA_UVC
        }
        if (device.vendorId in KNOWN_LEGACY_EASYCAP_VIDS) {
            return UsbDeviceRole.CAMERA_EASYCAP_LEGACY
        }
        if (hasAudioInterface(device)) {
            return UsbDeviceRole.USB_AUDIO
        }
        return UsbDeviceRole.OTHER
    }

    private fun hasUvcInterface(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun hasAudioInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun isCameraRole(role: UsbDeviceRole): Boolean {
        return role == UsbDeviceRole.CAMERA_UVC || role == UsbDeviceRole.CAMERA_EASYCAP_LEGACY
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.autodeck.hondadeck.USB_PERMISSION"
        private val KNOWN_UVC_CAPTURE_VIDS = setOf(0x534D, 0x1B3F) // MacroSilicon MS210x/MS2130
        private val KNOWN_LEGACY_EASYCAP_VIDS = setOf(0x1B71, 0x05E1, 0xEB1A, 0x1C88) // UTV007, STK1160, EM2860, Somagic
    }
}
