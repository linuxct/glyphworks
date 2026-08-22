package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

class ImageToGridTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    @Test
    fun `a ramp quantises into the levels its thresholds imply`() {
        val cells = okCells(quantise(ramp(130, 130), 13, 3, threshold = 0.5))

        for (y in 0 until 13) {
            for (x in 0 until 13) {
                if (!PanelMask.contains(x, y, 13)) continue
                val n = x / 12.0
                val expected = when {
                    n < 0.5 -> '0'
                    n < 0.75 -> '1'
                    else -> '2'
                }
                assertEquals("cell ($x, $y)", expected, cells[y * 13 + x])
            }
        }
        for (x in 0 until 13) {
            val chars = (0 until 13).filter { PanelMask.contains(x, it, 13) }.map { cells[it * 13 + x] }
            assertEquals("column $x disagrees with itself: $chars", 1, chars.toSet().size)
        }
    }

    @Test
    fun `nothing is ever drawn on a cell the panel has no LED for`() {
        for (codename in PokemonCodename.entries) {
            val cells = okCells(
                quantise(ramp(200, 200), codename.size, 3, threshold = 0.0),
            )

            for (i in cells.indices) {
                val x = i % codename.size
                val y = i / codename.size
                if (!PanelMask.contains(x, y, codename.size)) {
                    assertEquals("${codename.codename} ($x, $y) has no LED", '0', cells[i])
                }
            }
            assertEquals(
                "${codename.codename}: every live cell of a full-frame image is lit",
                PanelMask.count(codename.size),
                cells.count { it != '0' },
            )
        }
    }

    @Test
    fun `the output is exactly one frame's worth of cells, at every geometry`() {
        for (codename in PokemonCodename.entries) {
            val cells = okCells(quantise(ramp(64, 64), codename.size, 3))

            assertEquals(codename.cellCount, cells.length)
            assertEquals(codename.size * codename.size, cells.length)
        }
    }

    @Test
    fun `each knob changes the output, and invert is exactly the opposite frame`() {
        val image = ramp(130, 130)

        val plain = okCells(quantise(image, 13, 3, threshold = 0.5))
        val inverted = okCells(quantise(image, 13, 3, threshold = 0.5, invert = true))
        val lower = okCells(quantise(image, 13, 3, threshold = 0.2))
        val higher = okCells(quantise(image, 13, 3, threshold = 0.9))
        val flattened = okCells(quantise(image, 13, 3, threshold = 0.5, contrast = 0.25))
        val punchy = okCells(quantise(image, 13, 3, threshold = 0.5, contrast = 4.0))

        assertTrue(lit(lower) > lit(plain))
        assertTrue(lit(higher) < lit(plain))
        for (y in 0 until 13) {
            val row = plain.substring(y * 13, (y + 1) * 13)
            val other = inverted.substring(y * 13, (y + 1) * 13)
            assertEquals("row $y", row.reversed(), other)
        }
        assertTrue(flattened != plain)
        assertTrue(punchy != plain)
        assertTrue(punchy.count { it == '2' } > flattened.count { it == '2' })
    }

    @Test
    fun `the automatic threshold separates a subject from its background`() {
        val w = 100
        val pixels = IntArray(w * w) { 30 }
        for (y in 40 until 78) {
            for (x in 40 until 78) pixels[y * w + x] = 230
        }

        val result = ImageQuantiser.quantise(SourceImage(w, w, pixels), 13, 3) as ImageQuantiser.Result.Ok

        assertTrue("chosen, not supplied", result.automatic)
        assertTrue(result.lit > 0)
        assertTrue(result.lit < result.sampled / 2)
        val cells = result.cells
        assertEquals('2', cells[6 * 13 + 6])
        assertEquals('0', cells[6 * 13 + 1])
    }

    @Test
    fun `a converted photo is a document apply_design accepts as it stands`() {
        val ctx = ctx(ramp(130, 130))
        val document = ok(convert(ctx = ctx))[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content

        val applied = GlyphAiTools.run(
            GlyphAiTools.APPLY_DESIGN,
            buildJsonObject { put(GlyphAiTools.ARG_DESIGN, document) }.toString(),
            ctx,
        )

        assertFalse(applied.json, applied.isError)
        val design = applied.design!!
        val frames = design.variantFor(bellsprout)!!.frames
        assertEquals(1, frames.size)
        assertEquals(ok(convert(ctx = ctx))["cells"]!!.jsonPrimitive.content, frames[0].cells)
        assertTrue(DesignCodec.decode(DesignCodec.encode(design)) is DesignCodec.Result.Ok)
    }

    @Test
    fun `the result carries the drawing, the cells and the settings it used`() {
        val body = ok(convert())

        assertNotNull(body["preview"])
        assertEquals(bellsprout.cellCount, body["cells"]!!.jsonPrimitive.content.length)
        assertEquals("bellsprout", body["variant"]!!.jsonPrimitive.content)
        assertEquals(PanelMask.count(13), body["live_leds"]!!.jsonPrimitive.content.toInt())
        val chosen = body[GlyphAiTools.ARG_THRESHOLD]!!.jsonPrimitive.content.toDouble()
        val again = ok(convert(threshold = chosen))
        assertEquals(body["cells"], again["cells"])
    }

    @Test
    fun `with no attachment it says so instead of drawing from memory`() {
        val result = GlyphAiTools.run(
            GlyphAiTools.IMAGE_TO_GRID,
            "{}",
            GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout),
        )

        val message = errorOf(result)
        assertTrue(message, message.contains("No image is attached"))
        assertTrue(expected(result), expected(result).contains("earlier turn"))
    }

    @Test
    fun `each attached image converts to its own frame`() {
        val ctx = ctx(ramp(130, 130), ramp(130, 130, invert = true))

        val first = ok(convert(index = 0, ctx = ctx))["cells"]!!.jsonPrimitive.content
        val second = ok(convert(index = 1, ctx = ctx))["cells"]!!.jsonPrimitive.content

        assertTrue("two different pictures convert differently", first != second)
        assertEquals(0, ok(convert(ctx = ctx))["image"]!!.jsonObject["index"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a flat image is refused rather than converted into nothing`() {
        val result = convert(ctx = ctx(SourceImage(40, 40, IntArray(1600) { 128 })))

        val message = errorOf(result)
        assertTrue(message, message.contains("flat field"))
        assertTrue(message, message.contains("no picture in it"))
        val body = body(result)
        assertNull(body[GlyphAiTools.KEY_APPLY_THIS])
        assertNull(body["cells"])
    }

    @Test
    fun `a panel the design does not carry is refused, naming what is allowed`() {
        val result = convert(variant = "arbok")

        assertTrue(errorOf(result), errorOf(result).contains("arbok"))
        assertEquals(
            listOf("bellsprout"),
            body(result)["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `no shape of nonsense throws, and each failure says what was expected`() {
        val ctx = ctx(ramp(64, 64))
        val nonsense = listOf(
            "",
            "[]",
            "null",
            "{",
            """{"image_index": "first"}""",
            """{"image_index": -1}""",
            """{"image_index": 9}""",
            """{"threshold": "half"}""",
            """{"threshold": 1.0}""",
            """{"threshold": -0.5}""",
            """{"contrast": 0}""",
            """{"contrast": 99}""",
            """{"contrast": "hard"}""",
            """{"invert": "yes"}""",
            """{"variant": "pikachu"}""",
            """{"variant": 7}""",
        )

        for (arguments in nonsense) {
            val result = GlyphAiTools.run(GlyphAiTools.IMAGE_TO_GRID, arguments, ctx)
            val parsed = Json.parseToJsonElement(result.json).jsonObject

            if (result.isError) {
                assertTrue("$arguments has no error text", parsed.containsKey("error"))
                assertNull("an error must never carry a design", result.design)
            }
        }
    }

    private fun ramp(
        width: Int,
        height: Int,
        invert: Boolean = false,
        bands: Int = 13,
    ): SourceImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val band = (x * bands / width).coerceAtMost(bands - 1)
                pixels[y * width + x] = (if (invert) bands - 1 - band else band) * BAND_STEP
            }
        }
        return SourceImage(width, height, pixels)
    }

    private fun quantise(
        image: SourceImage,
        size: Int,
        levels: Int,
        threshold: Double? = null,
        contrast: Double = ImageQuantiser.DEFAULT_CONTRAST,
        invert: Boolean = false,
    ): ImageQuantiser.Result =
        ImageQuantiser.quantise(image, size, levels, threshold, contrast, invert)

    private fun okCells(result: ImageQuantiser.Result): String {
        assertTrue("expected a conversion, got $result", result is ImageQuantiser.Result.Ok)
        return (result as ImageQuantiser.Result.Ok).cells
    }

    private fun lit(cells: String): Int = cells.count { it != '0' }

    private val BAND_STEP = 20

    private fun ctx(
        vararg images: SourceImage,
        design: Design = TestDesigns.bellsproutOnly(),
    ): GlyphToolContext = GlyphToolContext(
        design = design,
        openVariant = if (design.variantFor(bellsprout) != null) bellsprout else arbok,
        images = images.toList(),
    )

    private fun convert(
        index: Int? = null,
        variant: String? = null,
        threshold: Double? = null,
        contrast: Double? = null,
        invert: Boolean? = null,
        ctx: GlyphToolContext = ctx(ramp(130, 130)),
    ): GlyphToolResult = GlyphAiTools.run(
        GlyphAiTools.IMAGE_TO_GRID,
        buildJsonObject {
            index?.let { put(GlyphAiTools.ARG_IMAGE_INDEX, it) }
            variant?.let { put(GlyphAiTools.ARG_VARIANT, it) }
            threshold?.let { put(GlyphAiTools.ARG_THRESHOLD, it) }
            contrast?.let { put(GlyphAiTools.ARG_CONTRAST, it) }
            invert?.let { put(GlyphAiTools.ARG_INVERT, it) }
        }.toString(),
        ctx,
    )

    private fun body(result: GlyphToolResult): JsonObject =
        Json.parseToJsonElement(result.json).jsonObject

    private fun ok(result: GlyphToolResult): JsonObject {
        assertFalse("expected success, got ${result.json}", result.isError)
        return body(result)
    }

    private fun errorOf(result: GlyphToolResult): String {
        assertTrue("expected an error, got ${result.json}", result.isError)
        assertNull("an error must never carry a design to apply", result.design)
        return body(result)["error"]!!.jsonPrimitive.content
    }

    private fun expected(result: GlyphToolResult): String =
        body(result)["expected"]!!.jsonPrimitive.content
}
