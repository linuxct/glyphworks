package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename

class GlyphAiToolsTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    @Test
    fun `get_current_design emits only the variants the design carries`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)

        val result = call(GlyphAiTools.GET_CURRENT_DESIGN, "{}", ctx)
        val body = ok(result)

        assertEquals(listOf("bellsprout"), body["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("bellsprout"), body["variants"]!!.jsonObject.keys)
        assertFalse(result.json.contains("arbok"))
    }

    @Test
    fun `get_current_design reports the editor context and the document's own fields`() {
        val ctx = GlyphToolContext(
            TestDesigns.bothVariants(),
            openVariant = arbok,
            selectedFrameIndex = 1,
        )

        val body = ok(call(GlyphAiTools.GET_CURRENT_DESIGN, "{}", ctx))

        assertEquals("Slow Ember", body["name"]!!.jsonPrimitive.content)
        assertEquals("dynamic", body["kind"]!!.jsonPrimitive.content)
        assertEquals("playPause", body["keyMode"]!!.jsonPrimitive.content)
        assertTrue(body["loop"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(0, 2048, 4095), body["levels"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        val editor = body["editor"]!!.jsonObject
        assertEquals("arbok", editor["open_variant"]!!.jsonPrimitive.content)
        assertEquals(1, editor["selected_frame_index"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `get_current_design carries the cells and a drawing of every frame`() {
        val design = TestDesigns.bellsproutOnly()
        val body = ok(call(GlyphAiTools.GET_CURRENT_DESIGN, "{}", GlyphToolContext(design)))

        val frames = body["variants"]!!.jsonObject["bellsprout"]!!.jsonObject["frames"]!!.jsonArray
        assertEquals(2, frames.size)
        assertEquals(TestDesigns.blank(bellsprout), frames[0].jsonObject["cells"]!!.jsonPrimitive.content)
        assertEquals(
            GlyphAsciiPreview.renderCells(TestDesigns.lit(bellsprout), design.levels, bellsprout),
            frames[1].jsonObject["preview"]!!.jsonPrimitive.content,
        )
        val drawn = frames[1].jsonObject["preview"]!!.jsonPrimitive.content
        assertEquals(137, drawn.count { it != '\n' && it != GlyphAsciiPreview.OFF_PANEL })
    }

    @Test
    fun `previews are capped for a long animation and the cap is declared`() {
        val many = (0 until GlyphAiTools.MAX_PREVIEW_FRAMES + 4).map {
            DesignFrame(120, TestDesigns.blank(bellsprout))
        }
        val design = TestDesigns.design(mapOf("bellsprout" to DesignVariant(many)))

        val body = ok(call(GlyphAiTools.GET_CURRENT_DESIGN, "{}", GlyphToolContext(design)))
        val variant = body["variants"]!!.jsonObject["bellsprout"]!!.jsonObject

        assertEquals(many.size, variant["frame_count"]!!.jsonPrimitive.content.toInt())
        assertTrue(variant["previews_truncated"]!!.jsonPrimitive.boolean)
        assertEquals(
            GlyphAiTools.MAX_PREVIEW_FRAMES,
            variant["previewed_frames"]!!.jsonPrimitive.content.toInt(),
        )
        val frames = variant["frames"]!!.jsonArray
        assertEquals(many.size, frames.size)
        assertNotNull(frames[GlyphAiTools.MAX_PREVIEW_FRAMES - 1].jsonObject["preview"])
        assertNull(frames[GlyphAiTools.MAX_PREVIEW_FRAMES].jsonObject["preview"])
    }

    @Test
    fun `a valid document applies and decodes back identical`() {
        val design = TestDesigns.bellsproutOnly()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)

        val result = call(GlyphAiTools.APPLY_DESIGN, args(DesignCodec.encode(design)), ctx)
        val applied = requireNotNull(result.design)

        assertFalse(result.isError)
        assertEquals(design, applied)
        val reread = DesignCodec.decode(DesignCodec.encode(applied))
        assertTrue(reread is DesignCodec.Result.Ok)
        assertEquals(design, (reread as DesignCodec.Result.Ok).design)
    }

    @Test
    fun `an edit reaches the applied design and the preview shows it`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val document = document(
            variants = """{"bellsprout":{"frames":[{"durationMs":90,"cells":"${TestDesigns.lit(bellsprout)}"}]}}""",
        )

        val result = call(GlyphAiTools.APPLY_DESIGN, args(document), ctx)
        val applied = requireNotNull(result.design)

        assertEquals(1, applied.variantFor(bellsprout)!!.frames.size)
        assertEquals(90, applied.variantFor(bellsprout)!!.frames[0].durationMs)
        assertEquals(TestDesigns.lit(bellsprout), applied.variantFor(bellsprout)!!.frames[0].cells)

        val body = ok(result)
        assertTrue(body["applied"]!!.jsonPrimitive.boolean)
        val preview = body["variants"]!!.jsonObject["bellsprout"]!!.jsonObject["frames"]!!
            .jsonArray[0].jsonObject["preview"]!!.jsonPrimitive.content
        assertEquals(137, preview.count { it != '\n' && it != GlyphAsciiPreview.OFF_PANEL })
    }

    @Test
    fun `a variant left out of the document is kept exactly as it was`() {
        val design = TestDesigns.bothVariants()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)
        val document = document(
            variants = """{"bellsprout":{"frames":[{"durationMs":120,"cells":"${TestDesigns.lit(bellsprout)}"}]}}""",
        )

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals(design.variantFor(arbok), applied.variantFor(arbok))
        assertEquals(TestDesigns.lit(bellsprout), applied.variantFor(bellsprout)!!.frames[0].cells)
    }

    @Test
    fun `fields the app manages are taken from the canvas, not from the model`() {
        val design = TestDesigns.bellsproutOnly()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)
        val document = """
            {
              "format": "glyph.design",
              "formatVersion": 1,
              "id": "../../etc/passwd",
              "author": "somebody else",
              "createdAt": "1999-01-01T00:00:00Z",
              "createdWith": "not GlyphWorks",
              "kind": "dynamic",
              "keyMode": "playPause",
              "loop": true,
              "levels": [0, 2048, 4095],
              "variants": {"bellsprout":{"frames":[{"durationMs":120,"cells":"${TestDesigns.blank(bellsprout)}"}]}}
            }
        """.trimIndent()

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals(design.id, applied.id)
        assertEquals(design.author, applied.author)
        assertEquals(design.createdAt, applied.createdAt)
        assertEquals(design.createdWith, applied.createdWith)
    }

    @Test
    fun `kind loop keyMode and levels are the model's to change`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val document = document(
            name = "\"Ember\"",
            kind = "\"static\"",
            keyMode = "\"playOnce\"",
            loop = "false",
            levels = "[0, 4095]",
            variants = """{"bellsprout":{"frames":[{"durationMs":500,"cells":"${"1".repeat(bellsprout.cellCount)}"}]}}""",
        )

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals("Ember", applied.name)
        assertEquals(DesignKind.STATIC, applied.kind)
        assertEquals(KeyMode.PLAY_ONCE, applied.keyMode)
        assertFalse(applied.loop)
        assertEquals(listOf(0, 4095), applied.levels)
    }

    @Test
    fun `the document may arrive as a JSON object instead of JSON text`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val raw = Json.parseToJsonElement(DesignCodec.encode(TestDesigns.bellsproutOnly()))
        val arguments = buildJsonObject { put("design", raw) }.toString()

        val result = call(GlyphAiTools.APPLY_DESIGN, arguments, ctx)

        assertFalse(result.isError)
        assertEquals(TestDesigns.bellsproutOnly(), result.design)
    }

    @Test
    fun `a document carrying only variants leaves every other field as it was`() {
        val ctx = GlyphToolContext(onCanvas(), openVariant = bellsprout)
        val document = """
            {"variants":{"bellsprout":{"frames":[{"durationMs":200,"cells":"${TestDesigns.lit(bellsprout)}"}]}}}
        """.trimIndent()

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals("name", "Slow Ember", applied.name)
        assertEquals("kind", DesignKind.DYNAMIC, applied.kind)
        assertEquals("keyMode", KeyMode.PLAY_ONCE, applied.keyMode)
        assertTrue("loop", applied.loop)
        assertEquals("levels", listOf(0, 1024, 4095), applied.levels)
        assertEquals(TestDesigns.lit(bellsprout), applied.variantFor(bellsprout)!!.frames.single().cells)
    }

    @Test
    fun `a document that sets kind changes it`() {
        assertEquals(DesignKind.STATIC, applyingOnly("\"kind\": \"static\"").kind)
    }

    @Test
    fun `a variant the document does carry replaces it, frames and all`() {
        val design = TestDesigns.bothVariants()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)
        val document = """
            {"variants":{"bellsprout":{"frames":[{"durationMs":500,"cells":"${TestDesigns.lit(bellsprout)}"}]}}}
        """.trimIndent()

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals(1, applied.variantFor(bellsprout)!!.frames.size)
        assertEquals(500, applied.variantFor(bellsprout)!!.frames.single().durationMs)
        assertEquals(design.variantFor(arbok), applied.variantFor(arbok))
    }

    @Test
    fun `a palette shorter than the kept frames index is still refused`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)

        val result = call(GlyphAiTools.APPLY_DESIGN, args("""{"levels": [0, 4095]}"""), ctx)

        assertTrue(errorOf(result), errorOf(result).contains("palette index 2"))
    }

    @Test
    fun `a variant the design does not carry is refused, and the refusal names what is allowed`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val document = document(
            variants = """{"arbok":{"frames":[{"durationMs":120,"cells":"${TestDesigns.blank(arbok)}"}]}}""",
        )

        for (tool in listOf(GlyphAiTools.APPLY_DESIGN, GlyphAiTools.VALIDATE_DESIGN)) {
            val result = call(tool, args(document), ctx)
            val message = errorOf(result)

            assertTrue("$tool: $message", message.contains("arbok"))
            assertEquals(
                listOf("bellsprout"),
                body(result)["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(body(result)["expected"]!!.jsonPrimitive.content.contains("bellsprout"))
        }
    }

    @Test
    fun `a variant the design does carry may be written while the other is on screen`() {
        val ctx = GlyphToolContext(TestDesigns.bothVariants(), openVariant = bellsprout)
        val document = document(
            variants = """{"arbok":{"frames":[{"durationMs":120,"cells":"${TestDesigns.lit(arbok)}"}]}}""",
        )

        val applied = requireNotNull(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx).design)

        assertEquals(TestDesigns.lit(arbok), applied.variantFor(arbok)!!.frames[0].cells)
    }

    @Test
    fun `a design carrying no known artwork cannot be written`() {
        val ctx = GlyphToolContext(TestDesigns.noVariants())

        val message = errorOf(call(GlyphAiTools.APPLY_DESIGN, args(document()), ctx))

        assertTrue(message, message.contains("cannot be edited"))
    }

    @Test
    fun `a cells string of the wrong length is refused with the length expected`() {
        val short = TestDesigns.blank(bellsprout).dropLast(1)
        val message = errorOf(applying(cells = short))

        assertTrue(message, message.contains("168 cells"))
        assertTrue(expected(applying(cells = short)).contains("${bellsprout.cellCount}"))
    }

    @Test
    fun `a palette index past the end of levels is refused with the legal range`() {
        val cells = TestDesigns.blank(bellsprout).let { it.take(20) + "5" + it.drop(21) }
        val result = applying(cells = cells)

        assertTrue(errorOf(result), errorOf(result).contains("palette index 5"))
        assertTrue(errorOf(result), errorOf(result).contains("column 7, row 1"))
        assertTrue(expected(result).contains("'0'..'2'"))
    }

    @Test
    fun `a duration outside the bounds is refused at both ends`() {
        for (bad in listOf(DesignCodec.MIN_DURATION_MS - 1, DesignCodec.MAX_DURATION_MS + 1, 0, -5)) {
            val result = applying(durationMs = bad)

            assertTrue("durationMs $bad", errorOf(result).contains("durationMs $bad"))
            assertTrue(expected(result).contains("${DesignCodec.MIN_DURATION_MS}"))
            assertTrue(expected(result).contains("${DesignCodec.MAX_DURATION_MS}"))
        }
    }

    @Test
    fun `no shape of nonsense throws`() {
        val ctx = GlyphToolContext(TestDesigns.bothVariants(), openVariant = bellsprout)
        val nonsense = listOf(
            "",
            "   ",
            "null",
            "[]",
            "\"design\"",
            "{",
            "{\"design\":}",
            "{\"design\": null}",
            "{\"design\": 7}",
            "{\"design\": []}",
            "{\"design\": \"\"}",
            "{\"design\": \"not json at all\"}",
            "{\"design\": \"[]\"}",
            "{\"design\": \"{\\\"variants\\\": 7}\"}",
            "{\"design\": \"{\\\"levels\\\": \\\"bright\\\"}\"}",
            "{\"design\": \"{\\\"variants\\\": {\\\"bellsprout\\\": 7}}\"}",
            "{\"design\": \"{\\\"variants\\\": {\\\"bellsprout\\\": {\\\"frames\\\": \\\"lots\\\"}}}\"}",
            args(document(loop = "\"yes\"")),
            args(document(kind = "\"kaleidoscope\"")),
            args(document(name = "\"${"x".repeat(DesignCodec.MAX_NAME_LENGTH + 1)}\"")),
        )

        for (arguments in nonsense) {
            for (tool in listOf(GlyphAiTools.APPLY_DESIGN, GlyphAiTools.VALIDATE_DESIGN)) {
                val result = call(tool, arguments, ctx)
                val parsed = Json.parseToJsonElement(result.json).jsonObject
                if (result.isError) {
                    assertTrue("$tool/$arguments has no error text", parsed.containsKey("error"))
                    assertNull(result.design)
                }
            }
        }
    }

    @Test
    fun `a document past the size cap is refused before it is parsed`() {
        val result = call(
            GlyphAiTools.APPLY_DESIGN,
            args("x".repeat(DesignCodec.MAX_CHARS + 1)),
            GlyphToolContext(TestDesigns.bellsproutOnly()),
        )

        assertTrue(errorOf(result), errorOf(result).contains("characters"))
    }

    @Test
    fun `validate_design accepts what apply_design accepts and hands back nothing to apply`() {
        val design = TestDesigns.bellsproutOnly()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)

        val result = call(GlyphAiTools.VALIDATE_DESIGN, args(DesignCodec.encode(design)), ctx)
        val body = ok(result)

        assertTrue(body["valid"]!!.jsonPrimitive.boolean)
        assertFalse(body["applied"]!!.jsonPrimitive.boolean)
        assertNull(result.design)
        assertEquals(design.name, result.validated?.name)
    }

    @Test
    fun `only the dry run reports a validated draft, and only a passing one`() {
        val design = TestDesigns.bellsproutOnly()
        val ctx = GlyphToolContext(design, openVariant = bellsprout)

        val applied = call(GlyphAiTools.APPLY_DESIGN, args(DesignCodec.encode(design)), ctx)
        assertNotNull(applied.design)
        assertNull("an apply is not a draft to fall back on; it already landed", applied.validated)

        val refused = call(
            GlyphAiTools.VALIDATE_DESIGN,
            args(document(variants = """{"bellsprout":{"frames":[{"durationMs":120,"cells":"tooshort"}]}}""")),
            ctx,
        )
        assertTrue(refused.isError)
        assertNull("a draft that did not validate is not a draft", refused.validated)
    }

    @Test
    fun `validate_design shows the same drawings apply_design would`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val document = document(
            variants = """{"bellsprout":{"frames":[{"durationMs":120,"cells":"${TestDesigns.lit(bellsprout)}"}]}}""",
        )

        val dry = ok(call(GlyphAiTools.VALIDATE_DESIGN, args(document), ctx))
        val wet = ok(call(GlyphAiTools.APPLY_DESIGN, args(document), ctx))

        assertEquals(dry["variants"], wet["variants"])
    }

    @Test
    fun `every spec is valid JSON that names its own tool`() {
        for (tool in GlyphAiTools.build()) {
            val spec = Json.parseToJsonElement(tool.specJson).jsonObject

            assertEquals("function", spec["type"]!!.jsonPrimitive.content)
            assertEquals(tool.name, spec["name"]!!.jsonPrimitive.content)
            assertTrue(spec["description"]!!.jsonPrimitive.content.length > 40)
            val parameters = spec["parameters"]!!.jsonObject
            assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
            val properties = parameters["properties"]!!.jsonObject.keys
            val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(properties, required)
        }
    }

    @Test
    fun `only the writing tools carry a design for the caller to put on the canvas`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val scrollArgs = buildJsonObject {
            put(GlyphAiTools.ARG_SOURCE_ROWS, Json.parseToJsonElement("""["2","2","2"]"""))
        }.toString()

        val marqueeArgs = buildJsonObject { put(GlyphAiTools.ARG_TEXT, "HI") }.toString()

        for ((tool, args) in listOf(
            GlyphAiTools.GET_CURRENT_DESIGN to "{}",
            GlyphAiTools.MARQUEE_TEXT to marqueeArgs,
            GlyphAiTools.SCROLL_FRAMES to scrollArgs,
            GlyphAiTools.VALIDATE_DESIGN to args(DesignCodec.encode(TestDesigns.bellsproutOnly())),
        )) {
            val result = call(tool, args, ctx)
            assertFalse(tool, result.isError)
            assertNull(tool, result.design)
        }
        assertNotNull(call(GlyphAiTools.APPLY_DESIGN, args(DesignCodec.encode(TestDesigns.bellsproutOnly())), ctx).design)
        assertNotNull(
            call(
                GlyphAiTools.SET_FRAMES,
                buildJsonObject {
                    put(GlyphAiTools.ARG_MODE, GlyphAiTools.MODE_REPLACE)
                    put(GlyphAiTools.ARG_AT, 0)
                    put(
                        GlyphAiTools.ARG_FRAME_LIST,
                        Json.parseToJsonElement("""["${TestDesigns.lit(bellsprout)}"]"""),
                    )
                }.toString(),
                ctx,
            ).design,
        )
    }

    @Test
    fun `an invented tool name is an error listing the real ones`() {
        val result = GlyphAiTools.run("delete_everything", "{}", GlyphToolContext(TestDesigns.bellsproutOnly()))

        assertTrue(result.isError)
        assertTrue(errorOf(result).contains("delete_everything"))
        assertTrue(body(result)["available_tools"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("apply_design"))
    }

    private fun call(name: String, arguments: String, ctx: GlyphToolContext): GlyphToolResult =
        GlyphAiTools.run(name, arguments, ctx)

    private fun applying(
        cells: String = TestDesigns.blank(bellsprout),
        durationMs: Int = 120,
    ): GlyphToolResult = call(
        GlyphAiTools.APPLY_DESIGN,
        args(document(variants = """{"bellsprout":{"frames":[{"durationMs":$durationMs,"cells":"$cells"}]}}""")),
        GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout),
    )

    private fun onCanvas(): Design = TestDesigns.bellsproutOnly().copy(
        keyMode = KeyMode.PLAY_ONCE,
        levels = listOf(0, 1024, 4095),
    )

    private fun applyingOnly(fields: String): Design {
        val ctx = GlyphToolContext(onCanvas(), openVariant = bellsprout)
        val document = """
            {
              $fields,
              "variants": {"bellsprout":{"frames":[{"durationMs":120,"cells":"${TestDesigns.blank(bellsprout)}"}]}}
            }
        """.trimIndent()
        val result = call(GlyphAiTools.APPLY_DESIGN, args(document), ctx)
        assertFalse("expected success, got ${result.json}", result.isError)
        return requireNotNull(result.design)
    }

    private fun args(document: String): String =
        buildJsonObject { put("design", document) }.toString()

    private fun document(
        name: String = "\"Slow Ember\"",
        kind: String = "\"dynamic\"",
        keyMode: String = "\"playPause\"",
        loop: String = "true",
        levels: String = "[0, 2048, 4095]",
        variants: String = """{"bellsprout":{"frames":[{"durationMs":120,"cells":"${TestDesigns.blank(bellsprout)}"}]}}""",
    ): String = """
        {
          "format": "glyph.design",
          "formatVersion": 1,
          "name": $name,
          "kind": $kind,
          "keyMode": $keyMode,
          "loop": $loop,
          "levels": $levels,
          "variants": $variants
        }
    """.trimIndent()

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
