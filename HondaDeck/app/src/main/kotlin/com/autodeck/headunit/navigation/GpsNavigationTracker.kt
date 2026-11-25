package com.autodeck.headunit.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.abs

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
    val trailPoints: List<GeoPoint> = emptyList()
)

/**
 * Gerencia o GPS por hardware do Galaxy Tab A7 (SM-T500), configura tiles escuros CartoDark
 * com cache em disco para uso offline e dispara rotas no Google Maps / Waze.
 */
class GpsNavigationTracker(
    private val appContext: Context,
    private val scope: CoroutineScope
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
            cfg.userAgentValue = "HondaDeck/2.0 (Android SM-T500)"
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
                (prev.trailPoints + GeoPoint(loc.latitude, loc.longitude)).takeLast(60)
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

    fun downloadOfflineRegionAroundCurrentPosition(mapView: MapView, radiusKm: Double = 6.0) {
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
                offlineDownloadStatus = "Baixando mapa escuro (~${radiusKm.toInt()} km)..."
            )
        }

        scope.launch(Dispatchers.Main) {
            runCatching {
                val cm = CacheManager(mapView)
                cm.downloadAreaAsync(
                    appContext,
                    box,
                    12,
                    15,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            _state.update {
                                it.copy(
                                    offlineDownloading = false,
                                    offlineDownloadStatus = "Mapa escuro da região salvo no tablet!"
                                )
                            }
                        }

                        override fun onTaskFailed(errors: Int) {
                            _state.update {
                                it.copy(
                                    offlineDownloading = false,
                                    offlineDownloadStatus = if (errors > 0) {
                                        "Cache parcial ($errors blocos pendentes)."
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

    companion object {
        /**
         * Mapa base escuro da Esri (sem chave). O Carto dark_all passou a exigir API key
         * e devolvia tiles "API KEY REQUIRED". A Esri usa a ordem {z}/{y}/{x}.
         */
        val CARTO_DARK_TILE_SOURCE: OnlineTileSourceBase = esriSource(
            "EsriDarkGrayBase",
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/",
            maxZoom = 16
        )

        /** Camada de nomes de ruas/bairros, desenhada por cima do mapa base. */
        val ESRI_DARK_LABELS_SOURCE: OnlineTileSourceBase = esriSource(
            "EsriDarkGrayReference",
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/",
            maxZoom = 16
        )

        private fun esriSource(name: String, base: String, maxZoom: Int): OnlineTileSourceBase =
            object : OnlineTileSourceBase(name, 0, maxZoom, 256, "", arrayOf(base), "Esri, HERE, Garmin, © OpenStreetMap") {
                override fun getTileURLString(pMapTileIndex: Long): String =
                    baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
            }

        /** Tinge o cinza da Esri para o azul-marinho do painel HUD. */
        fun hudTintFilter(): android.graphics.ColorMatrixColorFilter =
            android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    0.30f, 0f, 0f, 0f, 0f,
                    0f, 0.44f, 0f, 0f, 0f,
                    0f, 0f, 0.66f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

        fun createNavigationArrowDrawable(context: Context): BitmapDrawable {
            val sizePx = (58 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val bmp = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = sizePx / 2f
            val cy = sizePx / 2f

            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(72, 41, 231, 255)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, sizePx * 0.44f, haloPaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(215, 41, 231, 255)
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.045f
            }
            canvas.drawCircle(cx, cy, sizePx * 0.44f, ringPaint)

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(41, 231, 255)
                style = Paint.Style.FILL
            }
            val arrow = Path().apply {
                moveTo(cx, cy - sizePx * 0.27f)
                lineTo(cx + sizePx * 0.20f, cy + sizePx * 0.20f)
                lineTo(cx, cy + sizePx * 0.09f)
                lineTo(cx - sizePx * 0.20f, cy + sizePx * 0.20f)
                close()
            }
            canvas.drawPath(arrow, arrowPaint)
            return bmp.toDrawable(context.resources)
        }
    }
}
