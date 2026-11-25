package com.autodeck.hondadeck.ponte

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Base64
import com.autodeck.protocol.apps.AppLaunchMatcher
import com.autodeck.protocol.apps.AppMatchResult
import com.autodeck.protocol.apps.LaunchableApp
import com.autodeck.protocol.phone.PhoneAppEntry
import com.autodeck.protocol.phone.PhoneAppIcon
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class PhoneAppIconsSnapshot(
    val icons: List<PhoneAppIcon>,
    val roles: Map<String, String>,
    val installedRelevantPackages: List<String>
)

/**
 * Lista, abre aplicativos e extrai ícones oficiais instalados no celular Samsung
 * sob comando do tablet (`apps.list`, `apps.open`, `apps.icons`).
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

    /**
     * Coleta os ícones oficiais (192 px PNG com máscara adaptativa do sistema) dos apps relevantes
     * instalados neste celular. Ignora pacotes cujo hash já coincide com `knownHashes`.
     */
    fun collectRelevantIcons(
        requestedPackages: Collection<String>? = null,
        knownHashes: Map<String, String> = emptyMap()
    ): PhoneAppIconsSnapshot {
        val roles = resolveAppRoles()
        val candidates = linkedSetOf<String>()
        if (!requestedPackages.isNullOrEmpty()) {
            requestedPackages.mapTo(candidates) { it.trim() }
        } else {
            candidates += context.packageName
            roles.values.forEach { if (it.isNotBlank()) candidates += it }
            candidates += RELEVANT_PACKAGES
        }

        val installedRelevant = mutableListOf<String>()
        val icons = mutableListOf<PhoneAppIcon>()

        for (pkg in candidates) {
            if (pkg.isBlank()) continue
            if (!isPackageInstalled(pkg)) continue
            installedRelevant += pkg
            val extracted = extractAppIcon(pkg) ?: continue
            if (knownHashes[pkg] == extracted.hash) continue
            icons += extracted
        }

        return PhoneAppIconsSnapshot(
            icons = icons,
            roles = roles,
            installedRelevantPackages = installedRelevant
        )
    }

    fun extractAppIcon(packageName: String): PhoneAppIcon? {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return null
        val pm = context.packageManager
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull() ?: return null
        val drawable = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull() ?: return null
        val pngBytes = renderDrawableToPng(drawable, ICON_SIZE_PX) ?: return null
        val label = runCatching { pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty() }.getOrDefault("")
        val hash = sha256Short(pngBytes)
        val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        return PhoneAppIcon(
            packageName = pkg,
            label = label,
            hash = hash,
            pngBase64 = b64
        )
    }

    fun resolveAppRoles(): Map<String, String> {
        val roles = linkedMapOf<String, String>()
        roles["ponte"] = context.packageName

        val defaultDialer = runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            tm?.defaultDialerPackage
        }.getOrNull()?.takeIf { it.isNotBlank() && isPackageInstalled(it) }
        val dialer = defaultDialer ?: DIALER_CANDIDATES.firstOrNull { isPackageInstalled(it) }
        if (!dialer.isNullOrBlank()) roles["dialer"] = dialer

        val defaultSms = runCatching {
            Telephony.Sms.getDefaultSmsPackage(context)
        }.getOrNull()?.takeIf { it.isNotBlank() && isPackageInstalled(it) }
        val sms = defaultSms ?: SMS_CANDIDATES.firstOrNull { isPackageInstalled(it) }
        if (!sms.isNullOrBlank()) roles["sms"] = sms

        val calendar = CALENDAR_CANDIDATES.firstOrNull { isPackageInstalled(it) }
        if (!calendar.isNullOrBlank()) roles["calendar"] = calendar

        val whatsapp = WHATSAPP_CANDIDATES.firstOrNull { isPackageInstalled(it) }
        if (!whatsapp.isNullOrBlank()) roles["whatsapp"] = whatsapp

        return roles
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun renderDrawableToPng(drawable: Drawable, sizePx: Int): ByteArray? {
        val bitmap = runCatching { Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return null
        return try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
                val save = canvas.save()
                val mask = runCatching { drawable.iconMask }.getOrNull()
                if (mask != null && !mask.isEmpty) {
                    canvas.clipPath(mask)
                } else {
                    val fallback = Path().apply {
                        val r = sizePx * 0.22f
                        addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, Path.Direction.CW)
                    }
                    canvas.clipPath(fallback)
                }
                drawable.draw(canvas)
                canvas.restoreToCount(save)
            } else {
                drawable.draw(canvas)
            }
            val baos = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            if (ok) baos.toByteArray().takeIf { it.isNotEmpty() } else null
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256Short(bytes: ByteArray): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault(bytes.size.toString())

    companion object {
        const val ICON_SIZE_PX = 192

        private val WHATSAPP_CANDIDATES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val SMS_CANDIDATES = listOf(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms"
        )
        private val DIALER_CANDIDATES = listOf(
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.dialer"
        )
        private val CALENDAR_CANDIDATES = listOf(
            "com.samsung.android.calendar",
            "com.google.android.calendar"
        )

        val RELEVANT_PACKAGES: List<String> = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.calendar",
            "com.google.android.calendar",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "deezer.android.app",
            "com.google.android.apps.maps",
            "com.waze",
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.ubercab"
        )
    }
}
