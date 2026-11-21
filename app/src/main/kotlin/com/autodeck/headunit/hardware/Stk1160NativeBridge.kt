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
    external fun nativeInitChip(fd: Int, channel: Int, isNtsc: Boolean): Int

    @JvmStatic
    external fun nativeSelectChannel(fd: Int, channel: Int): Int

    @JvmStatic
    external fun nativeSetStandard(fd: Int, isNtsc: Boolean): Int

    @JvmStatic
    external fun nativeReadSignalStatus(fd: Int): Int

    @JvmStatic
    external fun nativeStartStreaming(
        fd: Int,
        altSetting: Int,
        isoPacketSize: Int,
        isNtsc: Boolean,
        surface: Surface?
    ): Int

    @JvmStatic
    external fun nativeUpdateSurface(surface: Surface?)

    @JvmStatic
    external fun nativeCopyLastLumaGrid(out192: IntArray): Boolean

    @JvmStatic
    external fun nativeStopStreaming()

    @JvmStatic
    external fun nativeReleaseInterface(fd: Int)
}
