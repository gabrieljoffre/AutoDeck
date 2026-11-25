package com.autodeck.hondadeck.ponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autodeck.protocol.phone.PhoneLinkCodec
import com.autodeck.protocol.phone.PhoneLinkMessage
import com.autodeck.protocol.phone.PhoneLinkProtocol
import com.autodeck.protocol.phone.UdpDiscoveryAnnouncement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serviço em primeiro plano da Ponte no celular:
 * - Servidor TCP JSON por linha na porta 5290 (só aceita clientes pareados com token).
 * - Anúncio NSD/mDNS (`_hondadeck._tcp.`) + escuta/resposta UDP na porta 5291.
 * - Repassa em tempo real estado de chamadas e notificações de mensagens (WhatsApp/SMS) ao tablet.
 */
class PonteServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var storage: PonteStorage
    private lateinit var contactsManager: PonteContactsManager
    private lateinit var callManager: PonteCallManager
    private lateinit var whatsAppManager: PonteWhatsAppManager

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegListener: NsdManager.RegistrationListener? = null

    private val clients = CopyOnWriteArrayList<ClientSession>()
    private val running = AtomicBoolean(false)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        storage = PonteStorage(applicationContext)
        contactsManager = PonteContactsManager(applicationContext)
        callManager = PonteCallManager(applicationContext, contactsManager)
        whatsAppManager = PonteWhatsAppManager(applicationContext, contactsManager)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                storage.setServiceEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REGENERATE_PIN -> {
                val pin = storage.regeneratePin()
                PonteStateHub.update { it.copy(pin = pin, lastActivity = "Novo PIN gerado") }
            }
            ACTION_UNPAIR_ALL -> {
                storage.clearPairedTokens()
                disconnectAllClients()
                PonteStateHub.update {
                    it.copy(
                        pairedCount = 0,
                        connectedTabletName = null,
                        connectedTabletAddress = null,
                        lastActivity = "Todos os tablets foram despareados"
                    )
                }
            }
        }

        storage.setServiceEnabled(true)
        startForeground(NOTIFICATION_ID, buildNotification("Aguardando tablet HondaDeck…"))

        if (running.compareAndSet(false, true)) {
            startServerLoops()
        } else {
            refreshPublishedState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        monitorJob?.cancel()
        unregisterNsd()
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        disconnectAllClients()
        callManager.stopMonitoring()
        scope.cancel()
        PonteStateHub.update {
            it.copy(
                serverRunning = false,
                connectedTabletName = null,
                connectedTabletAddress = null,
                lastActivity = "Serviço da Ponte parado"
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServerLoops() {
        callManager.startMonitoring()
        refreshPublishedState()
        registerNsd()

        // 1) Servidor TCP JSON por linha na porta 5290
        scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                try {
                    val ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PhoneLinkProtocol.TCP_PORT))
                    }
                    serverSocket = ss
                    Log.i(TAG, "Servidor TCP Ponte escutando na porta ${PhoneLinkProtocol.TCP_PORT}")
                    PonteStateHub.update {
                        it.copy(
                            serverRunning = true,
                            localIp = resolveBestLocalIpv4(),
                            pin = storage.getOrCreatePin(),
                            pairedCount = storage.pairedCount()
                        )
                    }
                    while (isActive && running.get() && !ss.isClosed) {
                        val socket = ss.accept()
                        scope.launch(Dispatchers.IO) {
                            handleClientSocket(socket)
                        }
                    }
                } catch (err: Throwable) {
                    if (running.get()) {
                        Log.w(TAG, "Erro no servidor TCP Ponte: ${err.message}")
                        delay(2_000L)
                    }
                }
            }
        }

        // 2) Descoberta UDP na porta 5291 (responde probes e anuncia presença na rede local)
        scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                try {
                    val ds = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(PhoneLinkProtocol.UDP_DISCOVERY_PORT))
                    }
                    udpSocket = ds
                    val buf = ByteArray(1024)
                    Log.i(TAG, "UDP Discovery Ponte escutando na porta ${PhoneLinkProtocol.UDP_DISCOVERY_PORT}")
                    while (isActive && running.get() && !ds.isClosed) {
                        val packet = DatagramPacket(buf, buf.size)
                        ds.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (PhoneLinkCodec.isUdpProbe(msg)) {
                            val replyBytes = PhoneLinkCodec.encodeUdpAnnouncement(
                                UdpDiscoveryAnnouncement(
                                    name = storage.getPhoneDisplayName(),
                                    port = PhoneLinkProtocol.TCP_PORT,
                                    deviceId = storage.getOrCreateDeviceId()
                                )
                            ).toByteArray(Charsets.UTF_8)
                            val reply = DatagramPacket(
                                replyBytes,
                                replyBytes.size,
                                packet.address,
                                packet.port
                            )
                            runCatching { ds.send(reply) }
                        }
                    }
                } catch (err: Throwable) {
                    if (running.get()) {
                        Log.w(TAG, "Erro no UDP Discovery Ponte: ${err.message}")
                        delay(2_500L)
                    }
                }
            }
        }

        // 3) Beacon UDP periódico (facilita descoberta em hotspot Samsung mesmo sem mDNS)
        scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                delay(5_000L)
                val ip = resolveBestLocalIpv4()
                PonteStateHub.update { it.copy(localIp = ip) }
                if (clients.none { it.authenticated }) {
                    sendUdpBroadcastAnnouncement()
                }
            }
        }

        // 4) Observadores de chamadas e notificações -> broadcast para tablets autenticados
        monitorJob = scope.launch {
            launch {
                callManager.callState.collect { callInfo ->
                    broadcastEvent(
                        PhoneLinkProtocol.EVENT_CALL_STATE,
                        PhoneLinkCodec.callInfoToMap(callInfo)
                    )
                }
            }
            launch {
                PonteNotificationBridge.events.collect { ev ->
                    when (ev) {
                        is PonteNotificationBridge.NotifEvent.Received -> {
                            PonteStateHub.logActivity("Mensagem repassada: ${ev.message.sender} (${ev.message.appLabel})")
                            broadcastEvent(
                                PhoneLinkProtocol.EVENT_MESSAGE_RECEIVED,
                                PhoneLinkCodec.messageToMap(ev.message)
                            )
                        }
                        is PonteNotificationBridge.NotifEvent.Removed -> {
                            broadcastEvent(
                                PhoneLinkProtocol.EVENT_MESSAGE_REMOVED,
                                linkedMapOf("id" to ev.id)
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress.orEmpty()
        socket.tcpNoDelay = true
        val session = ClientSession(socket = socket, remoteIp = remoteIp)
        clients += session
        Log.i(TAG, "Cliente TCP conectado: $remoteIp")

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (running.get() && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val msg = PhoneLinkCodec.decode(line)
                if (msg is PhoneLinkMessage.Request) {
                    val response = dispatchRequest(session, msg)
                    session.send(response)
                }
            }
        } catch (err: Throwable) {
            Log.d(TAG, "Conexão encerrada ($remoteIp): ${err.message}")
        } finally {
            clients -= session
            session.close()
            syncConnectedTabletUi()
        }
    }

    private suspend fun dispatchRequest(
        session: ClientSession,
        req: PhoneLinkMessage.Request
    ): PhoneLinkMessage.Response {
        // 1) Pareamento com PIN de 6 dígitos
        if (req.method == PhoneLinkProtocol.METHOD_PAIR) {
            val pin = req.params["pin"]?.toString().orEmpty()
            val clientName = req.params["clientName"]?.toString().orEmpty().ifBlank { "HondaDeck Tablet" }
            if (!storage.verifyPin(pin)) {
                PonteStateHub.logActivity("Tentativa de pareamento com PIN inválido ($clientName)")
                return PhoneLinkMessage.Response(
                    id = req.id,
                    ok = false,
                    error = "PIN incorreto. Confira os 6 dígitos exibidos no app HondaDeck Ponte.",
                    errorCode = PhoneLinkProtocol.ERR_INVALID_PIN
                )
            }
            val token = storage.issueToken(clientName)
            session.authenticated = true
            session.clientName = clientName
            // Rotaciona o PIN após pareamento bem-sucedido por segurança
            val nextPin = storage.regeneratePin()
            PonteStateHub.update {
                it.copy(
                    pin = nextPin,
                    pairedCount = storage.pairedCount(),
                    lastActivity = "Tablet \"$clientName\" pareado com sucesso"
                )
            }
            syncConnectedTabletUi()
            sendInitialSnapshotTo(session)
            return PhoneLinkMessage.Response(
                id = req.id,
                ok = true,
                result = linkedMapOf(
                    "token" to token,
                    "phoneName" to storage.getPhoneDisplayName(),
                    "deviceId" to storage.getOrCreateDeviceId(),
                    "locked" to whatsAppManager.isDeviceLocked(),
                    "accessibilityEnabled" to PonteAccessibilityController.isEnabledInSettings(this),
                    "notificationsEnabled" to PonteNotificationListenerService.isPermissionGranted(this)
                )
            )
        }

        // 2) Autenticação por token salvo
        if (req.method == PhoneLinkProtocol.METHOD_AUTH) {
            val token = req.params["token"]?.toString()
            val clientName = req.params["clientName"]?.toString().orEmpty().ifBlank { "HondaDeck Tablet" }
            if (!storage.isValidToken(token)) {
                return PhoneLinkMessage.Response(
                    id = req.id,
                    ok = false,
                    error = "Token inválido ou revogado. Faça o pareamento novamente com o PIN.",
                    errorCode = PhoneLinkProtocol.ERR_UNAUTHORIZED
                )
            }
            session.authenticated = true
            session.clientName = clientName
            syncConnectedTabletUi()
            sendInitialSnapshotTo(session)
            return PhoneLinkMessage.Response(
                id = req.id,
                ok = true,
                result = linkedMapOf(
                    "phoneName" to storage.getPhoneDisplayName(),
                    "deviceId" to storage.getOrCreateDeviceId(),
                    "locked" to whatsAppManager.isDeviceLocked(),
                    "accessibilityEnabled" to PonteAccessibilityController.isEnabledInSettings(this),
                    "notificationsEnabled" to PonteNotificationListenerService.isPermissionGranted(this),
                    "callState" to PhoneLinkCodec.callInfoToMap(callManager.callState.value)
                )
            )
        }

        // Todos os demais comandos exigem cliente pareado e autenticado
        if (!session.authenticated) {
            return PhoneLinkMessage.Response(
                id = req.id,
                ok = false,
                error = "Cliente não autenticado. Envie auth ou pair primeiro.",
                errorCode = PhoneLinkProtocol.ERR_UNAUTHORIZED
            )
        }

        return when (req.method) {
            PhoneLinkProtocol.METHOD_PING -> PhoneLinkMessage.Response(
                id = req.id,
                ok = true,
                result = linkedMapOf(
                    "pong" to System.currentTimeMillis(),
                    "locked" to whatsAppManager.isDeviceLocked(),
                    "accessibilityEnabled" to PonteAccessibilityController.isEnabledInSettings(this),
                    "notificationsEnabled" to PonteNotificationListenerService.isPermissionGranted(this),
                    "callState" to PhoneLinkCodec.callInfoToMap(callManager.callState.value)
                )
            )

            PhoneLinkProtocol.METHOD_STATUS_GET -> PhoneLinkMessage.Response(
                id = req.id,
                ok = true,
                result = linkedMapOf(
                    "phoneName" to storage.getPhoneDisplayName(),
                    "locked" to whatsAppManager.isDeviceLocked(),
                    "accessibilityEnabled" to PonteAccessibilityController.isEnabledInSettings(this),
                    "notificationsEnabled" to PonteNotificationListenerService.isPermissionGranted(this),
                    "callState" to PhoneLinkCodec.callInfoToMap(callManager.callState.value)
                )
            )

            PhoneLinkProtocol.METHOD_CONTACTS_SEARCH -> {
                if (!contactsManager.hasPermission()) {
                    PhoneLinkMessage.Response(
                        id = req.id,
                        ok = false,
                        error = "Permissão de contatos não concedida no celular.",
                        errorCode = PhoneLinkProtocol.ERR_PERMISSION_DENIED
                    )
                } else {
                    val q = req.params["query"]?.toString().orEmpty()
                    val found = contactsManager.search(q)
                    PonteStateHub.logActivity("Busca de contato: \"$q\" (${found.size} resultado(s))")
                    PhoneLinkMessage.Response(
                        id = req.id,
                        ok = true,
                        result = linkedMapOf(
                            "contacts" to found.map { PhoneLinkCodec.contactToMap(it) }
                        )
                    )
                }
            }

            PhoneLinkProtocol.METHOD_CALL_DIAL -> {
                val target = req.params["number"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: req.params["contact"]?.toString().orEmpty()
                val nameHint = req.params["contact"]?.toString().orEmpty()
                val res = callManager.dial(target, nameHint)
                res.fold(
                    onSuccess = { msg ->
                        PonteStateHub.logActivity(msg)
                        PhoneLinkMessage.Response(id = req.id, ok = true, result = linkedMapOf("message" to msg))
                    },
                    onFailure = { err ->
                        PhoneLinkMessage.Response(
                            id = req.id,
                            ok = false,
                            error = err.message ?: "Erro ao iniciar ligação.",
                            errorCode = if (err is SecurityException) PhoneLinkProtocol.ERR_PERMISSION_DENIED else PhoneLinkProtocol.ERR_INTERNAL
                        )
                    }
                )
            }

            PhoneLinkProtocol.METHOD_CALL_ANSWER -> {
                callManager.answer().fold(
                    onSuccess = { msg ->
                        PonteStateHub.logActivity(msg)
                        PhoneLinkMessage.Response(id = req.id, ok = true, result = linkedMapOf("message" to msg))
                    },
                    onFailure = { err ->
                        PhoneLinkMessage.Response(
                            id = req.id,
                            ok = false,
                            error = err.message ?: "Erro ao atender chamada.",
                            errorCode = PhoneLinkProtocol.ERR_PERMISSION_DENIED
                        )
                    }
                )
            }

            PhoneLinkProtocol.METHOD_CALL_END -> {
                callManager.end().fold(
                    onSuccess = { msg ->
                        PonteStateHub.logActivity(msg)
                        PhoneLinkMessage.Response(id = req.id, ok = true, result = linkedMapOf("message" to msg))
                    },
                    onFailure = { err ->
                        PhoneLinkMessage.Response(
                            id = req.id,
                            ok = false,
                            error = err.message ?: "Erro ao encerrar chamada.",
                            errorCode = PhoneLinkProtocol.ERR_PERMISSION_DENIED
                        )
                    }
                )
            }

            PhoneLinkProtocol.METHOD_WHATSAPP_SEND_TEXT -> {
                val number = req.params["number"]?.toString().orEmpty()
                val text = req.params["text"]?.toString().orEmpty()
                val contactName = req.params["contactName"]?.toString().orEmpty()
                val out = whatsAppManager.sendText(number, text, contactName)
                PonteStateHub.logActivity(out.message)
                PhoneLinkMessage.Response(
                    id = req.id,
                    ok = out.ok,
                    result = if (out.ok) linkedMapOf("message" to out.message) else emptyMap(),
                    error = if (!out.ok) out.message else null,
                    errorCode = out.errorCode
                )
            }

            PhoneLinkProtocol.METHOD_WHATSAPP_SEND_VOICE -> {
                val number = req.params["number"]?.toString().orEmpty()
                val audioB64 = req.params["audioBase64"]?.toString().orEmpty()
                val mime = req.params["mimeType"]?.toString().orEmpty()
                val fileName = req.params["fileName"]?.toString().orEmpty()
                val contactName = req.params["contactName"]?.toString().orEmpty()
                val out = whatsAppManager.sendVoice(number, audioB64, mime, fileName, contactName)
                PonteStateHub.logActivity(out.message)
                PhoneLinkMessage.Response(
                    id = req.id,
                    ok = out.ok,
                    result = if (out.ok) linkedMapOf("message" to out.message) else emptyMap(),
                    error = if (!out.ok) out.message else null,
                    errorCode = out.errorCode
                )
            }

            PhoneLinkProtocol.METHOD_WHATSAPP_CALL -> {
                val number = req.params["number"]?.toString().orEmpty()
                val contactName = req.params["contactName"]?.toString().orEmpty()
                val voipId = (req.params["voipDataId"] as? Number)?.toLong()
                val out = whatsAppManager.voiceCall(number, contactName, voipId)
                PonteStateHub.logActivity(out.message)
                PhoneLinkMessage.Response(
                    id = req.id,
                    ok = out.ok,
                    result = if (out.ok) linkedMapOf("message" to out.message) else emptyMap(),
                    error = if (!out.ok) out.message else null,
                    errorCode = out.errorCode
                )
            }

            PhoneLinkProtocol.METHOD_MESSAGES_LIST -> {
                val msgs = PonteNotificationBridge.listMessages()
                PhoneLinkMessage.Response(
                    id = req.id,
                    ok = true,
                    result = linkedMapOf("messages" to msgs.map { PhoneLinkCodec.messageToMap(it) })
                )
            }

            PhoneLinkProtocol.METHOD_MESSAGES_REPLY -> {
                val id = req.params["id"]?.toString().orEmpty()
                val text = req.params["text"]?.toString().orEmpty()
                PonteNotificationBridge.reply(applicationContext, id, text).fold(
                    onSuccess = { msg ->
                        PonteStateHub.logActivity(msg)
                        PhoneLinkMessage.Response(id = req.id, ok = true, result = linkedMapOf("message" to msg))
                    },
                    onFailure = { err ->
                        PhoneLinkMessage.Response(
                            id = req.id,
                            ok = false,
                            error = err.message ?: "Não foi possível responder a mensagem.",
                            errorCode = PhoneLinkProtocol.ERR_NOT_FOUND
                        )
                    }
                )
            }

            PhoneLinkProtocol.METHOD_MESSAGES_DISMISS -> {
                val id = req.params["id"]?.toString().orEmpty()
                PonteNotificationBridge.dismiss(id)
                PhoneLinkMessage.Response(id = req.id, ok = true)
            }

            else -> PhoneLinkMessage.Response(
                id = req.id,
                ok = false,
                error = "Método desconhecido: ${req.method}",
                errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
            )
        }
    }

    private fun sendInitialSnapshotTo(session: ClientSession) {
        scope.launch(Dispatchers.IO) {
            delay(120L)
            session.send(
                PhoneLinkMessage.Event(
                    PhoneLinkProtocol.EVENT_CALL_STATE,
                    PhoneLinkCodec.callInfoToMap(callManager.callState.value)
                )
            )
            PonteNotificationBridge.listMessages().reversed().forEach { msg ->
                session.send(
                    PhoneLinkMessage.Event(
                        PhoneLinkProtocol.EVENT_MESSAGE_RECEIVED,
                        PhoneLinkCodec.messageToMap(msg)
                    )
                )
            }
        }
    }

    private fun broadcastEvent(event: String, data: Map<String, Any?>) {
        val env = PhoneLinkMessage.Event(event = event, data = data)
        clients.filter { it.authenticated }.forEach { client ->
            scope.launch(Dispatchers.IO) {
                client.send(env)
            }
        }
    }

    private fun syncConnectedTabletUi() {
        val active = clients.firstOrNull { it.authenticated }
        PonteStateHub.update {
            it.copy(
                connectedTabletName = active?.clientName,
                connectedTabletAddress = active?.remoteIp,
                pairedCount = storage.pairedCount()
            )
        }
        val notifText = if (active != null) {
            "Conectado a ${active.clientName} (${active.remoteIp})"
        } else {
            "Aguardando tablet HondaDeck · PIN ${storage.getOrCreatePin()}"
        }
        updateNotification(notifText)
    }

    private fun refreshPublishedState() {
        callManager.startMonitoring()
        PonteStateHub.update {
            it.copy(
                serverRunning = running.get(),
                localIp = resolveBestLocalIpv4(),
                pin = storage.getOrCreatePin(),
                pairedCount = storage.pairedCount(),
                callState = callManager.callState.value
            )
        }
    }

    private fun disconnectAllClients() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun registerNsd() {
        unregisterNsd()
        val mgr = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr
        val info = NsdServiceInfo().apply {
            serviceName = "${PhoneLinkProtocol.NSD_SERVICE_NAME}-${storage.getOrCreateDeviceId().take(6)}"
            serviceType = PhoneLinkProtocol.NSD_SERVICE_TYPE
            port = PhoneLinkProtocol.TCP_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD registrado: ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registro falhou ($errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdRegListener = listener
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unregisterNsd() {
        val mgr = nsdManager
        val listener = nsdRegListener
        if (mgr != null && listener != null) {
            runCatching { mgr.unregisterService(listener) }
        }
        nsdRegListener = null
    }

    private fun sendUdpBroadcastAnnouncement() {
        val ds = udpSocket ?: return
        val bytes = PhoneLinkCodec.encodeUdpAnnouncement(
            UdpDiscoveryAnnouncement(
                name = storage.getPhoneDisplayName(),
                port = PhoneLinkProtocol.TCP_PORT,
                deviceId = storage.getOrCreateDeviceId()
            )
        ).toByteArray(Charsets.UTF_8)
        runCatching {
            val packet = DatagramPacket(
                bytes,
                bytes.size,
                InetAddress.getByName("255.255.255.255"),
                PhoneLinkProtocol.UDP_DISCOVERY_PORT
            )
            ds.send(packet)
        }
    }

    private fun resolveBestLocalIpv4(): String {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val n = iface.name.lowercase()
                val priority = when {
                    n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") -> 1
                    n.startsWith("rndis") || n.startsWith("eth") -> 2
                    else -> 3
                }
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates += priority to (addr.hostAddress ?: continue)
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second.orEmpty()
        }.getOrDefault("")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HondaDeck Ponte",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a ligação local entre o celular e o painel HondaDeck"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ponte_launcher)
            .setContentTitle("HondaDeck Ponte ativo")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private class ClientSession(
        val socket: Socket,
        val remoteIp: String
    ) {
        @Volatile
        var authenticated: Boolean = false

        @Volatile
        var clientName: String = "HondaDeck Tablet"

        private val writeLock = Any()
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        fun send(message: PhoneLinkMessage) {
            val line = PhoneLinkCodec.encode(message)
            synchronized(writeLock) {
                runCatching {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "PonteServerService"
        private const val CHANNEL_ID = "hondadeck_ponte_service"
        private const val NOTIFICATION_ID = 5290

        const val ACTION_START = "com.autodeck.hondadeck.ponte.START"
        const val ACTION_STOP = "com.autodeck.hondadeck.ponte.STOP"
        const val ACTION_REGENERATE_PIN = "com.autodeck.hondadeck.ponte.REGEN_PIN"
        const val ACTION_UNPAIR_ALL = "com.autodeck.hondadeck.ponte.UNPAIR_ALL"

        fun start(context: Context) {
            val intent = Intent(context, PonteServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PonteServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, PonteServerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
