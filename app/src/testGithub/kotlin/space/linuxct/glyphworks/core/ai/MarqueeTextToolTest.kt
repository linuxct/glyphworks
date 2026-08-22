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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.MarqueeFont
import space.linuxct.glyphworks.core.design.PokemonCodename

class MarqueeTextToolTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    private val tooLong = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"

    @Test
    fun `apply_this is a document apply_design accepts as it stands`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val body = ok(marquee("HELLO", ctx = ctx))
        val document = body[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content

        val applied = GlyphAiTools.run(
            GlyphAiTools.APPLY_DESIGN,
            buildJsonObject { put(GlyphAiTools.ARG_DESIGN, document) }.toString(),
            ctx,
        )

        assertFalse(applied.json, applied.isError)
        val design = applied.design!!
        assertEquals(framesOf(body).size, design.variantFor(bellsprout)!!.frames.size)
        assertEquals(framesOf(body), design.variantFor(bellsprout)!!.frames.map { it.cells })
        assertTrue(DesignCodec.decode(DesignCodec.encode(design)) is DesignCodec.Result.Ok)
    }

    @Test
    fun `it applies nothing itself`() {
        val result = marquee("HELLO")

        assertFalse(result.isError)
        assertNull("a computing tool must never hand back a design to apply", result.design)
        assertNull(result.validated)
        assertFalse(body(result)["applied"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the document sets kind and loop and mentions nothing else`() {
        val document = Json.parseToJsonElement(
            ok(marquee("HELLO"))[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content,
        ).jsonObject

        assertEquals(setOf("kind", "loop", "variants"), document.keys)
        assertEquals("dynamic", document["kind"]!!.jsonPrimitive.content)
        assertTrue(document["loop"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a marquee leaves the palette, the key mode and the other panel alone`() {
        val canvas = TestDesigns.design(
            mapOf(
                bellsprout.codename to TestDesigns.frames(bellsprout),
                arbok.codename to TestDesigns.frames(arbok),
            ),
        ).copy(keyMode = KeyMode.PLAY_ONCE, levels = listOf(0, 1000, 4095))
        val ctx = GlyphToolContext(canvas, openVariant = bellsprout)
        val document = ok(marquee("HI", ctx = ctx))[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content

        val applied = GlyphAiTools.run(
            GlyphAiTools.APPLY_DESIGN,
            buildJsonObject { put(GlyphAiTools.ARG_DESIGN, document) }.toString(),
            ctx,
        ).design!!

        assertEquals(KeyMode.PLAY_ONCE, applied.keyMode)
        assertEquals(listOf(0, 1000, 4095), applied.levels)
        assertEquals(canvas.name, applied.name)
        assertEquals(canvas.variantFor(arbok), applied.variantFor(arbok))
        assertEquals(20, applied.variantFor(bellsprout)!!.frames.size)
    }

    @Test
    fun `it writes only the panel it was asked for`() {
        val body = ok(marquee("HI", variant = "arbok", ctx = GlyphToolContext(TestDesigns.bothVariants())))

        assertEquals("arbok", body["variant"]!!.jsonPrimitive.content)
        assertEquals(arbok.cellCount, framesOf(body).first().length)
        assertEquals(3, body["top_row"]!!.jsonPrimitive.content.toInt())
        assertEquals(18, body["glyph_height"]!!.jsonPrimitive.content.toInt())
        assertFalse(ok(marquee("HI")).toString().contains("arbok"))
    }

    @Test
    fun `the strip is the whole phrase in one picture`() {
        val body = ok(marquee("GLYPH"))

        assertEquals(MarqueeFont.picture("GLYPH").joinToString("\n"), body["strip"]!!.jsonPrimitive.content)
        assertEquals(MarqueeFont.stripWidth("GLYPH"), body["strip_width"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `characters it cannot draw are all named at once`() {
        val result = marquee("A♥B中C")

        assertTrue(errorOf(result).contains("♥"))
        assertTrue(errorOf(result).contains("中"))
        assertEquals(
            listOf("♥", "中"),
            body(result)["unsupported_characters"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(expected(result).contains("cafe"))
    }

    @Test
    fun `a phrase past the frame limit is refused with numbers, not a shrug`() {
        val result = marquee(tooLong)
        val body = body(result)

        assertTrue(errorOf(result).contains("${tooLong.length} characters"))
        assertTrue(body["frames_needed"]!!.jsonPrimitive.content.toInt() > DesignCodec.MAX_FRAMES)
        assertEquals(DesignCodec.MAX_FRAMES, body["max_frames"]!!.jsonPrimitive.content.toInt())
        val prefix = body["longest_text_that_fits"]!!.jsonPrimitive.content
        assertEquals(prefix.length, body["longest_text_that_fits_length"]!!.jsonPrimitive.content.toInt())
        assertTrue(prefix.isNotEmpty() && tooLong.startsWith(prefix))
        assertTrue(expected(result).contains("Do NOT ask for fewer frames"))
    }

    @Test
    fun `a panel the design does not carry is refused with the ones it does`() {
        val result = marquee("HI", variant = "arbok")

        assertTrue(errorOf(result).contains("arbok"))
        assertEquals(
            listOf("bellsprout"),
            body(result)["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `every frame is listed and the previews are capped`() {
        val body = ok(marquee("HELLO WORLD"))
        val frames = body["frames"]!!.jsonArray

        assertEquals(body["frame_count"]!!.jsonPrimitive.content.toInt(), frames.size)
        assertEquals(frames.indices.toList(), frames.map { it.jsonObject["index"]!!.jsonPrimitive.content.toInt() })
        assertEquals(
            GlyphAiTools.MAX_SCROLL_PREVIEW_FRAMES,
            frames.count { it.jsonObject.containsKey("preview") },
        )
        assertTrue(body["previews_truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(frames.none { it.jsonObject.containsKey("cells") })
    }

    private fun ctx(): GlyphToolContext =
        GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)

    private fun marquee(
        text: String,
        variant: String? = null,
        scale: Int? = null,
        step: Int? = null,
        durationMs: Int? = null,
        paletteIndex: Int? = null,
        ctx: GlyphToolContext = ctx(),
    ): GlyphToolResult = GlyphAiTools.run(
        GlyphAiTools.MARQUEE_TEXT,
        buildJsonObject {
            put(GlyphAiTools.ARG_TEXT, text)
            variant?.let { put(GlyphAiTools.ARG_VARIANT, it) }
            scale?.let { put(GlyphAiTools.ARG_SCALE, it) }
            step?.let { put(GlyphAiTools.ARG_STEP, it) }
            durationMs?.let { put(GlyphAiTools.ARG_DURATION_MS, it) }
            paletteIndex?.let { put(GlyphAiTools.ARG_PALETTE_INDEX, it) }
        }.toString(),
        ctx,
    )

    private fun framesOf(body: JsonObject): List<String> =
        Json.parseToJsonElement(body[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content)
            .jsonObject["variants"]!!.jsonObject
            .values.first().jsonObject["frames"]!!.jsonArray
            .map { it.jsonObject["cells"]!!.jsonPrimitive.content }

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
