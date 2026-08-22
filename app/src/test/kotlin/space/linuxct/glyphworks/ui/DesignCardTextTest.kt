package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

class DesignCardTextTest {
    private val separator = " · "

    private fun strings() = DesignCardStrings(
        noArt = "no artwork yet",
        kindStatic = "Static",
        kindDynamic = "Dynamic",
        frameCount = { n -> if (n == 1) "1 frame" else "$n frames" },
        by = { author -> "by $author" },
        variantEmpty = { name -> "$name (empty)" },
        deviceName = { codename ->
            when (codename) {
                PokemonCodename.BELLSPROUT -> "Nothing Phone (4a) Pro"
                PokemonCodename.ARBOK -> "Nothing Phone (3)"
            }
        },
    )

    private fun design(
        name: String = "Slow Ember",
        author: String = "",
        modifiedAt: String = "2026-07-30T12:34:56Z",
        kind: DesignKind = DesignKind.DYNAMIC,
        variants: Map<String, Int> = mapOf("bellsprout" to 12),
    ) = Design(
        id = "abc",
        name = name,
        author = author,
        modifiedAt = modifiedAt,
        kind = kind,
        variants = variants.mapValues { (_, count) -> DesignVariant(List(count) { DesignFrame(120, "") }) },
    )

    private fun legacyCardText(design: Design, name: String, s: DesignCardStrings): DesignCardText {
        val frames = design.variants.values.maxOfOrNull { it.frames.size } ?: 0
        val meta = if (frames == 0) s.noArt else s.frameCount(frames)
        val credit = if (design.author.isNotBlank()) {
            s.by(design.author)
        } else {
            formatTimestamp(design.modifiedAt)
        }
        val summaryParts = mutableListOf(
            if (design.kind == DesignKind.DYNAMIC) s.kindDynamic else s.kindStatic,
            s.frameCount(frames),
        )
        if (design.author.isNotBlank()) summaryParts += s.by(design.author)
        val present = PokemonCodename.entries.mapNotNull { codename ->
            val variant = design.variantFor(codename) ?: return@mapNotNull null
            val device = s.deviceName(codename)
            if (variant.frames.isEmpty()) s.variantEmpty(device) else device
        }
        val variants = if (present.isEmpty()) s.noArt else present.joinToString(separator)
        val provenance = variants + separator + formatTimestamp(design.modifiedAt)
        val spoken = name + separator + summaryParts.joinToString(separator) + separator + provenance
        return DesignCardText(meta, credit, spoken)
    }

    @Test
    fun `an unnamed design uses the name the card was given`() {
        val text = designCardText(design(name = ""), "Unnamed", strings())
        assertTrue(text.spoken.startsWith("Unnamed$separator"))
    }

    @Test
    fun `the credit line is the author when there is one`() {
        assertEquals("by linuxct", designCardText(design(author = "linuxct"), "n", strings()).credit)
    }

    @Test
    fun `the credit line is the modified date when there is not`() {
        val design = design()
        assertEquals(formatTimestamp(design.modifiedAt), designCardText(design, "n", strings()).credit)
    }

    @Test
    fun `the meta line counts the richest variant`() {
        val design = design(variants = mapOf("bellsprout" to 0, "arbok" to 46))
        assertEquals(46, designFrameCount(design))
        assertEquals("46 frames", designCardText(design, "n", strings()).meta)
    }

    @Test
    fun `a design with no art anywhere says so`() {
        val design = design(variants = mapOf("bellsprout" to 0))
        assertEquals("no artwork yet", designCardText(design, "n", strings()).meta)
    }

    @Test
    fun `the spoken sentence keeps everything the cell dropped`() {
        val design = design(author = "linuxct", variants = mapOf("bellsprout" to 12, "arbok" to 0))
        val spoken = designCardText(design, "Slow Ember", strings()).spoken
        assertEquals(
            "Slow Ember · Dynamic · 12 frames · by linuxct · Nothing Phone (4a) Pro · " +
                "Nothing Phone (3) (empty) · " + formatTimestamp(design.modifiedAt),
            spoken,
        )
    }
}
