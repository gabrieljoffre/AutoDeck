package com.autodeck.headunit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWebApiTest {

    private val playerJson = """
        {
          "device": {"id": "dev-cel", "name": "Galaxy do Ana", "type": "Smartphone", "is_active": true},
          "repeat_state": "context",
          "shuffle_state": true,
          "progress_ms": 42000,
          "is_playing": true,
          "item": {
            "uri": "spotify:track:abc",
            "name": "Faixa Teste",
            "duration_ms": 215000,
            "artists": [{"name": "Artista A"}, {"name": "Artista B"}],
            "album": {"name": "Álbum X", "images": [
              {"url": "https://img/640.jpg", "width": 640},
              {"url": "https://img/300.jpg", "width": 300},
              {"url": "https://img/64.jpg", "width": 64}
            ]}
          }
        }
    """.trimIndent()

    @Test
    fun parsePlayerState_lerFaixaDispositivoEOpcoes() {
        val snap = SpotifyWebApi.parsePlayerState(playerJson)
        assertNotNull(snap)
        snap!!
        assertEquals("Faixa Teste", snap.title)
        assertEquals("Artista A, Artista B", snap.artist)
        assertEquals("Álbum X", snap.album)
        assertEquals("spotify:track:abc", snap.trackUri)
        assertTrue(snap.isPlaying)
        assertEquals(42_000L, snap.progressMs)
        assertEquals(215_000L, snap.durationMs)
        assertTrue(snap.shuffle)
        assertEquals("all", snap.repeatMode)
        assertEquals("Galaxy do Ana", snap.device?.name)
        assertEquals("Smartphone", snap.device?.type)
        assertEquals("https://img/300.jpg", snap.imageUrl)
    }

    @Test
    fun parsePlayerState_semItemRetornaNulo() {
        assertNull(SpotifyWebApi.parsePlayerState("""{"is_playing": false, "device": {"id": "x", "name": "n", "type": "Tablet"}}"""))
        assertNull(SpotifyWebApi.parsePlayerState("isto não é json"))
    }

    @Test
    fun parsePlayerState_repeatTrackViraOne() {
        val snap = SpotifyWebApi.parsePlayerState(playerJson.replace("\"context\"", "\"track\""))
        assertEquals("one", snap?.repeatMode)
        val off = SpotifyWebApi.parsePlayerState(playerJson.replace("\"context\"", "\"off\""))
        assertEquals("off", off?.repeatMode)
    }

    @Test
    fun parseDevices_ignoraSemIdEMarcaAtivo() {
        val devices = SpotifyWebApi.parseDevices(
            """
            {"devices": [
              {"id": "a1", "name": "SM-T500", "type": "Tablet", "is_active": false},
              {"id": null, "name": "Restrito", "type": "Speaker", "is_active": false},
              {"id": "b2", "name": "Celular", "type": "Smartphone", "is_active": true}
            ]}
            """.trimIndent()
        )
        assertEquals(2, devices.size)
        assertEquals("a1", devices[0].id)
        assertFalse(devices[0].isActive)
        assertTrue(devices[1].isActive)
    }

    @Test
    fun parseDevices_jsonInvalidoDaListaVazia() {
        assertTrue(SpotifyWebApi.parseDevices("{}").isEmpty())
        assertTrue(SpotifyWebApi.parseDevices("xx").isEmpty())
    }

    @Test
    fun playBody_faixaUsaUrisEOutrosUsamContexto() {
        assertEquals("""{"uris":["spotify:track:123"]}""", SpotifyWebApi.playBody("spotify:track:123"))
        assertEquals("""{"context_uri":"spotify:playlist:9"}""", SpotifyWebApi.playBody("spotify:playlist:9"))
        assertEquals("""{"context_uri":"spotify:album:7"}""", SpotifyWebApi.playBody("spotify:album:7"))
    }

    @Test
    fun escopos_incluemControleDoPlayer() {
        val scopes = SpotifyWebApi.SCOPES.toSet()
        assertTrue("user-read-playback-state" in scopes)
        assertTrue("user-modify-playback-state" in scopes)
        assertTrue("app-remote-control" in scopes)
    }
}
