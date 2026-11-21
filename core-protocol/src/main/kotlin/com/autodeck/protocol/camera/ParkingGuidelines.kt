package com.autodeck.protocol.camera

data class GuidelineCalibration(
    val topWidthRatio: Float = 0.42f,
    val bottomWidthRatio: Float = 0.88f,
    val topYRatio: Float = 0.48f,
    val bottomYRatio: Float = 0.96f,
    val horizontalOffsetRatio: Float = 0f,
    val mirrorHorizontal: Boolean = false,
    val enabled: Boolean = true
) {
    fun clamped(): GuidelineCalibration {
        val safeTopY = topYRatio.coerceIn(0.20f, 0.80f)
        val safeBottomY = bottomYRatio.coerceIn((safeTopY + 0.10f).coerceAtMost(0.99f), 0.99f)
        val safeTopW = topWidthRatio.coerceIn(0.15f, 0.85f)
        val safeBottomW = bottomWidthRatio.coerceIn(safeTopW.coerceAtLeast(0.25f), 0.98f)
        return copy(
            topWidthRatio = safeTopW,
            bottomWidthRatio = safeBottomW,
            topYRatio = safeTopY,
            bottomYRatio = safeBottomY,
            horizontalOffsetRatio = horizontalOffsetRatio.coerceIn(-0.25f, 0.25f)
        )
    }
}
