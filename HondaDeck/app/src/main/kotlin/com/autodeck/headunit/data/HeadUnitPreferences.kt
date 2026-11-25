package com.autodeck.headunit.data

import android.content.Context
import androidx.core.content.edit
import com.autodeck.headunit.BuildConfig
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.camera.NoSignalCalibration
import com.autodeck.protocol.obd2.AlertThresholds
import com.autodeck.protocol.obd2.FuelType
import com.autodeck.protocol.obd2.TireSpec
import com.autodeck.protocol.obd2.TransmissionType

enum class VoiceAssistantMode(val labelPt: String, val descriptionPt: String) {
    HYBRID_GEMINI(
        "Híbrido (Gemini + Voz Local Offline)",
        "Usa Gemini quando há internet do celular e cai para Voz Local Offline sem internet."
    ),
    LOCAL_ONLY(
        "Apenas Voz Local Offline",
        "Responde telemetria, música, mapa e WhatsApp direto no tablet sem usar API."
    ),
    SYSTEM_ASSISTANT(
        "Assistente do Android",
        "Encaminha o atalho de voz para o assistente padrão instalado no tablet."
    )
}

/** Modelo do Copiloto: responde no plano gratuito da chave (os 2.x foram desativados). */
const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"

data class HeadUnitConfig(
    val setupCompleted: Boolean = true,
    val obdDeviceMac: String? = "00:10:CC:4F:36:03",
    val obdDeviceName: String? = "OBDII",
    val savedObdProtocol: String? = "3",
    val useSimulatedObd: Boolean = false,
    val useSimulatedCamera: Boolean = false,
    val obdFastTurboMode: Boolean = true,
    val speedometerMaxKmh: Int = 160,
    val fuelType: FuelType = FuelType.GASOLINE_E27,
    val engineDisplacementLiters: Double = 1.7,
    val fuelCalibrationFactor: Double = 1.0,
    val preferSpeedDensity: Boolean = true,
    val transmissionType: TransmissionType = TransmissionType.MANUAL_5,
    val tireCorrectionFactor: Double = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15.speedCorrectionFactor,
    val virtualTankCapacityLiters: Double = 50.0,
    val virtualTankRemainingLiters: Double = 50.0,
    val autoConnectOnLaunch: Boolean = true,
    val autoStartOnPower: Boolean = true,
    val launcherModeEnabled: Boolean = false,
    val duckAudioOnReverse: Boolean = true,
    // Apps padrão & Cápsula Flutuante
    val preferredMusicPackage: String = "com.spotify.music",
    val preferredMusicLabel: String = "Spotify",
    val autoWarmupMusicOnIgnition: Boolean = true,
    val autoPlayMusicOnIgnition: Boolean = false,
    val preferredMapPackage: String = "com.google.android.apps.maps",
    val preferredMapLabel: String = "Google Maps",
    val showFloatingHudOnExternalApps: Boolean = true,
    // Gatilho de ignição Bluetooth (Taramps) & Standby
    val ignitionTriggerEnabled: Boolean = true,
    val ignitionBtMac: String = "",
    val ignitionBtName: String = "TARAMPS",
    val ignitionOffStandbyDelaySec: Int = 8,
    val showBootSplashOnIgnition: Boolean = true,
    // Agente Gemini & Voz
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val voiceAssistantMode: VoiceAssistantMode = VoiceAssistantMode.HYBRID_GEMINI,
    val wakeWordEnabled: Boolean = true,
    val wakeWordPhrase: String = "ok honda",
    val continuousConversationEnabled: Boolean = true,
    val whatsappVoiceAlertsEnabled: Boolean = true,
    // Câmera de Ré
    val cameraVendorId: Int? = 0x05E1,
    val cameraProductId: Int? = 0x0408,
    val cameraInputChannel: Int = 0,
    val cameraIsNtsc: Boolean = true,
    val noSignalCalibration: NoSignalCalibration? = null,
    val guidelines: GuidelineCalibration = GuidelineCalibration(),
    val alertThresholds: AlertThresholds = AlertThresholds(),
    val tripDistanceKm: Double = 0.0,
    val tripFuelLiters: Double = 0.0,
    // Interface Web (Órbita)
    val accentColor: String = "#22D3EE",
    val introMode: String = "ANIMATION",
    val mapTheme: String = "AUTO",
    val spotifyShowPlaylists: Boolean = true,
    val spotifyShowRecent: Boolean = true
)

class HeadUnitPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): HeadUnitConfig {
        val fuelName = prefs.getString(KEY_FUEL_TYPE, FuelType.GASOLINE_E27.name)
        val fuelType = FuelType.entries.firstOrNull { it.name == fuelName } ?: FuelType.GASOLINE_E27

        val assistantName = prefs.getString(KEY_VOICE_ASSISTANT_MODE, VoiceAssistantMode.HYBRID_GEMINI.name)
        val voiceAssistantMode = VoiceAssistantMode.entries.firstOrNull { it.name == assistantName }
            ?: VoiceAssistantMode.HYBRID_GEMINI

        val camVid = if (prefs.contains(KEY_CAMERA_VID)) prefs.getInt(KEY_CAMERA_VID, -1) else 0x05E1
        val camPid = if (prefs.contains(KEY_CAMERA_PID)) prefs.getInt(KEY_CAMERA_PID, -1) else 0x0408
        val transName = prefs.getString(KEY_TRANSMISSION_TYPE, TransmissionType.MANUAL_5.name)
        val transmissionType = TransmissionType.entries.firstOrNull { it.name == transName }
            ?: TransmissionType.MANUAL_5
        val defaultTireFactor = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15.speedCorrectionFactor.toFloat()
        val tankCap = prefs.getFloat(KEY_VIRTUAL_TANK_CAP_L, 50.0f).toDouble().coerceAtLeast(1.0)
        val tankRem = prefs.getFloat(KEY_VIRTUAL_TANK_REM_L, tankCap.toFloat()).toDouble().coerceIn(0.0, tankCap)

