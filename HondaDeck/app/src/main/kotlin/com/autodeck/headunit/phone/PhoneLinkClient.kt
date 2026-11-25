package com.autodeck.headunit.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.autodeck.headunit.web.WebResourceRouter
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.ContactMatchResult
import com.autodeck.protocol.phone.PhoneAppEntry
import com.autodeck.protocol.phone.PhoneAppIcon
import com.autodeck.protocol.phone.PhoneCalendarEvent
import com.autodeck.protocol.phone.PhoneCallInfo
import com.autodeck.protocol.phone.PhoneContact
import com.autodeck.protocol.phone.PhoneLinkCodec
import com.autodeck.protocol.phone.PhoneLinkMessage
import com.autodeck.protocol.phone.PhoneLinkProtocol
import com.autodeck.protocol.phone.PhoneMessageNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class PhoneConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    PAIRING,
    CONNECTED
}

data class DiscoveredPhone(
    val name: String,
    val host: String,
    val port: Int = PhoneLinkProtocol.TCP_PORT,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class PhoneLinkUiState(
    val connectionState: PhoneConnectionState = PhoneConnectionState.DISCONNECTED,
    val paired: Boolean = false,
    val phoneName: String = "",
    val host: String = "",
    val port: Int = PhoneLinkProtocol.TCP_PORT,
    val discoveredPhones: List<DiscoveredPhone> = emptyList(),
    val phoneLocked: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val callState: PhoneCallInfo = PhoneCallInfo(),
    val messages: List<PhoneMessageNotification> = emptyList(),
    val recordingVoiceNote: Boolean = false,
    val voiceNoteStatus: String = "",
    val contactsCount: Int = 0,
    val contactsSyncedAtMs: Long = 0L,
    val contactsSyncing: Boolean = false,
    val contactsSyncError: String? = null,
    val iconsVersion: Int = 0,
    val iconPackages: Set<String> = emptySet(),
    val appRoles: Map<String, String> = emptyMap(),
    val statusMessage: String = "Celular desconectado",
    val lastError: String? = null
) {
    val isConnected: Boolean get() = connectionState == PhoneConnectionState.CONNECTED
}

sealed interface PhoneLinkClientEvent {
    data class MessageReceived(val message: PhoneMessageNotification) : PhoneLinkClientEvent
    data class CallStateChanged(val call: PhoneCallInfo) : PhoneLinkClientEvent
    data class ContactsSynced(val count: Int, val syncedAtMs: Long) : PhoneLinkClientEvent
    data class Toast(val text: String, val isError: Boolean = false) : PhoneLinkClientEvent
}

/**
 * Cliente de ponte do tablet (`:app`) para o app HondaDeck Ponte (`:ponte`) no celular Samsung.
 *
 * - Descobre o celular na rede local via NSD (`_hondadeck._tcp.`) + UDP 5291 (incluindo gateway do hotspot).
 * - Conecta via TCP JSON por linha na porta 5290, autentica por token e reconecta automaticamente.
 * - Pareia uma única vez usando PIN de 6 dígitos exibido no celular.
 * - Expõe estado em tempo real (conexão, chamada ativa, mensagens recebidas) e RPCs (contatos, ligações, WhatsApp texto/áudio/voz).
 */
class PhoneLinkClient(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val contactsCache = PhoneContactsCache(
        runCatching { appContext.filesDir?.let { File(it, "phone_contacts_cache.json") } }.getOrNull()
    )
    private val phoneIconsDir: File? = runCatching { WebResourceRouter.phoneIconsDir(appContext) }.getOrNull()
    private val iconHashes = ConcurrentHashMap<String, String>()
    private val iconVersionCounter = AtomicInteger(1)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _state = MutableStateFlow(loadInitialUiState())
    val state: StateFlow<PhoneLinkUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PhoneLinkClientEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PhoneLinkClientEvent> = _events.asSharedFlow()

    private val reqCounter = AtomicInteger(1)
    private val pendingRpc = ConcurrentHashMap<String, CompletableDeferred<PhoneLinkMessage.Response>>()
    private val writeLock = Any()

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeWriter: BufferedWriter? = null

    @Volatile
    private var started = false

    private var connectionLoopJob: Job? = null
    private var udpListenerJob: Job? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (started) return
        started = true
        startNsdDiscovery()
        startUdpDiscoveryListener()
        startAutoReconnectLoop()
        triggerDiscoveryProbe()
    }

    fun stop() {
        started = false
        connectionLoopJob?.cancel()
        udpListenerJob?.cancel()
        stopNsdDiscovery()
        closeActiveSocket()
    }

    fun triggerDiscoveryProbe() {
        scope.launch(Dispatchers.IO) {
            if (!_state.value.isConnected) {
                _state.update {
                    it.copy(
                        connectionState = if (it.connectionState == PhoneConnectionState.DISCONNECTED) {
                            PhoneConnectionState.DISCOVERING
                        } else {
                            it.connectionState
                        },
                        statusMessage = if (it.paired) "Procurando ${it.phoneName.ifBlank { "celular" }}…" else "Procurando HondaDeck Ponte na rede…"
                    )
                }
            }
            sendUdpDiscoveryProbes()
        }
    }

    fun requestReconnect() {
        scope.launch(Dispatchers.IO) {
            closeActiveSocket()
            sendUdpDiscoveryProbes()
        }
    }

    suspend fun pairWithPin(pin: String, hostOverride: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val cleanPin = pin.replace(Regex("""\D+"""), "")
            if (cleanPin.length != 6) {
                return@withContext Result.failure(IllegalArgumentException("Digite o PIN de 6 dígitos exibido no app HondaDeck Ponte."))
            }

            _state.update {
                it.copy(
                    connectionState = PhoneConnectionState.PAIRING,
                    lastError = null,
                    statusMessage = "Pareando com o celular…"
                )
            }

            val candidateHost = resolveTargetHostForPairing(hostOverride)
            if (candidateHost == null) {
                val err = "Celular com HondaDeck Ponte não encontrado na rede. Abra o app no celular ou informe o IP."
                _state.update {
                    it.copy(
                        connectionState = PhoneConnectionState.DISCONNECTED,
                        statusMessage = err,
                        lastError = err
                    )
                }
                return@withContext Result.failure(IllegalStateException(err))
            }

            val (host, port) = candidateHost
            closeActiveSocket()

            val socketResult = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            }
            val sock = socketResult.getOrElse { err ->
                val msg = "Não foi possível conectar a $host:$port (${err.message ?: "rede indisponível"})."
                _state.update {
                    it.copy(
                        connectionState = PhoneConnectionState.DISCONNECTED,
                        statusMessage = msg,
                        lastError = msg
                    )
                }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            return@withContext try {
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val reqId = "pair-${reqCounter.getAndIncrement()}"
                val req = PhoneLinkMessage.Request(
                    id = reqId,
                    method = PhoneLinkProtocol.METHOD_PAIR,
                    params = linkedMapOf(
                        "pin" to cleanPin,
                        "clientName" to buildTabletName()
                    )
                )
                writer.write(PhoneLinkCodec.encode(req))
                writer.write("\n")
                writer.flush()

                sock.soTimeout = 8_000
                val line = reader.readLine()
                sock.soTimeout = 0
                val res = line?.let { PhoneLinkCodec.decode(it) } as? PhoneLinkMessage.Response
                if (res == null || !res.ok) {
                    runCatching { sock.close() }
                    val err = res?.error ?: "Pareamento rejeitado pelo celular."
                    _state.update {
                        it.copy(
                            connectionState = PhoneConnectionState.DISCONNECTED,
                            statusMessage = err,
                            lastError = err
                        )
                    }
                    Result.failure(IllegalStateException(err))
                } else {
                    val token = res.result["token"]?.toString().orEmpty()
                    val phoneName = res.result["phoneName"]?.toString().orEmpty().ifBlank { "Celular Samsung" }
                    val locked = res.result["locked"] as? Boolean ?: false
                    val accOk = res.result["accessibilityEnabled"] as? Boolean ?: false
                    val notifOk = res.result["notificationsEnabled"] as? Boolean ?: false

                    savePairing(host = host, port = port, token = token, phoneName = phoneName)
                    attachAuthenticatedSocket(sock, writer, reader, host, port, phoneName, locked, accOk, notifOk)
                    _events.tryEmit(PhoneLinkClientEvent.Toast("Celular $phoneName pareado com sucesso"))
                    Result.success("Pareado com $phoneName")
                }
            } catch (err: Throwable) {
                runCatching { sock.close() }
                val msg = "Falha no pareamento: ${err.message ?: "tempo esgotado"}"
                _state.update {
                    it.copy(
                        connectionState = PhoneConnectionState.DISCONNECTED,
                        statusMessage = msg,
                        lastError = msg
                    )
                }
                Result.failure(err)
            }
        }

    fun unpair() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_HOST)
            remove(KEY_PORT)
            remove(KEY_PHONE_NAME)
            remove(KEY_ICON_HASHES)
            remove(KEY_APP_ROLES)
        }
        closeActiveSocket()
        contactsCache.clear()
        clearPhoneIconsCache()
        _state.update {
            it.copy(
                connectionState = PhoneConnectionState.DISCONNECTED,
                paired = false,
                phoneName = "",
                host = "",
                phoneLocked = false,
                callState = PhoneCallInfo(),
                messages = emptyList(),
                contactsCount = 0,
                contactsSyncedAtMs = 0L,
                contactsSyncing = false,
                contactsSyncError = null,
                iconsVersion = iconVersionCounter.incrementAndGet(),
                iconPackages = emptySet(),
                appRoles = emptyMap(),
                statusMessage = "Celular despareado",
                lastError = null
            )
        }
        _events.tryEmit(PhoneLinkClientEvent.Toast("Celular despareado"))
    }

    fun setVoiceNoteRecordingState(recording: Boolean, status: String = "") {
        _state.update { it.copy(recordingVoiceNote = recording, voiceNoteStatus = status) }
    }

    fun markMessageRead(id: String) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == id) m.copy(isRead = true) else m
                }
            )
        }
    }

    fun markLatestMessageRead() {
        val first = _state.value.messages.firstOrNull { !it.isRead } ?: _state.value.messages.firstOrNull() ?: return
        markMessageRead(first.id)
    }

    fun dismissMessage(id: String) {
        _state.update { st ->
            st.copy(messages = st.messages.filterNot { it.id == id })
        }
        scope.launch(Dispatchers.IO) {
            if (_state.value.isConnected) {
                sendRpc(PhoneLinkProtocol.METHOD_MESSAGES_DISMISS, linkedMapOf("id" to id), timeoutMs = 4_000L)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cache local de contatos + RPCs usados pelo Copiloto e pela UI
    // -------------------------------------------------------------------------

    fun getCachedContacts(): List<PhoneContact> = contactsCache.contacts()

    fun getTopFrequentContacts(limit: Int = 30): List<PhoneContact> = contactsCache.topFrequent(limit)

    fun searchCachedContacts(query: String, limit: Int = 10): List<PhoneContact> =
        contactsCache.search(query, limit)

    fun resolveCachedContact(query: String, maxAmbiguous: Int = 5): ContactMatchResult =
        contactsCache.resolve(query, maxAmbiguous)

    suspend fun syncContactsNow(includePhotos: Boolean = true): Result<Int> {
        _state.update { it.copy(contactsSyncing = true, contactsSyncError = null) }
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_CONTACTS_SYNC,
            linkedMapOf("includePhotos" to includePhotos),
            timeoutMs = 20_000L
        )
        return res.fold(
            onSuccess = { r ->
                val raw = r.result["contacts"] as? List<*> ?: emptyList<Any?>()
                val parsed = raw.mapNotNull { PhoneLinkCodec.mapToContact(it) }
                val syncedAt = (r.result["syncedAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
                contactsCache.save(parsed, syncedAt)
                _state.update {
                    it.copy(
                        contactsCount = parsed.size,
                        contactsSyncedAtMs = syncedAt,
                        contactsSyncing = false,
                        contactsSyncError = null
                    )
                }
                _events.tryEmit(PhoneLinkClientEvent.ContactsSynced(parsed.size, syncedAt))
                Result.success(parsed.size)
            },
            onFailure = { err ->
                val msg = err.message ?: "Não foi possível sincronizar os contatos."
                _state.update { it.copy(contactsSyncing = false, contactsSyncError = msg) }
                Result.failure(err)
            }
        )
    }

    suspend fun fetchPhoneCalendarEvents(startMs: Long, endMs: Long): Result<List<PhoneCalendarEvent>> {
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_CALENDAR_LIST,
            linkedMapOf("startMs" to startMs, "endMs" to endMs),
            timeoutMs = 10_000L
        )
        return res.map { r ->
            val raw = r.result["events"] as? List<*> ?: emptyList<Any?>()
            raw.mapNotNull { PhoneLinkCodec.mapToCalendarEvent(it) }
        }
    }

    suspend fun createPhoneCalendarEvent(
        title: String,
        startMs: Long,
        endMs: Long,
        location: String = "",
        reminderMinutes: Int? = null
    ): Result<String> {
        val params = linkedMapOf<String, Any?>(
            "title" to title,
            "startMs" to startMs,
            "endMs" to endMs,
            "location" to location
        )
        if (reminderMinutes != null) params["reminderMinutes"] = reminderMinutes
        val res = sendRpc(PhoneLinkProtocol.METHOD_CALENDAR_CREATE, params, timeoutMs = 10_000L)
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Evento criado na agenda do celular." } }
    }

    suspend fun listPhoneApps(): Result<List<PhoneAppEntry>> {
        val res = sendRpc(PhoneLinkProtocol.METHOD_APPS_LIST, emptyMap(), timeoutMs = 8_000L)
        return res.map { r ->
            val raw = r.result["apps"] as? List<*> ?: emptyList<Any?>()
            raw.mapNotNull { PhoneLinkCodec.mapToAppEntry(it) }
        }
    }

    suspend fun openAppOnPhone(appName: String): Result<String> {
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_APPS_OPEN,
            linkedMapOf("app" to appName.trim()),
            timeoutMs = 10_000L
        )
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Abrindo $appName no celular." } }
    }

    suspend fun searchContacts(query: String): Result<List<PhoneContact>> {
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_CONTACTS_SEARCH,
            linkedMapOf("query" to query.trim()),
            timeoutMs = 8_000L
        )
        return res.map { r ->
            val raw = r.result["contacts"] as? List<*> ?: emptyList<Any?>()
            raw.mapNotNull { PhoneLinkCodec.mapToContact(it) }
        }
    }

    suspend fun dialCall(number: String?, contact: String?): Result<String> {
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_CALL_DIAL,
            linkedMapOf(
                "number" to number.orEmpty(),
                "contact" to contact.orEmpty()
            ),
            timeoutMs = 8_000L
        )
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Ligação iniciada." } }
    }

    suspend fun answerCall(): Result<String> {
        val res = sendRpc(PhoneLinkProtocol.METHOD_CALL_ANSWER, emptyMap(), timeoutMs = 6_000L)
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Chamada atendida." } }
    }

    suspend fun endCall(): Result<String> {
        val res = sendRpc(PhoneLinkProtocol.METHOD_CALL_END, emptyMap(), timeoutMs = 6_000L)
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Chamada encerrada." } }
    }

    suspend fun sendWhatsAppText(number: String, text: String, contactName: String = ""): Result<String> {
        val normalized = BrazilPhoneNumber.normalize(number) ?: number
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_WHATSAPP_SEND_TEXT,
            linkedMapOf(
                "number" to normalized,
                "text" to text,
                "contactName" to contactName
            ),
            timeoutMs = 16_000L
        )
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Mensagem enviada no WhatsApp." } }
    }

    suspend fun sendWhatsAppVoice(
        number: String,
        audioFile: File,
        mimeType: String = "audio/mp4",
        contactName: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Arquivo de áudio vazio."))
        }
        val bytes = runCatching { audioFile.readBytes() }.getOrElse {
            return@withContext Result.failure(it)
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val normalized = BrazilPhoneNumber.normalize(number) ?: number
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_WHATSAPP_SEND_VOICE,
            linkedMapOf(
                "number" to normalized,
                "audioBase64" to b64,
                "mimeType" to mimeType,
                "fileName" to audioFile.name,
                "contactName" to contactName
            ),
            timeoutMs = 40_000L
        )
        res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Áudio enviado no WhatsApp." } }
    }

    suspend fun callWhatsApp(
        number: String,
        contactName: String = "",
        voipDataId: Long? = null
    ): Result<String> {
        val normalized = BrazilPhoneNumber.normalize(number) ?: number
        val params = linkedMapOf<String, Any?>(
            "number" to normalized,
            "contactName" to contactName
        )
        if (voipDataId != null) params["voipDataId"] = voipDataId
        val res = sendRpc(PhoneLinkProtocol.METHOD_WHATSAPP_CALL, params, timeoutMs = 15_000L)
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Ligação no WhatsApp iniciada." } }
    }

    suspend fun replyMessage(messageId: String, text: String): Result<String> {
        val res = sendRpc(
            PhoneLinkProtocol.METHOD_MESSAGES_REPLY,
            linkedMapOf("id" to messageId, "text" to text),
            timeoutMs = 8_000L
        )
        if (res.isSuccess) {
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.id == messageId }) }
        }
        return res.map { it.result["message"]?.toString().orEmpty().ifBlank { "Resposta enviada." } }
    }

    // -------------------------------------------------------------------------
    // Transporte interno, autenticação e reconexão
    // -------------------------------------------------------------------------

    private suspend fun sendRpc(
        method: String,
        params: Map<String, Any?>,
        timeoutMs: Long
    ): Result<PhoneLinkMessage.Response> = withContext(Dispatchers.IO) {
        val writer = activeWriter
        if (!_state.value.isConnected || writer == null) {
            return@withContext Result.failure(
                IllegalStateException("Celular não está conectado ao HondaDeck Ponte. Verifique Ajustes › Celular.")
            )
        }
        val id = "req-${reqCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<PhoneLinkMessage.Response>()
        pendingRpc[id] = deferred

        val wire = PhoneLinkCodec.encode(PhoneLinkMessage.Request(id = id, method = method, params = params))
        val sent = synchronized(writeLock) {
            runCatching {
                writer.write(wire)
                writer.write("\n")
                writer.flush()
                true
            }.getOrDefault(false)
        }
        if (!sent) {
            pendingRpc.remove(id)
            closeActiveSocket()
            return@withContext Result.failure(IllegalStateException("Conexão perdida com o celular."))
        }

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingRpc.remove(id)
        if (response == null) {
            return@withContext Result.failure(IllegalStateException("O celular demorou para responder."))
        }
        if (!response.ok) {
            if (response.errorCode == PhoneLinkProtocol.ERR_PHONE_LOCKED) {
                _state.update { it.copy(phoneLocked = true) }
            } else if (response.errorCode == PhoneLinkProtocol.ERR_ACCESSIBILITY_DISABLED) {
                _state.update { it.copy(accessibilityEnabled = false) }
            }
            return@withContext Result.failure(
                PhoneLinkRpcException(
                    message = response.error ?: "Falha no comando $method",
                    errorCode = response.errorCode
                )
            )
        }
        Result.success(response)
    }

    private fun startAutoReconnectLoop() {
        connectionLoopJob?.cancel()
        connectionLoopJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 2_000L
            while (isActive && started) {
              try {
                val token = prefs.getString(KEY_TOKEN, null).orEmpty()
                if (token.isBlank()) {
                    delay(2_500L)
                    continue
                }
                if (_state.value.isConnected && activeSocket?.isClosed == false) {
                    // Heartbeat periódico
                    val pingRes = sendRpc(PhoneLinkProtocol.METHOD_PING, emptyMap(), timeoutMs = 5_000L)
                    if (pingRes.isSuccess) {
                        val r = pingRes.getOrNull()?.result.orEmpty()
                        _state.update { st ->
                            st.copy(
                                phoneLocked = r["locked"] as? Boolean ?: st.phoneLocked,
                                accessibilityEnabled = r["accessibilityEnabled"] as? Boolean ?: st.accessibilityEnabled,
                                notificationsEnabled = r["notificationsEnabled"] as? Boolean ?: st.notificationsEnabled,
                                callState = r["callState"]?.let { PhoneLinkCodec.mapToCallInfo(it) } ?: st.callState
                            )
                        }
                        backoffMs = 2_000L
                        delay(HEARTBEAT_INTERVAL_MS)
                    } else {
                        closeActiveSocket()
                        delay(1_500L)
                    }
                    continue
                }

                // Tenta conectar ao último host salvo ou ao celular recém-descoberto
                val target = resolveBestReconnectTarget()
                if (target == null) {
                    sendUdpDiscoveryProbes()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                    continue
                }

                val connected = tryConnectAndAuth(target.first, target.second, token)
                if (connected) {
                    backoffMs = 2_000L
                    delay(HEARTBEAT_INTERVAL_MS)
                } else {
                    sendUdpDiscoveryProbes()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                }
              } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
              } catch (err: Throwable) {
                // Nunca deixar o laço morrer (ex.: a Ponte reiniciou no meio de uma chamada): fecha e tenta de novo.
                android.util.Log.w("PhoneLinkClient", "Laço de reconexão: ${err.message}; tentando de novo.")
                runCatching { closeActiveSocket() }
                delay(3_000L)
              }
            }
        }
    }

    private fun tryConnectAndAuth(host: String, port: Int, token: String): Boolean {
        _state.update {
            it.copy(
                connectionState = PhoneConnectionState.CONNECTING,
                statusMessage = "Conectando a ${it.phoneName.ifBlank { host }}…"
            )
        }
        val sock = runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        }.getOrNull() ?: run {
            _state.update {
                it.copy(
                    connectionState = PhoneConnectionState.DISCONNECTED,
                    statusMessage = "Celular fora de alcance"
                )
            }
            return false
        }

        return try {
            val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val authReq = PhoneLinkMessage.Request(
                id = "auth-${reqCounter.getAndIncrement()}",
                method = PhoneLinkProtocol.METHOD_AUTH,
                params = linkedMapOf(
                    "token" to token,
                    "clientName" to buildTabletName()
                )
            )
            writer.write(PhoneLinkCodec.encode(authReq))
            writer.write("\n")
            writer.flush()

            sock.soTimeout = 6_000
            val line = reader.readLine()
            sock.soTimeout = 0
            val res = line?.let { PhoneLinkCodec.decode(it) } as? PhoneLinkMessage.Response
            if (res == null || !res.ok) {
                runCatching { sock.close() }
                if (res?.errorCode == PhoneLinkProtocol.ERR_UNAUTHORIZED) {
                    prefs.edit { remove(KEY_TOKEN) }
                    _state.update {
                        it.copy(
                            connectionState = PhoneConnectionState.DISCONNECTED,
                            paired = false,
                            statusMessage = "Pareamento expirado — digite o PIN novamente",
                            lastError = res.error
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            connectionState = PhoneConnectionState.DISCONNECTED,
                            statusMessage = res?.error ?: "Falha na autenticação"
                        )
                    }
                }
                false
            } else {
                val phoneName = res.result["phoneName"]?.toString().orEmpty()
                    .ifBlank { prefs.getString(KEY_PHONE_NAME, "Celular").orEmpty() }
                val locked = res.result["locked"] as? Boolean ?: false
                val accOk = res.result["accessibilityEnabled"] as? Boolean ?: false
                val notifOk = res.result["notificationsEnabled"] as? Boolean ?: false
                prefs.edit {
                    putString(KEY_HOST, host)
                    putInt(KEY_PORT, port)
                    putString(KEY_PHONE_NAME, phoneName)
                }
                attachAuthenticatedSocket(sock, writer, reader, host, port, phoneName, locked, accOk, notifOk)
                true
            }
        } catch (err: Throwable) {
            runCatching { sock.close() }
            _state.update {
                it.copy(
                    connectionState = PhoneConnectionState.DISCONNECTED,
                    statusMessage = "Celular desconectado"
                )
            }
            false
        }
    }

    private fun attachAuthenticatedSocket(
        socket: Socket,
        writer: BufferedWriter,
        reader: BufferedReader,
        host: String,
        port: Int,
        phoneName: String,
        locked: Boolean,
        accessibilityEnabled: Boolean,
        notificationsEnabled: Boolean
    ) {
        activeSocket = socket
        activeWriter = writer
        _state.update {
            it.copy(
                connectionState = PhoneConnectionState.CONNECTED,
                paired = true,
                phoneName = phoneName,
                host = host,
                port = port,
                phoneLocked = locked,
                accessibilityEnabled = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                statusMessage = "Conectado a $phoneName",
                lastError = null
            )
        }

        // Leitor contínuo de respostas e eventos
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    when (val msg = PhoneLinkCodec.decode(line)) {
                        is PhoneLinkMessage.Response -> {
                            pendingRpc.remove(msg.id)?.complete(msg)
                        }
                        is PhoneLinkMessage.Event -> {
                            handleServerEvent(msg)
                        }
                        else -> Unit
                    }
                }
            } catch (err: Throwable) {
                Log.d(TAG, "Socket da Ponte encerrado: ${err.message}")
            } finally {
                if (activeSocket === socket) {
                    closeActiveSocket()
                }
            }
        }
    }

    private fun handleServerEvent(ev: PhoneLinkMessage.Event) {
        when (ev.event) {
            PhoneLinkProtocol.EVENT_CALL_STATE -> {
                val info = PhoneLinkCodec.mapToCallInfo(ev.data)
                _state.update { it.copy(callState = info) }
                _events.tryEmit(PhoneLinkClientEvent.CallStateChanged(info))
            }

            PhoneLinkProtocol.EVENT_MESSAGE_RECEIVED -> {
                val notif = PhoneLinkCodec.mapToMessage(ev.data) ?: return
                _state.update { st ->
                    val filtered = st.messages.filterNot { it.id == notif.id }
                    st.copy(messages = (listOf(notif) + filtered).take(20))
                }
                _events.tryEmit(PhoneLinkClientEvent.MessageReceived(notif))
            }

            PhoneLinkProtocol.EVENT_MESSAGE_REMOVED -> {
                val id = ev.data["id"]?.toString().orEmpty()
                if (id.isNotBlank()) {
                    _state.update { st ->
                        st.copy(messages = st.messages.filterNot { it.id == id })
                    }
                }
            }

            PhoneLinkProtocol.EVENT_PHONE_STATUS -> {
                _state.update { st ->
                    st.copy(
                        phoneLocked = ev.data["locked"] as? Boolean ?: st.phoneLocked,
                        accessibilityEnabled = ev.data["accessibilityEnabled"] as? Boolean ?: st.accessibilityEnabled,
                        notificationsEnabled = ev.data["notificationsEnabled"] as? Boolean ?: st.notificationsEnabled
                    )
                }
            }

            PhoneLinkProtocol.EVENT_CONTACTS_UPDATED -> {
                val raw = ev.data["contacts"] as? List<*> ?: return
                val parsed = raw.mapNotNull { PhoneLinkCodec.mapToContact(it) }
                val syncedAt = (ev.data["syncedAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
                contactsCache.save(parsed, syncedAt)
                _state.update {
                    it.copy(
                        contactsCount = parsed.size,
                        contactsSyncedAtMs = syncedAt,
                        contactsSyncing = false,
                        contactsSyncError = null
                    )
                }
                _events.tryEmit(PhoneLinkClientEvent.ContactsSynced(parsed.size, syncedAt))
            }

            PhoneLinkProtocol.EVENT_APP_ICONS -> {
                applyIncomingAppIcons(ev.data)
            }
        }
    }

    suspend fun syncPhoneAppIcons(packages: List<String>? = null): Result<Int> {
        val params = linkedMapOf<String, Any?>(
            "knownHashes" to iconHashes.toMap()
        )
        if (!packages.isNullOrEmpty()) params["packages"] = packages
        val res = sendRpc(PhoneLinkProtocol.METHOD_APPS_ICONS, params, timeoutMs = 15_000L)
        return res.map { r -> applyIncomingAppIcons(r.result) }
    }

    private fun applyIncomingAppIcons(payload: Map<String, Any?>): Int {
        val dir = phoneIconsDir ?: return 0
        dir.mkdirs()

        val rawIcons = payload["icons"] as? List<*> ?: emptyList<Any?>()
        val parsedIcons = rawIcons.mapNotNull { PhoneLinkCodec.mapToAppIcon(it) }
        var changedCount = 0

        for (icon in parsedIcons) {
            val safePkg = WebResourceRouter.parsePackageIconPath("${icon.packageName}.png") ?: continue
            val target = File(dir, "$safePkg.png")
            if (icon.hash.isNotBlank() && iconHashes[safePkg] == icon.hash && target.isFile && target.length() > 0L) {
                continue
            }
            val bytes = runCatching { Base64.decode(icon.pngBase64, Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: continue
            val wrote = runCatching {
                val tmp = File(dir, "$safePkg.png.tmp.${System.nanoTime()}")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
            if (wrote) {
                if (icon.hash.isNotBlank()) iconHashes[safePkg] = icon.hash
                changedCount++
            }
        }

        val incomingRoles = (payload["roles"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val ks = k?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val vs = v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ks to vs
            }
            ?.toMap()
            .orEmpty()

        val mergedRoles = _state.value.appRoles + incomingRoles
        val availablePackages = listCachedPhoneIconPackages()

        prefs.edit {
            putString(KEY_ICON_HASHES, PhoneLinkCodec.encodeValue(iconHashes.toMap()))
            if (mergedRoles.isNotEmpty()) {
                putString(KEY_APP_ROLES, PhoneLinkCodec.encodeValue(mergedRoles))
            }
        }

        if (changedCount > 0 || incomingRoles.isNotEmpty() || availablePackages != _state.value.iconPackages) {
            val nextVer = if (changedCount > 0) iconVersionCounter.incrementAndGet() else _state.value.iconsVersion.coerceAtLeast(1)
            _state.update { st ->
                st.copy(
                    iconsVersion = nextVer,
                    iconPackages = availablePackages,
                    appRoles = mergedRoles
                )
            }
        }
        return changedCount
    }

    private fun listCachedPhoneIconPackages(): Set<String> {
        val dir = phoneIconsDir ?: return emptySet()
        return runCatching {
            dir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".png") }
                ?.mapNotNull { WebResourceRouter.parsePackageIconPath(it.name) }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun clearPhoneIconsCache() {
        iconHashes.clear()
        runCatching {
            phoneIconsDir?.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".png")) f.delete()
            }
        }
    }

    private fun loadPersistedStringMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val parsed = PhoneLinkCodec.parseJsonValue(raw) as? Map<*, *> ?: return emptyMap()
        return parsed.entries.mapNotNull { (k, v) ->
            val ks = k?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val vs = v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ks to vs
        }.toMap()
    }

    private fun closeActiveSocket() {
        val sock = activeSocket
        activeSocket = null
        activeWriter = null
        runCatching { sock?.close() }
        pendingRpc.values.forEach { it.cancel() }
        pendingRpc.clear()
        _state.update { st ->
            if (st.connectionState == PhoneConnectionState.CONNECTED) {
                st.copy(
                    connectionState = PhoneConnectionState.DISCONNECTED,
                    callState = PhoneCallInfo(),
                    statusMessage = "Celular desconectado"
                )
            } else {
                st
            }
        }
    }

    // -------------------------------------------------------------------------
    // Descoberta NSD/mDNS + UDP 5291 + gateway do hotspot
    // -------------------------------------------------------------------------

    private fun startNsdDiscovery() {
        val mgr = nsdManager ?: return
        stopNsdDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD busca iniciada para $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_hondadeck") != true) return
                resolveNsdService(mgr, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD start falhou ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsdDiscoveryListener = listener
        runCatching {
            mgr.discoverServices(PhoneLinkProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNsdService(mgr: NsdManager, info: NsdServiceInfo) {
        runCatching {
            mgr.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port.takeIf { it > 0 } ?: PhoneLinkProtocol.TCP_PORT
                    registerDiscoveredPhone(
                        DiscoveredPhone(
                            name = resolved.serviceName.ifBlank { "HondaDeck Ponte" },
                            host = host,
                            port = port
                        )
                    )
                }
            })
        }
    }

    private fun stopNsdDiscovery() {
        val mgr = nsdManager
        val listener = nsdDiscoveryListener
        if (mgr != null && listener != null) {
            runCatching { mgr.stopServiceDiscovery(listener) }
        }
        nsdDiscoveryListener = null
    }

    private fun startUdpDiscoveryListener() {
        udpListenerJob?.cancel()
        udpListenerJob = scope.launch(Dispatchers.IO) {
            while (isActive && started) {
                try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(PhoneLinkProtocol.UDP_DISCOVERY_PORT))
                    }.use { ds ->
                        val buf = ByteArray(1024)
                        while (isActive && started && !ds.isClosed) {
                            val packet = DatagramPacket(buf, buf.size)
                            ds.receive(packet)
                            val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val ann = PhoneLinkCodec.decodeUdpAnnouncement(payload) ?: continue
                            val host = packet.address?.hostAddress ?: continue
                            registerDiscoveredPhone(
                                DiscoveredPhone(
                                    name = ann.name,
                                    host = host,
                                    port = ann.port
                                )
                            )
                        }
                    }
                } catch (err: Throwable) {
                    if (started) {
                        Log.d(TAG, "UDP listener reiniciando: ${err.message}")
                        delay(3_000L)
                    }
                }
            }
        }
    }

    private fun sendUdpDiscoveryProbes() {
        val probeBytes = PhoneLinkCodec.encodeUdpProbe(buildTabletName()).toByteArray(Charsets.UTF_8)
        val targets = linkedSetOf("255.255.255.255", "192.168.43.1")
        resolveGatewayIpv4()?.let { targets += it }

        runCatching {
            DatagramSocket().use { ds ->
                ds.broadcast = true
                ds.soTimeout = 1_200
                for (ip in targets) {
                    runCatching {
                        val addr = InetAddress.getByName(ip)
                        ds.send(DatagramPacket(probeBytes, probeBytes.size, addr, PhoneLinkProtocol.UDP_DISCOVERY_PORT))
                    }
                }
                val start = System.currentTimeMillis()
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() - start < 1_200L) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val got = runCatching { ds.receive(pkt); true }.getOrDefault(false)
                    if (!got) break
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val ann = PhoneLinkCodec.decodeUdpAnnouncement(text) ?: continue
                    val host = pkt.address?.hostAddress ?: continue
                    registerDiscoveredPhone(DiscoveredPhone(name = ann.name, host = host, port = ann.port))
                }
            }
        }
    }

    private fun registerDiscoveredPhone(phone: DiscoveredPhone) {
        _state.update { st ->
            val updated = (listOf(phone) + st.discoveredPhones.filterNot { it.host == phone.host }).take(6)
            st.copy(discoveredPhones = updated)
        }
    }

    private fun resolveTargetHostForPairing(hostOverride: String?): Pair<String, Int>? {
        val explicit = hostOverride?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            val parts = explicit.split(':')
            val h = parts[0].trim()
            val p = parts.getOrNull(1)?.toIntOrNull() ?: PhoneLinkProtocol.TCP_PORT
            if (h.isNotEmpty()) return h to p
        }
        // Tenta um probe UDP imediato para pegar celulares novos
        sendUdpDiscoveryProbes()
        _state.value.discoveredPhones.firstOrNull()?.let { return it.host to it.port }
        val savedHost = prefs.getString(KEY_HOST, null).orEmpty()
        if (savedHost.isNotBlank()) return savedHost to prefs.getInt(KEY_PORT, PhoneLinkProtocol.TCP_PORT)
        // Fallback: gateway do Wi-Fi (no hotspot do celular, o celular é o gateway!)
        resolveGatewayIpv4()?.let { return it to PhoneLinkProtocol.TCP_PORT }
        return null
    }

    private fun resolveBestReconnectTarget(): Pair<String, Int>? {
        val savedHost = prefs.getString(KEY_HOST, null).orEmpty()
        val savedPort = prefs.getInt(KEY_PORT, PhoneLinkProtocol.TCP_PORT)
        val discovered = _state.value.discoveredPhones.firstOrNull()
        return when {
            discovered != null -> discovered.host to discovered.port
            savedHost.isNotBlank() -> savedHost to savedPort
            else -> resolveGatewayIpv4()?.let { it to PhoneLinkProtocol.TCP_PORT }
        }
    }

    private fun resolveGatewayIpv4(): String? {
        val cm = connectivityManager ?: return null
        return runCatching {
            val active = cm.activeNetwork ?: return@runCatching null
            val lp = cm.getLinkProperties(active) ?: return@runCatching null
            lp.routes
                .mapNotNull { it.gateway }
                .firstOrNull { it is Inet4Address && !it.isAnyLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun savePairing(host: String, port: Int, token: String, phoneName: String) {
        prefs.edit {
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putString(KEY_TOKEN, token)
            putString(KEY_PHONE_NAME, phoneName)
        }
    }

    private fun loadInitialUiState(): PhoneLinkUiState {
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        val host = prefs.getString(KEY_HOST, null).orEmpty()
        val port = prefs.getInt(KEY_PORT, PhoneLinkProtocol.TCP_PORT)
        val name = prefs.getString(KEY_PHONE_NAME, null).orEmpty()
        val snap = contactsCache.current()
        iconHashes.putAll(loadPersistedStringMap(KEY_ICON_HASHES))
        val roles = loadPersistedStringMap(KEY_APP_ROLES)
        val cachedPkgs = listCachedPhoneIconPackages()
        return PhoneLinkUiState(
            paired = token.isNotBlank(),
            phoneName = name,
            host = host,
            port = port,
            contactsCount = snap.count,
            contactsSyncedAtMs = snap.syncedAtMs,
            iconsVersion = if (cachedPkgs.isNotEmpty()) 1 else 0,
            iconPackages = cachedPkgs,
            appRoles = roles,
            statusMessage = if (token.isNotBlank()) "Aguardando ${name.ifBlank { "celular" }}…" else "Celular não pareado"
        )
    }

    private fun buildTabletName(): String =
        listOfNotNull(Build.MANUFACTURER?.trim(), Build.MODEL?.trim())
            .joinToString(" ")
            .ifBlank { "HondaDeck Tablet" }

    companion object {
        private const val TAG = "PhoneLinkClient"
        private const val PREFS_NAME = "hondadeck_phone_link"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_PHONE_NAME = "phone_name"
        private const val KEY_ICON_HASHES = "icon_hashes"
        private const val KEY_APP_ROLES = "app_roles"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

class PhoneLinkRpcException(
    override val message: String,
    val errorCode: String? = null
) : IllegalStateException(message)
