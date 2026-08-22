package space.linuxct.glyphworks.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename
import kotlin.math.abs

class DesignPreviewTest {

    private val phoneWindow = 411.dp

    @Test
    fun `a phone gets three columns`() {
        assertEquals(3, designGridColumns(phoneWindow))
    }

    @Test
    fun `a wider window gets more`() {
        assertEquals(5, designGridColumns(600.dp))
        assertEquals(6, designGridColumns(800.dp))
    }

    @Test
    fun `an unmeasured window falls back to the phone count`() {
        assertEquals(DESIGN_GRID_MIN_COLUMNS, designGridColumns(Dp.Unspecified))
        assertEquals(DESIGN_GRID_MIN_COLUMNS, designGridColumns(0.dp))
    }

    private fun cardChrome(column: Int, columns: Int): Dp =
        designCellInsetWidth(column, columns) + designDiscSideInset(column, columns) * 2

    @Test
    fun `every column's card is the same height`() {
        for (columns in DESIGN_GRID_MIN_COLUMNS..DESIGN_GRID_MAX_COLUMNS) {
            val expected = cardChrome(0, columns)
            for (column in 0 until columns) {
                assertEquals(
                    "column $column of $columns",
                    expected.value,
                    cardChrome(column, columns).value,
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `a static design shows one frame`() {
        val steps = previewSteps(listOf(120, 120, 120), dynamic = false)
        assertEquals(1, steps.size)
        assertEquals(0, steps[0].frameIndex)
    }

    @Test
    fun `a design with no frames has nothing to play`() {
        assertTrue(previewSteps(emptyList(), dynamic = true).isEmpty())
    }

    @Test
    fun `a short animation plays every frame in order`() {
        val steps = previewSteps(listOf(100, 200, 300), dynamic = true)
        assertEquals(listOf(0, 1, 2), steps.map { it.frameIndex })
        assertEquals(listOf(100, 200, 300), steps.map { it.holdMs })
    }

    @Test
    fun `the format's maximum is capped and evenly spaced`() {
        val steps = previewSteps(List(240) { 40 }, dynamic = true)
        assertEquals(PREVIEW_MAX_STEPS, steps.size)
        assertEquals(listOf(0, 30, 60, 90, 120, 150, 180, 210), steps.map { it.frameIndex })
    }

    @Test
    fun `a sampled preview runs at roughly the design's own speed`() {
        val durations = List(46) { 80 }
        val real = durations.sum()
        val loop = previewSteps(durations, dynamic = true).sumOf { it.holdMs }
        assertTrue("$loop should be within a fifth of $real", abs(loop - real) * 5 <= real)
    }

    @Test
    fun `the fastest legal frame is slowed to the floor`() {
        val steps = previewSteps(listOf(20, 20, 20), dynamic = true)
        assertTrue(steps.all { it.holdMs == PREVIEW_MIN_HOLD_MS })
    }

    @Test
    fun `a minute-long frame is held to the ceiling`() {
        val steps = previewSteps(listOf(60_000, 100), dynamic = true)
        assertEquals(PREVIEW_MAX_HOLD_MS, steps[0].holdMs)
        assertEquals(100, steps[1].holdMs)
    }

    private fun cellsFor(codename: PokemonCodename, seed: Int): String {
        val sb = StringBuilder(codename.cellCount)
        for (i in 0 until codename.cellCount) sb.append('0' + (i * 7 + seed * 13) % 3)
        return sb.toString()
    }

    private fun designOf(
        codename: PokemonCodename,
        cells: List<String>,
        kind: DesignKind = DesignKind.DYNAMIC,
    ) = Design(
        id = "art-${codename.codename}",
        modifiedAt = "2026-07-30T12:00:00Z",
        kind = kind,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            codename.codename to DesignVariant(cells.map { DesignFrame(durationMs = 120, cells = it) }),
        ),
    )

    private fun eagerFrame(design: Design, codename: PokemonCodename, frameIndex: Int): IntArray =
        DesignFrames.decode(
            design.variantFor(codename)!!.frames[frameIndex].cells,
            design.levels,
            codename.size,
        ) ?: IntArray(codename.cellCount)

    @Test
    fun `lazy decoding yields exactly the frames eager decoding did`() {
        for (codename in PokemonCodename.entries) {
            for (count in listOf(1, 5, PREVIEW_MAX_STEPS, 240)) {
                val design = designOf(codename, List(count) { cellsFor(codename, it) })
                val art = designPreviewArt(design, codename)
                assertEquals(codename.size, art.size)
                assertEquals(art.steps.size, art.frameCount)
                for ((step, plan) in art.steps.withIndex()) {
                    assertArrayEquals(
                        "${codename.codename} x$count step $step",
                        eagerFrame(design, codename, plan.frameIndex),
                        art.frame(step),
                    )
                }
            }
        }
    }

    @Test
    fun `composing a card decodes nothing`() {
        val codename = PokemonCodename.BELLSPROUT
        val art = designPreviewArt(designOf(codename, List(8) { cellsFor(codename, it) }), codename)
        assertEquals(0, art.decodedCount)
    }

    @Test
    fun `a step is decoded once, on first ask, and kept`() {
        val codename = PokemonCodename.BELLSPROUT
        val art = designPreviewArt(designOf(codename, List(8) { cellsFor(codename, it) }), codename)
        val first = art.frame(0)
        assertEquals(1, art.decodedCount)
        assertSame(first, art.frame(0))
        assertEquals(1, art.decodedCount)
        art.frame(3)
        assertEquals(2, art.decodedCount)
    }

    @Test
    fun `a frame that will not decode becomes a blank frame`() {
        val codename = PokemonCodename.BELLSPROUT
        val design = designOf(codename, listOf(cellsFor(codename, 0), "nonsense", cellsFor(codename, 2)))
        val art = designPreviewArt(design, codename)
        val blank = art.frame(1)!!
        assertEquals(codename.cellCount, blank.size)
        assertTrue(blank.all { it == 0 })
        assertArrayEquals(eagerFrame(design, codename, 2), art.frame(2))
        assertSame(blank, art.frame(1))
    }

    @Test
    fun `a design with no artwork has no frames to ask for`() {
        assertEquals(0, DesignPreviewArt.Empty.frameCount)
        assertNull(DesignPreviewArt.Empty.frame(0))
        val empty = Design(id = "empty", variants = mapOf("bellsprout" to DesignVariant(emptyList())))
        val art = designPreviewArt(empty, PokemonCodename.BELLSPROUT)
        assertEquals(0, art.frameCount)
        assertTrue(art.steps.isEmpty())
    }

    @Test
    fun `a static design carries only the frame it plays`() {
        val codename = PokemonCodename.ARBOK
        val design = designOf(codename, List(100) { cellsFor(codename, it) }, kind = DesignKind.STATIC)
        val art = designPreviewArt(design, codename)
        assertEquals(1, art.frameCount)
        assertArrayEquals(eagerFrame(design, codename, 0), art.frame(0))
    }

    @Test
    fun `a design drawn for the other phone still previews`() {
        val other = PokemonCodename.ARBOK
        val design = designOf(other, listOf(cellsFor(other, 1)))
        val art = designPreviewArt(design, PokemonCodename.BELLSPROUT)
        assertEquals(other.size, art.size)
        assertArrayEquals(eagerFrame(design, other, 0), art.frame(0))
    }
}
