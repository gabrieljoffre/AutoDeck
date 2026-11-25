package com.autodeck.headunit.web

import com.autodeck.headunit.calendar.BrazilHolidays
import com.autodeck.headunit.data.FavoritePlace
import com.autodeck.headunit.data.RecentPlace
import com.autodeck.headunit.media.SpotifyItem
import com.autodeck.headunit.navigation.ManeuverIcon
import com.autodeck.headunit.service.HeadUnitUiState
import com.autodeck.headunit.weather.WeatherKind
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.obd2.ObdSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Informações auxiliares para montar o estado enviado à WebView sem depender de classes Android.
 */
data class WebExtras(
    val nowEpochMs: Long = System.currentTimeMillis(),
    val wifiConnected: Boolean = false,
    val city: String = "Anápolis",
    val appVersion: String = "3.0.0",
    val accentOverride: String? = null,
    val favoritePlacesOverride: List<FavoritePlace>? = null,
    val recentPlacesOverride: List<RecentPlace>? = null,
    val recentSearchesOverride: List<String>? = null,
    val mapsJsApiKey: String = ""
)

/**
 * Serializador puro (`HeadUnitUiState` -> JSON do contrato `.agy/contrato-ponte.md`),
 * livre de objetos Android (sem Bitmap, RemoteInput ou Context).
 */
object StateDto {

    val ACCENT_OPTIONS = listOf("#22D3EE", "#A3E635", "#FB923C", "#A78BFA", "#F472B6")

