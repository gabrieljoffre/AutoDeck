package com.autodeck.headunit.ui

import android.app.Application
import android.content.Context
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.service.HeadUnitRuntime
import com.autodeck.headunit.service.HeadUnitUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel enxuto da Activity principal: coordena ciclo de vida, assistente inicial,
 * autorização Spotify, câmera de ré e alertas críticos sobre a WebView.
 * Toda a interação das abas principais acontece via `WebBridge` direto no `HeadUnitRuntime`.
 */
class HeadUnitViewModel(application: Application) : AndroidViewModel(application) {

    val runtime: HeadUnitRuntime = HeadUnitRuntime.get(application)
    val uiState: StateFlow<HeadUnitUiState> = runtime.uiState

    fun setAppForeground(inForeground: Boolean) = runtime.setAppForeground(inForeground)
    fun onPermissionsUpdated() = runtime.onPermissionsUpdated()
    fun completeSetup(newConfig: HeadUnitConfig) = runtime.completeSetup(newConfig)
    fun updateConfig(transform: (HeadUnitConfig) -> HeadUnitConfig) = runtime.updateConfig(transform)
    fun setLauncherModeEnabled(enabled: Boolean) = runtime.setLauncherModeEnabled(enabled)
    fun dismissCurrentAlert(title: String) = runtime.dismissCurrentAlert(title)

    // Ignição & Standby
    fun wakeFromStandbyManually() = runtime.wakeFromStandbyManually()

    // Spotify OAuth & App Remote
    fun connectSpotify(activityContext: Context) = runtime.connectSpotify(activityContext)
    fun onSpotifyToken(token: String, expiresInSec: Int) = runtime.onSpotifyToken(token, expiresInSec)
    fun setSpotifyAuthRequester(requester: (() -> Unit)?) = runtime.setSpotifyAuthRequester(requester)
    fun setCalendarPermissionRequester(requester: (() -> Unit)?) = runtime.setCalendarPermissionRequester(requester)
    fun onSpotifyAuthorized(activityContext: Context) = runtime.onSpotifyAuthorized(activityContext)
    fun onSpotifyAuthFailed(reason: String) = runtime.onSpotifyAuthFailed(reason)

    // Câmera de Ré & USB
    fun exitManualCameraPreview() = runtime.exitManualCameraPreview()
    fun toggleSimulatedReverse() = runtime.toggleSimulatedReverse()
    fun calibrateCameraNoSignal(): Boolean = runtime.calibrateCameraNoSignal()
    fun attachCameraSurface(surface: Surface?) = runtime.attachCameraSurface(surface)
    fun selectCameraChannel(channel: Int) = runtime.selectCameraChannel(channel)
    fun setCameraStandardNtsc(isNtsc: Boolean) = runtime.setCameraStandardNtsc(isNtsc)
    fun setCameraStandardMode(mode: com.autodeck.protocol.camera.CameraVideoStandardMode) =
        runtime.setCameraStandardMode(mode)
    fun refreshUsbDevices() = runtime.refreshUsbDevices()
}