        return HeadUnitConfig(
            setupCompleted = prefs.getBoolean(KEY_SETUP_COMPLETED, true),
            obdDeviceMac = prefs.getString(KEY_OBD_MAC, "00:10:CC:4F:36:03"),
            obdDeviceName = prefs.getString(KEY_OBD_NAME, "OBDII"),
            savedObdProtocol = prefs.getString(KEY_SAVED_OBD_PROTOCOL, "3"),
            useSimulatedObd = prefs.getBoolean(KEY_SIMULATED_OBD, false),
            useSimulatedCamera = prefs.getBoolean(KEY_SIMULATED_CAMERA, false),
            obdFastTurboMode = prefs.getBoolean(KEY_OBD_FAST_TURBO, true),
            speedometerMaxKmh = prefs.getInt(KEY_SPEEDO_MAX_KMH, 160).coerceIn(140, 260),
            fuelType = fuelType,
            engineDisplacementLiters = prefs.getFloat(KEY_ENGINE_DISPLACEMENT, 1.7f).toDouble(),
            fuelCalibrationFactor = prefs.getFloat(KEY_FUEL_CAL_FACTOR, 1.0f).toDouble(),
            preferSpeedDensity = prefs.getBoolean(KEY_PREFER_SPEED_DENSITY, true),
            transmissionType = transmissionType,
            tireCorrectionFactor = prefs.getFloat(KEY_TIRE_CORRECTION_FACTOR, defaultTireFactor).toDouble(),
            virtualTankCapacityLiters = tankCap,
            virtualTankRemainingLiters = tankRem,
            autoConnectOnLaunch = prefs.getBoolean(KEY_AUTO_CONNECT, true),
            autoStartOnPower = prefs.getBoolean(KEY_AUTO_START_POWER, true),
            launcherModeEnabled = prefs.getBoolean(KEY_LAUNCHER_MODE, false),
            duckAudioOnReverse = prefs.getBoolean(KEY_DUCK_AUDIO, true),
            preferredMusicPackage = prefs.getString(KEY_PREF_MUSIC_PKG, "com.spotify.music") ?: "com.spotify.music",
            preferredMusicLabel = prefs.getString(KEY_PREF_MUSIC_LABEL, "Spotify") ?: "Spotify",
            autoWarmupMusicOnIgnition = prefs.getBoolean(KEY_AUTO_WARMUP_MUSIC, true),
            autoPlayMusicOnIgnition = prefs.getBoolean(KEY_AUTO_PLAY_MUSIC, false),
            preferredMapPackage = prefs.getString(KEY_PREF_MAP_PKG, "com.google.android.apps.maps") ?: "com.google.android.apps.maps",
            preferredMapLabel = prefs.getString(KEY_PREF_MAP_LABEL, "Google Maps") ?: "Google Maps",
            showFloatingHudOnExternalApps = prefs.getBoolean(KEY_SHOW_FLOATING_HUD, true),
            ignitionTriggerEnabled = prefs.getBoolean(KEY_IGNITION_TRIGGER_ENABLED, true),
            ignitionBtMac = prefs.getString(KEY_IGNITION_BT_MAC, "") ?: "",
            ignitionBtName = prefs.getString(KEY_IGNITION_BT_NAME, "TARAMPS") ?: "TARAMPS",
            ignitionOffStandbyDelaySec = prefs.getInt(KEY_IGNITION_STANDBY_DELAY_SEC, 8).coerceIn(3, 60),
            showBootSplashOnIgnition = prefs.getBoolean(KEY_SHOW_BOOT_ON_IGNITION, true),
            // Vazio nas Configurações = usa a chave embutida no build (local.properties).
            geminiApiKey = (prefs.getString(KEY_GEMINI_API_KEY, "") ?: "").ifBlank { BuildConfig.GEMINI_API_KEY },
            // Os modelos 2.x foram desativados pelo Google: quem ainda tiver um salvo passa para o atual.
            geminiModel = (prefs.getString(KEY_GEMINI_MODEL, null) ?: "")
                .takeIf { it.isNotBlank() && !it.startsWith("gemini-2.") } ?: DEFAULT_GEMINI_MODEL,
            voiceAssistantMode = voiceAssistantMode,
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true),
            wakeWordPhrase = prefs.getString(KEY_WAKE_WORD_PHRASE, "ok honda") ?: "ok honda",
            continuousConversationEnabled = prefs.getBoolean(KEY_CONTINUOUS_CONV, true),
            whatsappVoiceAlertsEnabled = prefs.getBoolean(KEY_WHATSAPP_VOICE_ALERTS, true),
            cameraVendorId = camVid?.takeIf { it > 0 },
            cameraProductId = camPid?.takeIf { it > 0 },
            cameraInputChannel = prefs.getInt(KEY_CAMERA_INPUT_CH, 0).coerceIn(0, 3),
            cameraIsNtsc = prefs.getBoolean(KEY_CAMERA_IS_NTSC, true),
            noSignalCalibration = loadNoSignalCalibration(),
            guidelines = GuidelineCalibration(
                topWidthRatio = prefs.getFloat(KEY_GUIDE_TOP_W, 0.42f),
                bottomWidthRatio = prefs.getFloat(KEY_GUIDE_BOT_W, 0.88f),
                topYRatio = prefs.getFloat(KEY_GUIDE_TOP_Y, 0.48f),
                bottomYRatio = prefs.getFloat(KEY_GUIDE_BOT_Y, 0.96f),
                horizontalOffsetRatio = prefs.getFloat(KEY_GUIDE_OFFSET_X, 0f),
                mirrorHorizontal = prefs.getBoolean(KEY_GUIDE_MIRROR, false),
                enabled = prefs.getBoolean(KEY_GUIDE_ENABLED, true)
            ).clamped(),
            alertThresholds = AlertThresholds(
                coolantWarningC = prefs.getFloat(KEY_ALERT_COOLANT_WARN, 105f).toDouble(),
                coolantCriticalC = prefs.getFloat(KEY_ALERT_COOLANT_CRIT, 112f).toDouble(),
                batteryLowV = prefs.getFloat(KEY_ALERT_BATT_LOW, 12.0f).toDouble(),
                batteryHighV = prefs.getFloat(KEY_ALERT_BATT_HIGH, 15.3f).toDouble()
            ),
            tripDistanceKm = prefs.getFloat(KEY_TRIP_DIST_KM, 0f).toDouble(),
            tripFuelLiters = prefs.getFloat(KEY_TRIP_FUEL_L, 0f).toDouble(),
            accentColor = (prefs.getString(KEY_ACCENT_COLOR, "#22D3EE") ?: "#22D3EE").ifBlank { "#22D3EE" },
            introMode = when ((prefs.getString(KEY_INTRO_MODE, "ANIMATION") ?: "ANIMATION").uppercase()) {
                "VIDEO" -> "VIDEO"
                else -> "ANIMATION"
            },
            mapTheme = when ((prefs.getString(KEY_MAP_THEME, "AUTO") ?: "AUTO").uppercase()) {
                "LIGHT" -> "LIGHT"
                "DARK" -> "DARK"
                else -> "AUTO"
            },
            spotifyShowPlaylists = prefs.getBoolean(KEY_SPOTIFY_SHOW_PLAYLISTS, true),
            spotifyShowRecent = prefs.getBoolean(KEY_SPOTIFY_SHOW_RECENT, true)
        )
    }

    fun save(config: HeadUnitConfig) {
        prefs.edit {
            putBoolean(KEY_SETUP_COMPLETED, config.setupCompleted)
            putString(KEY_OBD_MAC, config.obdDeviceMac)
            putString(KEY_OBD_NAME, config.obdDeviceName)
            putString(KEY_SAVED_OBD_PROTOCOL, config.savedObdProtocol)
            putBoolean(KEY_SIMULATED_OBD, config.useSimulatedObd)
            putBoolean(KEY_SIMULATED_CAMERA, config.useSimulatedCamera)
            putBoolean(KEY_OBD_FAST_TURBO, config.obdFastTurboMode)
            putInt(KEY_SPEEDO_MAX_KMH, config.speedometerMaxKmh)
            putString(KEY_FUEL_TYPE, config.fuelType.name)
            putFloat(KEY_ENGINE_DISPLACEMENT, config.engineDisplacementLiters.toFloat())
            putFloat(KEY_FUEL_CAL_FACTOR, config.fuelCalibrationFactor.toFloat())
            putBoolean(KEY_PREFER_SPEED_DENSITY, config.preferSpeedDensity)
            putString(KEY_TRANSMISSION_TYPE, config.transmissionType.name)
            putFloat(KEY_TIRE_CORRECTION_FACTOR, config.tireCorrectionFactor.toFloat())
            putFloat(KEY_VIRTUAL_TANK_CAP_L, config.virtualTankCapacityLiters.toFloat())
            putFloat(KEY_VIRTUAL_TANK_REM_L, config.virtualTankRemainingLiters.toFloat())
            putBoolean(KEY_AUTO_CONNECT, config.autoConnectOnLaunch)
            putBoolean(KEY_AUTO_START_POWER, config.autoStartOnPower)
            putBoolean(KEY_LAUNCHER_MODE, config.launcherModeEnabled)
            putBoolean(KEY_DUCK_AUDIO, config.duckAudioOnReverse)

            putString(KEY_PREF_MUSIC_PKG, config.preferredMusicPackage)
            putString(KEY_PREF_MUSIC_LABEL, config.preferredMusicLabel)
            putBoolean(KEY_AUTO_WARMUP_MUSIC, config.autoWarmupMusicOnIgnition)
            putBoolean(KEY_AUTO_PLAY_MUSIC, config.autoPlayMusicOnIgnition)
            putString(KEY_PREF_MAP_PKG, config.preferredMapPackage)
            putString(KEY_PREF_MAP_LABEL, config.preferredMapLabel)
            putBoolean(KEY_SHOW_FLOATING_HUD, config.showFloatingHudOnExternalApps)

            putBoolean(KEY_IGNITION_TRIGGER_ENABLED, config.ignitionTriggerEnabled)
            putString(KEY_IGNITION_BT_MAC, config.ignitionBtMac)
            putString(KEY_IGNITION_BT_NAME, config.ignitionBtName)
            putInt(KEY_IGNITION_STANDBY_DELAY_SEC, config.ignitionOffStandbyDelaySec)
            putBoolean(KEY_SHOW_BOOT_ON_IGNITION, config.showBootSplashOnIgnition)

            putString(KEY_GEMINI_API_KEY, config.geminiApiKey)
            putString(KEY_GEMINI_MODEL, config.geminiModel)
            putString(KEY_VOICE_ASSISTANT_MODE, config.voiceAssistantMode.name)
            putBoolean(KEY_WAKE_WORD_ENABLED, config.wakeWordEnabled)
            putString(KEY_WAKE_WORD_PHRASE, config.wakeWordPhrase)
            putBoolean(KEY_CONTINUOUS_CONV, config.continuousConversationEnabled)
            putBoolean(KEY_WHATSAPP_VOICE_ALERTS, config.whatsappVoiceAlertsEnabled)

            if (config.cameraVendorId != null && config.cameraProductId != null) {
                putInt(KEY_CAMERA_VID, config.cameraVendorId)
                putInt(KEY_CAMERA_PID, config.cameraProductId)
            } else {
                remove(KEY_CAMERA_VID)
                remove(KEY_CAMERA_PID)
            }
            putInt(KEY_CAMERA_INPUT_CH, config.cameraInputChannel.coerceIn(0, 3))
            putBoolean(KEY_CAMERA_IS_NTSC, config.cameraIsNtsc)

            val cal = config.noSignalCalibration
            if (cal != null) {
                putFloat(KEY_NO_SIG_MEAN, cal.meanLuma.toFloat())
                putFloat(KEY_NO_SIG_STD, cal.lumaStdDev.toFloat())
                putFloat(KEY_NO_SIG_TOL, cal.toleranceMad.toFloat())
                putString(KEY_NO_SIG_GRID, cal.referenceGrid.joinToString(","))
            } else {
                remove(KEY_NO_SIG_MEAN)
                remove(KEY_NO_SIG_STD)
                remove(KEY_NO_SIG_TOL)
                remove(KEY_NO_SIG_GRID)
            }

            val guide = config.guidelines.clamped()
            putFloat(KEY_GUIDE_TOP_W, guide.topWidthRatio)
            putFloat(KEY_GUIDE_BOT_W, guide.bottomWidthRatio)
            putFloat(KEY_GUIDE_TOP_Y, guide.topYRatio)
            putFloat(KEY_GUIDE_BOT_Y, guide.bottomYRatio)
            putFloat(KEY_GUIDE_OFFSET_X, guide.horizontalOffsetRatio)
            putBoolean(KEY_GUIDE_MIRROR, guide.mirrorHorizontal)
            putBoolean(KEY_GUIDE_ENABLED, guide.enabled)

            putFloat(KEY_ALERT_COOLANT_WARN, config.alertThresholds.coolantWarningC.toFloat())
            putFloat(KEY_ALERT_COOLANT_CRIT, config.alertThresholds.coolantCriticalC.toFloat())
            putFloat(KEY_ALERT_BATT_LOW, config.alertThresholds.batteryLowV.toFloat())
            putFloat(KEY_ALERT_BATT_HIGH, config.alertThresholds.batteryHighV.toFloat())

            putFloat(KEY_TRIP_DIST_KM, config.tripDistanceKm.toFloat())
            putFloat(KEY_TRIP_FUEL_L, config.tripFuelLiters.toFloat())
            putString(KEY_ACCENT_COLOR, config.accentColor)
            putString(KEY_INTRO_MODE, config.introMode)
            putString(KEY_MAP_THEME, config.mapTheme)
            putBoolean(KEY_SPOTIFY_SHOW_PLAYLISTS, config.spotifyShowPlaylists)
            putBoolean(KEY_SPOTIFY_SHOW_RECENT, config.spotifyShowRecent)
        }
    }

    private fun loadNoSignalCalibration(): NoSignalCalibration? {
        val gridCsv = prefs.getString(KEY_NO_SIG_GRID, null) ?: return null
        val values = gridCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        if (values.isEmpty()) return null
        return NoSignalCalibration(
            meanLuma = prefs.getFloat(KEY_NO_SIG_MEAN, 0f).toDouble(),
            lumaStdDev = prefs.getFloat(KEY_NO_SIG_STD, 0f).toDouble(),
            referenceGrid = values,
            toleranceMad = prefs.getFloat(KEY_NO_SIG_TOL, 12f).toDouble()
        )
    }

    private companion object {
        const val PREFS_NAME = "hondadeck_headunit_prefs"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_OBD_MAC = "obd_mac"
        const val KEY_OBD_NAME = "obd_name"
        const val KEY_SAVED_OBD_PROTOCOL = "saved_obd_protocol"
        const val KEY_SIMULATED_OBD = "simulated_obd"
        const val KEY_SIMULATED_CAMERA = "simulated_camera"
        const val KEY_OBD_FAST_TURBO = "obd_fast_turbo"
        const val KEY_SPEEDO_MAX_KMH = "speedo_max_kmh"
        const val KEY_FUEL_TYPE = "fuel_type"
        const val KEY_ENGINE_DISPLACEMENT = "engine_displacement"
        const val KEY_FUEL_CAL_FACTOR = "fuel_cal_factor"
        const val KEY_PREFER_SPEED_DENSITY = "prefer_speed_density"
        const val KEY_AUTO_CONNECT = "auto_connect_launch"
        const val KEY_AUTO_START_POWER = "auto_start_power"
        const val KEY_LAUNCHER_MODE = "launcher_mode"
        const val KEY_DUCK_AUDIO = "duck_audio_reverse"
        const val KEY_PREF_MUSIC_PKG = "pref_music_pkg"
        const val KEY_PREF_MUSIC_LABEL = "pref_music_label"
        const val KEY_AUTO_WARMUP_MUSIC = "auto_warmup_music"
        const val KEY_AUTO_PLAY_MUSIC = "auto_play_music"
        const val KEY_PREF_MAP_PKG = "pref_map_pkg"
        const val KEY_PREF_MAP_LABEL = "pref_map_label"
        const val KEY_SHOW_FLOATING_HUD = "show_floating_hud"
        const val KEY_IGNITION_TRIGGER_ENABLED = "ignition_trigger_enabled"
        const val KEY_IGNITION_BT_MAC = "ignition_bt_mac"
        const val KEY_IGNITION_BT_NAME = "ignition_bt_name"
        const val KEY_IGNITION_STANDBY_DELAY_SEC = "ignition_standby_delay_sec"
        const val KEY_SHOW_BOOT_ON_IGNITION = "show_boot_on_ignition"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_VOICE_ASSISTANT_MODE = "voice_assistant_mode"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_WAKE_WORD_PHRASE = "wake_word_phrase"
        const val KEY_CONTINUOUS_CONV = "continuous_conv"
        const val KEY_WHATSAPP_VOICE_ALERTS = "whatsapp_voice_alerts"
        const val KEY_CAMERA_VID = "camera_vid"
        const val KEY_CAMERA_PID = "camera_pid"
        const val KEY_CAMERA_INPUT_CH = "camera_input_ch"
        const val KEY_CAMERA_IS_NTSC = "camera_is_ntsc"
        const val KEY_NO_SIG_MEAN = "no_sig_mean"
        const val KEY_NO_SIG_STD = "no_sig_std"
        const val KEY_NO_SIG_TOL = "no_sig_tol"
        const val KEY_NO_SIG_GRID = "no_sig_grid"
        const val KEY_GUIDE_TOP_W = "guide_top_w"
        const val KEY_GUIDE_BOT_W = "guide_bot_w"
        const val KEY_GUIDE_TOP_Y = "guide_top_y"
        const val KEY_GUIDE_BOT_Y = "guide_bot_y"
        const val KEY_GUIDE_OFFSET_X = "guide_offset_x"
        const val KEY_GUIDE_MIRROR = "guide_mirror"
        const val KEY_GUIDE_ENABLED = "guide_enabled"
        const val KEY_ALERT_COOLANT_WARN = "alert_coolant_warn"
        const val KEY_ALERT_COOLANT_CRIT = "alert_coolant_crit"
        const val KEY_ALERT_BATT_LOW = "alert_batt_low"
        const val KEY_ALERT_BATT_HIGH = "alert_batt_high"
        const val KEY_TRIP_DIST_KM = "trip_dist_km"
        const val KEY_TRIP_FUEL_L = "trip_fuel_liters"
        const val KEY_TRANSMISSION_TYPE = "transmission_type"
        const val KEY_TIRE_CORRECTION_FACTOR = "tire_correction_factor"
        const val KEY_VIRTUAL_TANK_CAP_L = "virtual_tank_cap_l"
        const val KEY_VIRTUAL_TANK_REM_L = "virtual_tank_rem_l"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_INTRO_MODE = "intro_mode"
        const val KEY_MAP_THEME = "map_theme"
        const val KEY_SPOTIFY_SHOW_PLAYLISTS = "spotify_show_playlists"
        const val KEY_SPOTIFY_SHOW_RECENT = "spotify_show_recent"
    }
}
