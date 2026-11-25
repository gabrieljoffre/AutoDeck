package com.autodeck.headunit.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {

    @Test
    fun `expande termos curtos posto e oficina e ordena resultados por distancia`() = runBlocking {
        assertEquals("posto de combustível", PlaceSearch.expandQuery("posto"))
        assertEquals("oficina mecânica", PlaceSearch.expandQuery("Oficina"))
        assertEquals("Shopping Brasil Park", PlaceSearch.expandQuery("Shopping Brasil Park"))

        val sampleJson = """
            [
              {"name": "Posto Longe", "display_name": "Posto Longe, Bairro Sul, Anápolis, GO", "lat": "-16.3800", "lon": "-48.9534"},
              {"name": "Posto Perto", "display_name": "Posto Perto, Centro, Anápolis, GO", "lat": "-16.3290", "lon": "-48.9534"}
            ]
        """.trimIndent()

        var calls = 0
        var requestedUrl = ""
        val engine = PlaceSearch(
            httpFetcher = { url ->
                calls++
                requestedUrl = url
                sampleJson
            },
            minIntervalMs = 0L
        )

        val first = engine.search("posto", -16.3281, -48.9534)
        assertEquals(2, first.size)
        assertEquals("Posto Perto", first[0].name)
        assertEquals("Posto Longe", first[1].name)
        assertTrue(first[0].distanceKm < first[1].distanceKm)
        assertTrue(requestedUrl.contains("countrycodes=br"))
        assertTrue(requestedUrl.contains("bounded=0"))

        // Segunda busca igual usa cache em memória sem nova chamada HTTP
        val cached = engine.search("posto", -16.3281, -48.9534)
        assertEquals(1, calls)
        assertEquals(first[0].name, cached[0].name)
    }
}
