package com.autodeck.hondadeck.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.PhoneCallInfo
import com.autodeck.protocol.phone.PhoneCallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gerencia discagem, atendimento, encerramento e monitoramento do estado de chamadas
 * telefônicas do celular via `TelecomManager` e `TelephonyCallback` / `PhoneStateListener`.
 */
class PonteCallManager(
    private val context: Context,
    private val contactsManager: PonteContactsManager
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private val _callState = MutableStateFlow(PhoneCallInfo())
    val callState: StateFlow<PhoneCallInfo> = _callState.asStateFlow()

    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    private var lastKnownNumber: String = ""
    private var lastKnownName: String = ""
    private var startedMonitoring = false

    fun hasCallPermissions(): Boolean {
        val call = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val state = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val answer = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        return call && state && answer
    }

    fun startMonitoring() {
        if (startedMonitoring) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE não concedida; monitoramento de chamadas adiado.")
            return
        }
        val tm = telephonyManager ?: return
        startedMonitoring = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModernCallback(tm)
        } else {
            registerLegacyListener(tm)
        }
    }

    fun stopMonitoring() {
        if (!startedMonitoring) return
        startedMonitoring = false
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                runCatching { tm.unregisterTelephonyCallback(it) }
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
            legacyListener = null
        }
    }

    @SuppressLint("MissingPermission")
    fun dial(numberOrContact: String, contactNameHint: String = ""): Result<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("Permissão de chamadas (CALL_PHONE) não concedida no celular."))
        }
        val raw = numberOrContact.trim()
        if (raw.isEmpty()) {
            return Result.failure(IllegalArgumentException("Número ou contato não informado."))
        }

        var targetNumber = BrazilPhoneNumber.normalizeForDial(raw)
        var resolvedName = contactNameHint.trim()

        if (targetNumber == null) {
            val match = contactsManager.search(raw, limit = 1).firstOrNull()
                ?: return Result.failure(IllegalStateException("Contato \"$raw\" não encontrado na agenda do celular."))
            targetNumber = BrazilPhoneNumber.normalizeForDial(match.normalizedPhone) ?: match.normalizedPhone
            resolvedName = match.name
        } else if (resolvedName.isBlank()) {
            resolvedName = contactsManager.resolveDisplayNameByNumber(targetNumber).orEmpty()
        }

        lastKnownNumber = targetNumber
        lastKnownName = resolvedName
        _callState.value = PhoneCallInfo(
            status = PhoneCallStatus.DIALING,
            number = targetNumber,
            displayName = resolvedName.ifBlank { BrazilPhoneNumber.formatDisplay(targetNumber) },
            isWhatsApp = false,
            startedAtMs = System.currentTimeMillis()
        )
        PonteStateHub.update { it.copy(callState = _callState.value) }

        return runCatching {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(targetNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val label = resolvedName.ifBlank { BrazilPhoneNumber.formatDisplay(targetNumber) }
            "Ligando para $label."
        }
    }

    @SuppressLint("MissingPermission")
    fun answer(): Result<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("Permissão para atender chamadas não concedida no celular."))
        }
        val tm = telecomManager ?: return Result.failure(IllegalStateException("TelecomManager indisponível."))
        return runCatching {
            @Suppress("DEPRECATION")
            tm.acceptRingingCall()
            "Chamada atendida."
        }
    }

    @SuppressLint("MissingPermission")
    fun end(): Result<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("Permissão para encerrar chamadas não concedida no celular."))
        }
        val tm = telecomManager ?: return Result.failure(IllegalStateException("TelecomManager indisponível."))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Result.failure(UnsupportedOperationException("Encerrar chamada exige Android 9 ou superior."))
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val ended = tm.endCall()
            if (ended) {
                updateState(PhoneCallStatus.IDLE, "", "")
                "Chamada encerrada."
            } else {
                "Nenhuma chamada ativa para encerrar."
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleTelephonyState(state, null)
            }
        }
        telephonyCallback = cb
        runCatching {
            tm.registerTelephonyCallback(context.mainExecutor, cb)
        }.onFailure { err ->
            Log.w(TAG, "Falha ao registrar TelephonyCallback: ${err.message}")
            startedMonitoring = false
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyListener(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleTelephonyState(state, phoneNumber)
            }
        }
        legacyListener = listener
        runCatching {
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }.onFailure { err ->
            Log.w(TAG, "Falha ao registrar PhoneStateListener: ${err.message}")
            startedMonitoring = false
        }
    }

    private fun handleTelephonyState(state: Int, incomingNumber: String?) {
        val num = incomingNumber?.trim().orEmpty().ifBlank { lastKnownNumber }
        if (num.isNotBlank()) lastKnownNumber = num
        val name = if (num.isNotBlank()) {
            contactsManager.resolveDisplayNameByNumber(num) ?: lastKnownName
        } else {
            lastKnownName
        }
        if (name.isNotBlank()) lastKnownName = name

        val mapped = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> PhoneCallStatus.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallStatus.ACTIVE
            else -> PhoneCallStatus.IDLE
        }
        if (mapped == PhoneCallStatus.IDLE) {
            lastKnownNumber = ""
            lastKnownName = ""
            updateState(PhoneCallStatus.IDLE, "", "")
        } else {
            updateState(mapped, num, name)
        }
    }

    private fun updateState(status: PhoneCallStatus, number: String, displayName: String) {
        val prev = _callState.value
        val started = when {
            status == PhoneCallStatus.IDLE -> 0L
            prev.status == status && prev.startedAtMs > 0L -> prev.startedAtMs
            else -> System.currentTimeMillis()
        }
        val info = PhoneCallInfo(
            status = status,
            number = number,
            displayName = displayName.ifBlank { if (number.isNotBlank()) BrazilPhoneNumber.formatDisplay(number) else "" },
            isWhatsApp = false,
            startedAtMs = started
        )
        _callState.value = info
        PonteStateHub.update { it.copy(callState = info) }
    }

    companion object {
        private const val TAG = "PonteCallManager"
    }
}