    fun buildStateJson(ui: HeadUnitUiState, extras: WebExtras = WebExtras()): JSONObject {
        val snap = ui.headUnit.obdSnapshot
        val derived = snap.derived
        val accent = (extras.accentOverride ?: ui.config.accentColor).ifBlank { "#22D3EE" }
        val favorites = extras.favoritePlacesOverride ?: ui.favoritePlaces
        val recentPlaces = extras.recentPlacesOverride ?: ui.recentPlaces
        val recentSearches = extras.recentSearchesOverride ?: ui.spotifyRecentSearches

        val obdStatusStr = when {
            ui.config.useSimulatedObd && ui.headUnit.obdStatus == LinkStatus.CONNECTED -> "SIMULATED"
            ui.headUnit.obdStatus == LinkStatus.CONNECTED -> "CONNECTED"
            ui.headUnit.obdStatus == LinkStatus.CONNECTING -> "CONNECTING"
            else -> "DISCONNECTED"
        }

        val speedKmh = resolveSpeedKmh(ui)
        val rpm = safeNum(snap.rpm).coerceAtLeast(0.0)
        val gear = normalizeGear(derived.estimatedGearLabel)
        val fuelPct = safeNum(snap.fuelLevelPct ?: derived.virtualTankPercent).coerceIn(0.0, 100.0)

        val visibleAlerts = ui.headUnit.obdAlerts
            .filter { it.title != ui.headUnit.dismissedAlertTitle }
            .map { if (it.detail.isNotBlank()) "${it.title}: ${it.detail}" else it.title }

        val weatherNow = ui.weather.now
        val weatherObj = JSONObject().apply {
            put("available", weatherNow != null)
            put("tempC", weatherNow?.tempC ?: 0)
            put("feelsLikeC", weatherNow?.feelsLikeC ?: 0)
            put("maxC", ui.weather.todayMaxC ?: weatherNow?.tempC ?: 0)
            put("minC", ui.weather.todayMinC ?: weatherNow?.tempC ?: 0)
            put("kind", (weatherNow?.kind ?: WeatherKind.PARTLY_CLOUDY).name)
            put("summary", weatherNow?.kind?.labelPt ?: ui.weather.statusText)
            put("city", extras.city)
            put("isDay", weatherNow?.isDay ?: true)
            put("humidityPct", weatherNow?.humidityPct ?: 0)
            put("windKmh", weatherNow?.windKmh ?: 0)
            put("windDirDeg", weatherNow?.windDirDeg ?: 0)
            put("windDirLabel", weatherNow?.windDirLabel.orEmpty())
            put("rainChancePct", weatherNow?.rainChancePct ?: 0)
            put("precipMm", round1(safeNum(weatherNow?.precipMm)))
            put("uvIndex", round1(safeNum(weatherNow?.uvIndex)))
            put("sunrise", weatherNow?.sunrise.orEmpty())
            put("sunset", weatherNow?.sunset.orEmpty())
            put("hourly", JSONArray().apply {
                ui.weather.nextHours.forEach { h ->
                    put(JSONObject().apply {
                        put("hour", h.hourLabel)
                        put("tempC", h.tempC)
                        put("kind", h.kind.name)
                        put("rainChancePct", h.rainChancePct)
                        put("precipMm", round1(safeNum(h.precipMm)))
                    })
                }
            })
            put("daily", JSONArray().apply {
                ui.weather.nextDays.forEach { d ->
                    put(JSONObject().apply {
                        put("day", d.dayLabel)
                        put("dateIso", d.dateIso)
                        put("minC", d.minC)
                        put("maxC", d.maxC)
                        put("kind", d.kind.name)
                        put("rainChancePct", d.rainChancePct)
                        put("precipMm", round1(safeNum(d.precipMm)))
                        put("uvMax", round1(safeNum(d.uvMax)))
                        put("sunrise", d.sunrise)
                        put("sunset", d.sunset)
                    })
                }
            })
        }

        val phone = ui.phoneLink
        val statusObj = JSONObject().apply {
            put("wifi", extras.wifiConnected)
            put("bluetooth", ui.headUnit.audioBtStatus == LinkStatus.CONNECTED)
            put("btName", ui.headUnit.audioBtDeviceName)
            put("obd", obdStatusStr)
            put("gps", ui.gpsState.hasFix)
            put("phone", phone.isConnected)
            put("phoneState", phone.connectionState.name)
            put("phoneName", phone.phoneName)
        }

        val telemetryObj = JSONObject().apply {
            put("speedKmh", round1(speedKmh))
            put("rpm", rpm.roundToInt())
            put("gear", gear)
            put("fuelPct", round1(fuelPct))
            put("voltage", round2(safeNum(snap.batteryVoltageV)))
            put("coolantC", round1(safeNum(snap.coolantTempC)))
            put("loadPct", round1(safeNum(snap.engineLoadPct)))
            put("alerts", JSONArray(visibleAlerts))
        }

        val media = ui.mediaState
        val mediaObj = JSONObject().apply {
            put("status", ui.spotifyStatus.name)
            put("playing", media.isPlaying)
            put("title", if (media.hasActiveTrack) media.title else "")
            put("artist", if (media.hasActiveTrack) media.artist else "")
            put("album", media.album)
            put("context", media.contextLabel.ifBlank { media.album.ifBlank { media.appLabel } })
            put("positionMs", media.positionMs.coerceAtLeast(0L))
            put("durationMs", media.durationMs.coerceAtLeast(0L))
            put("shuffle", media.shuffle)
            put("repeat", media.repeatMode)
            put("coverUrl", if (media.hasCover) "/cover/current.jpg?t=${media.coverVersion}" else "")
            put("liked", media.liked)
        }

        val playlistsSource = ui.spotifyAllPlaylists.ifEmpty { ui.spotifyHondaPlaylists }
        val spotifyObj = JSONObject().apply {
            put("playlists", JSONArray().apply {
                playlistsSource.forEach { item ->
                    put(spotifyItemToJson(item, includeHondaFlag = true))
                }
            })
            put("recent", JSONArray().apply {
                ui.spotifyRecent.forEach { item ->
                    put(spotifyItemToJson(item, includeHondaFlag = false))
                }
            })
            put("recentSearches", JSONArray(recentSearches))
            put("search", JSONObject().apply {
                put("query", ui.spotifySearchQuery)
                put("loading", ui.spotifySearchLoading)
                put("error", ui.spotifySearchError ?: JSONObject.NULL)
                put("tracks", spotifyListToJson(ui.spotifySearchResults.tracks))
                put("artists", spotifyListToJson(ui.spotifySearchResults.artists))
                put("albums", spotifyListToJson(ui.spotifySearchResults.albums))
                put("playlists", spotifyListToJson(ui.spotifySearchResults.playlists))
            })
        }

        val route = ui.route
        val nextStep = route.nextStep
        val nextInstruction = when {
            nextStep == null -> ""
            nextStep.streetName.isNotBlank() -> "${nextStep.instructionPt} na ${nextStep.streetName}"
            else -> nextStep.instructionPt
        }
        val etaEpochMs = if (route.isActive && route.remainingDurationS > 0.0) {
            extras.nowEpochMs + (route.remainingDurationS * 1000.0).toLong()
        } else {
            0L
        }

        val mapsKey = extras.mapsJsApiKey.trim()
        val mapObj = JSONObject().apply {
            put("provider", if (mapsKey.isNotEmpty()) "google" else "esri")
            put("jsKey", mapsKey)
            put("lat", safeNum(ui.gpsState.latitude, -16.3281))
            put("lon", safeNum(ui.gpsState.longitude, -48.9534))
            put("bearing", round1(safeNum(ui.gpsState.bearingDeg.toDouble())))
            put("hasFix", ui.gpsState.hasFix)
            put("favorites", JSONArray().apply {
                favorites.forEach { f ->
                    put(JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("address", f.address)
                        put("lat", safeNum(f.lat))
                        put("lon", safeNum(f.lon))
                        put("voice", f.voice)
                        put("icon", f.icon)
                    })
                }
            })
            put("recentPlaces", JSONArray().apply {
                recentPlaces.forEach { r ->
                    put(JSONObject().apply {
                        put("name", r.name)
                        put("address", r.address)
                        put("lat", safeNum(r.lat))
                        put("lon", safeNum(r.lon))
                    })
                }
            })
            put("search", JSONObject().apply {
                put("query", ui.placeSearch.query)
                put("loading", ui.placeSearch.loading)
                put("results", JSONArray().apply {
                    ui.placeSearch.results.forEach { r ->
                        put(JSONObject().apply {
                            put("name", r.name)
                            put("address", r.address)
                            put("lat", safeNum(r.lat))
                            put("lon", safeNum(r.lon))
                            put("distanceKm", round1(safeNum(r.distanceKm)))
                        })
                    }
                })
            })
            put("route", JSONObject().apply {
                put("active", route.isActive)
                put("loading", route.isLoading)
                put("error", route.error ?: JSONObject.NULL)
                put("destination", route.destinationLabel)
                put("polyline", JSONArray().apply {
                    route.polyline.forEach { pt ->
                        put(JSONArray().put(safeNum(pt.lat)).put(safeNum(pt.lon)))
                    }
                })
                put("nextInstruction", nextInstruction)
                put("nextIcon", mapManeuverIcon(nextStep?.icon))
                put("distanceToNextM", route.distanceToNextM.roundToInt().coerceAtLeast(0))
                put("remainingM", route.remainingDistanceM.roundToInt().coerceAtLeast(0))
                put("remainingS", route.remainingDurationS.roundToInt().coerceAtLeast(0))
                put("etaEpochMs", etaEpochMs)
                put("arrived", route.arrived)
            })
        }

