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
        val engine = PlaceSearch(
            httpFetcher = {
                calls++
                sampleJson
            },
            minIntervalMs = 0L
        )

        val first = engine.search("posto", -16.3281, -48.9534)
        assertEquals(2, first.size)
        assertEquals("Posto Perto", first[0].name)
        assertEquals("Posto Longe", first[1].name)
        assertTrue(first[0].distanceKm < first[1].distanceKm)

        // Segunda busca igual usa cache em memória sem nova chamada HTTP
        val cached = engine.search("posto", -16.3281, -48.9534)
        assertEquals(1, calls)
        assertEquals(first[0].name, cached[0].name)
    }

    @Test
    fun `busca inteligente usa Photon com vies na posicao, mescla favoritos e complementa com Nominatim`() = runBlocking {
        val photonGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [-48.9522, -16.3194]},
                  "properties": {
                    "name": "Brasil Park Shopping",
                    "street": "Avenida Brasil Norte",
                    "housenumber": "505",
                    "district": "Centro",
                    "city": "Anápolis",
                    "state": "Goiás"
                  }
                }
              ]
            }
        """.trimIndent()

        val nominatimJson = """
            [
              {
                "name": "Brasil Park Estacionamento",
                "display_name": "Brasil Park Estacionamento, Centro, Anápolis, GO",
                "lat": "-16.3200",
                "lon": "-48.9518"
              }
            ]
        """.trimIndent()

        val requestedUrls = mutableListOf<String>()
        val engine = PlaceSearch(
            httpFetcher = { url ->
                requestedUrls += url
                if (url.contains("photon.komoot.io")) photonGeoJson else nominatimJson
            },
            minIntervalMs = 0L
        )

        val hints = listOf(
            LocalPlaceHint(
                name = "Casa Brasilia",
                address = "Bairro Jundiaí, Anápolis - GO",
                lat = -16.3338,
                lon = -48.9435,
                isFavorite = true
            )
        )

        // Query incompleta e com pequeno erro ("brasi" -> casa favorito + Photon + complemento Nominatim)
        val results = engine.search("brasi", -16.3281, -48.9534, localHints = hints)

        assertTrue(requestedUrls.any { it.contains("photon.komoot.io") && it.contains("lat=-16.32810") && it.contains("lon=-48.95340") })
        assertTrue(requestedUrls.any { it.contains("nominatim.openstreetmap.org") && it.contains("countrycodes=br") })
        assertEquals("Casa Brasilia", results[0].name)
        val shopping = results.first { it.name == "Brasil Park Shopping" }
        assertTrue(shopping.address.contains("Centro"))
        assertTrue(shopping.address.contains("Anápolis - GO"))
        assertTrue(results.any { it.name == "Brasil Park Estacionamento" })
    }

    @Test
    fun `posto busca resultados locais quando consulta expandida retorna apenas cidades distantes`() = runBlocking {
        val farPhotonJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-49.2600,-16.8100]},"properties":{"name":"Posto de combustível","city":"Aparecida de Goiânia","state":"Goiás"}},
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-48.0300,-15.8000]},"properties":{"name":"Posto de Combustível","city":"Vicente Pires","state":"Distrito Federal"}},
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-47.8200,-15.6500]},"properties":{"name":"Posto de Combustível","city":"Sobradinho","state":"Distrito Federal"}},
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-50.3600,-15.4400]},"properties":{"name":"Posto de Combustível","city":"Faina","state":"Goiás"}}
              ]
            }
        """.trimIndent()
        val localPhotonJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-48.9480,-16.3260]},"properties":{"name":"Posto BR Petrobras","street":"Avenida Jamel Cecílio","city":"Anápolis","state":"Goiás"}},
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-48.9510,-16.3290]},"properties":{"name":"Posto Alé","street":"Avenida Brasil","city":"Anápolis","state":"Goiás"}},
                {"type":"Feature","geometry":{"type":"Point","coordinates":[-48.9420,-16.3210]},"properties":{"name":"Posto Carreteiro","street":"Avenida Universitária","city":"Anápolis","state":"Goiás"}}
              ]
            }
        """.trimIndent()

        val engine = PlaceSearch(
            httpFetcher = { url ->
                when {
                    url.contains("photon.komoot.io") && url.contains("combust") -> farPhotonJson
                    url.contains("photon.komoot.io") -> localPhotonJson
                    else -> "[]"
                }
            },
            minIntervalMs = 0L
        )

        val results = engine.search("posto", -16.3281, -48.9534)
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().distanceKm < 5.0)
        assertTrue(results.any { it.name == "Posto BR Petrobras" })
    }
}
