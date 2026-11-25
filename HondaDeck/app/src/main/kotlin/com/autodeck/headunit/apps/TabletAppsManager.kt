package com.autodeck.headunit.apps

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.autodeck.protocol.apps.AppLaunchMatcher
import com.autodeck.protocol.apps.AppMatchResult
import com.autodeck.protocol.apps.LaunchableApp

/**
 * Descobre e abre aplicativos instalados no tablet pelo nome falado
 * (ex.: "YouTube Music", "Câmera", "Waze", "Chrome", "Calculadora").
 */
class TabletAppsManager(private val context: Context) {

    fun listInstalledApps(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return activities
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                LaunchableApp(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun openApp(spokenName: String): Result<String> {
        val clean = spokenName.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe o nome do aplicativo para abrir."))
        }
        val apps = listInstalledApps()
        val match = AppLaunchMatcher.resolve(apps, clean)
        return when (match) {
            is AppMatchResult.Matched -> launchPackage(match.app)
            is AppMatchResult.Ambiguous -> {
                val names = match.candidates.joinToString(", ") { it.label }
                Result.failure(IllegalStateException("Encontrei mais de um aplicativo parecido: $names. Qual você quer abrir?"))
            }
            AppMatchResult.NotFound -> {
                // Fallback para câmera do sistema caso não esteja listada no launcher
                if (AppLaunchMatcher.cleanQuery(clean) == "camera") {
                    val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val ok = runCatching { context.startActivity(camIntent) }.isSuccess
                    if (ok) return Result.success("Abrindo a câmera.")
                }
                Result.failure(NoSuchElementException("Não encontrei o aplicativo \"$clean\" instalado no tablet."))
            }
        }
    }

    private fun launchPackage(app: LaunchableApp): Result<String> {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return Result.failure(IllegalStateException("Não foi possível iniciar ${app.label}."))
        return runCatching {
            context.startActivity(launchIntent)
            "Abrindo ${app.label}."
        }
    }
}
