package com.autodeck.hondadeck.ponte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reinicia automaticamente o serviço da Ponte após o celular ligar (quando habilitado).
 */
class PonteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val storage = PonteStorage(context)
        if (storage.isServiceEnabled()) {
            PonteServerService.start(context)
        }
    }
}
