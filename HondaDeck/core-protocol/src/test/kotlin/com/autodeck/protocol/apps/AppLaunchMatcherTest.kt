package com.autodeck.protocol.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchMatcherTest {

    private val installed = listOf(
        LaunchableApp("com.google.android.apps.youtube.music", "YT Music"),
        LaunchableApp("com.google.android.youtube", "YouTube"),
        LaunchableApp("com.waze", "Waze"),
        LaunchableApp("com.sec.android.app.camera", "Câmera"),
        LaunchableApp("com.spotify.music", "Spotify"),
        LaunchableApp("com.whatsapp", "WhatsApp"),
        LaunchableApp("com.android.chrome", "Chrome"),
        LaunchableApp("com.sec.android.app.popupcalculator", "Calculadora"),
        LaunchableApp("com.google.android.apps.maps", "Maps")
    )

    @Test
    fun `resolve nomes falados com apelidos e sem acentos`() {
        val ytMusic = AppLaunchMatcher.resolve(installed, "YouTube Music")
        assertTrue(ytMusic is AppMatchResult.Matched)
        assertEquals("com.google.android.apps.youtube.music", (ytMusic as AppMatchResult.Matched).app.packageName)

        val cam = AppLaunchMatcher.resolve(installed, "a câmera")
        assertTrue(cam is AppMatchResult.Matched)
        assertEquals("com.sec.android.app.camera", (cam as AppMatchResult.Matched).app.packageName)

        val waze = AppLaunchMatcher.resolve(installed, "o Waze")
        assertTrue(waze is AppMatchResult.Matched)
        assertEquals("com.waze", (waze as AppMatchResult.Matched).app.packageName)

        val zap = AppLaunchMatcher.resolve(installed, "zap")
        assertTrue(zap is AppMatchResult.Matched)
        assertEquals("com.whatsapp", (zap as AppMatchResult.Matched).app.packageName)

        val calc = AppLaunchMatcher.resolve(installed, "calculadora")
        assertTrue(calc is AppMatchResult.Matched)
        assertEquals("com.sec.android.app.popupcalculator", (calc as AppMatchResult.Matched).app.packageName)

        val notFound = AppLaunchMatcher.resolve(installed, "aplicativo inexistente xyz")
        assertEquals(AppMatchResult.NotFound, notFound)
    }

    @Test
    fun `distingue YouTube de YouTube Music sem confusao`() {
        val yt = AppLaunchMatcher.resolve(installed, "YouTube")
        assertTrue(yt is AppMatchResult.Matched)
        assertEquals("com.google.android.youtube", (yt as AppMatchResult.Matched).app.packageName)

        val ytm = AppLaunchMatcher.resolve(installed, "o aplicativo YouTube Music")
        assertTrue(ytm is AppMatchResult.Matched)
        assertEquals("com.google.android.apps.youtube.music", (ytm as AppMatchResult.Matched).app.packageName)
    }
}
