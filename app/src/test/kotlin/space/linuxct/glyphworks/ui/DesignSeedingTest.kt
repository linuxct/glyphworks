package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

class DesignSeedingTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    private fun blankFrame(codename: PokemonCodename) =
        DesignVariant(frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))))

    @Test
    fun `this phone only seeds one variant, with a blank frame to draw on`() {
        val variants = seedVariants(setOf(bellsprout), home = bellsprout)
        assertEquals(setOf(bellsprout.codename), variants.keys)
        assertEquals(blankFrame(bellsprout), variants.getValue(bellsprout.codename))
    }

    @Test
    fun `the other phone only seeds that phone, and nothing of this one`() {
        val variants = seedVariants(setOf(arbok), home = bellsprout)
        assertEquals(setOf(arbok.codename), variants.keys)
        assertEquals(DesignVariant(), variants.getValue(arbok.codename))
    }

    @Test
    fun `both seeds the home variant with a frame and the other empty`() {
        val variants = seedVariants(PokemonCodename.entries.toSet(), home = bellsprout)
        assertEquals(listOf(bellsprout.codename, arbok.codename), variants.keys.toList())
        assertEquals(blankFrame(bellsprout), variants.getValue(bellsprout.codename))
        assertEquals(DesignVariant(), variants.getValue(arbok.codename))
    }

    @Test
    fun `an empty choice falls back to this phone rather than to nothing`() {
        assertEquals(setOf(bellsprout.codename), seedVariants(emptySet(), home = bellsprout).keys)
    }

    private fun design(targets: Set<PokemonCodename>, home: PokemonCodename) = Design(
        format = DESIGN_FORMAT,
        formatVersion = DESIGN_FORMAT_VERSION,
        id = "0123456789abcdef0123456789abcdef",
        name = "Slow Ember",
        createdAt = "2026-07-30T12:00:00Z",
        modifiedAt = "2026-07-30T12:00:00Z",
        createdWith = "GlyphWorks 2.0.0",
        levels = DEFAULT_LEVELS,
        variants = seedVariants(targets, home),
    )

    private fun assertSurvivesTheCodec(targets: Set<PokemonCodename>, home: PokemonCodename) {
        val original = design(targets, home)
        val result = DesignCodec.decode(DesignCodec.encode(original))
        assertTrue("rejected: $result", result is DesignCodec.Result.Ok)
        assertEquals(original.variants, (result as DesignCodec.Result.Ok).design.variants)
    }

    @Test
    fun `a design for this phone only is valid`() =
        assertSurvivesTheCodec(setOf(bellsprout), home = bellsprout)
}
