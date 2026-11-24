package com.autodeck.protocol.obd2

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Um abastecimento informado pelo motorista (por voz ou na tela).
 *
 * @param odometerKm hodômetro do painel no momento do abastecimento (o Civic 2005 não
 *   informa o hodômetro pelo OBD, então ele vem do motorista).
 * @param fullTank true quando completou o tanque. O consumo real só é medido entre dois
 *   abastecimentos com tanque cheio (método tanque cheio a tanque cheio).
 * @param appTripKm / appTripFuelL totais do contador de viagem do app no momento do
 *   abastecimento. Servem para comparar o combustível estimado pelo OBD com o real.
 */
data class FuelEntry(
    val timestampMs: Long,
    val odometerKm: Double?,
    val liters: Double,
    val pricePerLiter: Double?,
    val fullTank: Boolean,
    val appTripKm: Double? = null,
    val appTripFuelL: Double? = null
) {
    val totalCost: Double?
        get() = pricePerLiter?.let { it * liters }
}

/** Trecho entre dois abastecimentos de tanque cheio. */
data class FuelInterval(
    val fromMs: Long,
    val toMs: Long,
    val distanceKm: Double,
    val liters: Double,
    val cost: Double?,
    /** Litros que o app estimou pelo OBD no mesmo trecho (null se o contador foi zerado). */
    val appEstimatedLiters: Double?
) {
    val kmPerL: Double get() = distanceKm / liters
    val costPerKm: Double? get() = cost?.let { it / distanceKm }
}

data class FuelLogSummary(
    val entryCount: Int = 0,
    val lastKmPerL: Double? = null,
    val averageKmPerL: Double? = null,
    val averagePricePerLiter: Double? = null,
    val lastPricePerLiter: Double? = null,
    val totalSpent: Double = 0.0,
    val spentLast30Days: Double = 0.0,
    val costPerKm: Double? = null,
    /** Novo fator de calibração sugerido para o cálculo de combustível do OBD. */
    val suggestedCalibrationFactor: Double? = null
)

data class ParsedRefuel(
    val liters: Double?,
    val pricePerLiter: Double?,
    val totalPaid: Double?,
    val odometerKm: Double?,
    val fullTank: Boolean
) {
    /** Litros informados, ou calculados por total pago / preço. */
    val resolvedLiters: Double?
        get() = liters ?: if (totalPaid != null && pricePerLiter != null && pricePerLiter > 0) totalPaid / pricePerLiter else null

    val resolvedPrice: Double?
        get() = pricePerLiter ?: if (totalPaid != null && liters != null && liters > 0) totalPaid / liters else null
}

object FuelLogCalculator {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Trechos tanque cheio -> tanque cheio. Abastecimentos parciais no meio entram nos
     * litros do trecho (o tanque cheio seguinte "repõe" tudo o que foi gasto desde o anterior).
     */
    fun intervals(entries: List<FuelEntry>): List<FuelInterval> {
        val sorted = entries.sortedBy { it.timestampMs }
        val result = mutableListOf<FuelInterval>()
        var anchor: FuelEntry? = null
        var litersSince = 0.0
        var costSince: Double? = 0.0
        for (e in sorted) {
            val start = anchor
            if (start != null) {
                litersSince += e.liters
                costSince = if (costSince != null && e.totalCost != null) costSince + e.totalCost!! else null
            }
            if (!e.fullTank) continue
            if (start != null && start.odometerKm != null && e.odometerKm != null) {
                val dist = e.odometerKm - start.odometerKm
                if (dist > 0 && litersSince > 0) {
                    val appLiters = if (start.appTripFuelL != null && e.appTripFuelL != null) {
                        (e.appTripFuelL - start.appTripFuelL).takeIf { it > 0 }
                    } else {
                        null
                    }
                    result += FuelInterval(
                        fromMs = start.timestampMs,
                        toMs = e.timestampMs,
                        distanceKm = dist,
                        liters = litersSince,
                        cost = costSince,
                        appEstimatedLiters = appLiters
                    )
                }
            }
            anchor = e
            litersSince = 0.0
            costSince = 0.0
        }
        return result
    }

    fun summarize(entries: List<FuelEntry>, currentCalibrationFactor: Double, nowMs: Long): FuelLogSummary {
        if (entries.isEmpty()) return FuelLogSummary()
        val ivs = intervals(entries)
        val totalDist = ivs.sumOf { it.distanceKm }
        val totalLiters = ivs.sumOf { it.liters }
        val costed = ivs.filter { it.cost != null }
        val priced = entries.filter { it.pricePerLiter != null }
        val pricedLiters = priced.sumOf { it.liters }

        return FuelLogSummary(
            entryCount = entries.size,
            lastKmPerL = ivs.lastOrNull()?.kmPerL,
            averageKmPerL = if (totalLiters > 0) totalDist / totalLiters else null,
            averagePricePerLiter = if (pricedLiters > 0) priced.sumOf { it.totalCost ?: 0.0 } / pricedLiters else null,
            lastPricePerLiter = entries.maxByOrNull { it.timestampMs }?.pricePerLiter,
            totalSpent = entries.sumOf { it.totalCost ?: 0.0 },
            spentLast30Days = entries.filter { nowMs - it.timestampMs <= 30 * DAY_MS }.sumOf { it.totalCost ?: 0.0 },
            costPerKm = costed.sumOf { it.distanceKm }.takeIf { it > 0 }
                ?.let { d -> costed.sumOf { it.cost ?: 0.0 } / d },
            suggestedCalibrationFactor = suggestCalibration(ivs.lastOrNull(), currentCalibrationFactor)
        )
    }

