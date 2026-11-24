package com.autodeck.headunit.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.service.CameraDebugState
import com.autodeck.headunit.service.HeadUnitRuntime
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.TransmissionType
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.views.MapView

class HeadUnitViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = HeadUnitRuntime.get(application)

    val uiState: StateFlow<HeadUnitUiState> = runtime.uiState
    val cameraDebug: StateFlow<CameraDebugState> = runtime.cameraDebug

    fun setAppForeground(inForeground: Boolean) = runtime.setAppForeground(inForeground)
    fun onPermissionsUpdated() = runtime.onPermissionsUpdated()
    fun completeSetup(newConfig: HeadUnitConfig) = runtime.completeSetup(newConfig)
    fun reopenSetup() = runtime.reopenSetup()
    fun updateConfig(transform: (HeadUnitConfig) -> HeadUnitConfig) = runtime.updateConfig(transform)
    fun setLauncherModeEnabled(enabled: Boolean) = runtime.setLauncherModeEnabled(enabled)
    fun selectTab(tab: MainTab) = runtime.selectTab(tab)
    fun dismissCurrentAlert(title: String) = runtime.dismissCurrentAlert(title)

    // Ignição & Standby
    fun enterStandbyManually() = runtime.enterStandbyManually()
    fun wakeFromStandbyManually() = runtime.wakeFromStandbyManually()

    // Música & Volume
    fun toggleMusicPlayPause() = runtime.toggleMusicPlayPause()
    fun skipMusicNext() = runtime.skipMusicNext()
    fun skipMusicPrevious() = runtime.skipMusicPrevious()
    fun seekMusicTo(posMs: Long) = runtime.seekMusicTo(posMs)
    fun openPreferredMusicApp(): Boolean = runtime.openPreferredMusicApp()
    fun setMasterVolumePct(pct: Int) = runtime.setMasterVolumePct(pct)

    // Navegação & Mapa Offline
    fun openPreferredMapApp(): Boolean = runtime.openPreferredMapApp()
    fun navigateToDestination(destination: String): Boolean = runtime.navigateToDestination(destination)
    fun downloadOfflineMap(mapView: MapView, radiusKm: Double = 8.0) =
        runtime.downloadOfflineMap(mapView, radiusKm)

    // Agente Gemini & WhatsApp
    fun triggerVoiceAgent() = runtime.triggerVoiceAgent()
    fun stopVoiceAgent() = runtime.stopVoiceAgent()
    fun sendAgentPrompt(text: String) = runtime.sendAgentPrompt(text)
    fun readLatestWhatsAppAloud() = runtime.readLatestWhatsAppAloud()
    fun replyToWhatsApp(item: WhatsAppNotificationItem, replyText: String): Boolean =
        runtime.replyToWhatsApp(item, replyText)
    fun dismissWhatsApp(key: String) = runtime.dismissWhatsApp(key)
    fun launchWhatsApp(): Boolean = runtime.launchWhatsApp()

    // Câmera de Ré
    fun toggleManualCameraPreview() = runtime.toggleManualCameraPreview()
    fun exitManualCameraPreview() = runtime.exitManualCameraPreview()
    fun toggleSimulatedReverse() = runtime.toggleSimulatedReverse()
    fun calibrateCameraNoSignal(): Boolean = runtime.calibrateCameraNoSignal()
    fun clearNoSignalCalibration() = runtime.clearNoSignalCalibration()
    fun attachCameraSurface(surface: Surface?) = runtime.attachCameraSurface(surface)
    fun selectCameraChannel(channel: Int) = runtime.selectCameraChannel(channel)
    fun setCameraStandardNtsc(isNtsc: Boolean) = runtime.setCameraStandardNtsc(isNtsc)

    // OBD-II & USB
    fun connectObdNow() = runtime.connectObdNow()
    fun disconnectObd() = runtime.disconnectObd()
    fun readDtcCodes() = runtime.readDtcCodes()
    fun clearDtcCodes() = runtime.clearDtcCodes()
    fun resetTripConsumption() = runtime.resetTripConsumption()
    fun markTankFull() = runtime.markTankFull()
    fun addFuelLiters(liters: Double) = runtime.addFuelLiters(liters)
    fun logRefuel(liters: Double, pricePerLiter: Double?, odometerKm: Double?, fullTank: Boolean): String =
        runtime.logRefuel(liters, pricePerLiter, odometerKm, fullTank)
    fun deleteFuelEntry(timestampMs: Long) = runtime.deleteFuelEntry(timestampMs)
    fun setVirtualTankLiters(liters: Double) = runtime.setVirtualTankLiters(liters)
    fun setTransmissionType(type: TransmissionType) = runtime.setTransmissionType(type)
    fun refreshUsbDevices() = runtime.refreshUsbDevices()
    fun requestUsbPermission(deviceName: String) = runtime.requestUsbPermission(deviceName)
}
