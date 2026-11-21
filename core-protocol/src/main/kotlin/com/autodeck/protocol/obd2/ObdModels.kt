package com.autodeck.protocol.obd2

enum class FuelType(
    val labelPt: String,
    val afr: Double,
    val densityGramsPerLiter: Double
) {
    GASOLINE_E27("Gasolina Comum/Aditivada (E27)", 13.2, 745.0),
    GASOLINE_PURE("Gasolina Pura (E0)", 14.7, 735.0),
    ETHANOL("Etanol Hidratado (E100)", 9.0, 789.0),
    DIESEL("Diesel S10/S500", 14.5, 835.0)
}

enum class ObdPid(
    val command: String,
    val expectedHeader: String,
    val labelPt: String,
    val unit: String
) {
    ENGINE_LOAD("0104", "4104", "Carga do Motor", "%"),
    COOLANT_TEMP("0105", "4105", "Temp. do Motor", "°C"),
    STFT_BANK1("0106", "4106", "Ajuste Curto Comb.", "%"),
    LTFT_BANK1("0107", "4107", "Ajuste Longo Comb.", "%"),
    INTAKE_MAP("010B", "410B", "Pressão Coletor (MAP)", "kPa"),
    RPM("010C", "410C", "Rotação (RPM)", "rpm"),
    SPEED("010D", "410D", "Velocidade", "km/h"),
    TIMING_ADVANCE("010E", "410E", "Avanço Ignição", "°"),
    INTAKE_TEMP("010F", "410F", "Temp. Admissão", "°C"),
    MAF("0110", "4110", "Fluxo de Ar (MAF)", "g/s"),
    THROTTLE_POS("0111", "4111", "Posição Borboleta", "%"),
    FUEL_LEVEL("012F", "412F", "Nível do Tanque", "%"),
    CONTROL_MODULE_VOLTAGE("0142", "4142", "Voltagem ECU", "V")
}

data class ObdSnapshot(
    val coolantTempC: Double? = null,
    val instantKmPerL: Double? = null,
    val averageKmPerL: Double? = null,
    val litersPerHour: Double? = null,
    val batteryVoltageV: Double? = null,
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val engineLoadPct: Double? = null,
    val throttlePct: Double? = null,
    val intakeMapKpa: Double? = null,
    val mafGramsPerSec: Double? = null,
    val intakeTempC: Double? = null,
    val timingAdvanceDeg: Double? = null,
    val stftPct: Double? = null,
    val ltftPct: Double? = null,
    val fuelLevelPct: Double? = null,
    val milOn: Boolean = false,
    val milDtcCount: Int = 0,
    val isStale: Boolean = false,
    val timestampMs: Long = 0L
)

data class SupportedPidCapabilities(
    val supportsMap: Boolean = true,
    val supportsMaf: Boolean = false
)

enum class DtcSystem(val prefix: Char, val labelPt: String) {
    POWERTRAIN('P', "Motor / Transmissão"),
    CHASSIS('C', "Chassi / ABS / Direção"),
    BODY('B', "Carroceria / Conforto"),
    NETWORK('U', "Rede CAN / Comunicação")
}

enum class DtcCategory(
    val modeCommand: String,
    val responsePrefix: String,
    val labelPt: String
) {
    STORED("03", "43", "Confirmada"),
    PENDING("07", "47", "Pendente"),
    PERMANENT("0A", "4A", "Permanente")
}

data class DtcCode(
    val code: String,
    val system: DtcSystem,
    val descriptionPt: String,
    val category: DtcCategory = DtcCategory.STORED
)

enum class AlertSeverity {
    WARNING,
    CRITICAL
}

data class ObdAlert(
    val severity: AlertSeverity,
    val title: String,
    val detail: String
)

data class AlertThresholds(
    val coolantWarningC: Double = 105.0,
    val coolantCriticalC: Double = 112.0,
    val batteryLowV: Double = 12.0,
    val batteryHighV: Double = 15.3,
    val lowBatterySustainMs: Long = 10_000L
)

enum class ObdPollingProfile {
    DOCK_BASIC,
    SCANNER_DETAILED
}
