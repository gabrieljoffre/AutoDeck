package com.autodeck.headunit.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.autodeck.headunit.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Botão "Atualizar app" (só na versão completo, a instalada pelo USB).
 *
 * 1. Lê `version.json` da release fixa `hondadeck-latest` no GitHub (publicada pelo
 *    workflow build-hondadeck.yml junto com os APKs).
 * 2. Se o `versionCode` de lá for maior que o instalado, baixa `HondaDeck-completo.apk`.
 * 3. Abre o instalador do Android (o usuário só toca em "Atualizar"). Mesmo pacote e mesma
 *    assinatura (keystore do repositório): configurações e dados são mantidos.
 *
 * A primeira vez o Android pede para liberar "Instalar apps desconhecidos" para o HondaDeck.
 * As chaves de API sobrevivem à troca pelo APK do GitHub (sem chaves) via [com.autodeck.headunit.data.BakedKeys].
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val RELEASE_BASE = "https://github.com/gabrieljoffre/AutoDeck/releases/download/hondadeck-latest/"
    const val VERSION_URL = RELEASE_BASE + "version.json"
    const val APK_URL = RELEASE_BASE + "HondaDeck-completo.apk"
    private const val USER_AGENT = "HondaDeck-Updater"

    enum class Status { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, NEEDS_PERMISSION, ERROR }

    data class UpdateState(
        val supported: Boolean = BuildConfig.HAS_SELF_UPDATE,
        val status: Status = Status.IDLE,
        val currentCode: Int = BuildConfig.VERSION_CODE,
        val currentName: String = BuildConfig.VERSION_NAME,
        val latestCode: Int = 0,
        val latestName: String = "",
        val latestDate: String = "",
        val progressPct: Int = 0,
        val message: String = "",
        val checkedAtMs: Long = 0L
    )

    data class RemoteVersion(val code: Int, val name: String, val date: String, val sizeBytes: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var remote: RemoteVersion? = null

    /** Consulta a versão publicada. [quiet] = checagem automática (não mostra erro de rede). */
    fun check(quiet: Boolean = false) {
        if (!BuildConfig.HAS_SELF_UPDATE || job?.isActive == true) return
        job = scope.launch {
            if (!quiet) _state.value = _state.value.copy(status = Status.CHECKING, message = "Procurando atualização...")
            val result = runCatching { fetchRemoteVersion() }
            result.onSuccess { r ->
                remote = r
                val newer = r.code > BuildConfig.VERSION_CODE
                _state.value = _state.value.copy(
                    status = if (newer) Status.AVAILABLE else Status.UP_TO_DATE,
                    latestCode = r.code,
                    latestName = r.name,
                    latestDate = r.date,
                    progressPct = 0,
                    checkedAtMs = System.currentTimeMillis(),
                    message = if (newer) "Nova versão ${r.name} disponível" else "O app já está na versão mais nova"
                )
            }.onFailure { err ->
                Log.w(TAG, "Falha ao consultar versão: ${err.message}")
                _state.value = if (quiet) {
                    _state.value.copy(status = Status.IDLE)
                } else {
                    _state.value.copy(status = Status.ERROR, message = "Sem conexão com o GitHub. Tente de novo com internet.")
                }
            }
        }
    }

    /** Baixa o APK novo e abre o instalador do Android. */
    fun downloadAndInstall(context: Context) {
        if (!BuildConfig.HAS_SELF_UPDATE || job?.isActive == true) return
        val appContext = context.applicationContext
        if (!canInstall(appContext)) {
            _state.value = _state.value.copy(
                status = Status.NEEDS_PERMISSION,
                message = "Ative \"Permitir desta fonte\" para o HondaDeck e toque em Atualizar de novo."
            )
            openInstallPermissionScreen(appContext)
            return
        }
        job = scope.launch {
            try {
                val r = remote ?: fetchRemoteVersion().also { remote = it }
                if (r.code <= BuildConfig.VERSION_CODE) {
                    _state.value = _state.value.copy(status = Status.UP_TO_DATE, message = "O app já está na versão mais nova")
                    return@launch
                }
                _state.value = _state.value.copy(status = Status.DOWNLOADING, progressPct = 0, message = "Baixando ${r.name}...")
                val apk = downloadApk(appContext, r)
                verifyApk(appContext, apk, r)
                _state.value = _state.value.copy(status = Status.READY, progressPct = 100, message = "Toque em Atualizar na janela do Android")
                launchInstaller(appContext, apk)
            } catch (err: Exception) {
                Log.w(TAG, "Atualização falhou: ${err.message}", err)
                _state.value = _state.value.copy(status = Status.ERROR, message = "Não deu para atualizar: ${err.message ?: "erro desconhecido"}")
            }
        }
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermissionScreen(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Tela de permissão indisponível: ${it.message}") }
    }

    private fun fetchRemoteVersion(): RemoteVersion {
        val body = httpGet(VERSION_URL + "?t=" + System.currentTimeMillis() / 60_000L)
        return parseVersionJson(body)
    }

    fun parseVersionJson(body: String): RemoteVersion {
        val json = JSONObject(body)
        val code = json.optInt("versionCode", 0)
        require(code > 0) { "version.json sem versionCode" }
        return RemoteVersion(
            code = code,
            name = json.optString("versionName").ifBlank { code.toString() },
            date = json.optString("builtAt"),
            sizeBytes = json.optLong("completoBytes", 0L)
        )
    }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub redireciona para objects.githubusercontent.com
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun downloadApk(context: Context, r: RemoteVersion): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "HondaDeck-completo-${r.code}.apk")
        val conn = open(APK_URL)
        try {
            if (conn.responseCode !in 200..299) error("download HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.sizeBytes
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = _state.value.copy(progressPct = pct, message = "Baixando ${r.name}... $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (r.sizeBytes > 0 && target.length() != r.sizeBytes) {
            target.delete()
            error("arquivo incompleto (${target.length()} de ${r.sizeBytes} bytes)")
        }
        return target
    }

    /** Confere se o arquivo é mesmo o HondaDeck e é mais novo antes de abrir o instalador. */
    private fun verifyApk(context: Context, apk: File, r: RemoteVersion) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("APK baixado está corrompido")
        require(info.packageName == context.packageName) { "APK de outro aplicativo (${info.packageName})" }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
        require(code == r.code) { "versão do APK ($code) diferente do anunciado (${r.code})" }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            error("instalador do Android não encontrado")
        }
    }
}
