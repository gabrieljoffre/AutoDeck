package com.autodeck.headunit.web

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Roteador local de recursos da WebView (mesma origem `https://appassets.androidplatform.net`):
 * - `/cover/current.jpg`: capa atual da música em JPEG (404 se não houver).
 * - `/tiles/{z}/{y}/{x}.png`: mapa base Esri Dark Gray com cache em disco (`filesDir/tiles`, LRU ~300 MB).
 * - `/labels/{z}/{y}/{x}.png`: camada de nomes Esri Dark Gray com cache em disco.
 * - `/tiles/light/{z}/{y}/{x}.png`: mapa claro Esri World Street Map (visual próximo ao Google Maps) com cache em disco.
 * - `/appicon/{pacote}.png`: ícone real de app instalado no tablet (192 px PNG com máscara adaptativa), cache em disco, 404 se não instalado.
 * - `/phoneicon/{pacote}.png`: ícone real de app enviado pelo celular (HondaDeck Ponte), salvo em `filesDir/phoneicons`, 404 se ausente.
 */
class WebResourceRouter(
    context: Context,
    private val coverProvider: () -> Bitmap?
) {
    private val appContext = context.applicationContext
    private val tilesRootDir = File(appContext.filesDir, "tiles").apply { mkdirs() }
    private val appIconsRootDir = File(appContext.filesDir, "appicons").apply { mkdirs() }
    private val phoneIconsRootDir = phoneIconsDir(appContext)
    private val writeCounter = AtomicInteger(0)

    @Volatile
    private var cachedCoverBitmapRef: Bitmap? = null

    @Volatile
    private var cachedCoverJpeg: ByteArray? = null

    val coverHandler = WebViewAssetLoader.PathHandler { path ->
        handleCover(path)
    }

    val tilesHandler = WebViewAssetLoader.PathHandler { path ->
        handleTile(layer = LAYER_BASE, path = path)
    }

    val lightTilesHandler = WebViewAssetLoader.PathHandler { path ->
        handleTile(layer = LAYER_LIGHT, path = path)
    }

    val labelsHandler = WebViewAssetLoader.PathHandler { path ->
        handleTile(layer = LAYER_LABELS, path = path)
    }

    val appIconHandler = WebViewAssetLoader.PathHandler { path ->
        handleTabletAppIcon(path)
    }

    val phoneIconHandler = WebViewAssetLoader.PathHandler { path ->
        handlePhoneAppIcon(path)
    }

    private fun handleCover(path: String): WebResourceResponse {
        val cleanPath = path.trimStart('/').substringBefore('?')
        if (cleanPath != "current.jpg") {
            return notFound()
        }
        val bmp = coverProvider() ?: return notFound()
        val bytes = synchronized(this) {
            if (cachedCoverBitmapRef === bmp && cachedCoverJpeg != null) {
                cachedCoverJpeg!!
            } else {
                val baos = ByteArrayOutputStream()
                val ok = runCatching { bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos) }.getOrDefault(false)
                if (!ok) return notFound()
                val encoded = baos.toByteArray()
                cachedCoverBitmapRef = bmp
                cachedCoverJpeg = encoded
                encoded
            }
        }
        return WebResourceResponse(
            "image/jpeg",
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "no-cache"),
            ByteArrayInputStream(bytes)
        )
    }

    private fun handleTile(layer: String, path: String): WebResourceResponse {
        val coords = parseTilePath(path) ?: return notFound()
        val (z, y, x) = coords
        val file = tileFile(layer, z, y, x)

        // 1) Cache em disco (serve também offline)
        if (file.isFile && file.length() > 0L) {
            val bytes = runCatching {
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return tileResponse(bytes)
            }
        }

        // 2) Download da Esri e gravação no cache
        val bytes = fetchAndCacheTile(layer, z, y, x) ?: return notFound()
        return tileResponse(bytes)
    }

    private fun tileResponse(bytes: ByteArray): WebResourceResponse {
        val mime = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "image/jpeg"
        } else {
            "image/png"
        }
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "public, max-age=604800"),
            ByteArrayInputStream(bytes)
        )
    }

    private fun tileFile(layer: String, z: Int, y: Int, x: Int): File =
        File(File(File(File(tilesRootDir, layer), z.toString()), y.toString()), "$x.png")

    private fun fetchAndCacheTile(layer: String, z: Int, y: Int, x: Int): ByteArray? {
        val urlStr = tileUrlForLayer(layer, z, y, x)
        val bytes = runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return null

        val target = tileFile(layer, z, y, x)
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp.${System.nanoTime()}")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            if (writeCounter.incrementAndGet() % TRIM_CHECK_INTERVAL == 0) {
                trimCacheIfNeeded()
            }
        }
        return bytes
    }

    /**
     * Baixa previamente os tiles base + labels de uma região circular para uso offline.
     * Pula tiles já presentes no cache em disco.
     */
    suspend fun downloadOfflineRegion(
        lat: Double,
        lon: Double,
        radiusKm: Double = 12.0,
        minZoom: Int = 11,
        maxZoom: Int = 16,
        onProgress: (done: Int, total: Int, status: String) -> Unit = { _, _, _ -> }
    ): OfflineDownloadResult = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<TileTask>()
        val zMin = minZoom.coerceIn(0, 16)
        val zMax = maxZoom.coerceIn(zMin, 16)
        for (z in zMin..zMax) {
            val range = tileBoundsForRadius(lat, lon, radiusKm, z)
            for (y in range.minY..range.maxY) {
                for (x in range.minX..range.maxX) {
                    tasks += TileTask(LAYER_BASE, z, y, x)
                    tasks += TileTask(LAYER_LABELS, z, y, x)
                    tasks += TileTask(LAYER_LIGHT, z, y, x)
                }
            }
        }
        val total = tasks.size
        if (total == 0) {
            return@withContext OfflineDownloadResult(0, 0, 0)
        }
        onProgress(0, total, "Baixando mapa offline (~${radiusKm.toInt()} km)...")

        val done = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(4)

        coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    semaphore.withPermit {
                        val file = tileFile(task.layer, task.z, task.y, task.x)
                        if (file.isFile && file.length() > 0L) {
                            file.setLastModified(System.currentTimeMillis())
                        } else {
                            val ok = fetchAndCacheTile(task.layer, task.z, task.y, task.x) != null
                            if (ok) downloaded.incrementAndGet() else failed.incrementAndGet()
                        }
                        val cur = done.incrementAndGet()
                        if (cur == total || cur % 40 == 0) {
                            val pct = (cur * 100) / total
                            onProgress(cur, total, "Baixando mapa offline ($pct%)...")
                        }
                    }
                }
            }.awaitAll()
        }

        trimCacheIfNeeded()
        OfflineDownloadResult(
            totalTiles = total,
            downloadedTiles = downloaded.get(),
            failedTiles = failed.get()
        )
    }

    /** Limita o cache em disco a ~300 MB removendo os tiles menos usados recentemente (LRU). */
    @Synchronized
    fun trimCacheIfNeeded(maxBytes: Long = MAX_CACHE_BYTES, targetBytes: Long = TRIM_TARGET_BYTES) {
        runCatching {
            val files = tilesRootDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".png") }
                .toList()
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxBytes) return

            val sortedOldestFirst = files.sortedBy { it.lastModified() }
            for (f in sortedOldestFirst) {
                if (totalSize <= targetBytes) break
                val len = f.length()
                if (f.delete()) {
                    totalSize -= len
                }
            }
        }.onFailure {
            Log.w(TAG, "Falha ao podar cache LRU de tiles: ${it.message}")
        }
    }

    private fun handleTabletAppIcon(path: String): WebResourceResponse {
        val pkg = parsePackageIconPath(path) ?: return notFound()
        val pm = appContext.packageManager
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.getOrNull()

        val cacheFile = File(appIconsRootDir, "$pkg.png")
        if (pkgInfo == null) {
            if (cacheFile.exists()) runCatching { cacheFile.delete() }
            return notFound()
        }

        val lastUpdateMs = pkgInfo.lastUpdateTime
        if (cacheFile.isFile && cacheFile.length() > 0L && cacheFile.lastModified() >= lastUpdateMs) {
            val cached = runCatching { cacheFile.readBytes() }.getOrNull()
            if (cached != null && cached.isNotEmpty()) {
                return iconPngResponse(cached)
            }
        }

        val drawable = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return notFound()
        val pngBytes = renderDrawableToPngBytes(drawable, APP_ICON_SIZE_PX) ?: return notFound()

        runCatching {
            appIconsRootDir.mkdirs()
            val tmp = File(appIconsRootDir, "$pkg.png.tmp.${System.nanoTime()}")
            tmp.writeBytes(pngBytes)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeBytes(pngBytes)
                tmp.delete()
            }
            cacheFile.setLastModified(maxOf(System.currentTimeMillis(), lastUpdateMs))
        }

        return iconPngResponse(pngBytes)
    }

    private fun handlePhoneAppIcon(path: String): WebResourceResponse {
        val pkg = parsePackageIconPath(path) ?: return notFound()
        val file = File(phoneIconsRootDir, "$pkg.png")
        if (!file.isFile || file.length() <= 0L) return notFound()
        val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return notFound()
        return iconPngResponse(bytes)
    }

    private fun iconPngResponse(bytes: ByteArray): WebResourceResponse = WebResourceResponse(
        "image/png",
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "public, max-age=86400"),
        ByteArrayInputStream(bytes)
    )

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    data class TileBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    data class OfflineDownloadResult(
        val totalTiles: Int,
        val downloadedTiles: Int,
        val failedTiles: Int
    )

    private data class TileTask(val layer: String, val z: Int, val y: Int, val x: Int)

    companion object {
        private const val TAG = "WebResourceRouter"
        private const val USER_AGENT = "HondaDeck/3.0 (Android)"
        const val LAYER_BASE = "base"
        const val LAYER_LABELS = "labels"
        const val LAYER_LIGHT = "light"
        private const val ESRI_BASE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/"
        private const val ESRI_LABELS_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/"
        private const val ESRI_LIGHT_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"

        fun tileUrlForLayer(layer: String, z: Int, y: Int, x: Int): String {
            val baseUrl = when (layer) {
                LAYER_LABELS -> ESRI_LABELS_URL
                LAYER_LIGHT -> ESRI_LIGHT_URL
                else -> ESRI_BASE_URL
            }
            return "$baseUrl$z/$y/$x"
        }

        private const val MAX_CACHE_BYTES = 300L * 1024L * 1024L
        private const val TRIM_TARGET_BYTES = 260L * 1024L * 1024L
        private const val TRIM_CHECK_INTERVAL = 120
        const val APP_ICON_SIZE_PX = 192

        private val TILE_PATH_REGEX = Regex("""^/?(\d{1,2})/(\d+)/(\d+)\.png$""")
        private val PACKAGE_ICON_PATH_REGEX =
            Regex("""^/?([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)\.png$""")

        fun phoneIconsDir(context: Context): File =
            File(context.applicationContext.filesDir, "phoneicons").apply { mkdirs() }

        /** Valida `{pacote}.png` (impede path traversal) e retorna o nome do pacote. */
        fun parsePackageIconPath(path: String): String? {
            val clean = path.substringBefore('?').trim()
            val m = PACKAGE_ICON_PATH_REGEX.matchEntire(clean) ?: return null
            val pkg = m.groupValues[1]
            if (pkg.length > 180 || pkg.contains("..")) return null
            return pkg
        }

        /** Renderiza um Drawable de app (incluindo AdaptiveIconDrawable com máscara do sistema) em PNG. */
        fun renderDrawableToPngBytes(drawable: Drawable, sizePx: Int = APP_ICON_SIZE_PX): ByteArray? {
            val size = sizePx.coerceIn(48, 512)
            val bitmap = runCatching { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }.getOrNull()
                ?: return null
            return try {
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
                    val save = canvas.save()
                    val mask = runCatching { drawable.iconMask }.getOrNull()
                    if (mask != null && !mask.isEmpty) {
                        canvas.clipPath(mask)
                    } else {
                        val fallbackMask = Path().apply {
                            val r = size * 0.22f
                            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW)
                        }
                        canvas.clipPath(fallbackMask)
                    }
                    drawable.draw(canvas)
                    canvas.restoreToCount(save)
                } else {
                    drawable.draw(canvas)
                }
                val baos = ByteArrayOutputStream()
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                if (ok) baos.toByteArray().takeIf { it.isNotEmpty() } else null
            } catch (_: Throwable) {
                null
            } finally {
                bitmap.recycle()
            }
        }

        /** Extrai (z, y, x) de um caminho `{z}/{y}/{x}.png`. */
        fun parseTilePath(path: String): Triple<Int, Int, Int>? {
            val clean = path.substringBefore('?')
            val m = TILE_PATH_REGEX.matchEntire(clean) ?: return null
            val z = m.groupValues[1].toIntOrNull() ?: return null
            val y = m.groupValues[2].toIntOrNull() ?: return null
            val x = m.groupValues[3].toIntOrNull() ?: return null
            if (z !in 0..19) return null
            val maxIndex = (1 shl z) - 1
            if (x !in 0..maxIndex || y !in 0..maxIndex) return null
            return Triple(z, y, x)
        }

        fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
            val n = 1 shl zoom
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return x to y
        }

        fun tileBoundsForRadius(lat: Double, lon: Double, radiusKm: Double, zoom: Int): TileBounds {
            val latDelta = radiusKm / 111.0
            val lonDelta = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            val (x1, y1) = latLonToTile(lat + latDelta, lon - lonDelta, zoom)
            val (x2, y2) = latLonToTile(lat - latDelta, lon + lonDelta, zoom)
            return TileBounds(
                minX = minOf(x1, x2),
                maxX = maxOf(x1, x2),
                minY = minOf(y1, y2),
                maxY = maxOf(y1, y2)
            )
        }
    }
}
