package com.autodeck.headunit.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PmtilesArchiveTest {

    @Test
    fun `ids da curva de Hilbert iguais aos do go-pmtiles`() {
        assertEquals(0L, PmtilesArchive.zxyToTileId(0, 0, 0))
        assertEquals(1L, PmtilesArchive.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmtilesArchive.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmtilesArchive.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmtilesArchive.zxyToTileId(1, 1, 0))
        assertEquals(5L, PmtilesArchive.zxyToTileId(2, 0, 0))
    }

    @Test
    fun `diretorio com offsets em sequencia e busca por run`() {
        // 2 entradas: tileId 10 (run 3, len 5, offset 0) e tileId 20 (run 0 = folha, len 7, offset seguido)
        val bytes = byteArrayOf(2, 10, 10, 3, 0, 5, 7, 1, 0)
        val dir = PmtilesArchive.parseDirectory(bytes)
        assertEquals(20L, dir[1].tileId)
        assertEquals(5L, dir[1].offset)
        assertEquals(10L, PmtilesArchive.findEntry(dir, 12)?.tileId)
        assertNull(PmtilesArchive.findEntry(dir, 13))
        assertEquals(20L, PmtilesArchive.findEntry(dir, 99)?.tileId)
        assertNull(PmtilesArchive.findEntry(dir, 3))
    }

    /** Roda só onde o arquivo real existe (notebook de desenvolvimento). */
    @Test
    fun `arquivo real de Goias e DF tem ruas de Anapolis`() {
        val file = listOf(File("../../../_mapas/go-df.pmtiles"), File("../../_mapas/go-df.pmtiles"))
            .firstOrNull { it.isFile }
        assumeTrue("go-df.pmtiles ausente", file != null)
        PmtilesArchive(file!!).use { a ->
            assertEquals(15, a.header.maxZoom)
            assertTrue(a.header.minLon < -48.95 && a.header.maxLon > -48.95)
            // Centro de Anápolis no zoom 14
            val tile = a.getTile(14, 5964, 8945)
            assertNotNull(tile)
            assertTrue(tile!!.size > 1000)
            assertTrue(String(tile, Charsets.ISO_8859_1).contains("roads"))
            assertNull(a.getTile(14, 0, 0))
        }
    }
}
