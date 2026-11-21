package com.autodeck.headunit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autodeck.headunit.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (`connectedDevice`) responsável por manter Carlinkit, OBD-II,
 * monitoramento de energia pós-chave e overlay da câmera de ré ativos mesmo quando outro
 * aplicativo (ex: HUR) estiver em primeiro plano.
 */
class HeadUnitForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: HeadUnitRuntime
    private var overlayJob: Job? = null
    private var overlayView: View? = null
    private var powerReceiverRegistered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> runtime.onPowerConnectionChanged(true)
                Intent.ACTION_POWER_DISCONNECTED -> runtime.onPowerConnectionChanged(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = HeadUnitRuntime.get(applicationContext)
        createNotificationChannel()
        startForegroundCompat(buildNotification("Central ativa em segundo plano"))
        registerPowerReceiver()
        observeBackgroundReverseOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        removeSystemOverlay()
        overlayJob?.cancel()
        if (powerReceiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            powerReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPowerReceiver() {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        powerReceiverRegistered = true
    }

    private fun observeBackgroundReverseOverlay() {
        overlayJob = serviceScope.launch {
            runtime.uiState.collectLatest { state ->
                val needSystemOverlay = state.headUnit.isReverseOverlayVisible && !state.isAppInForeground
                if (needSystemOverlay) {
                    showSystemOverlayIfPermitted()
                } else {
                    removeSystemOverlay()
                }
            }
        }
    }

    private fun showSystemOverlayIfPermitted() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E606090F"))
        }
        val label = TextView(this).apply {
            text = "MARCHA RÉ DETECTADA\nToque para voltar ao AutoDeck"
            setTextColor(Color.CYAN)
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener {
                val intent = Intent(this@HeadUnitForegroundService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching {
            wm.addView(container, params)
            overlayView = container
        }
    }

    private fun removeSystemOverlay() {
        val view = overlayView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(view) }
        overlayView = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoDeck Conexões Automotivas",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém CarPlay, OBD-II e Câmera de Ré conectados."
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDeck Ativo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "autodeck_connected_device"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, HeadUnitForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
