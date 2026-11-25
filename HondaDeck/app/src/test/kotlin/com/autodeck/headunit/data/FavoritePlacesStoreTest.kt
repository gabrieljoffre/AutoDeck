package com.autodeck.headunit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritePlacesStoreTest {

    @Test
    fun `encode e decode de favoritos preserva campos e normaliza icones`() {
        val items = listOf(
            FavoritePlace(
                id = "casa",
                name = "Casa",
                address = "Centro, Anápolis - GO",
                lat = -16.3281,
                lon = -48.9534,
                voice = "ir para casa",
                icon = "home"
            ),
            FavoritePlace(
                id = "posto1",
                name = "Posto Ipiranga",
                address = "Av. Brasil",
                lat = -16.3301,
                lon = -48.9502,
                voice = "ir para o posto",
                icon = "invalido"
            )
        )

        val encoded = FavoritePlacesStore.encodeFavorites(items)
        val decoded = FavoritePlacesStore.decodeFavorites(encoded)

        assertEquals(2, decoded.size)
        assertEquals("casa", decoded[0].id)
        assertEquals("Casa", decoded[0].name)
        assertEquals(-16.3281, decoded[0].lat, 0.00001)
        assertEquals("home", decoded[0].icon)
        assertEquals("star", decoded[1].icon)
    }

    @Test
    fun `encode e decode de lugares recentes respeita limite de 15 e tolera JSON invalido`() {
        val many = (1..20).map { i ->
            RecentPlace("Lugar $i", "Rua $i", -16.3 - i * 0.001, -48.95)
        }
        val encoded = FavoritePlacesStore.encodeRecentPlaces(many)
        val decoded = FavoritePlacesStore.decodeRecentPlaces(encoded)

        assertEquals(FavoritePlacesStore.MAX_RECENTS, decoded.size)
        assertEquals("Lugar 1", decoded.first().name)
        assertEquals("Lugar 15", decoded.last().name)

        assertTrue(FavoritePlacesStore.decodeFavorites("json quebrado").isEmpty())
        assertTrue(FavoritePlacesStore.decodeRecentPlaces(null).isEmpty())
    }
}
