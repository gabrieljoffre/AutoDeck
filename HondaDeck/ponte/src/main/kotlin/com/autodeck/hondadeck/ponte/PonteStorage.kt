package com.autodeck.hondadeck.ponte

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.UUID

/**
 * Armazenamento local da Ponte: PIN de 6 dígitos para pareamento, tokens autorizados
 * e identificador estável do celular.
 */
class PonteStorage(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, created) }
        return created
    }

    fun getPhoneDisplayName(): String {
        val model = listOfNotNull(
            Build.MANUFACTURER?.trim()?.takeIf { it.isNotEmpty() },
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(" ")
        return model.ifBlank { "Celular HondaDeck" }
    }

    fun getOrCreatePin(): String {
        val saved = prefs.getString(KEY_PIN, null)
        if (saved != null && saved.length == 6 && saved.all { it.isDigit() }) {
            return saved
        }
        return regeneratePin()
    }

    fun regeneratePin(): String {
        val pin = String.format("%06d", rng.nextInt(1_000_000))
        prefs.edit { putString(KEY_PIN, pin) }
        return pin
    }

    fun verifyPin(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.length != 6 || !clean.all { it.isDigit() }) return false
        return clean == getOrCreatePin()
    }

    fun issueToken(clientName: String): String {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val updated = getPairedTokens().toMutableSet()
        updated += token
        prefs.edit {
            putStringSet(KEY_TOKENS, updated)
            putString(KEY_LAST_CLIENT_NAME, clientName.ifBlank { "HondaDeck Tablet" })
        }
        return token
    }

    fun isValidToken(token: String?): Boolean {
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return clean in getPairedTokens()
    }

    fun getPairedTokens(): Set<String> =
        prefs.getStringSet(KEY_TOKENS, emptySet())?.toSet().orEmpty()

    fun pairedCount(): Int = getPairedTokens().size

    fun clearPairedTokens() {
        prefs.edit {
            remove(KEY_TOKENS)
            remove(KEY_LAST_CLIENT_NAME)
        }
    }

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }

    private companion object {
        const val PREFS_NAME = "hondadeck_ponte_prefs"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PIN = "pairing_pin"
        const val KEY_TOKENS = "paired_tokens"
        const val KEY_LAST_CLIENT_NAME = "last_client_name"
        const val KEY_SERVICE_ENABLED = "service_enabled"
    }
}
