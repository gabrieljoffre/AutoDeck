package com.autodeck.headunit.web

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

/**
 * Leitor mínimo de arquivo PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md),
 * usado pelo mapa offline de Goiás + DF (`go-df.pmtiles`, recorte do Protomaps Basemap).
 *
 * Só leitura, sem dependências: cabeçalho de 127 bytes, diretórios (gzip) e tiles (gzip).
 * Seguro para várias threads (a WebView pede tiles em paralelo): leituras posicionais no FileChannel.
 */
class PmtilesArchive(file: File) : Closeable {

    data class Header(
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val leafDirsOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double
    )

    internal data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    val header: Header
    private val rootDir: List<Entry>
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 64
    }

    init {
        val h = read(0, HEADER_SIZE)
        val magic = String(h, 0, 7, Charsets.US_ASCII)
        require(magic == "PMTiles" && h[7].toInt() == 3) { "Arquivo não é PMTiles v3" }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        header = Header(
            rootDirOffset = bb.getLong(8),
            rootDirLength = bb.getLong(16),
            leafDirsOffset = bb.getLong(40),
            tileDataOffset = bb.getLong(56),
            internalCompression = h[97].toInt() and 0xFF,
            tileCompression = h[98].toInt() and 0xFF,
            tileType = h[99].toInt() and 0xFF,
            minZoom = h[100].toInt() and 0xFF,
            maxZoom = h[101].toInt() and 0xFF,
            minLon = bb.getInt(102) / 1e7,
            minLat = bb.getInt(106) / 1e7,
            maxLon = bb.getInt(110) / 1e7,
            maxLat = bb.getInt(114) / 1e7
        )
        rootDir = readDirectory(header.rootDirOffset, header.rootDirLength.toInt())
    }

    /** Bytes do tile (já descompactado), ou null se não existe no arquivo. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val tileId = zxyToTileId(z, x, y)
        var dir = rootDir
        repeat(4) { // a especificação garante no máximo 3 níveis de diretório
            val e = findEntry(dir, tileId) ?: return null
            if (e.runLength > 0) {
                val raw = read(header.tileDataOffset + e.offset, e.length)
                return decompress(raw, header.tileCompression)
            }
            val leafOffset = header.leafDirsOffset + e.offset
            dir = synchronized(leafCache) { leafCache[leafOffset] }
                ?: readDirectory(leafOffset, e.length).also { synchronized(leafCache) { leafCache[leafOffset] = it } }
        }
        return null
    }

    private fun readDirectory(offset: Long, length: Int): List<Entry> =
        parseDirectory(decompress(read(offset, length), header.internalCompression))

    private fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) error("Fim inesperado do arquivo PMTiles")
            pos += n
        }
        return buf.array()
    }

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        private const val HEADER_SIZE = 127
        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_GZIP = 2

        /** Id do tile na curva de Hilbert (igual ao ZxyToID do go-pmtiles). */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..31) { "zoom inválido: $z" }
            var acc = ((1L shl (z * 2)) - 1) / 3
            var xx = x.toLong()
            var yy = y.toLong()
            var s = if (z == 0) 0L else 1L shl (z - 1)
            while (s > 0) {
                val rx = if ((xx and s) != 0L) 1L else 0L
                val ry = if ((yy and s) != 0L) 1L else 0L
                acc += s * s * ((3L * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        xx = s - 1 - (xx and (s - 1))
                        yy = s - 1 - (yy and (s - 1))
                    }
                    val t = xx
                    xx = yy
                    yy = t
                }
                s = s shr 1
            }
            return acc
        }

        internal fun parseDirectory(bytes: ByteArray): List<Entry> {
            val r = VarintReader(bytes)
            val n = r.next().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) {
                last += r.next()
                ids[i] = last
            }
            val runs = IntArray(n) { r.next().toInt() }
            val lengths = IntArray(n) { r.next().toInt() }
            val offsets = LongArray(n)
            for (i in 0 until n) {
                val v = r.next()
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }

        /** Maior entrada com tileId <= procurado; vale se for diretório-folha ou se o tile está no "run". */
        internal fun findEntry(entries: List<Entry>, tileId: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId.compareTo(tileId)
                when {
                    c < 0 -> lo = mid + 1
                    c > 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0) return e
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }

        private fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            COMPRESSION_NONE, 0 -> bytes
            else -> error("Compressão PMTiles não suportada: $compression")
        }
    }

    private class VarintReader(private val data: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