        val lastUserMsg = ui.agentState.messages.lastOrNull { it.isUser }
        val lastBotMsg = ui.agentState.messages.lastOrNull { !it.isUser }
        val inVoiceSession = ui.agentState.copilotMode == "live" || ui.agentState.copilotMode == "ask"
        val resolvedUserText = if (inVoiceSession) ui.agentState.userText else ui.agentState.userText.ifBlank { lastUserMsg?.text ?: "" }
        val resolvedReplyText = if (inVoiceSession) ui.agentState.replyText else ui.agentState.replyText.ifBlank { lastBotMsg?.text ?: "" }
        val copilotObj = JSONObject().apply {
            put("listening", ui.agentState.isListening)
            put("thinking", ui.agentState.isThinking)
            put("speaking", ui.agentState.isSpeaking)
            put("online", ui.agentState.isOnlineAvailable)
            put("model", ui.config.geminiModel)
            put("liveModel", ui.config.geminiLiveModel)
            put("mode", ui.agentState.copilotMode)
            put("phase", ui.agentState.livePhase)
            put("level", round2(ui.agentState.audioLevel.toDouble().coerceIn(0.0, 1.0)))
            put("userText", resolvedUserText)
            put("replyText", resolvedReplyText)
            put("status", ui.agentState.statusText)
            put("lastUser", lastUserMsg?.text ?: "")
            put("lastReply", lastBotMsg?.text ?: "")
            put("lastAction", lastBotMsg?.actionBadge ?: "")
        }

