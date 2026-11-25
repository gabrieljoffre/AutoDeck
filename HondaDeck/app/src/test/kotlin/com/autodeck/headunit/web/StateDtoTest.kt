package com.autodeck.headunit.web

import com.autodeck.headunit.data.FavoritePlace
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.RecentPlace
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.SpotifyItem
import com.autodeck.headunit.media.SpotifyItemKind
import com.autodeck.headunit.media.SpotifyLinkStatus
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.navigation.GpsLocationState
import com.autodeck.headunit.navigation.InAppRouteState
import com.autodeck.headunit.navigation.ManeuverIcon
import com.autodeck.headunit.navigation.PlaceSearchResult
import com.autodeck.headunit.navigation.PlaceSearchState
import com.autodeck.headunit.navigation.RoutePoint
import com.autodeck.headunit.navigation.RouteStep
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.weather.WeatherKind
import com.autodeck.headunit.weather.WeatherNow
import com.autodeck.headunit.weather.WeatherState
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.obd2.DerivedTelemetry
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.DtcSystem
import com.autodeck.protocol.obd2.FuelEntry
import com.autodeck.protocol.obd2.FuelLogSummary
import com.autodeck.protocol.obd2.ObdSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDtoTest {

    @Test
    fun `estado padrao gera JSON valido com todas as chaves do contrato`() {
        val json = StateDto.buildStateJson(
            ui = HeadUnitUiState(),
            extras = WebExtras(nowEpochMs = 1_760_000_000_000L, wifiConnected = true)
        )

        assertEquals(1, json.getInt("v"))
        assertEquals("#22D3EE", json.getString("accent"))
        assertEquals("HONDA", json.getString("brand"))

        val expectedTopLevelKeys = listOf(
            "v", "accent", "brand", "clock", "weather", "status", "telemetry",
            "media", "spotify", "map", "copilot", "audio", "notification",
            "vehicle", "settings"
        )
        for (key in expectedTopLevelKeys) {
            assertTrue("Chave '$key' ausente no JSON", json.has(key))
        }

        assertEquals(1_760_000_000_000L, json.getJSONObject("clock").getLong("epochMs"))
        assertTrue(json.getJSONObject("status").getBoolean("wifi"))
        assertEquals("DISCONNECTED", json.getJSONObject("status").getString("obd"))
        assertTrue(json.isNull("notification"))

        val settings = json.getJSONObject("settings")
        assertEquals(5, settings.getJSONArray("accentOptions").length())
        assertEquals("ANIMATION", settings.getString("introMode"))
        assertEquals("3.0.0", settings.getString("version"))

        val maintenance = json.getJSONObject("vehicle").getJSONObject("maintenance")
        assertNotNull(maintenance.getJSONArray("items"))
    }

    @Test
    fun `serializa telemetria, rota, spotify, favoritos, notificacao e abastecimento`() {
        val ui = HeadUnitUiState(
            headUnit = HeadUnitState(
                audioBtStatus = LinkStatus.CONNECTED,
                audioBtDeviceName = "Taramps",
                obdStatus = LinkStatus.CONNECTED,
                obdSnapshot = ObdSnapshot(
                    rpm = 2450.0,
                    speedKmh = 62.0,
                    batteryVoltageV = 14.1,
                    coolantTempC = 88.0,
                    engineLoadPct = 34.0,
                    derived = DerivedTelemetry(
                        correctedSpeedKmh = 63.5,
                        estimatedGearLabel = "3ª",
                        virtualTankRemainingLiters = 32.5,
                        virtualTankPercent = 65.0
                    )
                ),
                dtcCodes = listOf(DtcCode("P0443", DtcSystem.POWERTRAIN, "Válvula de purga EVAP"))
            ),
            config = HeadUnitConfig(
                useSimulatedObd = true,
                accentColor = "#A3E635",
                introMode = "VIDEO",
                geminiApiKey = "chave-teste"
            ),
            mediaState = MediaPlaybackState(
                title = "Bem Querer",
                artist = "Maurício Manieri",
                album = "A Noite Inteira",
                contextLabel = "Minhas curtidas",
                hasCover = true,
                coverVersion = 7L,
                isPlaying = true,
                positionMs = 42_000L,
                durationMs = 210_000L,
                shuffle = true,
                repeatMode = "all",
                liked = true,
                hasActiveTrack = true
            ),
            spotifyStatus = SpotifyLinkStatus.CONNECTED,
            spotifyAllPlaylists = listOf(
                SpotifyItem(SpotifyItemKind.PLAYLIST, "Honda Civic Hits", "Ana", "spotify:playlist:123", "https://img")
            ),
            spotifyRecentSearches = listOf("Panda", "Coldplay"),
            gpsState = GpsLocationState(latitude = -16.3281, longitude = -48.9534, bearingDeg = 90f, hasFix = true),
            route = InAppRouteState(
                destinationLabel = "Posto Ipiranga",
                polyline = listOf(RoutePoint(-16.32, -48.95), RoutePoint(-16.33, -48.96)),
                nextStep = RouteStep("Vire à direita", "Av. Brasil", ManeuverIcon.SHARP_RIGHT, 300.0),
                distanceToNextM = 250.0,
                remainingDistanceM = 1500.0,
                remainingDurationS = 180.0
            ),
            weather = WeatherState(
                now = WeatherNow(26, 28, WeatherKind.CLEAR, true),
                todayMinC = 18,
                todayMaxC = 29
            ),
            whatsAppMessages = listOf(
                WhatsAppNotificationItem(
                    key = "k1",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    sender = "João",
                    message = "Chegando em 5 min",
                    timestampMs = 1_760_000_000_000L
                )
            ),
            fuelEntries = listOf(
                FuelEntry(
                    timestampMs = 1_760_000_000_000L,
                    odometerKm = 123456.0,
                    liters = 30.0,
                    pricePerLiter = 6.19,
                    fullTank = true
                )
            ),
            fuelSummary = FuelLogSummary(
                entryCount = 1,
                lastKmPerL = 11.4,
                averageKmPerL = 11.2,
                averagePricePerLiter = 6.19,
                spentLast30Days = 185.7,
                costPerKm = 0.55
            ),
            masterVolumePct = 55,
            isAudioMuted = true,
            favoritePlaces = listOf(
                FavoritePlace("casa", "Casa", "Centro, Anápolis", -16.328, -48.953, "ir para casa", "home")
            ),
            recentPlaces = listOf(
                RecentPlace("Shopping", "Av. Brasil", -16.33, -48.94)
            ),
            placeSearch = PlaceSearchState(
                query = "posto",
                loading = false,
                results = listOf(PlaceSearchResult("Posto Shell", "Av. Brasil", -16.329, -48.952, 0.8))
            )
        )

        val json = StateDto.buildStateJson(ui, WebExtras(nowEpochMs = 1_760_000_000_000L, wifiConnected = true))

        assertEquals("#A3E635", json.getString("accent"))
        assertEquals("SIMULATED", json.getJSONObject("status").getString("obd"))
        assertEquals("Taramps", json.getJSONObject("status").getString("btName"))
        assertTrue(json.getJSONObject("weather").getBoolean("available"))
        assertEquals(26, json.getJSONObject("weather").getInt("tempC"))

        val tel = json.getJSONObject("telemetry")
        assertEquals(63.5, tel.getDouble("speedKmh"), 0.01)
        assertEquals(2450, tel.getInt("rpm"))
        assertEquals("3", tel.getString("gear"))
        assertEquals(65.0, tel.getDouble("fuelPct"), 0.01)

        val media = json.getJSONObject("media")
        assertEquals("CONNECTED", media.getString("status"))
        assertTrue(media.getBoolean("playing"))
        assertEquals("/cover/current.jpg?t=7", media.getString("coverUrl"))
        assertEquals("all", media.getString("repeat"))
        assertTrue(media.getBoolean("liked"))

        val pl0 = json.getJSONObject("spotify").getJSONArray("playlists").getJSONObject(0)
        assertTrue(pl0.getBoolean("honda"))

        val route = json.getJSONObject("map").getJSONObject("route")
        assertTrue(route.getBoolean("active"))
        assertEquals("Vire à direita na Av. Brasil", route.getString("nextInstruction"))
        assertEquals("RIGHT", route.getString("nextIcon"))
        assertEquals(2, route.getJSONArray("polyline").length())

        val notif = json.getJSONObject("notification")
        assertEquals("k1", notif.getString("id"))
        assertEquals("João", notif.getString("title"))

        val audio = json.getJSONObject("audio")
        assertEquals(55, audio.getInt("volumePct"))
        assertTrue(audio.getBoolean("muted"))

        val dtc0 = json.getJSONObject("vehicle").getJSONArray("dtcCodes").getJSONObject(0)
        assertEquals("P0443", dtc0.getString("code"))
        assertTrue(json.getJSONObject("settings").getBoolean("geminiKeySet"))
        assertEquals("VIDEO", json.getJSONObject("settings").getString("introMode"))

        val fastTel = StateDto.buildTelemetryJson(ui)
        assertEquals(63.5, fastTel.getDouble("speedKmh"), 0.01)
        assertEquals("3", fastTel.getString("gear"))
        assertFalse(fastTel.has("voltage"))
    }
}
