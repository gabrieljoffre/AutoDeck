package com.autodeck.hondadeck.ponte

import com.autodeck.protocol.phone.PhoneCallInfo
import com.autodeck.protocol.phone.PhoneLinkProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PontePermissionStatus(
    val contacts: Boolean = false,
    val phone: Boolean = false,
    val postNotifications: Boolean = true,
    val notificationListener: Boolean = false,
    val accessibility: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false
) {
    val allEssentialGranted: Boolean
        get() = contacts && phone && notificationListener && accessibility
}

data class PonteUiState(
    val serverRunning: Boolean = false,
    val localIp: String = "",
    val port: Int = PhoneLinkProtocol.TCP_PORT,
    val pin: String = "------",
    val pairedCount: Int = 0,
    val connectedTabletName: String? = null,
    val connectedTabletAddress: String? = null,
    val callState: PhoneCallInfo = PhoneCallInfo(),
    val lastActivity: String = "Aguardando tablet HondaDeck na rede local",
    val permissions: PontePermissionStatus = PontePermissionStatus()
) {
    val isTabletConnected: Boolean get() = !connectedTabletName.isNullOrBlank()
}

/**
 * Estado compartilhado em memória entre o serviço da Ponte, listeners e a tela principal.
 */
object PonteStateHub {
    private val _uiState = MutableStateFlow(PonteUiState())
    val uiState: StateFlow<PonteUiState> = _uiState.asStateFlow()

    fun update(transform: (PonteUiState) -> PonteUiState) {
        _uiState.update(transform)
    }

    fun logActivity(message: String) {
        _uiState.update { it.copy(lastActivity = message) }
    }
}
