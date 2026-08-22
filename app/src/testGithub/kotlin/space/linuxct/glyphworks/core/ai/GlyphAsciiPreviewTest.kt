package space.linuxct.glyphworks.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

class GlyphAsciiPreviewTest {

    @Test
    fun `a fully lit frame renders exactly the live cells at each geometry`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val lit = IntArray(size * size) { DesignFrames.MAX_BRIGHTNESS }

            val drawn = GlyphAsciiPreview.render(lit, size)
            val cells = drawn.filter { it != '\n' }

            assertEquals("every cell has a character", size * size, cells.length)
            assertEquals(
                "${codename.codename} draws its LEDs",
                PanelMask.count(size),
                cells.count { it != GlyphAsciiPreview.OFF_PANEL },
            )
        }
    }

    @Test
    fun `every masked cell is blank however bright it was drawn`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val lit = IntArray(size * size) { DesignFrames.MAX_BRIGHTNESS }
            val rows = GlyphAsciiPreview.render(lit, size).split("\n")

            assertEquals(size, rows.size)
            for (y in 0 until size) {
                assertEquals(size, rows[y].length)
                for (x in 0 until size) {
                    val onPanel = PanelMask.contains(x, y, size)
                    val char = rows[y][x]
                    if (onPanel) {
                        assertTrue(
                            "($x,$y) at $size is an LED and must be drawn, got '$char'",
                            char != GlyphAsciiPreview.OFF_PANEL,
                        )
                    } else {
                        assertEquals(
                            "($x,$y) at $size is off panel and must be blank",
                            GlyphAsciiPreview.OFF_PANEL,
                            char,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the panel map draws a hash for every LED and nothing else`() {
        for (codename in PokemonCodename.entries) {
            val map = GlyphAsciiPreview.panelMap(codename.size).filter { it != '\n' }

            assertEquals(codename.cellCount, map.length)
            assertEquals(PanelMask.count(codename.size), map.count { it == '#' })
            assertEquals(
                codename.cellCount - PanelMask.count(codename.size),
                map.count { it == GlyphAsciiPreview.OFF_PANEL },
            )
        }
    }

    @Test
    fun `rendering cells matches rendering the decoded frame`() {
        val codename = PokemonCodename.BELLSPROUT
        val cells = "2".repeat(codename.cellCount)

        val fromCells = GlyphAsciiPreview.renderCells(cells, DEFAULT_LEVELS, codename)
        val decoded = DesignFrames.decode(cells, DEFAULT_LEVELS, codename.size)

        assertNotNull(decoded)
        assertEquals(GlyphAsciiPreview.render(decoded!!, codename.size), fromCells)
    }

    @Test
    fun `cells the codec would refuse are not drawn at all`() {
        val codename = PokemonCodename.BELLSPROUT
        assertNull(GlyphAsciiPreview.renderCells("0".repeat(codename.cellCount - 1), DEFAULT_LEVELS, codename))
        assertNull(GlyphAsciiPreview.renderCells("!".repeat(codename.cellCount), DEFAULT_LEVELS, codename))
        assertNull(GlyphAsciiPreview.renderCells("5".repeat(codename.cellCount), DEFAULT_LEVELS, codename))
    }

    @Test
    fun `live spans describe the same rows the drawing does`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val spans = GlyphAsciiPreview.liveSpans(size)

            assertEquals(size, spans.size)
            assertEquals(
                PanelMask.count(size),
                spans.sumOf { it?.let { r -> r.last - r.first + 1 } ?: 0 },
            )
            for (y in 0 until size) {
                val span = spans[y] ?: continue
                for (x in span) {
                    assertTrue("($x,$y) inside the span must be live", PanelMask.contains(x, y, size))
                }
            }
        }
    }
}
