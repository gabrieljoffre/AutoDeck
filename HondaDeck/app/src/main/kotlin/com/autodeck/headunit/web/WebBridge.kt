package com.autodeck.headunit.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.autodeck.headunit.BuildConfig
import com.autodeck.headunit.service.HeadUnitRuntime
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.protocol.coordinator.MainTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Ponte bidirecional JS <-> Kotlin da interface web Órbita (`.agy/contrato-ponte.md`).
 * - Recebe comandos `{cmd, args}` via `@JavascriptInterface fun send(json: String)`.
 * - Valida contra a lista oficial de comandos do contrato e executa na Main Thread.
 * - Envia `state` (máx. 4/s com conflate), `telemetry` (~10/s) e `event` via `window.HD.receive(...)`.
 */
class WebBridge(
    private val activityContext: Context,
    private val runtime: HeadUnitRuntime,
    private val webView: WebView
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivityManager =
        activityContext.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var attached = false

    @Volatile
    private var uiReady = false

    @Volatile
    private var lastTelemetrySig: String = ""

    fun start() {
        if (attached) return
        attached = true

        // 1) Estado completo: StateFlow já é conflatado + intervalo mínimo de 250 ms (máx. 4/s)
        scope.launch {
            runtime.uiState.collect { ui ->
                if (!attached) return@collect
                if (uiReady) {
                    pushFullState(ui)
                    delay(STATE_MIN_INTERVAL_MS)
                }
            }
        }

        // 2) Telemetria rápida (~10/s) para animar velocímetro e fundo
        scope.launch {
            while (isActive && attached) {
                delay(TELEMETRY_INTERVAL_MS)
                if (!uiReady) continue
                val ui = runtime.uiState.value
                val payload = StateDto.buildTelemetryJson(ui)
                val sig = payload.toString()
                if (sig != lastTelemetrySig) {
                    lastTelemetrySig = sig
                    deliverToJs(StateDto.wrapMessage("telemetry", payload))
                }
            }
        }

        // 3) Eventos pontuais (toasts, respostas do copiloto, chegada de rota, openMode)
        scope.launch {
            runtime.webEvents.collect { ev ->
                if (!attached || !uiReady) return@collect
                val payload = StateDto.buildEventJson(ev.name, ev.text, ev.mode)
                deliverToJs(StateDto.wrapMessage("event", payload))
            }
        }
    }

    fun detach() {
        attached = false
        uiReady = false
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    @JavascriptInterface
    fun send(json: String) {
        val parsed = runCatching { JSONObject(json) }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "JSON inválido recebido da WebView: ${json.take(120)}")
            return
        }
        val cmd = parsed.optString("cmd").trim()
        if (cmd !in ALLOWED_COMMANDS) {
            Log.w(TAG, "Comando desconhecido ignorado: '$cmd'")
            return
        }
        val args = parsed.optJSONObject("args") ?: JSONObject()
        mainHandler.post {
            if (!attached) return@post
            runCatching { dispatch(cmd, args) }
                .onFailure { err ->
                    Log.e(TAG, "Erro ao executar comando '$cmd': ${err.message}", err)
                }
        }
    }

    private fun dispatch(cmd: String, args: JSONObject) {
        when (cmd) {
            "ui.ready" -> {
                uiReady = true
                pushFullState(runtime.uiState.value)
                runtime.connectSpotify(activityContext)
                if (runtime.uiState.value.spotifyStatus == com.autodeck.headunit.media.SpotifyLinkStatus.CONNECTED) {
                    runtime.refreshSpotifyPlaylists()
                }
            }

            "ui.setAccent" -> {
                val color = args.optString("color").trim()
                if (HEX_COLOR_REGEX.matches(color)) {
                    runtime.updateConfig { it.copy(accentColor = color.uppercase()) }
                }
            }

            "ui.mode" -> {
                val tab = when (args.optString("mode").trim().lowercase()) {
                    "map", "mapfull" -> MainTab.NAVIGATION
                    "music" -> MainTab.MUSIC
                    "settings" -> MainTab.SETTINGS
                    else -> MainTab.HOME
                }
                runtime.selectTab(tab)
            }

            "audio.setVolume" -> {
                if (args.has("pct")) {
                    val pct = args.optDouble("pct", 60.0)
                    if (pct.isFinite()) runtime.setMasterVolumePct(pct.roundToInt())
                }
            }

            "audio.toggleMute" -> runtime.toggleAudioMute()

            "music.toggle" -> runtime.toggleMusicPlayPause()
            "music.next" -> runtime.skipMusicNext()
            "music.prev" -> runtime.skipMusicPrevious()
            "music.shuffle" -> runtime.spotifyToggleShuffle()
            "music.repeat" -> runtime.spotifyToggleRepeat()
            "music.like" -> runtime.spotifyToggleLike()
            "music.seek" -> {
                val ms = args.optLong("ms", -1L)
                if (ms >= 0L) runtime.seekMusicTo(ms)
            }
            "music.playUri" -> {
                val uri = args.optString("uri").trim()
                if (uri.isNotBlank()) runtime.playSpotifyUri(uri)
            }
            "music.search" -> {
                runtime.searchSpotify(args.optString("query"))
            }
            "music.authorize" -> {
                runtime.requestSpotifyWebAuth()
            }
            "music.openApp" -> {
                runtime.openPreferredMusicApp()
                runtime.connectSpotify(activityContext)
            }

            "map.search" -> {
                runtime.searchPlaces(args.optString("query"))
            }
            "map.route" -> {
                val name = args.optString("name").trim()
                val lat = args.optFiniteDouble("lat")
                val lon = args.optFiniteDouble("lon")
                runtime.routeToPlace(name, lat, lon)
            }
            "map.stopRoute" -> runtime.stopInAppNavigation()
            "map.openExternal" -> {
                val name = args.optString("name").trim().ifBlank {
                    runtime.uiState.value.route.destinationLabel
                }
                val pkg = args.optString("package").trim().takeIf { it.isNotBlank() }
                if (name.isNotBlank()) runtime.navigateExternally(name, pkg)
            }
            "apps.open" -> {
                val pkg = args.optString("packageName").trim().takeIf { it.isNotBlank() }
                val name = args.optString("name").trim().takeIf { it.isNotBlank() }
                val target = args.optString("target", "tablet").trim().ifBlank { "tablet" }
                runtime.openAppFromUi(packageName = pkg, name = name, target = target)
            }
            "copilot.openGeminiApp" -> runtime.openOfficialGeminiApp()

            "fav.save" -> {
                runtime.saveFavoritePlace(
                    id = args.optString("id").takeIf { it.isNotBlank() },
                    name = args.optString("name"),
                    address = args.optString("address"),
                    lat = args.optFiniteDouble("lat"),
                    lon = args.optFiniteDouble("lon"),
                    voice = args.optString("voice"),
                    icon = args.optString("icon")
                )
            }
            "fav.delete" -> {
                runtime.deleteFavoritePlace(args.optString("id"))
            }
            "fav.useCurrentLocation" -> {
                runtime.saveCurrentLocationAsFavorite(
                    name = args.optString("name"),
                    voice = args.optString("voice"),
                    icon = args.optString("icon")
                )
            }

            "copilot.listen" -> runtime.triggerVoiceAgent()
            "copilot.cancel" -> runtime.stopVoiceAgent()
            "copilot.live.start" -> {
                val prompt = args.optString("prompt").trim().takeIf { it.isNotEmpty() }
                runtime.startCopilotLive(prompt)
            }
            "copilot.live.stop" -> runtime.stopCopilotLive()
            "copilot.ask.voice" -> runtime.startCopilotAskVoice()
            "copilot.clearMemory" -> runtime.clearCopilotMemory()
            "copilot.ask" -> {
                val text = args.optString("text").trim()
                if (text.isNotBlank()) runtime.sendAgentPrompt(text)
            }

            "notif.read" -> runtime.readLatestWhatsAppAloud()
            "notif.dismiss" -> runtime.dismissCurrentNotification()

            "phone.pair" -> {
                val pin = args.optString("pin").trim()
                val host = args.optString("host").trim().takeIf { it.isNotBlank() }
                runtime.pairPhoneWithPin(pin, host)
            }
            "phone.unpair" -> runtime.unpairPhone()
            "phone.discover" -> runtime.discoverPhone()
            "phone.reconnect" -> runtime.reconnectPhone()
            "phone.syncContacts" -> runtime.syncPhoneContactsNow()
            "phone.call.answer" -> runtime.answerPhoneCallFromUi()
            "phone.call.end" -> runtime.endPhoneCallFromUi()
            "phone.voice.cancel" -> runtime.cancelVoiceNoteRecording()

            "obd.connect" -> runtime.connectObdNow()
            "obd.disconnect" -> runtime.disconnectObd()
            "obd.readDtc" -> runtime.readDtcCodes()
            "obd.clearDtc" -> runtime.clearDtcCodes()
            "obd.setSimulator" -> {
                val enabled = args.optBoolean("enabled", false)
                runtime.updateConfig { it.copy(useSimulatedObd = enabled) }
            }
            "obd.selectDevice" -> {
                val addr = args.optString("address").trim()
                if (addr.isNotBlank()) {
                    val matched = runtime.uiState.value.pairedBluetoothDevices
                        .firstOrNull { it.macAddress.equals(addr, ignoreCase = true) }
                    runtime.updateConfig {
                        it.copy(
                            obdDeviceMac = addr,
                            obdDeviceName = matched?.name ?: it.obdDeviceName
                        )
                    }
                }
            }

            "fuel.log" -> {
                val liters = args.optFiniteDouble("liters") ?: return
                val price = args.optFiniteDouble("price")?.takeIf { it > 0.0 }
                val odometer = args.optFiniteDouble("odometer")?.takeIf { it > 0.0 }
                val full = args.optBoolean("full", true)
                val msg = runtime.logRefuel(liters, price, odometer, full)
                runtime.emitWebEvent("toast", msg)
            }
            "fuel.delete" -> {
                val ts = args.optString("id").toLongOrNull() ?: return
                runtime.deleteFuelEntry(ts)
                runtime.emitWebEvent("toast", "Registro de abastecimento excluído")
            }
            "fuel.tank" -> {
                when (args.optString("action").trim().lowercase()) {
                    "plus10" -> runtime.addFuelLiters(10.0)
                    "plus20" -> runtime.addFuelLiters(20.0)
                    "full" -> runtime.markTankFull()
                }
            }

            "settings.set" -> handleSettingsSet(args)
            "system.downloadOfflineMap" -> runtime.downloadOfflineMap()
            "camera.toggle" -> runtime.toggleManualCameraPreview()
            "calendar.refresh" -> runtime.refreshCalendar(force = true)
            "calendar.requestPermission" -> runtime.requestCalendarPermission()
        }
    }

    private fun handleSettingsSet(args: JSONObject) {
        val key = args.optString("key").trim()
        when (key) {
            "geminiKey" -> {
                val v = args.optString("value").trim()
                if (v.isNotBlank()) {
                    runtime.updateConfig { it.copy(geminiApiKey = v) }
                    runtime.emitWebEvent("toast", "Chave Gemini salva")
                }
            }
            "geminiModel" -> {
                val v = args.optString("value").trim()
                if (v.isNotBlank()) {
                    runtime.updateConfig { it.copy(geminiModel = v) }
                }
            }
            "geminiLiveModel" -> {
                val v = args.optString("value").trim()
                if (v.isNotBlank()) {
                    runtime.updateConfig { it.copy(geminiLiveModel = v) }
                }
            }
            "wakeWord" -> {
                val v = args.optString("value").trim()
                if (v.isNotBlank()) {
                    runtime.updateConfig { it.copy(wakeWordPhrase = v) }
                }
            }
            "wakeWordEnabled" -> {
                val v = args.optBoolean("value", true)
                runtime.updateConfig { it.copy(wakeWordEnabled = v) }
            }
            "introMode" -> {
                val v = when (args.optString("value").trim().uppercase()) {
                    "VIDEO" -> "VIDEO"
                    else -> "ANIMATION"
                }
                runtime.updateConfig { it.copy(introMode = v) }
            }
            "mapTheme" -> {
                val v = when (args.optString("value").trim().uppercase()) {
                    "LIGHT" -> "LIGHT"
                    "DARK" -> "DARK"
                    else -> "AUTO"
                }
                runtime.updateConfig { it.copy(mapTheme = v) }
            }
            "spotifyShowPlaylists" -> {
                val v = args.optBoolean("value", true)
                runtime.updateConfig { it.copy(spotifyShowPlaylists = v) }
            }
            "spotifyShowRecent" -> {
                val v = args.optBoolean("value", true)
                runtime.updateConfig { it.copy(spotifyShowRecent = v) }
            }
            "ignitionDevice" -> {
                val addr = args.optString("value").trim()
                val matched = runtime.uiState.value.pairedBluetoothDevices
                    .firstOrNull { it.macAddress.equals(addr, ignoreCase = true) }
                runtime.updateConfig {
                    it.copy(
                        ignitionBtMac = addr,
                        ignitionBtName = matched?.name ?: if (addr.isBlank()) "" else it.ignitionBtName
                    )
                }
            }
            "preferredMapPackage" -> {
                val pkg = args.optString("value").trim()
                if (pkg.isNotBlank()) {
                    val label = runtime.uiState.value.installedMapApps
                        .firstOrNull { it.packageName == pkg }?.label ?: pkg
                    runtime.updateConfig { it.copy(preferredMapPackage = pkg, preferredMapLabel = label) }
                }
            }
            "preferredMusicPackage" -> {
                val pkg = args.optString("value").trim()
                if (pkg.isNotBlank()) {
                    val label = runtime.uiState.value.installedMusicApps
                        .firstOrNull { it.packageName == pkg }?.label ?: pkg
                    runtime.updateConfig { it.copy(preferredMusicPackage = pkg, preferredMusicLabel = label) }
                }
            }
            else -> Log.w(TAG, "Chave desconhecida em settings.set: '$key'")
        }
    }

    private fun pushFullState(ui: HeadUnitUiState) {
        val extras = WebExtras(
            nowEpochMs = System.currentTimeMillis(),
            wifiConnected = isWifiConnected(),
            appVersion = BuildConfig.VERSION_NAME,
            mapsJsApiKey = BuildConfig.MAPS_JS_API_KEY
        )
        val stateJson = StateDto.buildStateJson(ui, extras)
        deliverToJs(StateDto.wrapMessage("state", stateJson))
    }

    private fun deliverToJs(envelopeJson: String) {
        val safeJson = envelopeJson
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        val script = "window.HD&&window.HD.receive($safeJson)"
        mainHandler.post {
            if (!attached) return@post
            runCatching { webView.evaluateJavascript(script, null) }
                .onFailure { err -> Log.w(TAG, "evaluateJavascript falhou: ${err.message}") }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = connectivityManager ?: return false
        return runCatching {
            val active = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(active) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private fun JSONObject.optFiniteDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val v = optDouble(name, Double.NaN)
        return if (v.isFinite()) v else null
    }

    companion object {
        private const val TAG = "WebBridge"
        private const val STATE_MIN_INTERVAL_MS = 250L
        private const val TELEMETRY_INTERVAL_MS = 100L
        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        val ALLOWED_COMMANDS: Set<String> = setOf(
            "ui.ready",
            "ui.setAccent",
            "ui.mode",
            "audio.setVolume",
            "audio.toggleMute",
            "music.toggle",
            "music.next",
            "music.prev",
            "music.shuffle",
            "music.repeat",
            "music.like",
            "music.seek",
            "music.playUri",
            "music.search",
            "music.authorize",
            "music.openApp",
            "map.search",
            "map.route",
            "map.stopRoute",
            "map.openExternal",
            "apps.open",
            "copilot.openGeminiApp",
            "fav.save",
            "fav.delete",
            "fav.useCurrentLocation",
            "copilot.listen",
            "copilot.cancel",
            "copilot.ask",
            "copilot.live.start",
            "copilot.live.stop",
            "copilot.ask.voice",
            "copilot.clearMemory",
            "notif.read",
            "notif.dismiss",
            "phone.pair",
            "phone.unpair",
            "phone.discover",
            "phone.reconnect",
            "phone.syncContacts",
            "phone.call.answer",
            "phone.call.end",
            "phone.voice.cancel",
            "obd.connect",
            "obd.disconnect",
            "obd.readDtc",
            "obd.clearDtc",
            "obd.setSimulator",
            "obd.selectDevice",
            "fuel.log",
            "fuel.delete",
            "fuel.tank",
            "settings.set",
            "system.downloadOfflineMap",
            "camera.toggle",
            "calendar.refresh",
            "calendar.requestPermission"
        )
    }
}
