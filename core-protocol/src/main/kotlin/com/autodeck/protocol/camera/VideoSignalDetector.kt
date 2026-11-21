package com.autodeck.protocol.camera

import kotlin.math.abs
import kotlin.math.sqrt

data class LumaGrid(
    val width: Int,
    val height: Int,
    val luma: IntArray,
    val chromaSpread: Double = 0.0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LumaGrid) return false
        return width == other.width &&
            height == other.height &&
            chromaSpread == other.chromaSpread &&
            luma.contentEquals(other.luma)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + luma.contentHashCode()
        result = 31 * result + chromaSpread.hashCode()
        return result
    }
}

data class NoSignalCalibration(
    val meanLuma: Double,
    val lumaStdDev: Double,
    val referenceGrid: IntArray,
    val toleranceMad: Double = 12.0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoSignalCalibration) return false
        return meanLuma == other.meanLuma &&
            lumaStdDev == other.lumaStdDev &&
            toleranceMad == other.toleranceMad &&
            referenceGrid.contentEquals(other.referenceGrid)
    }

    override fun hashCode(): Int {
        var result = meanLuma.hashCode()
        result = 31 * result + lumaStdDev.hashCode()
        result = 31 * result + referenceGrid.contentHashCode()
        result = 31 * result + toleranceMad.hashCode()
        return result
    }
}

data class SignalAnalysis(
    val hasActiveSignal: Boolean,
    val reason: String,
    val meanLuma: Double,
    val stdDev: Double,
    val diffFromReference: Double?,
    val temporalDiff: Double? = null
)

/**
 * Detecta se o conversor RCA->USB está exibindo imagem real da câmera de ré
 * (inclusive cenas escuras à noite com ruído analógico temporal) ou apenas o
 * frame estático "sem sinal" (preto, cinza, azul ou barras fixas).
 */
class VideoSignalDetector(
    var calibration: NoSignalCalibration? = null,
    val minStdDevForSignal: Double = 9.0,
    val minTemporalNoise: Double = 1.2
) {
    private var previousGrid: IntArray? = null

    fun resetHistory() {
        previousGrid = null
    }

    fun captureCalibration(
        grid: LumaGrid,
        toleranceMad: Double = 12.0
    ): NoSignalCalibration {
        val (mean, stdDev) = computeStats(grid.luma)
        val newCalibration = NoSignalCalibration(
            meanLuma = mean,
            lumaStdDev = stdDev,
            referenceGrid = grid.luma.copyOf(),
            toleranceMad = toleranceMad
        )
        calibration = newCalibration
        return newCalibration
    }

    fun analyze(grid: LumaGrid): SignalAnalysis {
        if (grid.luma.isEmpty()) {
            return SignalAnalysis(
                hasActiveSignal = false,
                reason = "Grade vazia",
                meanLuma = 0.0,
                stdDev = 0.0,
                diffFromReference = null,
                temporalDiff = null
            )
        }

        val (mean, stdDev) = computeStats(grid.luma)
        val cal = calibration
        val madFromRef = computeMad(grid.luma, cal?.referenceGrid)
        val temporalDiff = computeMad(grid.luma, previousGrid)
        previousGrid = grid.luma.copyOf()

        val isTemporallyStatic = temporalDiff == null || temporalDiff < minTemporalNoise

        if (cal != null && madFromRef != null && madFromRef <= cal.toleranceMad && isTemporallyStatic) {
            return SignalAnalysis(
                hasActiveSignal = false,
                reason = "Igual à referência calibrada 'Sem Sinal' (MAD=${"%.1f".format(madFromRef)})",
                meanLuma = mean,
                stdDev = stdDev,
                diffFromReference = madFromRef,
                temporalDiff = temporalDiff
            )
        }

        if (stdDev < minStdDevForSignal) {
            if (!isTemporallyStatic && stdDev >= 0.8) {
                return SignalAnalysis(
                    hasActiveSignal = true,
                    reason = "Sinal noturno/escuro detectado por ruído temporal (Δt=${"%.1f".format(temporalDiff)})",
                    meanLuma = mean,
                    stdDev = stdDev,
                    diffFromReference = madFromRef,
                    temporalDiff = temporalDiff
                )
            }
            return SignalAnalysis(
                hasActiveSignal = false,
                reason = "Tela uniforme estática sem sinal (stdDev=${"%.1f".format(stdDev)})",
                meanLuma = mean,
                stdDev = stdDev,
                diffFromReference = madFromRef,
                temporalDiff = temporalDiff
            )
        }

        return SignalAnalysis(
            hasActiveSignal = true,
            reason = "Sinal de vídeo ativo detectado",
            meanLuma = mean,
            stdDev = stdDev,
            diffFromReference = madFromRef,
            temporalDiff = temporalDiff
        )
    }

    private fun computeMad(current: IntArray, reference: IntArray?): Double? {
        if (reference == null || reference.size != current.size || current.isEmpty()) return null
        var sumDiff = 0L
        for (i in current.indices) {
            sumDiff += abs(current[i] - reference[i])
        }
        return sumDiff.toDouble() / current.size
    }

    private fun computeStats(values: IntArray): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var sqDiff = 0.0
        for (v in values) {
            val d = v - mean
            sqDiff += d * d
        }
        val stdDev = sqrt(sqDiff / values.size)
        return mean to stdDev
    }
}
