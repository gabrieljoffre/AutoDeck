package com.autodeck.headunit.hardware

import android.view.Surface

/**
 * Bridge JNI direto para o driver user-space C (usbdevfs) do chipset Syntek STK1160 + SAA7113/GM7113
 * (`VID_05E1&PID_0408`). Opera exclusivamente na interface de vídeo (interface 0), sem reivindicar
 * a interface de áudio USB para evitar falhas de banda no Hub.
 */
object Stk1160NativeBridge {

    val isLibraryLoaded: Boolean = try {
        System.loadLibrary("stk1160_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic
    external fun nativeInitChip(
        fd: Int,
        channel: Int,
        standardId: Int,
        autoDetectFreq: Boolean,
        regBrightness: Int,
        regContrast: Int,
        regSaturation: Int
    ): Int

    @JvmStatic
    external fun nativeSelectChannel(fd: Int, channel: Int): Int

    @JvmStatic
    external fun nativeSetStandard(
        fd: Int,
        standardId: Int,
        autoDetectFreq: Boolean,
        colorOnly: Boolean
    ): Int

    @JvmStatic
    external fun nativeSetImageAdjustments(
        fd: Int,
        regBrightness: Int,
        regContrast: Int,
        regSaturation: Int
    ): Int

    @JvmStatic
    external fun nativeReadSignalStatus(fd: Int): Int

    /**
     * Lê `0x1F` (byte baixo) e `0x1E` (byte alto) do SAA7113/GM7113C.
     * Retorna negativo em erro de I2C.
     */
    @JvmStatic
    external fun nativeReadExtendedStatus(fd: Int): Int

    /**
     * Consulta o bit `CODE` (travamento de subportadora de cor) do SAA7113/GM7113C.
     * Retorna `1` = cor travada, `0` = sem cor para o padrão atual, `< 0` = erro I2C.
     */
    @JvmStatic
    external fun nativeProbeColorLock(fd: Int): Int

    @JvmStatic
    external fun nativeStartStreaming(
        fd: Int,
        altSetting: Int,
        isoPacketSize: Int,
        standardId: Int,
        autoDetectFreq: Boolean,
        surface: Surface?
    ): Int

    @JvmStatic
    external fun nativeUpdateSurface(surface: Surface?)

    @JvmStatic
    external fun nativeCopyLastLumaGrid(out192: IntArray): Boolean

    /** false quando a thread ISO nativa terminou sozinha (falha de URB, poll ou USB removido). */
    @JvmStatic
    external fun nativeIsStreaming(): Boolean

    /**
     * Preenche `[framesRendered, framesDropped, isoPacketErrors, lastFrameAgeMs, stalledFlag]`.
     */
    @JvmStatic
    external fun nativeGetStreamStats(outStats5: LongArray): Boolean

    @JvmStatic
    external fun nativeStopStreaming()

    @JvmStatic
    external fun nativeReleaseInterface(fd: Int)
}
