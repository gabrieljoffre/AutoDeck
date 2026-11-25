package com.autodeck.protocol.camera

enum class ReverseCameraState {
    STANDBY,
    CONFIRMING_SIGNAL,
    ACTIVE,
    CONFIRMING_LOSS,
    MANUAL_PREVIEW
}

data class ReverseTimingConfig(
    val activateAfterFrames: Int = 3,
    val deactivateAfterMs: Long = 1_200L
)

/**
 * Máquina de estados com histerese temporal para subida instantânea da câmera de ré
 * sem disparos falsos por ruído elétrico nem piscadas ao manobrar.
 */
class ReverseStateMachine(
    private val config: ReverseTimingConfig = ReverseTimingConfig()
) {
    var state: ReverseCameraState = ReverseCameraState.STANDBY
        private set

    private var consecutiveSignalFrames: Int = 0
    private var signalLostSinceMs: Long? = null

    fun onVideoSignalSample(hasSignal: Boolean, nowMs: Long): ReverseCameraState {
        if (state == ReverseCameraState.MANUAL_PREVIEW) {
            return state
        }

        if (hasSignal) {
            signalLostSinceMs = null
            when (state) {
                ReverseCameraState.STANDBY -> {
                    consecutiveSignalFrames = 1
                    state = if (consecutiveSignalFrames >= config.activateAfterFrames) {
                        ReverseCameraState.ACTIVE
                    } else {
                        ReverseCameraState.CONFIRMING_SIGNAL
                    }
                }
                ReverseCameraState.CONFIRMING_SIGNAL -> {
                    consecutiveSignalFrames++
                    if (consecutiveSignalFrames >= config.activateAfterFrames) {
                        state = ReverseCameraState.ACTIVE
                    }
                }
                ReverseCameraState.ACTIVE,
                ReverseCameraState.CONFIRMING_LOSS -> {
                    state = ReverseCameraState.ACTIVE
                }
                ReverseCameraState.MANUAL_PREVIEW -> Unit
            }
        } else {
            consecutiveSignalFrames = 0
            when (state) {
                ReverseCameraState.STANDBY,
                ReverseCameraState.CONFIRMING_SIGNAL -> {
                    state = ReverseCameraState.STANDBY
                    signalLostSinceMs = null
                }
                ReverseCameraState.ACTIVE -> {
                    signalLostSinceMs = nowMs
                    state = if (config.deactivateAfterMs <= 0L) {
                        ReverseCameraState.STANDBY
                    } else {
                        ReverseCameraState.CONFIRMING_LOSS
                    }
                }
                ReverseCameraState.CONFIRMING_LOSS -> {
                    val lostSince = signalLostSinceMs ?: nowMs
                    if (nowMs - lostSince >= config.deactivateAfterMs) {
                        state = ReverseCameraState.STANDBY
                        signalLostSinceMs = null
                    }
                }
                ReverseCameraState.MANUAL_PREVIEW -> Unit
            }
        }
        return state
    }

    fun onUsbCameraAttached(nowMs: Long): ReverseCameraState {
        // Quando o conversor é conectado, inicia em STANDBY para validar sinal de vídeo
        if (state != ReverseCameraState.MANUAL_PREVIEW) {
            consecutiveSignalFrames = 0
            signalLostSinceMs = null
            state = ReverseCameraState.STANDBY
        }
        return state
    }

    fun onUsbCameraDetached(): ReverseCameraState {
        consecutiveSignalFrames = 0
        signalLostSinceMs = null
        if (state != ReverseCameraState.MANUAL_PREVIEW) {
            state = ReverseCameraState.STANDBY
        }
        return state
    }

    fun toggleManualPreview(): ReverseCameraState {
        state = if (state == ReverseCameraState.MANUAL_PREVIEW) {
            consecutiveSignalFrames = 0
            signalLostSinceMs = null
            ReverseCameraState.STANDBY
        } else {
            ReverseCameraState.MANUAL_PREVIEW
        }
        return state
    }

    fun enterManualPreview(): ReverseCameraState {
        if (state != ReverseCameraState.ACTIVE && state != ReverseCameraState.CONFIRMING_LOSS) {
            state = ReverseCameraState.MANUAL_PREVIEW
        }
        return state
    }

    fun exitManualPreview(): ReverseCameraState {
        if (state == ReverseCameraState.MANUAL_PREVIEW) {
            consecutiveSignalFrames = 0
            signalLostSinceMs = null
            state = ReverseCameraState.STANDBY
        }
        return state
    }
}
