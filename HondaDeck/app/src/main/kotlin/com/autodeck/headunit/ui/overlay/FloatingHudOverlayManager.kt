package com.autodeck.headunit.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.autodeck.headunit.MainActivity
import kotlin.math.abs

/**
 * Mini-Cápsula Flutuante Honda exibida por cima do Google Maps ou Spotify
 * mostrando velocidade em tempo real, marcha, botão de voz Gemini e atalho 1-toque para voltar.
 */
class FloatingHudOverlayManager(
    private val context: Context,
    private val onTriggerGeminiVoice: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var telemetryLabel: TextView? = null
    private var voiceButton: TextView? = null

    fun showOrUpdate(
        speedKmh: Int,
        gearLabel: String,
        coolantTempC: Int?,
        isAgentListening: Boolean
    ) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val wm = windowManager ?: return
        if (rootView == null) {
            createView(wm)
        }
        val tempStr = coolantTempC?.let { "${it}°C" } ?: "--°C"
        telemetryLabel?.text = "H  $speedKmh km/h  |  M:$gearLabel  |  $tempStr"
        voiceButton?.text = if (isAgentListening) "🎙️ Ouvindo" else "🎙️ Gemini"
        voiceButton?.setTextColor(
            if (isAgentListening) Color.parseColor("#22C55E") else Color.parseColor("#38F2FF")
        )
    }

    fun hide() {
        val view = rootView ?: return
        runCatching { windowManager?.removeView(view) }
        rootView = null
        layoutParams = null
        telemetryLabel = null
        voiceButton = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(wm: WindowManager) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#E8071019"))
            setStroke(dp(1), Color.parseColor("#CC00E5FF"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        val telemetry = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, dp(12), 0)
        }

        val voiceBtn = TextView(context).apply {
            text = "🎙️ Gemini"
            setTextColor(Color.parseColor("#38F2FF"))
            textSize = 14f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                onTriggerGeminiVoice()
            }
        }

        val homeBtn = TextView(context).apply {
            text = "🏠 Painel"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            }
        }

        container.addView(telemetry)
        container.addView(voiceBtn)
        container.addView(homeBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(18)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        container.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        dragging = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm.updateViewLayout(container, params) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            rootView = container
            layoutParams = params
            telemetryLabel = telemetry
            voiceButton = voiceBtn
        }
    }
}
