package com.autodeck.headunit.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class GpsTrailPoint(
    val latitude: Double,
    val longitude: Double
)

data class GpsLocationState(
    val latitude: Double = -16.3281,
    val longitude: Double = -48.9534,
    val bearingDeg: Float = 0f,
    val gpsSpeedKmh: Float = 0f,
    val accuracyMeters: Float = 0f,
    val hasFix: Boolean = false,
    val hasPermission: Boolean = false,
    val providerLabel: String = "Aguardando GPS",
    val offlineDownloadStatus: String = "Cache offline ativo",
    val offlineDownloading: Boolean = false,
    val trailPoints: List<GpsTrailPoint> = emptyList()
)

/**
 * Gerencia o GPS por hardware do Galaxy Tab A7 (SM-T500) e dispara rotas
 * externas no Google Maps / Waze quando solicitado.
 */
class GpsNavigationTracker(
    private val appContext: Context
) {
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _state = MutableStateFlow(GpsLocationState())
    val state: StateFlow<GpsLocationState> = _state.asStateFlow()

    private var listening = false
    private var lastTrailLat: Double? = null
    private var lastTrailLon: Double? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            applyLocation(location)
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = refreshPermissionAndStart()
        override fun onProviderDisabled(provider: String) = Unit
    }

    init {
        refreshPermissionAndStart()
    }

    @SuppressLint("MissingPermission")
    fun refreshPermissionAndStart() {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val granted = fine || coarse

        val lm = locationManager
        _state.update { it.copy(hasPermission = granted) }
        if (!granted || lm == null) {
            _state.update { it.copy(providerLabel = "Permissão GPS pendente") }
            return
        }

        runCatching {
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            (lastGps ?: lastNet)?.let { applyLocation(it, isCachedLastKnown = true) }
        }

        if (listening) return
        var registeredAny = false
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    900L,
                    1.5f,
                    listener,
                    Looper.getMainLooper()
                )
                registeredAny = true
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    4_000L,
                    10f,
                    listener,
                    Looper.getMainLooper()
                )
                registeredAny = true
            }
        }
        listening = registeredAny
        if (!registeredAny) {
            _state.update { it.copy(providerLabel = "Ligue a Localização do tablet") }
        }
    }

    private fun applyLocation(loc: Location, isCachedLastKnown: Boolean = false) {
        val prevLat = lastTrailLat
        val prevLon = lastTrailLon
        val shouldAppendTrail = !isCachedLastKnown && (
            prevLat == null || prevLon == null ||
                abs(loc.latitude - prevLat) > 0.00007 ||
                abs(loc.longitude - prevLon) > 0.00007
            )
        if (shouldAppendTrail) {
            lastTrailLat = loc.latitude
            lastTrailLon = loc.longitude
        }

        _state.update { prev ->
            val nextTrail = if (shouldAppendTrail) {
                (prev.trailPoints + GpsTrailPoint(loc.latitude, loc.longitude)).takeLast(60)
            } else {
                prev.trailPoints
            }
            prev.copy(
                latitude = loc.latitude,
                longitude = loc.longitude,
                bearingDeg = if (loc.hasBearing()) loc.bearing else prev.bearingDeg,
                gpsSpeedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else prev.gpsSpeedKmh,
                accuracyMeters = loc.accuracy,
                hasFix = true,
                providerLabel = if (isCachedLastKnown) {
                    "Última posição GPS"
                } else {
                    "GPS ${loc.accuracy.toInt()}m"
                },
                trailPoints = nextTrail
            )
        }
    }

    fun updateOfflineStatus(downloading: Boolean, status: String) {
        _state.update {
            it.copy(
                offlineDownloading = downloading,
                offlineDownloadStatus = status
            )
        }
    }

    fun openPreferredMapApp(preferredPackage: String): Boolean {
        if (preferredPackage == "INTERNAL_MAP") return false
        val intent = appContext.packageManager.getLaunchIntentForPackage(preferredPackage)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return true
    }

    fun navigateToDestination(destination: String, preferredPackage: String): Boolean {
        val encoded = Uri.encode(destination.trim())
        val uri = when (preferredPackage) {
            "com.waze" -> "waze://?q=$encoded&navigate=yes".toUri()
            else -> "google.navigation:q=$encoded".toUri()
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (preferredPackage != "INTERNAL_MAP") {
                setPackage(preferredPackage)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrElse {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                "geo:0,0?q=$encoded".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(fallback)
                true
            }.getOrDefault(false)
        }
    }

    fun stop() {
        if (!listening) return
        runCatching { locationManager?.removeUpdates(listener) }
        listening = false
    }
}
