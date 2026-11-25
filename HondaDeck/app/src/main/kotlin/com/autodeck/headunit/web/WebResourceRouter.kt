package com.autodeck.headunit.web

import android.content.Context
import android.graphics.Bitmap
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
 */
class WebResourceRouter(
    context: Context,
    private val coverProvider: () -> Bitmap?
) {
    private val tilesRootDir = File(context.applicationContext.filesDir, "tiles").apply { mkdirs() }
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

    val labelsHandler = WebViewAssetLoader.PathHandler { path ->
        handleTile(layer = LAYER_LABELS, path = path)
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
        val baseUrl = if (layer == LAYER_LABELS) ESRI_LABELS_URL else ESRI_BASE_URL
        val urlStr = "$baseUrl$z/$y/$x"
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
        private const val LAYER_BASE = "base"
        private const val LAYER_LABELS = "labels"
        private const val ESRI_BASE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/"
        private const val ESRI_LABELS_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/"

        private const val MAX_CACHE_BYTES = 300L * 1024L * 1024L
        private const val TRIM_TARGET_BYTES = 260L * 1024L * 1024L
        private const val TRIM_CHECK_INTERVAL = 120

        private val TILE_PATH_REGEX = Regex("""^/?(\d{1,2})/(\d+)/(\d+)\.png$""")

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
