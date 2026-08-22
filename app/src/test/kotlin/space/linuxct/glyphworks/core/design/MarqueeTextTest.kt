package space.linuxct.glyphworks.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.matrix.PanelMask

class MarqueeTextTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    @Test
    fun `the band is centred and the mask agrees with where it lands`() {
        assertEquals(1, MarqueeText.scaleFor(13))
        assertEquals(2, MarqueeText.scaleFor(25))

        assertEquals(2, MarqueeText.topRow(13, 1))
        assertEquals(3, MarqueeText.topRow(25, 2))

        for (x in 1..11) {
            for (y in 2..10) {
                assertTrue("($x, $y) should be on the panel", PanelMask.contains(x, y, 13))
            }
        }
        assertEquals(false, PanelMask.contains(0, 3, 13))
        assertEquals(false, PanelMask.contains(12, 9, 13))
    }

    @Test
    fun `the traverse is the panel plus the message`() {
        assertEquals(9, MarqueeFont.stripWidth("HI"))
        assertEquals(13 + 9 - 1, MarqueeText.frameCount("HI", 13, 1, 1))
        assertEquals(13 + 9 - 1, MarqueeText.frameCount("HI", 25, 2, 2))
    }

    @Test
    fun `a frame that would arrive blank is dropped rather than shipped`() {
        val frames = frames("HI", bellsprout)

        assertEquals(21, MarqueeText.frameCount("HI", 13, 1, 1))
        assertEquals(20, frames.size)
        assertTrue(frames.first().cells.any { it != '0' })
        assertTrue(frames.last().cells.any { it != '0' })
    }

    @Test
    fun `the first and last frames of HI are exactly these cells`() {
        val frames = frames("HI", bellsprout)

        assertEquals(setOf(12 to 4, 12 to 5, 12 to 6, 12 to 7, 12 to 8), litCells(frames.first().cells, 13))
        assertEquals(
            setOf(0 to 4, 0 to 5, 0 to 6, 0 to 7, 0 to 8, 1 to 2, 1 to 10),
            litCells(frames.last().cells, 13),
        )
    }

    @Test
    fun `no lit cell is ever outside the panel mask`() {
        for (text in listOf("HI", "HELLO WORLD", "@#\$%&", "GLYPH 42!")) {
            for (codename in listOf(bellsprout, arbok)) {
                for ((i, frame) in frames(text, codename).withIndex()) {
                    for ((x, y) in litCells(frame.cells, codename.size)) {
                        assertTrue(
                            "$text frame $i lights ($x, $y), which ${codename.codename} has no LED for",
                            PanelMask.contains(x, y, codename.size),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every frame is the frame before it moved left by one step`() {
        for (codename in listOf(bellsprout, arbok)) {
            val size = codename.size
            val step = MarqueeText.defaultStep(size)
            val frames = frames("HELLO WORLD", codename)
            for (i in 0 until frames.size - 1) {
                val before = litCells(frames[i].cells, size)
                val after = litCells(frames[i + 1].cells, size)
                for ((x, y) in before) {
                    if (!PanelMask.contains(x - step, y, size)) continue
                    assertTrue("frame $i cell ($x, $y) did not move", after.contains(x - step to y))
                }
                for ((x, y) in after) {
                    if (!PanelMask.contains(x + step, y, size)) continue
                    assertTrue("frame ${i + 1} cell ($x, $y) came from nowhere", before.contains(x + step to y))
                }
            }
        }
    }

    @Test
    fun `maxPrefixLength names a prefix that fits and a longer one that does not`() {
        val phrase = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"
        for (codename in listOf(bellsprout, arbok)) {
            val size = codename.size
            val scale = MarqueeText.scaleFor(size)
            val step = MarqueeText.defaultStep(size)
            val fits = MarqueeText.maxPrefixLength(phrase, size, scale, step)

            assertTrue("$fits characters of $phrase", fits in 1 until phrase.length)
            assertTrue(MarqueeText.frameCount(phrase.take(fits), size, scale, step) <= DesignCodec.MAX_FRAMES)
            assertTrue(MarqueeText.frameCount(phrase.take(fits + 1), size, scale, step) > DesignCodec.MAX_FRAMES)
            assertTrue(frames(phrase.take(fits), codename).isNotEmpty())
        }
    }

    @Test
    fun `a phrase past the limit produces no frames at all`() {
        val phrase = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"

        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames(phrase, 13))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("", 13))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("A♥B", 13))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("HI", 13, paletteIndex = 0))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("HI", 13, scale = 2))
    }

    @Test
    fun `a generated marquee validates through the real codec`() {
        for (codename in listOf(bellsprout, arbok)) {
            val design = Design(
                id = "abc123",
                name = "Marquee",
                createdAt = "2026-08-02T12:00:00Z",
                modifiedAt = "2026-08-02T12:00:00Z",
                kind = DesignKind.DYNAMIC,
                loop = true,
                levels = DEFAULT_LEVELS,
                variants = mapOf(codename.codename to DesignVariant(frames("HELLO WORLD", codename))),
            )

            val result = DesignCodec.validate(design)
            assertTrue("${codename.codename}: $result", result is DesignCodec.Result.Ok)

            val decoded = DesignCodec.decode(DesignCodec.encode(design))
            assertTrue(decoded is DesignCodec.Result.Ok)
            assertEquals(
                design.variantFor(codename)!!.frames,
                (decoded as DesignCodec.Result.Ok).design.variantFor(codename)!!.frames,
            )
        }
    }

    @Test
    fun `every frame is the right length and every duration is legal`() {
        for (codename in listOf(bellsprout, arbok)) {
            val frames = frames("HELLO WORLD", codename)
            assertNotEquals(0, frames.size)
            for (frame in frames) {
                assertEquals(codename.cellCount, frame.cells.length)
                assertEquals(MarqueeText.DEFAULT_DURATION_MS, frame.durationMs)
                assertTrue(frame.durationMs >= DesignCodec.MIN_DURATION_MS)
                assertTrue(frame.durationMs <= DesignCodec.MAX_DURATION_MS)
            }
        }
    }

    private fun frames(text: String, codename: PokemonCodename): List<DesignFrame> =
        MarqueeText.frames(text, codename.size, paletteIndex = DEFAULT_LEVELS.size - 1)

    private fun litCells(cells: String, size: Int): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (i in cells.indices) if (cells[i] != '0') out.add((i % size) to (i / size))
        return out
    }
}
