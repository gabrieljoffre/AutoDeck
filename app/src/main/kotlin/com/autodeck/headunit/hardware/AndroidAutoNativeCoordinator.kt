package com.autodeck.headunit.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.autodeck.protocol.coordinator.LinkStatus
import com.uniplay.androidauto.AndroidAutoEngine
import com.uniplay.androidauto.AndroidAutoWirelessManager
import com.uniplay.app.LocalHotspot
import com.uniplay.core.AudioChannel
import com.uniplay.core.LeanAudioTrack
import com.uniplay.core.MediaSink
import com.uniplay.core.SocketTransportChannel
import com.uniplay.core.TransportType
import com.uniplay.core.VideoLayout
import com.uniplay.core.ZeroCopyVideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receptor nativo de Android Auto (AAP) embutido no AutoDeck:
 * 1. Conecta automaticamente ao servidor do celular pelo gateway Wi-Fi na porta 5277.
 * 2. Aceita conexões sem fio oficiais (Bluetooth RFCOMM UUID 4de17a00-... + TCP porta 5000)
 *    inclusive com modo convidado (Hotspot local + Bluetooth visível) para qualquer celular.
 */
class AndroidAutoNativeCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val engine = AndroidAutoEngine(appContext)
    private val wirelessManager = AndroidAutoWirelessManager(appContext) { phoneName ->
        _detailMessage.value = "Pareando Android Auto sem fio com $phoneName..."
    }
    private val localHotspot = LocalHotspot(appContext)

    private val _status = MutableStateFlow(LinkStatus.DISCONNECTED)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _detailMessage = MutableStateFlow("Procurando Android Auto no Wi-Fi (porta 5277) ou Bluetooth...")
    val detailMessage: StateFlow<String> = _detailMessage.asStateFlow()

    private val _guestHotspotInfo = MutableStateFlow<String?>(null)
    val guestHotspotInfo: StateFlow<String?> = _guestHotspotInfo.asStateFlow()

    private val _guestWifiQrPayload = MutableStateFlow<String?>(null)
    val guestWifiQrPayload: StateFlow<String?> = _guestWifiQrPayload.asStateFlow()

    private val started = AtomicBoolean(false)
    private val stateLock = Any()

    private var currentSurface: Surface? = null
    private var videoDecoder: ZeroCopyVideoDecoder? = null
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var lastVideoWidth: Int = VideoLayout.CANVAS_WIDTH
    private var lastVideoHeight: Int = VideoLayout.CANVAS_HEIGHT
    private var renderPaused: Boolean = false
    private var audioDucked: Boolean = false

    private var mediaAudioTrack: LeanAudioTrack? = null
    private var speechAudioTrack: LeanAudioTrack? = null
    private var navAudioTrack: LeanAudioTrack? = null

    private var gatewayProbeJob: Job? = null
    private var tcpServerSocket: ServerSocket? = null
    private var tcpServerThread: Thread? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private var udpDiscoveryThread: Thread? = null
    private var btAclReceiverRegistered = false
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var ignoredObdMac: String? = "00:10:CC:4F:36:03"

    @Volatile
    private var activeCreds: LocalHotspot.Credentials? = null

    private val mediaSink = object : MediaSink {
        override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
            synchronized(stateLock) {
                lastSps = sps
                lastPps = pps
                if (width > 0 && height > 0) {
                    lastVideoWidth = width
                    lastVideoHeight = height
                }
                if (!renderPaused) {
                    ensureDecoderLocked()?.configure(sps, pps, lastVideoWidth, lastVideoHeight)
                }
            }
        }

        override fun onVideoNal(nalUnit: ByteArray, offset: Int, length: Int, timestampUs: Long) {
            val decoder = synchronized(stateLock) {
                if (renderPaused) return
                ensureDecoderLocked()
            } ?: return
            decoder.feedNalUnit(nalUnit, offset, length, timestampUs)
        }

        override fun onAudioData(channel: AudioChannel, pcmData: ByteArray, sampleRate: Int, channels: Int) {
            val track = synchronized(stateLock) {
                getOrCreateAudioTrackLocked(channel, sampleRate, channels)
            }
            track.write(pcmData)
        }

        override fun onSessionTerminated(reason: String) {
            synchronized(stateLock) {
                lastSps = null
                lastPps = null
                stopMediaLocked()
            }
            if (started.get()) {
                _status.value = LinkStatus.STANDBY
                _detailMessage.value = "Aguardando celular ($reason)"
                scope.launch(Dispatchers.IO) {
                    delay(600L)
                    if (started.get() && !engine.isRunning) {
                        tryConnectGatewayOnce()
                    }
                }
            } else {
                _status.value = LinkStatus.DISCONNECTED
            }
        }
    }

    private val btAclReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val device = IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java
            ) ?: return
            if (wirelessManager.shouldIgnoreDevice(device)) return

            val creds = activeCreds
            if (creds != null && !engine.isRunning) {
                wirelessManager.pokeBondedDevices(creds.ssid, creds.psk, creds.ip, AA_SERVER_PORT)
            }
            if (!engine.isRunning && started.get()) {
                scope.launch(Dispatchers.IO) {
                    tryConnectGatewayOnce()
                }
            }
        }
    }

    init {
        ConscryptInitializer.initialize()
    }

    fun setIgnoredObdMac(mac: String?) {
        ignoredObdMac = mac
        wirelessManager.ignoredMacAddress = mac
    }

    fun start() {
        if (started.getAndSet(true)) return
        _status.value = LinkStatus.STANDBY
        _detailMessage.value = "Aguardando Android Auto (Wi-Fi porta 5277 ou Bluetooth)"
        startIncomingTcpServer()
        startUdpDiscoveryServer()
        registerBtAclReceiver()
        registerWifiCallback()
        startGatewayProbeLoop()
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        gatewayProbeJob?.cancel()
        gatewayProbeJob = null
        unregisterWifiCallback()
        unregisterBtAclReceiver()
        stopUdpDiscoveryServer()
        stopIncomingTcpServer()
        wirelessManager.stop()
        localHotspot.stop()
        activeCreds = null
        _guestHotspotInfo.value = null
        _guestWifiQrPayload.value = null
        runCatching { engine.stop() }
        synchronized(stateLock) {
            lastSps = null
            lastPps = null
            stopMediaLocked()
        }
        _status.value = LinkStatus.DISCONNECTED
        _detailMessage.value = "Android Auto nativo parado"
    }

    fun retryNow() {
        if (!started.get()) start()
        scope.launch(Dispatchers.IO) {
            if (!engine.isRunning) {
                _detailMessage.value = "Tentando conectar ao celular na rede..."
                val connected = tryConnectGatewayOnce()
                if (!connected) {
                    val creds = activeCreds
                    if (creds != null) {
                        wirelessManager.pokeBondedDevices(creds.ssid, creds.psk, creds.ip, AA_SERVER_PORT)
                    }
                }
            }
        }
    }

    fun startGuestWirelessPairing() {
        if (!started.get()) start()
        _detailMessage.value = "Abrindo Hotspot de convidado + Bluetooth Android Auto..."
        localHotspot.start(
            onReady = { creds ->
                activeCreds = creds
                _guestHotspotInfo.value = "Wi-Fi: ${creds.ssid}  |  Senha: ${creds.psk}"
                _guestWifiQrPayload.value = buildWifiQrPayload(creds.ssid, creds.psk)
                _detailMessage.value = "Aponte a câmera do outro celular para o QR Code ou conecte no Bluetooth."
                wirelessManager.ignoredMacAddress = ignoredObdMac
                wirelessManager.start(creds.ssid, creds.psk, creds.ip, AA_SERVER_PORT)
            },
            onFailed = { err ->
                val ip = resolveLocalWifiIp()
                _guestHotspotInfo.value = if (ip != null) "Servidor TCP ativo em $ip:$AA_SERVER_PORT" else null
                _guestWifiQrPayload.value = null
                _detailMessage.value = "$err. Mantenha o celular no mesmo Wi-Fi ou pareie via Bluetooth."
            }
        )
    }

    fun attachSurface(surface: Surface?, width: Int, height: Int) {
        synchronized(stateLock) {
            currentSurface = surface
            if (width > 0 && height > 0) {
                lastVideoWidth = VideoLayout.CANVAS_WIDTH
                lastVideoHeight = VideoLayout.CANVAS_HEIGHT
            }
            if (surface == null) {
                videoDecoder?.stop()
                videoDecoder = null
            } else if (engine.isRunning && !renderPaused) {
                ensureDecoderLocked()
                engine.requestKeyframe()
            }
        }
    }

    fun setRenderPaused(paused: Boolean) {
        synchronized(stateLock) {
            if (renderPaused == paused) return
            renderPaused = paused
            if (paused) {
                videoDecoder?.stop()
                videoDecoder = null
            } else if (currentSurface != null && engine.isRunning) {
                ensureDecoderLocked()
                engine.requestKeyframe()
            }
        }
    }

    fun setAudioDucking(duck: Boolean) {
        synchronized(stateLock) {
            audioDucked = duck
            val vol = if (duck) 0.25f else 1.0f
            mediaAudioTrack?.setVolume(vol)
            speechAudioTrack?.setVolume(vol)
            navAudioTrack?.setVolume(vol)
        }
    }

    fun sendMotionEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (!engine.isRunning || viewWidth <= 0 || viewHeight <= 0) return
        val scaledX = (event.x / viewWidth.toFloat()).coerceIn(0f, 1f) * VideoLayout.CANVAS_WIDTH
        val scaledY = (event.y / viewHeight.toFloat()).coerceIn(0f, 1f) * VideoLayout.CANVAS_HEIGHT
        engine.sendTouchEvent(event.actionMasked, scaledX, scaledY)
    }

    private fun startGatewayProbeLoop() {
        gatewayProbeJob?.cancel()
        gatewayProbeJob = scope.launch(Dispatchers.IO) {
            while (isActive && started.get()) {
                if (!engine.isRunning) {
                    tryConnectGatewayOnce()
                }
                delay(GATEWAY_PROBE_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun tryConnectGatewayOnce(): Boolean {
        if (!started.get() || engine.isRunning) return false
        val gatewayIp = resolveWifiGatewayIp() ?: return false
        return tryConnectHost(
            host = gatewayIp,
            port = AA_HEADUNIT_SERVER_PORT,
            label = "Conectado ao celular ($gatewayIp:$AA_HEADUNIT_SERVER_PORT)"
        )
    }

    @Synchronized
    private fun tryConnectHost(host: String, port: Int, label: String): Boolean {
        if (!started.get() || engine.isRunning || host.isBlank() || port !in 1..65535) return false
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 1_200)
            val transport = SocketTransportChannel(socket, TransportType.WIRELESS_WIFI)
            startEngineSession(transport, label)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startUdpDiscoveryServer() {
        if (udpDiscoveryThread != null) return
        udpDiscoveryThread = Thread({
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AA_DISCOVERY_UDP_PORT))
                    broadcast = true
                }
                udpDiscoverySocket = socket
                val buf = ByteArray(512)
                while (started.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    val senderIp = pkt.address?.hostAddress ?: continue
                    val tabletIp = activeCreds?.ip ?: resolveLocalWifiIp() ?: continue

                    if (msg.startsWith("AUTODECK_DISCOVER") || msg.startsWith("AUTODECK_HELLO:")) {
                        val reply = "AUTODECK_TABLET:$tabletIp:$AA_SERVER_PORT".toByteArray(Charsets.UTF_8)
                        runCatching {
                            socket.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        }
                    }

                    if (msg.startsWith("AUTODECK_HELLO:") && !engine.isRunning) {
                        val parts = msg.removePrefix("AUTODECK_HELLO:").split(':')
                        val phoneIp = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: senderIp
                        val phonePort = parts.getOrNull(1)?.toIntOrNull() ?: AA_HEADUNIT_SERVER_PORT
                        scope.launch(Dispatchers.IO) {
                            tryConnectHost(
                                host = phoneIp,
                                port = phonePort,
                                label = "Conectado via AutoDeck Link ($phoneIp:$phonePort)"
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "AutoDeck-AA-UdpDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopUdpDiscoveryServer() {
        runCatching { udpDiscoverySocket?.close() }
        udpDiscoverySocket = null
        udpDiscoveryThread?.interrupt()
        udpDiscoveryThread = null
    }

    private fun startIncomingTcpServer() {
        if (tcpServerThread != null) return
        tcpServerThread = Thread({
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AA_SERVER_PORT))
                }
                tcpServerSocket = server
                while (started.get()) {
                    val client = server.accept() ?: break
                    val remote = client.inetAddress?.hostAddress ?: "celular"
                    if (engine.isRunning) {
                        runCatching { client.close() }
                        continue
                    }
                    val transport = SocketTransportChannel(client, TransportType.WIRELESS_WIFI)
                    startEngineSession(transport, "Conectado sem fio ($remote)")
                }
            } catch (_: Exception) {
            }
        }, "AutoDeck-AA-TcpServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopIncomingTcpServer() {
        runCatching { tcpServerSocket?.close() }
        tcpServerSocket = null
        tcpServerThread?.interrupt()
        tcpServerThread = null
    }

    private fun startEngineSession(transport: SocketTransportChannel, label: String) {
        _status.value = LinkStatus.CONNECTING
        _detailMessage.value = "$label — iniciando projeção..."
        synchronized(stateLock) {
            if (!renderPaused && currentSurface?.isValid == true) {
                ensureDecoderLocked()
            }
        }
        engine.start(transport, mediaSink)
    }

    private fun ensureDecoderLocked(): ZeroCopyVideoDecoder? {
        val existing = videoDecoder
        if (existing != null) return existing
        val surface = currentSurface ?: return null
        if (!surface.isValid) return null

        val decoder = ZeroCopyVideoDecoder(
            surface = surface,
            onCodecError = { err ->
                _detailMessage.value = "Erro no decodificador H.264: ${err.message}"
            },
            onFirstFrameRendered = { w, h ->
                _status.value = LinkStatus.CONNECTED
                _detailMessage.value = "Android Auto ativo (${w}x$h)"
            },
            onNeedKeyframe = {
                engine.requestKeyframe()
            }
        )
        val sps = lastSps
        val pps = lastPps
        if (sps != null && pps != null) {
            decoder.configure(sps, pps, lastVideoWidth, lastVideoHeight)
        }
        videoDecoder = decoder
        return decoder
    }

    private fun getOrCreateAudioTrackLocked(
        channel: AudioChannel,
        sampleRate: Int,
        channels: Int
    ): LeanAudioTrack {
        val existing = when (channel) {
            AudioChannel.MEDIA -> mediaAudioTrack
            AudioChannel.SPEECH_SIRI_ASSISTANT -> speechAudioTrack
            AudioChannel.NAVIGATION -> navAudioTrack
        }
        if (existing != null && existing.sampleRate == sampleRate && existing.channelCount == channels) {
            return existing
        }
        existing?.stop()
        val created = LeanAudioTrack(channel, sampleRate, channels).apply {
            setVolume(if (audioDucked) 0.25f else 1.0f)
            start()
        }
        when (channel) {
            AudioChannel.MEDIA -> mediaAudioTrack = created
            AudioChannel.SPEECH_SIRI_ASSISTANT -> speechAudioTrack = created
            AudioChannel.NAVIGATION -> navAudioTrack = created
        }
        return created
    }

    private fun stopMediaLocked() {
        videoDecoder?.stop()
        videoDecoder = null
        mediaAudioTrack?.stop()
        mediaAudioTrack = null
        speechAudioTrack?.stop()
        speechAudioTrack = null
        navAudioTrack?.stop()
        navAudioTrack = null
    }

    @Suppress("DEPRECATION")
    private fun resolveWifiGatewayIp(): String? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val props = cm.getLinkProperties(active)
                val gw = props?.routes?.firstOrNull { route ->
                    route.isDefaultRoute && route.gateway is Inet4Address
                }?.gateway?.hostAddress
                if (!gw.isNullOrBlank() && gw != "0.0.0.0") return gw
            }
        }
        val wm = appContext.getSystemService(WifiManager::class.java)
        val gwInt = wm?.dhcpInfo?.gateway ?: 0
        if (gwInt == 0) return null
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(gwInt).array()
        return runCatching {
            val addr = Inet4Address.getByAddress(bytes).hostAddress
            if (addr == "0.0.0.0") null else addr
        }.getOrNull()
    }

    private fun resolveLocalWifiIp(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun registerWifiCallback() {
        if (wifiNetworkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerImmediateWifiProbe()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                triggerImmediateWifiProbe()
            }
        }
        wifiNetworkCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun unregisterWifiCallback() {
        val cb = wifiNetworkCallback ?: return
        wifiNetworkCallback = null
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun triggerImmediateWifiProbe() {
        if (!started.get() || engine.isRunning) return
        scope.launch(Dispatchers.IO) {
            delay(350L)
            if (started.get() && !engine.isRunning) {
                tryConnectGatewayOnce()
            }
        }
    }

    private fun registerBtAclReceiver() {
        if (btAclReceiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            btAclReceiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
            ContextCompat.RECEIVER_EXPORTED
        )
        btAclReceiverRegistered = true
    }

    private fun unregisterBtAclReceiver() {
        if (!btAclReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(btAclReceiver) }
        btAclReceiverRegistered = false
    }

    companion object {
        const val AA_HEADUNIT_SERVER_PORT = 5277
        const val AA_SERVER_PORT = 5000
        const val AA_DISCOVERY_UDP_PORT = 5278
        private const val GATEWAY_PROBE_INTERVAL_MS = 3_000L

        fun buildWifiQrPayload(ssid: String, password: String): String {
            val escSsid = ssid.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
            val escPwd = password.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
            return "WIFI:T:WPA;S:$escSsid;P:$escPwd;;"
        }

        @SuppressLint("MissingPermission")
        fun buildDiscoverableBluetoothIntent(durationSec: Int = 120): Intent {
            return Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec)
            }
        }

        fun hasBluetoothEnabled(context: Context): Boolean {
            val mgr = context.getSystemService(BluetoothManager::class.java)
            return mgr?.adapter?.isEnabled == true
        }
    }
}
