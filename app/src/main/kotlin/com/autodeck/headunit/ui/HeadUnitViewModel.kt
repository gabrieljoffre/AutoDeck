package com.autodeck.headunit.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.service.CameraDebugState
import com.autodeck.headunit.service.HeadUnitRuntime
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.protocol.carplay.MultiTouchPoint
import com.autodeck.protocol.carplay.TouchAction
import com.autodeck.protocol.coordinator.MainTab
import kotlinx.coroutines.flow.StateFlow

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
    fun toggleManualCameraPreview() = runtime.toggleManualCameraPreview()
    fun exitManualCameraPreview() = runtime.exitManualCameraPreview()
    fun toggleSimulatedReverse() = runtime.toggleSimulatedReverse()
    fun calibrateCameraNoSignal(): Boolean = runtime.calibrateCameraNoSignal()
    fun clearNoSignalCalibration() = runtime.clearNoSignalCalibration()
    fun connectObdNow() = runtime.connectObdNow()
    fun disconnectObd() = runtime.disconnectObd()
    fun readDtcCodes() = runtime.readDtcCodes()
    fun clearDtcCodes() = runtime.clearDtcCodes()
    fun resetTripConsumption() = runtime.resetTripConsumption()
    fun refreshUsbDevices() = runtime.refreshUsbDevices()
    fun requestUsbPermission(deviceName: String) = runtime.requestUsbPermission(deviceName)
    fun attachProjectionSurface(surface: Surface?, width: Int, height: Int) =
        runtime.attachProjectionSurface(surface, width, height)
    fun attachCameraSurface(surface: Surface?) = runtime.attachCameraSurface(surface)
    fun selectCameraChannel(channel: Int) = runtime.selectCameraChannel(channel)
    fun setCameraStandardNtsc(isNtsc: Boolean) = runtime.setCameraStandardNtsc(isNtsc)
    fun sendProjectionTouch(action: TouchAction, normalizedX: Float, normalizedY: Float) =
        runtime.sendProjectionTouch(action, normalizedX, normalizedY)
    fun sendProjectionMultiTouch(points: List<MultiTouchPoint>) =
        runtime.sendProjectionMultiTouch(points)
    fun launchHurFallback(): Boolean = runtime.launchHurFallback()
}