    /**
     * Ajusta o fator do cálculo de combustível do OBD pela diferença entre os litros reais
     * do último trecho e os litros que o app estimou. Corrige metade da diferença por vez
     * (evita saltos por um abastecimento mal anotado) e limita entre 0,6 e 1,6.
     */
    fun suggestCalibration(last: FuelInterval?, currentFactor: Double): Double? {
        val est = last?.appEstimatedLiters ?: return null
        if (est < 3.0 || last.liters < 3.0) return null
        val ratio = last.liters / est
        if (ratio < 0.4 || ratio > 2.5) return null // provavelmente anotação errada
        val target = currentFactor * ratio
        val next = (currentFactor + (target - currentFactor) * 0.5).coerceIn(0.6, 1.6)
        return if (abs(next - currentFactor) < 0.01) null else (next * 1000).roundToInt() / 1000.0
    }

    /**
     * Entende frases como:
     *  "abasteci 40 litros a 6,29 com 123.456 km, completei"
     *  "coloquei 250 reais de gasolina a 6,19 o litro, hodômetro 98500"
     *  "enchi o tanque, 38,5 litros, paguei 241,50, km 123900"
     * Retorna null se não achar ao menos litros ou valor pago.
     */
    fun parseUtterance(text: String): ParsedRefuel? {
        val t = text.lowercase()
            .replace("r$", " reais ")
            .replace("quilômetros", "km").replace("quilometros", "km")
            .replace("hodômetro", "km").replace("hodometro", "km")
            .replace("odômetro", "km").replace("odometro", "km")

        val num = """(\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:[.,]\d+)?)"""
        val liters = Regex("""$num\s*(?:litros?|l\b|lts?\b)""").find(t)?.groupValues?.get(1)?.let(::toNumber)
        val price = Regex("""(?:a|por)\s+$num(?:\s*reais)?\s*(?:o\s+litro|por\s+litro|/l|o\s+l\b)?""")
            .findAll(t)
            .mapNotNull { m -> m.groupValues[1].let(::toNumber)?.takeIf { it in 2.0..15.0 } }
            .firstOrNull()
            ?: Regex("""$num\s*reais\s*(?:o|por)\s+litro""").find(t)?.groupValues?.get(1)?.let(::toNumber)
        val total = Regex("""(?:paguei|deu|total(?:\s+de)?|gastei)\s+$num""").find(t)?.groupValues?.get(1)?.let(::toNumber)
            ?: Regex("""$num\s*reais(?!\s*(?:o|por)\s+litro)""").find(t)?.groupValues?.get(1)?.let(::toNumber)
                ?.takeIf { it > 15.0 }
        val km = Regex("""$num\s*km""").find(t)?.groupValues?.get(1)?.let(::toNumber)
            ?: Regex("""km\s*:?\s*$num""").find(t)?.groupValues?.get(1)?.let(::toNumber)

        val partial = Regex("""\b(n[aã]o\s+(?:completei|enchi)|parcial|s[oó]\s+um\s+pouco)\b""").containsMatchIn(t)
        val full = !partial && Regex("""\b(completei|completo|cheio|enchi|tanque\s+cheio)\b""").containsMatchIn(t)

        if (liters == null && total == null) return null
        return ParsedRefuel(
            liters = liters,
            pricePerLiter = price,
            totalPaid = total,
            odometerKm = km?.takeIf { it >= 100.0 },
            fullTank = full
        )
    }

    /** "123.456" = 123456 (milhar), "40,5" = 40.5, "6.29" = 6.29. */
    fun toNumber(raw: String): Double? {
        val s = raw.trim()
        val normalized = when {
            Regex("""^\d{1,3}(\.\d{3})+(,\d+)?$""").matches(s) -> s.replace(".", "").replace(",", ".")
            s.contains(',') -> s.replace(",", ".")
            else -> s
        }
        return normalized.toDoubleOrNull()
    }

    /** Litros estimados no tanque agora, a partir do último tanque cheio e do consumo médio. */
    fun estimatedLitersInTank(
        entries: List<FuelEntry>,
        currentAppTripKm: Double,
        tankCapacityL: Double,
        averageKmPerL: Double?
    ): Double? {
        val sorted = entries.sortedBy { it.timestampMs }
        val lastFullIdx = sorted.indexOfLast { it.fullTank }
        if (lastFullIdx < 0 || averageKmPerL == null || averageKmPerL <= 0) return null
        val lastFull = sorted[lastFullIdx]
        val startKm = lastFull.appTripKm ?: return null
        val driven = currentAppTripKm - startKm
        if (driven < 0) return null // contador de viagem foi zerado
        val addedAfter = sorted.drop(lastFullIdx + 1).sumOf { it.liters }
        return (tankCapacityL + addedAfter - driven / averageKmPerL).coerceIn(0.0, tankCapacityL)
    }
}
