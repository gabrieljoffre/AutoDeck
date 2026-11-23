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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File

data class GpsLocationState(
    val latitude: Double = -23.55052,   // Posição inicial padrão até o primeiro fix GPS
    val longitude: Double = -46.63331,
    val bearingDeg: Float = 0f,
    val gpsSpeedKmh: Float = 0f,
    val accuracyMeters: Float = 0f,
    val hasFix: Boolean = false,
    val hasPermission: Boolean = false,
    val providerLabel: String = "Aguardando GPS",
    val offlineDownloadStatus: String = "Cache offline pronto",
    val offlineDownloading: Boolean = false
)

/**
 * Gerencia o GPS por hardware do Galaxy Tab A7 (SM-T500), configura cache offline
 * de mapas (OSMDroid) e dispara rotas no Google Maps / Waze.
 */
class GpsNavigationTracker(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _state = MutableStateFlow(GpsLocationState())
    val state: StateFlow<GpsLocationState> = _state.asStateFlow()

    private var listening = false

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
        configureOsmDroid()
        refreshPermissionAndStart()
    }

    private fun configureOsmDroid() {
        runCatching {
            val cfg = Configuration.getInstance()
            val osmBase = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
            val tileCache = File(osmBase, "tiles").apply { mkdirs() }
            cfg.osmdroidBasePath = osmBase
            cfg.osmdroidTileCache = tileCache
            cfg.userAgentValue = appContext.packageName
            // Cache generoso para funcionar offline por dias
            cfg.tileFileSystemCacheMaxBytes = 512L * 1024L * 1024L
            cfg.tileFileSystemCacheTrimBytes = 400L * 1024L * 1024L
        }
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
        listening = true
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    900L,
                    1.5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    4_000L,
                    10f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private fun applyLocation(loc: Location, isCachedLastKnown: Boolean = false) {
        _state.update { prev ->
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
                }
            )
        }
    }

    fun downloadOfflineRegionAroundCurrentPosition(mapView: MapView, radiusKm: Double = 8.0) {
        if (_state.value.offlineDownloading) return
        val cur = _state.value
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(cur.latitude)).coerceAtLeast(0.2))
        val box = BoundingBox(
            cur.latitude + latDelta,
            cur.longitude + lonDelta,
            cur.latitude - latDelta,
            cur.longitude - lonDelta
        )

        _state.update {
            it.copy(
                offlineDownloading = true,
                offlineDownloadStatus = "Baixando mapa offline (~${radiusKm.toInt()} km)..."
            )
        }

        scope.launch(Dispatchers.Main) {
            runCatching {
                val cm = CacheManager(mapView)
                cm.downloadAreaAsync(
                    appContext,
                    box,
                    12,
                    16,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            _state.update {
                                it.copy(
                                    offlineDownloading = false,
                                    offlineDownloadStatus = "Mapa offline da região salvo no tablet!"
                                )
                            }
                        }

                        override fun onTaskFailed(errors: Int) {
                            _state.update {
                                it.copy(
                                    offlineDownloading = false,
                                    offlineDownloadStatus = if (errors > 0) {
                                        "Cache concluído com $errors blocos pendentes (verifique internet)."
                                    } else {
                                        "Mapa offline salvo."
                                    }
                                )
                            }
                        }

                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                            _state.update {
                                it.copy(
                                    offlineDownloadStatus = "Baixando zoom $currentZoomLevel/$zoomMax ($progress)..."
                                )
                            }
                        }

                        override fun downloadStarted() = Unit
                        override fun setPossibleTilesInArea(total: Int) = Unit
                    }
                )
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        offlineDownloading = false,
                        offlineDownloadStatus = "Falha ao baixar cache: ${err.message}"
                    )
                }
            }
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
            "com.waze" -> Uri.parse("waze://?q=$encoded&navigate=yes")
            else -> Uri.parse("google.navigation:q=$encoded")
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
                Uri.parse("geo:0,0?q=$encoded")
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