        val audioObj = JSONObject().apply {
            put("volumePct", ui.masterVolumePct.coerceIn(0, 100))
            put("muted", ui.isAudioMuted)
            put("route", ui.audioRoute.routeLabel)
        }

        val unreadPhoneMsg = phone.messages.firstOrNull { !it.isRead }
        val unreadNotif = ui.whatsAppMessages.firstOrNull { !it.isReadByAgent }
        val notifValue: Any = when {
            unreadPhoneMsg != null -> JSONObject().apply {
                put("id", unreadPhoneMsg.id)
                put("app", unreadPhoneMsg.appLabel)
                put("packageName", unreadPhoneMsg.packageName)
                put("title", unreadPhoneMsg.sender)
                put("text", unreadPhoneMsg.text)
                put("source", "phone")
                put("canReply", unreadPhoneMsg.canReply)
            }
            unreadNotif != null -> JSONObject().apply {
                put("id", unreadNotif.key)
                put("app", unreadNotif.appLabel)
                put("packageName", unreadNotif.packageName)
                put("title", unreadNotif.sender)
                put("text", unreadNotif.message)
                put("source", "tablet")
                put("canReply", unreadNotif.canReply)
            }
            else -> JSONObject.NULL
        }

        val phoneObj = JSONObject().apply {
            put("connected", phone.isConnected)
            put("state", phone.connectionState.name)
            put("paired", phone.paired)
            put("phoneName", phone.phoneName)
            put("host", phone.host)
            put("port", phone.port)
            put("locked", phone.phoneLocked)
            put("accessibilityEnabled", phone.accessibilityEnabled)
            put("notificationsEnabled", phone.notificationsEnabled)
            put("statusMessage", phone.statusMessage)
            put("lastError", phone.lastError ?: JSONObject.NULL)
            put("recordingVoiceNote", phone.recordingVoiceNote)
            put("voiceNoteStatus", phone.voiceNoteStatus)
            put("contactsCount", phone.contactsCount)
            put("contactsSyncedAtMs", phone.contactsSyncedAtMs)
            put("contactsSyncing", phone.contactsSyncing)
            put("contactsSyncError", phone.contactsSyncError ?: JSONObject.NULL)
            put("iconsVersion", phone.iconsVersion)
            put("iconPackages", JSONArray(phone.iconPackages.sorted()))
            put("roles", JSONObject().apply {
                phone.appRoles.forEach { (k, v) -> put(k, v) }
            })
            put("discovered", JSONArray().apply {
                phone.discoveredPhones.forEach { d ->
                    put(JSONObject().apply {
                        put("name", d.name)
                        put("host", d.host)
                        put("port", d.port)
                    })
                }
            })
            put("call", JSONObject().apply {
                put("status", phone.callState.status.name)
                put("number", phone.callState.number)
                put("displayName", phone.callState.displayName)
                put("isWhatsApp", phone.callState.isWhatsApp)
                put("startedAtMs", phone.callState.startedAtMs)
            })
            put("messages", JSONArray().apply {
                phone.messages.take(10).forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("app", m.appLabel)
                        put("packageName", m.packageName)
                        put("sender", m.sender)
                        put("text", m.text)
                        put("timestampMs", m.timestampMs)
                        put("canReply", m.canReply)
                        put("read", m.isRead)
                    })
                }
            })
        }

        val fuelSummary = ui.fuelSummary
        val vehicleObj = JSONObject().apply {
            put("obdMessage", ui.obdMessage)
            put("simulator", ui.config.useSimulatedObd)
            put("dtcCodes", JSONArray().apply {
                ui.headUnit.dtcCodes.forEach { dtc ->
                    put(JSONObject().apply {
                        put("code", dtc.code)
                        put("desc", dtc.descriptionPt)
                    })
                }
            })
            put("dtcBusy", ui.dtcBusy)
            put("tankLiters", round1(safeNum(derived.virtualTankRemainingLiters)))
            put("tankPct", round1(safeNum(derived.virtualTankPercent)))
            put("pids", buildPidsJson(snap, obdStatusStr != "DISCONNECTED"))
            put("fuelLog", JSONObject().apply {
                put("entries", JSONArray().apply {
                    ui.fuelEntries.forEach { e ->
                        put(JSONObject().apply {
                            put("id", e.timestampMs.toString())
                            put("date", formatIsoDate(e.timestampMs))
                            put("liters", round2(safeNum(e.liters)))
                            put("price", round2(safeNum(e.pricePerLiter)))
                            put("odometer", round1(safeNum(e.odometerKm)))
                            put("full", e.fullTank)
                        })
                    }
                })
                put("summary", JSONObject().apply {
                    put("lastKmL", round1(safeNum(fuelSummary.lastKmPerL)))
                    put("avgKmL", round1(safeNum(fuelSummary.averageKmPerL)))
                    put("avgPrice", round2(safeNum(fuelSummary.averagePricePerLiter)))
                    put("spent30d", round2(safeNum(fuelSummary.spentLast30Days)))
                    put("costPerKm", round2(safeNum(fuelSummary.costPerKm)))
                })
            })
            put("maintenance", JSONObject().apply {
                put("items", JSONArray())
            })
        }

        val settingsObj = JSONObject().apply {
            put("accentOptions", JSONArray(ACCENT_OPTIONS))
            put("introMode", ui.config.introMode)
            put("mapTheme", ui.config.mapTheme)
            put("spotifyShowPlaylists", ui.config.spotifyShowPlaylists)
            put("spotifyShowRecent", ui.config.spotifyShowRecent)
            put("geminiKeySet", ui.config.geminiApiKey.isNotBlank())
            put("geminiModel", ui.config.geminiModel)
            put("geminiLiveModel", ui.config.geminiLiveModel)
            put("wakeWord", ui.config.wakeWordPhrase)
            put("wakeWordEnabled", ui.config.wakeWordEnabled)
            put("obdDevice", ui.config.obdDeviceMac.orEmpty())
            put("pairedDevices", JSONArray().apply {
                ui.pairedBluetoothDevices.forEach { d ->
                    put(JSONObject().apply {
                        put("name", d.name)
                        put("address", d.macAddress)
                    })
                }
            })
            put("ignitionDevice", ui.config.ignitionBtMac)
            put("preferredMapPackage", ui.config.preferredMapPackage)
            put("preferredMusicPackage", ui.config.preferredMusicPackage)
            put("cameraStandardMode", ui.config.cameraStandardMode.name)
            put("cameraDetectedStandard", ui.cameraHardware.detectedStandard.name)
            put("cameraDetectedLabel", ui.cameraHardware.detectedStandard.label)
            put("cameraStatusLabel", ui.cameraHardware.connectedDeviceLabel)
            put("cameraBrightness", ui.config.cameraBrightness.coerceIn(0, 100))
            put("cameraContrast", ui.config.cameraContrast.coerceIn(0, 100))
            put("cameraSaturation", ui.config.cameraSaturation.coerceIn(0, 100))
            put("version", extras.appVersion)
        }

        val appsObj = JSONObject().apply {
            put("preferredMapPackage", ui.config.preferredMapPackage)
            put("preferredMusicPackage", ui.config.preferredMusicPackage)
            put("tablet", JSONArray().apply {
                ui.installedTabletApps.forEach { app ->
                    put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                    })
                }
            })
            put("mapApps", JSONArray().apply {
                ui.installedMapApps.forEach { app ->
                    put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                    })
                }
            })
            put("musicApps", JSONArray().apply {
                ui.installedMusicApps.forEach { app ->
                    put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                    })
                }
            })
        }

        val currentYear = Instant.ofEpochMilli(extras.nowEpochMs).atZone(ZoneId.systemDefault()).year
        val calendarObj = JSONObject().apply {
            put("permissionGranted", ui.calendar.permissionGranted)
            put("phoneEventsCount", ui.calendar.phoneEventsCount)
            put("phoneCalendarAvailable", ui.calendar.phoneCalendarAvailable)
            put("updatedAtMs", ui.calendar.updatedAtMs)
            put("statusText", ui.calendar.statusText)
            put("events", JSONArray().apply {
                ui.calendar.events.forEach { ev ->
                    put(JSONObject().apply {
                        put("id", ev.id)
                        put("title", ev.title)
                        put("startMs", ev.startMs)
                        put("endMs", ev.endMs)
                        put("allDay", ev.allDay)
                        put("location", ev.location)
                        put("color", ev.colorHex)
                        put("dateIso", ev.dateIso)
                        put("endDateIso", ev.endDateIso)
                        put("source", ev.source)
                    })
                }
            })
            put("holidays", JSONArray().apply {
                BrazilHolidays.holidaysForYears((currentYear - 1)..(currentYear + 1)).forEach { h ->
                    put(JSONObject().apply {
                        put("dateIso", h.dateIso)
                        put("name", h.name)
                        put("movable", h.movable)
                    })
                }
            })
        }

        return JSONObject().apply {
            put("v", 1)
            put("accent", accent)
            put("brand", "HONDA")
            put("clock", JSONObject().put("epochMs", extras.nowEpochMs))
            put("weather", weatherObj)
            put("calendar", calendarObj)
            put("status", statusObj)
            put("telemetry", telemetryObj)
            put("media", mediaObj)
            put("spotify", spotifyObj)
            put("map", mapObj)
            put("copilot", copilotObj)
            put("audio", audioObj)
            put("notification", notifValue)
            put("phone", phoneObj)
            put("apps", appsObj)
            put("vehicle", vehicleObj)
            put("settings", settingsObj)
        }
    }

    /** Payload enxuto (~10/s) para animar velocímetro e fundo sem reenviar o estado completo. */
    fun buildTelemetryJson(ui: HeadUnitUiState): JSONObject {
        val snap = ui.headUnit.obdSnapshot
        val derived = snap.derived
        val speedKmh = resolveSpeedKmh(ui)
        val rpm = safeNum(snap.rpm).coerceAtLeast(0.0)
        val gear = normalizeGear(derived.estimatedGearLabel)
        val fuelPct = safeNum(snap.fuelLevelPct ?: derived.virtualTankPercent).coerceIn(0.0, 100.0)
        return JSONObject().apply {
            put("speedKmh", round1(speedKmh))
            put("rpm", rpm.roundToInt())
            put("gear", gear)
            put("fuelPct", round1(fuelPct))
            if (ui.agentState.copilotMode != "idle") {
                put("copilotLevel", round2(ui.agentState.audioLevel.toDouble().coerceIn(0.0, 1.0)))
            }
        }
    }

    fun buildEventJson(name: String, text: String = "", mode: String = ""): JSONObject =
        JSONObject().apply {
            put("name", name)
            if (text.isNotBlank()) put("text", text)
            if (mode.isNotBlank()) put("mode", mode)
        }

    fun wrapMessage(type: String, payload: JSONObject): String =
        JSONObject().apply {
            put("type", type)
            put("payload", payload)
        }.toString()

    internal fun normalizeGear(raw: String?): String {
        val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty().removeSuffix("ª")
        return when {
            cleaned in setOf("N", "1", "2", "3", "4", "5", "R") -> cleaned
            else -> "N"
        }
    }

    internal fun mapManeuverIcon(icon: ManeuverIcon?): String = when (icon) {
        ManeuverIcon.LEFT, ManeuverIcon.SHARP_LEFT -> "LEFT"
        ManeuverIcon.RIGHT, ManeuverIcon.SHARP_RIGHT -> "RIGHT"
        ManeuverIcon.SLIGHT_LEFT -> "SLIGHT_LEFT"
        ManeuverIcon.SLIGHT_RIGHT -> "SLIGHT_RIGHT"
        ManeuverIcon.UTURN -> "UTURN"
        ManeuverIcon.ARRIVE -> "ARRIVE"
        ManeuverIcon.ROUNDABOUT -> "ROUNDABOUT"
        ManeuverIcon.STRAIGHT, null -> "STRAIGHT"
    }

    private fun resolveSpeedKmh(ui: HeadUnitUiState): Double {
        val snap = ui.headUnit.obdSnapshot
        val obdSpeed = snap.derived.correctedSpeedKmh ?: snap.speedKmh
        val fallbackGps = if (ui.gpsState.hasFix) ui.gpsState.gpsSpeedKmh.toDouble() else 0.0
        return safeNum(obdSpeed ?: fallbackGps).coerceAtLeast(0.0)
    }

    private fun spotifyListToJson(items: List<SpotifyItem>): JSONArray = JSONArray().apply {
        items.forEach { put(spotifyItemToJson(it, includeHondaFlag = false)) }
    }

    private fun spotifyItemToJson(item: SpotifyItem, includeHondaFlag: Boolean): JSONObject =
        JSONObject().apply {
            put("kind", item.kind.name)
            put("name", item.name)
            put("subtitle", item.subtitle)
            put("uri", item.uri)
            put("imageUrl", item.imageUrl.orEmpty())
            if (includeHondaFlag) {
                put("honda", item.name.trimStart().startsWith("honda", ignoreCase = true))
            }
        }

    private fun buildPidsJson(snap: ObdSnapshot, obdActive: Boolean): JSONArray {
        val arr = JSONArray()
        if (!obdActive && snap.rpm == null && snap.coolantTempC == null && snap.batteryVoltageV == null) {
            return arr
        }
        fun addPid(label: String, value: String, unit: String) {
            arr.put(JSONObject().apply {
                put("label", label)
                put("value", value)
                put("unit", unit)
            })
        }
        addPid("Rotação", snap.rpm?.roundToInt()?.toString() ?: "--", "RPM")
        addPid("Velocidade", (snap.derived.correctedSpeedKmh ?: snap.speedKmh)?.roundToInt()?.toString() ?: "--", "km/h")
        addPid("Temp. Motor", snap.coolantTempC?.roundToInt()?.toString() ?: "--", "°C")
        addPid("Bateria", snap.batteryVoltageV?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "V")
        addPid("Carga Motor", snap.engineLoadPct?.roundToInt()?.toString() ?: "--", "%")
        addPid("Borboleta", snap.throttlePct?.roundToInt()?.toString() ?: "--", "%")
        addPid("MAP", snap.intakeMapKpa?.roundToInt()?.toString() ?: "--", "kPa")
        addPid("Admissão", snap.intakeTempC?.roundToInt()?.toString() ?: "--", "°C")
        addPid("Avanço", snap.timingAdvanceDeg?.roundToInt()?.toString() ?: "--", "°")
        addPid("STFT", snap.stftPct?.let { String.format(Locale.US, "%+.1f", it) } ?: "--", "%")
        addPid("LTFT", snap.ltftPct?.let { String.format(Locale.US, "%+.1f", it) } ?: "--", "%")
        addPid("Consumo Méd.", snap.averageKmPerL?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "km/L")
        return arr
    }

    private fun formatIsoDate(epochMs: Long): String = runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrDefault("")

    private fun safeNum(v: Double?, default: Double = 0.0): Double =
        if (v != null && v.isFinite()) v else default

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
}
