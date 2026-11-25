package com.autodeck.headunit.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResourceRouterTest {

    @Test
    fun `resolve urls Esri escuro e claro e valida caminho de tile`() {
        val darkUrl = WebResourceRouter.tileUrlForLayer(WebResourceRouter.LAYER_BASE, 15, 17854, 11928)
        val labelsUrl = WebResourceRouter.tileUrlForLayer(WebResourceRouter.LAYER_LABELS, 15, 17854, 11928)
        val lightUrl = WebResourceRouter.tileUrlForLayer(WebResourceRouter.LAYER_LIGHT, 15, 17854, 11928)

        assertTrue(darkUrl.contains("World_Dark_Gray_Base/MapServer/tile/15/17854/11928"))
        assertTrue(labelsUrl.contains("World_Dark_Gray_Reference/MapServer/tile/15/17854/11928"))
        assertTrue(lightUrl.contains("World_Street_Map/MapServer/tile/15/17854/11928"))

        assertEquals(Triple(15, 17854, 11928), WebResourceRouter.parseTilePath("15/17854/11928.png"))
        assertNull(WebResourceRouter.parseTilePath("../etc/passwd"))
        assertNull(WebResourceRouter.parseTilePath("25/0/0.png"))
    }

    @Test
    fun `valida caminho de icone de pacote e bloqueia path traversal`() {
        assertEquals("com.spotify.music", WebResourceRouter.parsePackageIconPath("com.spotify.music.png"))
        assertEquals("com.whatsapp", WebResourceRouter.parsePackageIconPath("/com.whatsapp.png?v=3"))
        assertEquals("com.google.android.apps.maps", WebResourceRouter.parsePackageIconPath("com.google.android.apps.maps.png"))
        assertNull(WebResourceRouter.parsePackageIconPath("../com.whatsapp.png"))
        assertNull(WebResourceRouter.parsePackageIconPath("sub/com.whatsapp.png"))
        assertNull(WebResourceRouter.parsePackageIconPath("com.whatsapp.jpg"))
        assertNull(WebResourceRouter.parsePackageIconPath("singleword.png"))
        assertNull(WebResourceRouter.parsePackageIconPath(""))
    }
}
