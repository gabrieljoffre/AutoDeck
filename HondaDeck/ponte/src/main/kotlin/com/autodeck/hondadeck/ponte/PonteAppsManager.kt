package com.autodeck.hondadeck.ponte

import android.content.Context
import android.content.Intent
import com.autodeck.protocol.apps.AppLaunchMatcher
import com.autodeck.protocol.apps.AppMatchResult
import com.autodeck.protocol.apps.LaunchableApp
import com.autodeck.protocol.phone.PhoneAppEntry

/**
 * Lista e abre aplicativos instalados no celular Samsung sob comando do tablet (`apps.list` / `apps.open`).
 */
class PonteAppsManager(private val context: Context) {

    fun listLaunchableApps(): List<PhoneAppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return resolved
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                PhoneAppEntry(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun openApp(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe o nome do aplicativo para abrir no celular."))
        }
        val installed = listLaunchableApps()
        val launchables = installed.map { LaunchableApp(it.packageName, it.label) }
        return when (val match = AppLaunchMatcher.resolve(launchables, clean)) {
            AppMatchResult.NotFound ->
                Result.failure(NoSuchElementException("Não encontrei o aplicativo \"$clean\" instalado no celular."))

            is AppMatchResult.Ambiguous -> {
                val names = match.candidates.joinToString(", ") { it.label }
                Result.failure(IllegalStateException("Encontrei mais de um aplicativo no celular: $names. Qual deles você quer abrir?"))
            }

            is AppMatchResult.Matched -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(match.app.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                } ?: return Result.failure(IllegalStateException("Não foi possível abrir ${match.app.label} no celular."))
                runCatching {
                    context.startActivity(launchIntent)
                    "Abrindo ${match.app.label} no celular."
                }
            }
        }
    }
}
