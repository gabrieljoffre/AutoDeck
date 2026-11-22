package com.autodeck.link

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class TabletDiscoveryResult(
    val tabletEndpoint: String?,
    val serverRunningOnPhone: Boolean,
    val phoneIp: String?
)

object AutoDeckLinkPrefs {
    private const val PREFS_NAME = "autodeck_link_prefs"
    private const val KEY_AUTO_BT = "auto_bt_enabled"
    private const val KEY_BT_TRIGGERS = "bt_triggers_csv"
    private const val DEFAULT_TRIGGERS = "TARAMPS,Y3,GALAXY,TAB,AUTODECK"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoBluetoothEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_BT, true)

    fun setAutoBluetoothEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_BT, enabled).apply()
    }

    fun getTriggerKeywords(context: Context): List<String> =
        (prefs(context).getString(KEY_BT_TRIGGERS, DEFAULT_TRIGGERS) ?: DEFAULT_TRIGGERS)
            .split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

    fun setTriggerKeywordsCsv(context: Context, csv: String) {
        prefs(context).edit().putString(KEY_BT_TRIGGERS, csv).apply()
    }
}

object AutoDeckLinkNetwork {

    const val AA_HEADUNIT_SERVER_PORT = 5277
    const val AUTODECK_DISCOVERY_UDP_PORT = 5278

    suspend fun isLocalHeadUnitServerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", AA_HEADUNIT_SERVER_PORT), 400)
            }
            true
        }.getOrDefault(false)
    }

    fun getLocalIpv4Address(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            val preferred = interfaces.firstOrNull { intf ->
                val n = intf.name.lowercase()
                n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")
            } ?: interfaces.firstOrNull()

            preferred?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?: interfaces.flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
        }.getOrNull()
    }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            for (intf in interfaces) {
                for (ifAddr in intf.interfaceAddresses) {
                    if (ifAddr.address is Inet4Address) {
                        ifAddr.broadcast?.let { targets += it }
                    }
                }
            }
        }
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        return targets.toList()
    }

    suspend fun announceToTablet(
        phoneIp: String? = getLocalIpv4Address(),
        aaPort: Int = AA_HEADUNIT_SERVER_PORT,
        listenTimeoutMs: Int = 1_400
    ): TabletDiscoveryResult = withContext(Dispatchers.IO) {
        val serverRunning = isLocalHeadUnitServerRunning()
        val resolvedIp = phoneIp ?: getLocalIpv4Address()
        val payloadStr = if (!resolvedIp.isNullOrBlank()) {
            "AUTODECK_HELLO:$resolvedIp:$aaPort"
        } else {
            "AUTODECK_DISCOVER"
        }
        val payload = payloadStr.toByteArray(Charsets.UTF_8)

        var tabletReply: String? = null
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = listenTimeoutMs

                for (addr in collectBroadcastTargets()) {
                    runCatching {
                        socket.send(
                            DatagramPacket(payload, payload.size, addr, AUTODECK_DISCOVERY_UDP_PORT)
                        )
                    }
                }

                val buf = ByteArray(256)
                val pkt = DatagramPacket(buf, buf.size)
                runCatching {
                    socket.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("AUTODECK_TABLET:")) {
                        tabletReply = msg.removePrefix("AUTODECK_TABLET:")
                    }
                }
            }
        }

        TabletDiscoveryResult(
            tabletEndpoint = tabletReply,
            serverRunningOnPhone = serverRunning,
            phoneIp = resolvedIp
        )
    }

    fun launchAndroidAutoSettings(context: Context): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.google.android.projection.gearhead")
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.google.android.projection.gearhead")
            }
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
