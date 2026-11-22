package com.autodeck.headunit.hardware

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateState(
    val currentVersion: String = "1.2.0",
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgressPct: Int = 0,
    val availableVersionName: String? = null,
    val availableDownloadUrl: String? = null,
    val statusMessage: String = "Toque em Verificar para buscar atualizações no GitHub.",
    val errorMessage: String? = null
)

/**
 * Atualizador OTA interno do AutoDeck via GitHub Releases.
 * Baixa o APK atualizado para o cache interno e abre o instalador do Android via FileProvider.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val installedVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.2.0"
    }.getOrDefault("1.2.0")

    private val _state = MutableStateFlow(UpdateState(currentVersion = installedVersion))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkForUpdate(silentIfUpToDate: Boolean = false) {
        if (_state.value.checking || _state.value.downloading) return
        scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    checking = true,
                    errorMessage = null,
                    statusMessage = if (silentIfUpToDate) it.statusMessage else "Verificando atualizações..."
                )
            }
            try {
                val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "AutoDeck-Updater/$installedVersion")
                }
                val code = conn.responseCode
                if (code == 404) {
                    _state.update {
                        it.copy(
                            checking = false,
                            availableVersionName = null,
                            availableDownloadUrl = null,
                            statusMessage = "Nenhuma release publicada ainda (versão atual v$installedVersion)."
                        )
                    }
                    conn.disconnect()
                    return@launch
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code ao consultar GitHub")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").trim()
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true) && !name.contains("link", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                            break
                        }
                    }
                }

                if (tag.isNotBlank() && apkUrl != null && isRemoteVersionNewer(tag, installedVersion)) {
                    _state.update {
                        it.copy(
                            checking = false,
                            availableVersionName = tag.removePrefix("v").removePrefix("V"),
                            availableDownloadUrl = apkUrl,
                            statusMessage = "Nova versão disponível: $tag (atual: v$installedVersion)"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            checking = false,
                            availableVersionName = null,
                            availableDownloadUrl = null,
                            statusMessage = "AutoDeck já está atualizado (v$installedVersion)."
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        errorMessage = if (silentIfUpToDate) null else "Sem conexão para verificar (${e.message ?: "rede indisponível"})"
                    )
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val url = _state.value.availableDownloadUrl ?: return
        if (_state.value.downloading) return

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(intent) }
            _state.update {
                it.copy(
                    statusMessage = "Ative 'Permitir desta fonte' para o AutoDeck e toque em Baixar e Atualizar novamente.",
                    errorMessage = null
                )
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    downloading = true,
                    downloadProgressPct = 0,
                    errorMessage = null,
                    statusMessage = "Baixando atualização..."
                )
            }
            try {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(updatesDir, "AutoDeck-update.apk")
                if (outFile.exists()) outFile.delete()

                val conn = openFollowingRedirects(url)
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                var lastPct = -1

                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct && (pct - lastPct >= 2 || pct == 100)) {
                                    lastPct = pct
                                    _state.update { it.copy(downloadProgressPct = pct) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    outFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(installIntent)

                _state.update {
                    it.copy(
                        downloading = false,
                        downloadProgressPct = 100,
                        statusMessage = "Download concluído! Confirme a instalação na tela do Android."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        downloading = false,
                        errorMessage = "Falha no download da atualização: ${e.message ?: "erro"}"
                    )
                }
            }
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "AutoDeck-Updater/$installedVersion")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IllegalStateException("Redirect sem Location")
                currentUrl = URL(URL(currentUrl), next).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("HTTP $code ao baixar APK")
            }
        }
        throw IllegalStateException("Excesso de redirecionamentos")
    }

    companion object {
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/gabrieljoffre/AutoDeck/releases/latest"

        fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
            val remoteParts = parseSemverParts(remoteTag)
            val currentParts = parseSemverParts(currentVersion)
            val count = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until count) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseSemverParts(version: String): List<Int> {
            return version
                .trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .split('.')
                .map { it.toIntOrNull() ?: 0 }
        }
    }
}
