package com.autodeck.headunit.data

import android.content.Context
import androidx.core.content.edit
import com.autodeck.protocol.camera.GuidelineCalibration
import com.autodeck.protocol.camera.NoSignalCalibration
import com.autodeck.protocol.coordinator.ProjectionMode
import com.autodeck.protocol.obd2.AlertThresholds
import com.autodeck.protocol.obd2.FuelType

data class HeadUnitConfig(
    val setupCompleted: Boolean = false,
    val obdDeviceMac: String? = null,
    val obdDeviceName: String? = null,
    val savedObdProtocol: String? = null,
    val useSimulatedObd: Boolean = false,
    val useSimulatedCamera: Boolean = false,
    val fuelType: FuelType = FuelType.GASOLINE_E27,
    val engineDisplacementLiters: Double = 1.6,
    val fuelCalibrationFactor: Double = 1.0,
    val preferSpeedDensity: Boolean = true,
    val projectionMode: ProjectionMode = ProjectionMode.CARLINKIT_NATIVE,
    val useBoxMic: Boolean = false,
    val autoConnectOnLaunch: Boolean = true,
    val autoStartOnPower: Boolean = true,
    val launcherModeEnabled: Boolean = false,
    val duckAudioOnReverse: Boolean = true,
    val cameraVendorId: Int? = null,
    val cameraProductId: Int? = null,
    val cameraInputChannel: Int = 0,
    val cameraIsNtsc: Boolean = true,
    val noSignalCalibration: NoSignalCalibration? = null,
    val guidelines: GuidelineCalibration = GuidelineCalibration(),
    val alertThresholds: AlertThresholds = AlertThresholds(),
    val tripDistanceKm: Double = 0.0,
    val tripFuelLiters: Double = 0.0
)

class HeadUnitPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): HeadUnitConfig {
        val fuelName = prefs.getString(KEY_FUEL_TYPE, FuelType.GASOLINE_E27.name)
        val fuelType = FuelType.entries.firstOrNull { it.name == fuelName } ?: FuelType.GASOLINE_E27

        val projName = prefs.getString(KEY_PROJECTION_MODE, ProjectionMode.CARLINKIT_NATIVE.name)
        val projectionMode = ProjectionMode.entries.firstOrNull { it.name == projName }
            ?: ProjectionMode.CARLINKIT_NATIVE

        val camVid = if (prefs.contains(KEY_CAMERA_VID)) prefs.getInt(KEY_CAMERA_VID, -1) else null
        val camPid = if (prefs.contains(KEY_CAMERA_PID)) prefs.getInt(KEY_CAMERA_PID, -1) else null

        return HeadUnitConfig(
            setupCompleted = prefs.getBoolean(KEY_SETUP_COMPLETED, false),
            obdDeviceMac = prefs.getString(KEY_OBD_MAC, null),
            obdDeviceName = prefs.getString(KEY_OBD_NAME, null),
            savedObdProtocol = prefs.getString(KEY_SAVED_OBD_PROTOCOL, null),
            useSimulatedObd = prefs.getBoolean(KEY_SIMULATED_OBD, false),
            useSimulatedCamera = prefs.getBoolean(KEY_SIMULATED_CAMERA, false),
            fuelType = fuelType,
            engineDisplacementLiters = prefs.getFloat(KEY_ENGINE_DISPLACEMENT, 1.6f).toDouble(),
            fuelCalibrationFactor = prefs.getFloat(KEY_FUEL_CAL_FACTOR, 1.0f).toDouble(),
            preferSpeedDensity = prefs.getBoolean(KEY_PREFER_SPEED_DENSITY, true),
            projectionMode = projectionMode,
            useBoxMic = prefs.getBoolean(KEY_USE_BOX_MIC, false),
            autoConnectOnLaunch = prefs.getBoolean(KEY_AUTO_CONNECT, true),
            autoStartOnPower = prefs.getBoolean(KEY_AUTO_START_POWER, true),
            launcherModeEnabled = prefs.getBoolean(KEY_LAUNCHER_MODE, false),
            duckAudioOnReverse = prefs.getBoolean(KEY_DUCK_AUDIO, true),
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
            tripFuelLiters = prefs.getFloat(KEY_TRIP_FUEL_L, 0f).toDouble()
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
            putString(KEY_FUEL_TYPE, config.fuelType.name)
            putFloat(KEY_ENGINE_DISPLACEMENT, config.engineDisplacementLiters.toFloat())
            putFloat(KEY_FUEL_CAL_FACTOR, config.fuelCalibrationFactor.toFloat())
            putBoolean(KEY_PREFER_SPEED_DENSITY, config.preferSpeedDensity)
            putString(KEY_PROJECTION_MODE, config.projectionMode.name)
            putBoolean(KEY_USE_BOX_MIC, config.useBoxMic)
            putBoolean(KEY_AUTO_CONNECT, config.autoConnectOnLaunch)
            putBoolean(KEY_AUTO_START_POWER, config.autoStartOnPower)
            putBoolean(KEY_LAUNCHER_MODE, config.launcherModeEnabled)
            putBoolean(KEY_DUCK_AUDIO, config.duckAudioOnReverse)

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
        const val PREFS_NAME = "autodeck_headunit_prefs"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_OBD_MAC = "obd_mac"
        const val KEY_OBD_NAME = "obd_name"
        const val KEY_SAVED_OBD_PROTOCOL = "saved_obd_protocol"
        const val KEY_SIMULATED_OBD = "simulated_obd"
        const val KEY_SIMULATED_CAMERA = "simulated_camera"
        const val KEY_FUEL_TYPE = "fuel_type"
        const val KEY_ENGINE_DISPLACEMENT = "engine_displacement"
        const val KEY_FUEL_CAL_FACTOR = "fuel_cal_factor"
        const val KEY_PREFER_SPEED_DENSITY = "prefer_speed_density"
        const val KEY_PROJECTION_MODE = "projection_mode"
        const val KEY_USE_BOX_MIC = "use_box_mic"
        const val KEY_AUTO_CONNECT = "auto_connect_launch"
        const val KEY_AUTO_START_POWER = "auto_start_power"
        const val KEY_LAUNCHER_MODE = "launcher_mode"
        const val KEY_DUCK_AUDIO = "duck_audio_reverse"
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
    }
}
