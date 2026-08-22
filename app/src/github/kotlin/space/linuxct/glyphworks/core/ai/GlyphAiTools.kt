package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import space.linuxct.glyphworks.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.MarqueeFont
import space.linuxct.glyphworks.core.design.MarqueeText
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

data class GlyphToolContext(
    val design: Design,
    val openVariant: PokemonCodename? = null,
    val selectedFrameIndex: Int = 0,
    val images: List<SourceImage> = emptyList(),
) {
    val allowedVariants: List<PokemonCodename>
        get() = PokemonCodename.entries.filter { design.variantFor(it) != null }
}

data class GlyphToolResult(
    val json: String,
    val isError: Boolean = false,
    val design: Design? = null,
    val validated: Design? = null,
)

data class GlyphTool(
    val name: String,
    val specJson: String,
    val run: (arguments: String, ctx: GlyphToolContext) -> GlyphToolResult,
)

object GlyphAiTools {

    const val GET_CURRENT_DESIGN = "get_current_design"
    const val APPLY_DESIGN = "apply_design"
    const val VALIDATE_DESIGN = "validate_design"
    const val SCROLL_FRAMES = "scroll_frames"
    const val MARQUEE_TEXT = "marquee_text"
    const val IMAGE_TO_GRID = "image_to_grid"
    const val SET_FRAMES = "set_frames"

    const val ARG_DESIGN = "design"

    const val ARG_VARIANT = "variant"

    const val ARG_SOURCE_ROWS = "source_rows"
    const val ARG_TOP_ROW = "top_row"
    const val ARG_START_COLUMN = "start_column"
    const val ARG_STEP = "step"
    const val ARG_FRAMES = "frames"
    const val ARG_DURATION_MS = "duration_ms"

    const val ARG_TEXT = "text"

    const val ARG_SCALE = "scale"

    const val ARG_PALETTE_INDEX = "palette_index"

    const val ARG_IMAGE_INDEX = "image_index"
    const val ARG_THRESHOLD = "threshold"
    const val ARG_CONTRAST = "contrast"
    const val ARG_INVERT = "invert"

    const val ARG_MODE = "mode"
    const val ARG_AT = "at"
    const val ARG_COUNT = "count"

    const val ARG_FRAME_LIST = "frames"

    const val MODE_REPLACE = "replace"
    const val MODE_INSERT = "insert"
    const val MODE_DELETE = "delete"

    const val KEY_APPLY_THIS = "apply_this"

    const val MAX_PREVIEW_FRAMES = 16

    const val MAX_SCROLL_PREVIEW_FRAMES = 24

    fun build(): List<GlyphTool> = listOf(
        GlyphTool(GET_CURRENT_DESIGN, SPEC_GET_CURRENT_DESIGN) { _, ctx -> getCurrentDesign(ctx) },
        GlyphTool(IMAGE_TO_GRID, SPEC_IMAGE_TO_GRID) { args, ctx -> imageToGrid(args, ctx) },
        GlyphTool(MARQUEE_TEXT, SPEC_MARQUEE_TEXT) { args, ctx -> marqueeText(args, ctx) },
        GlyphTool(SCROLL_FRAMES, SPEC_SCROLL_FRAMES) { args, ctx -> scrollFrames(args, ctx) },
        GlyphTool(SET_FRAMES, SPEC_SET_FRAMES) { args, ctx -> setFrames(args, ctx) },
        GlyphTool(VALIDATE_DESIGN, SPEC_VALIDATE_DESIGN) { args, ctx -> validateDesign(args, ctx) },
        GlyphTool(APPLY_DESIGN, SPEC_APPLY_DESIGN) { args, ctx -> applyDesign(args, ctx) },
    )

    fun run(name: String, arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val tool = build().firstOrNull { it.name == name }
            ?: return failure(
                "There is no tool called \"$name\".",
            ) {
                putJsonArray("available_tools") { build().forEach { add(it.name) } }
            }
        return tool.run(arguments, ctx)
    }

    private fun getCurrentDesign(ctx: GlyphToolContext): GlyphToolResult {
        val design = ctx.design
        val allowed = ctx.allowedVariants
        return success(
            buildJsonObject {
                put("name", design.name)
                put("kind", kindName(design.kind))
                put("keyMode", keyModeName(design))
                put("loop", design.loop)
                putJsonArray("levels") { design.levels.forEach { add(it) } }
                putJsonObject("editor") {
                    if (ctx.openVariant != null && allowed.contains(ctx.openVariant)) {
                        put("open_variant", ctx.openVariant.codename)
                    } else {
                        put("open_variant", JsonNull)
                    }
                    put("selected_frame_index", ctx.selectedFrameIndex)
                }
                putJsonArray("allowed_variants") { allowed.forEach { add(it.codename) } }
                put("variants", variantsJson(design, allowed, includeCells = true))
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    private fun validateDesign(arguments: String, ctx: GlyphToolContext): GlyphToolResult =
        when (val prepared = prepare(arguments, ctx)) {
            is Prepared.Bad -> prepared.result
            is Prepared.Ok -> success(
                buildJsonObject {
                    put("valid", true)
                    put("applied", false)
                    put(
                        "note",
                        "Nothing was changed. This is what apply_design would produce; " +
                            "check the previews, then call apply_design with the same document.",
                    )
                    putSummary(prepared.design, ctx)
                },
                validated = prepared.design,
            )
        }

    private fun applyDesign(arguments: String, ctx: GlyphToolContext): GlyphToolResult =
        when (val prepared = prepare(arguments, ctx)) {
            is Prepared.Bad -> prepared.result
            is Prepared.Ok -> success(
                buildJsonObject {
                    put("applied", true)
                    put(
                        "note",
                        "This is on the user's canvas now. Read the previews: if the art is " +
                            "off-centre, clipped by the disc or not what was asked for, fix it and " +
                            "apply again.",
                    )
                    putSummary(prepared.design, ctx)
                },
                design = prepared.design,
            )
        }

    private fun marqueeText(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_TEXT\": \"HELLO WORLD\"}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val raw = args[ARG_TEXT]
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure("\"$ARG_TEXT\" is missing, or is not a string.") {
                put("expected", "The phrase to scroll, e.g. \"HELLO WORLD\". It is the only argument that has to be set.")
            }
        if (text.isEmpty()) {
            return failure("\"$ARG_TEXT\" is empty, so there is nothing to scroll.")
        }
        val missing = MarqueeFont.unsupported(text)
        if (missing.isNotEmpty()) {
            return failure(
                "This face cannot draw ${missing.joinToString(", ") { "'$it'" }}.",
            ) {
                putJsonArray("unsupported_characters") { missing.forEach { add(it.toString()) } }
                put(
                    "expected",
                    "Letters A-Z and a-z, digits 0-9, a space, and the printable ASCII symbols " +
                        "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ . Both cases are drawn, and accents are dropped " +
                        "automatically (\"café\" scrolls as cafe), so anything listed above is genuinely " +
                        "not renderable at this size. Replace it, or draw that part by hand and scroll it with " +
                        "$SCROLL_FRAMES.",
                )
            }
        }

        val maxScale = size / MarqueeFont.HEIGHT
        val scale = when (val a = intArg(args, ARG_SCALE)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.scaleFor(size)
        if (scale < 1 || scale > maxScale) {
            return failure(
                "\"$ARG_SCALE\" is $scale, and a ${MarqueeFont.HEIGHT}-row letter at that scale is " +
                    "${MarqueeFont.HEIGHT * scale} rows on a $size-row panel.",
            ) {
                put(
                    "expected",
                    "1 to $maxScale on ${codename.codename}. null means ${MarqueeText.scaleFor(size)}, which " +
                        "fills the same fraction of the panel on every geometry.",
                )
            }
        }

        val step = when (val a = intArg(args, ARG_STEP)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.defaultStep(size)
        if (step < 1 || step > size) {
            return failure("\"$ARG_STEP\" is $step.") {
                put(
                    "expected",
                    "1 to $size columns per frame. null means ${MarqueeText.defaultStep(size)} on " +
                        "${codename.codename} — one letter-cell at scale $scale, which is the smoothest step " +
                        "that is not wasted. Doubling it halves the frame count and still reads.",
                )
            }
        }

        val durationMs = when (val a = intArg(args, ARG_DURATION_MS)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.DEFAULT_DURATION_MS
        if (durationMs < DesignCodec.MIN_DURATION_MS || durationMs > DesignCodec.MAX_DURATION_MS) {
            return failure("\"$ARG_DURATION_MS\" is $durationMs.") {
                put(
                    "expected",
                    "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} inclusive (out of " +
                        "range is rejected, not clamped). null means ${MarqueeText.DEFAULT_DURATION_MS}, which " +
                        "is a little over two letters a second. Higher is slower and easier to read.",
                )
            }
        }

        val brightest = levels.size - 1
        val paletteIndex = when (val a = intArg(args, ARG_PALETTE_INDEX)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: brightest
        if (paletteIndex < 1 || paletteIndex > brightest) {
            return failure(
                if (paletteIndex == 0) {
                    "\"$ARG_PALETTE_INDEX\" is 0, which is the off level, so every frame would be blank."
                } else {
                    "\"$ARG_PALETTE_INDEX\" is $paletteIndex, but this design's \"levels\" defines only " +
                        "${levels.size} entr${if (levels.size == 1) "y" else "ies"}."
                },
            ) {
                put(
                    "expected",
                    if (brightest < 1) {
                        "A palette with at least one lit level. Add one to \"levels\" with $APPLY_DESIGN first."
                    } else {
                        "1 to $brightest. null means $brightest, the brightest this design has."
                    },
                )
            }
        }

        val stripWidth = MarqueeFont.stripWidth(text)
        val frameCount = MarqueeText.frameCount(size, stripWidth, scale, step)
        if (frameCount > DesignCodec.MAX_FRAMES) {
            val prefix = MarqueeText.maxPrefixLength(text, size, scale, step)
            val neededStep = MarqueeText.stepThatFits(text, size, scale)
            return failure(
                "\"$ARG_TEXT\" is ${text.length} characters, which lays out $stripWidth columns wide and " +
                    "takes $frameCount frames on ${codename.codename}.",
            ) {
                put("frames_needed", frameCount)
                put("max_frames", DesignCodec.MAX_FRAMES)
                put("longest_text_that_fits", text.take(prefix))
                put("longest_text_that_fits_length", prefix)
                if (neededStep != null) put("step_that_would_fit", neededStep)
                put(
                    "expected",
                    buildString {
                        append("At most ${DesignCodec.MAX_FRAMES} frames per panel. ")
                        append("Scroll \"${text.take(prefix)}\" ($prefix characters) instead")
                        if (neededStep != null) {
                            append("; or keep the whole phrase and set \"$ARG_STEP\" to $neededStep, which ")
                            append("moves faster and still reads")
                        }
                        if (scale > 1) append("; or drop \"$ARG_SCALE\" to ${scale - 1} for smaller letters")
                        append(". Do NOT ask for fewer frames — a scroll cut short looks broken, not shorter.")
                    },
                )
            }
        }

        val frames = MarqueeText.frames(
            text = text,
            size = size,
            paletteIndex = paletteIndex,
            durationMs = durationMs,
            scale = scale,
            step = step,
        )
        if (frames.isEmpty()) {
            return failure("That text produced no frames on ${codename.codename}.") {
                put("expected", "A phrase with at least one letter, digit or symbol in it.")
            }
        }

        val probe = ctx.design.copy(
            kind = DesignKind.DYNAMIC,
            loop = true,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frames this produced would not be accepted: ${checked.reason}")
        }

        val document = buildJsonObject {
            put("kind", "dynamic")
            put("loop", true)
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        for (frame in frames) {
                            add(
                                buildJsonObject {
                                    put("durationMs", frame.durationMs)
                                    put("cells", frame.cells)
                                },
                            )
                        }
                    }
                }
            }
        }.toString()

        val shown = minOf(frames.size, MAX_SCROLL_PREVIEW_FRAMES)
        val picture = MarqueeFont.picture(text)
        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. READ \"strip\" FIRST — it is the whole phrase in one picture, " +
                        "and it is where a wrong letter is actually visible. Then send \"$KEY_APPLY_THIS\" to " +
                        "$APPLY_DESIGN EXACTLY as it came back. Do not retype the cells and do not rebuild the " +
                        "frames by hand.",
                )
                put("variant", codename.codename)
                put("panel_width", size)
                put(ARG_TEXT, text)
                put("drawn_as", MarqueeFont.drawnAs(text))
                put("strip", picture.joinToString("\n"))
                put("strip_width", stripWidth)
                put("glyph_height", MarqueeFont.HEIGHT * scale)
                put("top_row", MarqueeText.topRow(size, scale))
                put(ARG_SCALE, scale)
                put(ARG_STEP, step)
                put(ARG_PALETTE_INDEX, paletteIndex)
                put(ARG_DURATION_MS, durationMs)
                put("frame_count", frames.size)
                put("total_ms", frames.size * durationMs)
                put(
                    "frame_count_note",
                    "The full traverse: panel width + message width - 1 = $size + ${stripWidth * scale} - 1 = " +
                        "${size + stripWidth * scale - 1} columns of travel, $frameCount frames at $step per " +
                        "frame" +
                        if (frames.size == frameCount) {
                            ". Neither the first frame nor the last is blank."
                        } else {
                            ", of which ${frameCount - frames.size} were blank at the ends and were dropped, " +
                                "leaving ${frames.size}. The outermost column of the disc is only five rows " +
                                "tall, so a letter column that is all serif arrives as an empty panel; the " +
                                "animation now opens and closes on something lit."
                        },
                )
                val darkInside = frames.count { it.cells.all { c -> c == '0' } }
                if (darkInside > 0) {
                    put("blank_frames_inside", darkInside)
                    put(
                        "blank_frames_note",
                        "$darkInside frame${if (darkInside == 1) " is" else "s are"} completely dark, which " +
                            "means the text has a run of spaces wider than the panel. Shorten it unless the " +
                            "pause is what you wanted.",
                    )
                }
                put(
                    "clipping_note",
                    "The letters are ${MarqueeFont.HEIGHT * scale} rows tall on a $size-row panel, so the " +
                        "disc cuts their tops and bottoms in the outermost columns as they enter and leave. " +
                        "That is deliberate and is what makes them read as big; the clipped cells are dropped " +
                        "when the frames are built, so nothing outside the panel is stored.",
                )
                put(
                    "document_note",
                    "\"$KEY_APPLY_THIS\" sets kind to dynamic and loop to true, and writes only " +
                        "${codename.codename}'s frames. keyMode, levels, name and any other panel are left " +
                        "exactly as they are.",
                )
                if (shown < frames.size) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(KEY_APPLY_THIS, document)
                putJsonArray("frames") {
                    for (i in frames.indices) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", frames[i].durationMs)
                                if (i < shown) {
                                    val preview = GlyphAsciiPreview.renderCells(frames[i].cells, levels, codename)
                                    put(
                                        "preview",
                                        preview?.let { JsonPrimitive(it) }
                                            ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                    )
                                }
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    private fun scrollFrames(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_SOURCE_ROWS\": [\"1010111\", \"1010010\", …]}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val rows = when (val read = sourceRows(args)) {
            is Rows.Bad -> return read.result
            is Rows.Ok -> read.rows
        }
        val height = rows.size
        val width = rows[0].length
        for (r in 1 until height) {
            if (rows[r].length != width) {
                return failure("\"$ARG_SOURCE_ROWS\" row $r is ${rows[r].length} characters but row 0 is $width.") {
                    put(
                        "expected",
                        "Every row exactly the same length. The source is ONE rectangle — the whole " +
                            "message drawn once — and a ragged one has no single width to scroll.",
                    )
                }
            }
        }
        if (height > size) {
            return failure("The source is $height rows tall, but ${codename.codename} is only $size rows.") {
                put("expected", scrollHeightAdvice(codename))
            }
        }

        var firstLit = -1
        var lastLit = -1
        for (r in 0 until height) {
            val row = rows[r]
            for (c in 0 until width) {
                val ch = row[c]
                val index = base36(ch)
                if (index < 0) {
                    return failure(
                        "\"$ARG_SOURCE_ROWS\" row $r column $c uses the character '$ch', which is not a base36 digit.",
                    ) {
                        put(
                            "expected",
                            "A palette index in base36: '0'-'9' then 'a'-'z'. This design defines " +
                                "${levels.size} level${if (levels.size == 1) "" else "s"}, so the only legal " +
                                "characters are ${legalChars(levels)}.",
                        )
                    }
                }
                if (index >= levels.size) {
                    return failure(
                        "\"$ARG_SOURCE_ROWS\" row $r column $c uses palette index $index ('$ch'), but this " +
                            "design's \"levels\" defines only ${levels.size} " +
                            "entr${if (levels.size == 1) "y" else "ies"}.",
                    ) {
                        put(
                            "expected",
                            "Either use ${legalChars(levels)}, or add the level you meant to \"levels\" " +
                                "with $APPLY_DESIGN first.",
                        )
                    }
                }
                if (index > 0) {
                    if (firstLit < 0) firstLit = c
                    if (c < firstLit) firstLit = c
                    if (c > lastLit) lastLit = c
                }
            }
        }
        if (firstLit < 0) {
            return failure("\"$ARG_SOURCE_ROWS\" has no lit cell, so every frame it produced would be blank.") {
                put("expected", "At least one character above '0' — the message itself.")
            }
        }

        val band = GlyphAiPrompt.fullWidthRows(size)
        val defaultTop = if (band != null && height <= band.count()) {
            band.first + (band.count() - height) / 2
        } else {
            (size - height) / 2
        }
        val topRow = when (val a = intArg(args, ARG_TOP_ROW)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: defaultTop
        if (topRow < 0 || topRow + height > size) {
            return failure("\"$ARG_TOP_ROW\" $topRow would put a $height-row source outside a ${size}-row panel.") {
                put("expected", "0 to ${size - height} for a source $height rows tall. ${scrollHeightAdvice(codename)}")
            }
        }

        val step = when (val a = intArg(args, ARG_STEP)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: 1
        if (step < 1) {
            return failure("\"$ARG_STEP\" is $step.") {
                put("expected", "At least 1 column per frame. 1 is smoothest; 2 halves the frame count and still reads.")
            }
        }
        if (step > size) {
            return failure(
                "\"$ARG_STEP\" is $step, wider than the ${size}-column panel, so whole columns of the " +
                    "message would never be shown at all.",
            ) { put("expected", "1 to $size.") }
        }

        val defaultStart = firstLit - (size - 1)
        val startColumn = when (val a = intArg(args, ARG_START_COLUMN)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: defaultStart
        val startLimit = size + width
        if (startColumn < -startLimit || startColumn > startLimit) {
            return failure(
                "\"$ARG_START_COLUMN\" $startColumn is so far outside the message that the panel would " +
                    "show nothing at all.",
            ) {
                put(
                    "expected",
                    "${-startLimit} to $startLimit. $defaultStart is the default and puts the message's " +
                        "leading column on the panel's right-hand edge in frame 0.",
                )
            }
        }

        val fullTraverse = if (lastLit >= startColumn) (lastLit - startColumn) / step + 1 else 1
        val askedFrames = when (val a = intArg(args, ARG_FRAMES)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        }
        val frameCount = askedFrames ?: fullTraverse
        if (frameCount < 1) {
            return failure("\"$ARG_FRAMES\" is $frameCount.") {
                put("expected", "At least 1, or null for the full traverse ($fullTraverse frames from here).")
            }
        }
        if (frameCount > DesignCodec.MAX_FRAMES) {
            return failure("That would be $frameCount frames.") {
                put(
                    "expected",
                    "At most ${DesignCodec.MAX_FRAMES} per panel. Scroll a shorter message, or raise " +
                        "\"$ARG_STEP\" — 2 columns per frame halves the count and still reads.",
                )
            }
        }

        val durationMs = when (val a = intArg(args, ARG_DURATION_MS)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: DEFAULT_FRAME_DURATION_MS
        if (durationMs < DesignCodec.MIN_DURATION_MS || durationMs > DesignCodec.MAX_DURATION_MS) {
            return failure("\"$ARG_DURATION_MS\" is $durationMs.") {
                put(
                    "expected",
                    "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} inclusive (out of " +
                        "range is rejected, not clamped). 80-200 ms reads well for a scroll.",
                )
            }
        }

        val frames = ArrayList<DesignFrame>(frameCount)
        for (n in 0 until frameCount) {
            val offset = startColumn + n * step
            val cells = CharArray(size * size) { '0' }
            for (r in 0 until height) {
                val row = rows[r]
                val base = (topRow + r) * size
                for (x in 0 until size) {
                    val sourceColumn = offset + x
                    if (sourceColumn in 0 until width) cells[base + x] = row[sourceColumn]
                }
            }
            frames.add(DesignFrame(durationMs, String(cells)))
        }

        val probe = ctx.design.copy(
            kind = DesignKind.DYNAMIC,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frames this produced would not be accepted: ${checked.reason}")
        }

        val warnings = scrollWarnings(
            frames = frames,
            defaultStart = defaultStart,
            startColumn = startColumn,
            step = step,
            frameCount = frameCount,
            fullTraverse = fullTraverse,
            lastLit = lastLit,
            rows = rows,
            topRow = topRow,
            band = band,
            codename = codename,
        )

        val document = buildJsonObject {
            put("kind", "dynamic")
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        for (frame in frames) {
                            add(
                                buildJsonObject {
                                    put("durationMs", frame.durationMs)
                                    put("cells", frame.cells)
                                },
                            )
                        }
                    }
                }
            }
        }.toString()

        val shown = minOf(frameCount, MAX_SCROLL_PREVIEW_FRAMES)
        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. Read the pictures below against each other — the glyph must " +
                        "move by exactly $step column${if (step == 1) "" else "s"} per frame and never " +
                        "change row — then send \"$KEY_APPLY_THIS\" to $APPLY_DESIGN EXACTLY as it came " +
                        "back. Do not retype the cells and do not rebuild the frames by hand.",
                )
                put("variant", codename.codename)
                put("panel_width", size)
                putJsonObject("source") {
                    put("width", width)
                    put("height", height)
                    put("first_lit_column", firstLit)
                    put("last_lit_column", lastLit)
                }
                put(ARG_TOP_ROW, topRow)
                put(ARG_START_COLUMN, startColumn)
                put(ARG_STEP, step)
                put("frame_count", frameCount)
                put(ARG_DURATION_MS, durationMs)
                put(
                    "frame_count_note",
                    if (askedFrames == null) {
                        "The full traverse: panel width + source width - 1 = $size + $width - 1 = " +
                            "${size + width - 1} columns of travel, $frameCount frames at $step per frame."
                    } else {
                        "You asked for $frameCount. The full traverse from column $startColumn at $step " +
                            "per frame is $fullTraverse frames."
                    },
                )
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                if (shown < frameCount) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(KEY_APPLY_THIS, document)
                putJsonArray("frames") {
                    for (i in frames.indices) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", frames[i].durationMs)
                                put("source_column_at_panel_left", startColumn + i * step)
                                if (i < shown) {
                                    val preview = GlyphAsciiPreview.renderCells(frames[i].cells, levels, codename)
                                    put(
                                        "preview",
                                        preview?.let { JsonPrimitive(it) }
                                            ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                    )
                                }
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    private sealed interface Chosen {
        data class Ok(val codename: PokemonCodename) : Chosen
        data class Bad(val result: GlyphToolResult) : Chosen
    }

    private fun chooseVariant(
        args: JsonObject,
        ctx: GlyphToolContext,
        allowed: List<PokemonCodename>,
    ): Chosen {
        val raw = args[ARG_VARIANT]
        if (raw == null || raw is JsonNull) {
            val implied = ctx.openVariant?.takeIf { allowed.contains(it) } ?: allowed.singleOrNull()
            return implied?.let { Chosen.Ok(it) } ?: Chosen.Bad(
                failure(
                    "This design carries ${allowed.size} panels and none of them is open, so \"$ARG_VARIANT\" " +
                        "cannot be left null.",
                ) { putAllowed(allowed) },
            )
        }
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Chosen.Bad(failure("\"$ARG_VARIANT\" is not a panel codename.") { putAllowed(allowed) })
        val codename = PokemonCodename.ofCodename(text)
            ?: return Chosen.Bad(failure("There is no panel called \"$text\".") { putAllowed(allowed) })
        if (!allowed.contains(codename)) {
            return Chosen.Bad(
                failure(
                    "This design carries no \"$text\" artwork, so you cannot write it. " +
                        "Only the user can add a panel to a design, from the editor.",
                ) { putAllowed(allowed) },
            )
        }
        return Chosen.Ok(codename)
    }

    private sealed interface Rows {
        data class Ok(val rows: List<String>) : Rows
        data class Bad(val result: GlyphToolResult) : Rows
    }

    private fun sourceRows(args: JsonObject): Rows {
        val raw = args[ARG_SOURCE_ROWS]
            ?: return Rows.Bad(
                failure("Missing the \"$ARG_SOURCE_ROWS\" argument.") { put("expected", SOURCE_ROWS_EXPECTED) },
            )
        val rows: List<String> = when {
            raw is JsonArray -> {
                val out = ArrayList<String>(raw.size)
                for (i in raw.indices) {
                    val text = (raw[i] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return Rows.Bad(
                            failure("\"$ARG_SOURCE_ROWS\" entry $i is not a string.") {
                                put("expected", SOURCE_ROWS_EXPECTED)
                            },
                        )
                    out.add(text)
                }
                out
            }

            raw is JsonPrimitive && raw.isString -> raw.content.trim().lines().map { it.trim() }

            else -> return Rows.Bad(
                failure("\"$ARG_SOURCE_ROWS\" is neither an array of strings nor one string.") {
                    put("expected", SOURCE_ROWS_EXPECTED)
                },
            )
        }
        if (rows.isEmpty() || rows.all { it.isEmpty() }) {
            return Rows.Bad(
                failure("\"$ARG_SOURCE_ROWS\" is empty.") { put("expected", SOURCE_ROWS_EXPECTED) },
            )
        }
        return Rows.Ok(rows)
    }

    private fun scrollWarnings(
        frames: List<DesignFrame>,
        defaultStart: Int,
        startColumn: Int,
        step: Int,
        frameCount: Int,
        fullTraverse: Int,
        lastLit: Int,
        rows: List<String>,
        topRow: Int,
        band: IntRange?,
        codename: PokemonCodename,
    ): List<String> {
        val warnings = ArrayList<String>(3)

        val blank = frames.indices.filter { i -> frames[i].cells.all { it == '0' } }
        if (blank.isNotEmpty()) {
            warnings.add(
                buildString {
                    append(blank.size)
                    append(" frame")
                    if (blank.size != 1) append("s")
                    append(" (")
                    append(blank.take(10).joinToString(", "))
                    if (blank.size > 10) append(", …")
                    append(if (blank.size == 1) ") is" else ") are")
                    append(" completely blank, because the window is off the message there. That is a ")
                    append("beat of darkness on the panel")
                    if (blank.first() == 0) {
                        append(", and a blank frame 0 means the design opens by showing an empty panel")
                    }
                    append(". Leave \"")
                    append(ARG_START_COLUMN)
                    append("\" null — it would be ")
                    append(defaultStart)
                    append(", which puts the message's leading column on the panel in frame 0.")
                },
            )
        }

        val clipped = rows.indices.filter { r ->
            rows[r].any { base36(it) > 0 } && (band == null || (topRow + r) !in band)
        }
        if (clipped.isNotEmpty()) {
            val panelRows = clipped.map { topRow + it }
            warnings.add(
                "Panel row${if (panelRows.size == 1) "" else "s"} " +
                    "${panelRows.joinToString(", ")} carr${if (panelRows.size == 1) "ies" else "y"} lit " +
                    "cells but ${if (panelRows.size == 1) "is" else "are"} not live across every column of " +
                    "${codename.codename}, so those cells WILL be clipped by the disc as the art scrolls — " +
                    "at the start and the end of the travel, not in the middle, which is why no single " +
                    "frame shows it. " + scrollHeightAdvice(codename),
            )
        }

        if (startColumn + (frameCount - 1) * step < lastLit) {
            warnings.add(
                "The last frame still has the message crossing the panel, so this scroll stops rather than " +
                    "finishes. A scroll cut short does not look like a shorter scroll, it looks broken. " +
                    "The full traverse from here is $fullTraverse frames; leave \"$ARG_FRAMES\" null for it.",
            )
        }
        return warnings
    }

    private fun scrollHeightAdvice(codename: PokemonCodename): String {
        val band = GlyphAiPrompt.fullWidthRows(codename.size)
            ?: return "${codename.codename} has no row that is live across every column."
        return "Rows ${band.first} to ${band.last} are the only rows of ${codename.codename} live across " +
            "all ${codename.size} columns, so a source ${band.count()} rows tall or shorter, placed in " +
            "that band, keeps every cell at every horizontal offset."
    }

    private const val SOURCE_ROWS_EXPECTED: String =
        "An array of equal-length strings, one per row of the message, in the same base36 palette-index " +
            "encoding as cells — the WHOLE message drawn once, as wide as it needs to be. Row 0 is the " +
            "top row. \"HI\" is [\"1010111\", \"1010010\", \"1110010\", \"1010010\", \"1010111\"]."

    private sealed interface IntArg {
        data class Ok(val value: Int?) : IntArg
        data class Bad(val result: GlyphToolResult) : IntArg
    }

    private fun intArg(args: JsonObject, key: String): IntArg {
        val raw = args[key] ?: return IntArg.Ok(null)
        if (raw is JsonNull) return IntArg.Ok(null)
        val value = (raw as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return IntArg.Bad(
                failure("\"$key\" is not a whole number.") {
                    put("expected", "An integer, or null to take the default.")
                },
            )
        return IntArg.Ok(value)
    }

    private sealed interface DoubleArg {
        data class Ok(val value: Double?) : DoubleArg
        data class Bad(val result: GlyphToolResult) : DoubleArg
    }

    private fun doubleArg(args: JsonObject, key: String): DoubleArg {
        val raw = args[key] ?: return DoubleArg.Ok(null)
        if (raw is JsonNull) return DoubleArg.Ok(null)
        val value = (raw as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return DoubleArg.Bad(
                failure("\"$key\" is not a number.") {
                    put("expected", "A number, or null to take the default.")
                },
            )
        if (!value.isFinite()) {
            return DoubleArg.Bad(
                failure("\"$key\" is $value.") { put("expected", "A finite number, or null.") },
            )
        }
        return DoubleArg.Ok(value)
    }

    private sealed interface BoolArg {
        data class Ok(val value: Boolean?) : BoolArg
        data class Bad(val result: GlyphToolResult) : BoolArg
    }

    private fun boolArg(args: JsonObject, key: String): BoolArg {
        val raw = args[key] ?: return BoolArg.Ok(null)
        if (raw is JsonNull) return BoolArg.Ok(null)
        val value = when ((raw as? JsonPrimitive)?.content) {
            "true" -> true
            "false" -> false
            else -> return BoolArg.Bad(
                failure("\"$key\" is not true or false.") {
                    put("expected", "true, false, or null to take the default.")
                },
            )
        }
        return BoolArg.Ok(value)
    }

    private fun imageToGrid(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_IMAGE_INDEX\": 0, \"$ARG_THRESHOLD\": null, \"$ARG_CONTRAST\": null, \"$ARG_INVERT\": null}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }
        if (levels.size < 2) {
            return failure(
                "This design's \"levels\" has one entry, so every cell it can address is palette index 0 " +
                    "and the whole picture would be off.",
            ) {
                put(
                    "expected",
                    "A palette with something lit in it — [0, 4095] for pure on/off, [0, 2048, 4095] for " +
                        "one grey. Set it with $APPLY_DESIGN first.",
                )
            }
        }

        if (ctx.images.isEmpty()) {
            return failure("No image is attached to the message you are answering.") {
                put(
                    "expected",
                    "Ask the user to attach the picture to their next message. An attachment only travels " +
                        "with the message it was sent on, so a photo from an earlier turn is not available " +
                        "here — and you must not draw one from memory.",
                )
            }
        }
        val index = when (val a = intArg(args, ARG_IMAGE_INDEX)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: 0
        if (index < 0 || index >= ctx.images.size) {
            return failure(
                "There is no image $index on this message: ${ctx.images.size} " +
                    "${if (ctx.images.size == 1) "image was" else "images were"} attached.",
            ) {
                put(
                    "expected",
                    "0 to ${ctx.images.size - 1}, counting in the order they were attached. null means 0, " +
                        "the first one.",
                )
            }
        }
        val image = ctx.images[index]

        val threshold = when (val a = doubleArg(args, ARG_THRESHOLD)) {
            is DoubleArg.Bad -> return a.result
            is DoubleArg.Ok -> a.value
        }
        if (threshold != null && (threshold < 0.0 || threshold > ImageQuantiser.MAX_THRESHOLD)) {
            return failure("\"$ARG_THRESHOLD\" is $threshold.") {
                put(
                    "expected",
                    "0.0 to ${ImageQuantiser.MAX_THRESHOLD}, or null to have it chosen for you — which is " +
                        "usually better, because it is picked from this image's own histogram. Higher keeps " +
                        "only the brightest cells; lower lights more of the picture.",
                )
            }
        }
        val contrast = when (val a = doubleArg(args, ARG_CONTRAST)) {
            is DoubleArg.Bad -> return a.result
            is DoubleArg.Ok -> a.value
        } ?: ImageQuantiser.DEFAULT_CONTRAST
        if (contrast < ImageQuantiser.MIN_CONTRAST || contrast > ImageQuantiser.MAX_CONTRAST) {
            return failure("\"$ARG_CONTRAST\" is $contrast.") {
                put(
                    "expected",
                    "${ImageQuantiser.MIN_CONTRAST} to ${ImageQuantiser.MAX_CONTRAST}. " +
                        "${ImageQuantiser.DEFAULT_CONTRAST} leaves the image as it is; above it pushes light " +
                        "and dark apart, which is what a flat-looking photo needs.",
                )
            }
        }
        val invert = when (val a = boolArg(args, ARG_INVERT)) {
            is BoolArg.Bad -> return a.result
            is BoolArg.Ok -> a.value
        } ?: false

        val quantised = ImageQuantiser.quantise(
            image = image,
            size = size,
            levelCount = levels.size,
            threshold = threshold,
            contrast = contrast,
            invert = invert,
        )
        val done = when (quantised) {
            is ImageQuantiser.Result.Unusable -> return failure(
                "That image could not be read: it has no pixels this app can measure.",
            ) { put("expected", "Ask the user for a different picture.") }

            is ImageQuantiser.Result.Flat -> return failure(
                "That image is almost a flat field — its brightest and darkest cell differ by only " +
                    "${quantised.range} of 255 — so there is no picture in it to draw.",
            ) {
                put(
                    "expected",
                    "Nothing was produced, deliberately: stretching that would light cells at random and " +
                        "call it art. Either the wrong image was attached, or the subject fills so little of " +
                        "the frame that it disappeared. Ask the user for a closer or higher-contrast photo, " +
                        "or draw the thing yourself.",
                )
            }

            is ImageQuantiser.Result.Ok -> quantised
        }

        val frame = DesignFrame(DEFAULT_FRAME_DURATION_MS, done.cells)

        val probe = ctx.design.copy(variants = mapOf(codename.codename to DesignVariant(listOf(frame))))
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frame this produced would not be accepted: ${checked.reason}")
        }

        val warnings = imageWarnings(done, invert, contrast)
        val document = buildJsonObject {
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        add(
                            buildJsonObject {
                                put("durationMs", frame.durationMs)
                                put("cells", frame.cells)
                            },
                        )
                    }
                }
            }
        }.toString()

        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. This is the photograph itself, downscaled to the panel and " +
                        "masked to the disc — a literal conversion, not a drawing. LOOK AT THE PICTURE " +
                        "BELOW: if it reads as the thing it is meant to be, send \"$KEY_APPLY_THIS\" to " +
                        "$APPLY_DESIGN as it came back, or put its \"cells\" into one frame with $SET_FRAMES. " +
                        "If it does not read, adjust \"$ARG_THRESHOLD\", \"$ARG_CONTRAST\" or " +
                        "\"$ARG_INVERT\" and call again — or give up on the literal version and draw the " +
                        "silhouette yourself, which at this size is often the better answer.",
                )
                put("variant", codename.codename)
                put("size", size)
                putJsonObject("image") {
                    put("index", index)
                    put("attached", ctx.images.size)
                    put("width", image.width)
                    put("height", image.height)
                }
                put(ARG_THRESHOLD, done.threshold)
                put(
                    "threshold_note",
                    if (done.automatic) {
                        "Chosen from this image's own histogram (the cut that best separates its light and " +
                            "dark cells). Pass it back as \"$ARG_THRESHOLD\" to reproduce this exactly, or " +
                            "nudge it up to keep less and down to keep more."
                    } else {
                        "The value you asked for. Leave \"$ARG_THRESHOLD\" null to have it chosen from the " +
                            "image's own histogram."
                    },
                )
                put(ARG_CONTRAST, contrast)
                put(ARG_INVERT, invert)
                putJsonArray("levels") { levels.forEach { add(it) } }
                put("lit_cells", done.lit)
                put("sampled_cells", done.sampled)
                put("live_leds", PanelMask.count(size))
                put(
                    "framing_note",
                    "The whole image was scaled to fit and centred, aspect ratio kept, so nothing was " +
                        "cropped away — a picture that is not square leaves dark cells at two edges.",
                )
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                put("cells", done.cells)
                put(KEY_APPLY_THIS, document)
                put(
                    "preview",
                    GlyphAsciiPreview.renderCells(done.cells, levels, codename)
                        ?.let { JsonPrimitive(it) }
                        ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                )
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    private fun imageWarnings(
        done: ImageQuantiser.Result.Ok,
        invert: Boolean,
        contrast: Double,
    ): List<String> {
        val warnings = ArrayList<String>(2)
        val lit = done.lit
        val sampled = done.sampled.coerceAtLeast(1)
        if (lit == 0) {
            warnings.add(
                "Every cell came out off, so this frame is blank. \"$ARG_THRESHOLD\" " +
                    "${round2(done.threshold)} is above everything in the picture: lower it, or leave it " +
                    "null to have it chosen from the image itself.",
            )
        } else if (lit * 100 / sampled >= MOSTLY_LIT_PERCENT) {
            warnings.add(
                "$lit of the $sampled cells the picture covers are lit — nearly all of them — so the art " +
                    "has no outline and will read as a bright blob. Raise \"$ARG_THRESHOLD\" above " +
                    "${round2(done.threshold)}" +
                    (if (invert) ", or drop \"$ARG_INVERT\": a light background inverts to a lit panel." else ".") +
                    " Raising \"$ARG_CONTRAST\" above $contrast separates the subject further.",
            )
        } else if (lit * 100 / sampled <= BARELY_LIT_PERCENT) {
            warnings.add(
                "Only $lit of the $sampled cells the picture covers are lit, so almost nothing will be " +
                    "visible. Lower \"$ARG_THRESHOLD\" below ${round2(done.threshold)}" +
                    (if (invert) "." else ", or set \"$ARG_INVERT\" true if the subject is dark on a light background.") +
                    " Raising \"$ARG_CONTRAST\" above $contrast also helps a flat photograph.",
            )
        }
        return warnings
    }

    private const val MOSTLY_LIT_PERCENT = 90

    private const val BARELY_LIT_PERCENT = 4

    private fun round2(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private fun setFrames(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_MODE\": \"$MODE_REPLACE\", \"$ARG_AT\": 0, \"$ARG_FRAME_LIST\": [{\"durationMs\": 120, \"cells\": \"…\"}]}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val existing = ctx.design.variantFor(codename)?.frames.orEmpty()

        val modeRaw = args[ARG_MODE]
        if (modeRaw == null || modeRaw is JsonNull) {
            return failure("Missing the \"$ARG_MODE\" argument.") { putModes() }
        }
        val mode = (modeRaw as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: return failure("\"$ARG_MODE\" is not one of the three modes.") { putModes() }
        if (mode != MODE_REPLACE && mode != MODE_INSERT && mode != MODE_DELETE) {
            return failure("There is no \"$mode\" mode.") { putModes() }
        }

        val at = when (val a = intArg(args, ARG_AT)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: return failure("Missing the \"$ARG_AT\" argument.") {
            put(
                "expected",
                "The frame index the change starts at, counting from 0. " +
                    "${codename.codename} has ${existing.size} " +
                    "frame${if (existing.size == 1) "" else "s"} right now" +
                    if (existing.isEmpty()) "." else " (0 to ${existing.size - 1}).",
            )
        }

        val count = when (val a = intArg(args, ARG_COUNT)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        }
        val supplied = args[ARG_FRAME_LIST]?.takeIf { it !is JsonNull }

        if (mode == MODE_DELETE) {
            if (supplied != null) {
                return failure("\"$ARG_MODE\" is \"$MODE_DELETE\" but you also sent \"$ARG_FRAME_LIST\".") {
                    put(
                        "expected",
                        "A delete takes \"$ARG_AT\" and \"$ARG_COUNT\" and nothing else. To swap frames for " +
                            "different ones, use \"$MODE_REPLACE\".",
                    )
                }
            }
        } else if (count != null) {
            return failure("\"$ARG_COUNT\" only applies to \"$MODE_DELETE\", and \"$ARG_MODE\" is \"$mode\".") {
                put(
                    "expected",
                    "For \"$MODE_REPLACE\" and \"$MODE_INSERT\" the number of frames is however many you put " +
                        "in \"$ARG_FRAME_LIST\". Send \"$ARG_COUNT\" as null.",
                )
            }
        }

        val incoming: List<DesignFrame> = if (mode == MODE_DELETE) {
            emptyList()
        } else {
            when (val read = readFrames(supplied, codename, mode)) {
                is Frames.Bad -> return read.result
                is Frames.Ok -> read.frames
            }
        }

        val removed: Int
        when (mode) {
            MODE_REPLACE -> {
                if (existing.isEmpty()) {
                    return failure("${codename.codename} has no frames yet, so there is nothing to replace.") {
                        put("expected", "Use \"$MODE_INSERT\" with \"$ARG_AT\" 0 to put the first frames in.")
                    }
                }
                if (at < 0 || at > existing.size - 1) {
                    return failure(outOfRange(at, codename, existing.size))
                }
                if (at + incoming.size > existing.size) {
                    return failure(
                        "Replacing ${incoming.size} frame${if (incoming.size == 1) "" else "s"} from index " +
                            "$at would need frames $at to ${at + incoming.size - 1}, but ${codename.codename} " +
                            "only has ${existing.size} (0 to ${existing.size - 1}).",
                    ) {
                        put(
                            "expected",
                            "Replace at most ${existing.size - at} frame${if (existing.size - at == 1) "" else "s"} " +
                                "from index $at, or use \"$MODE_INSERT\" to add frames past the end.",
                        )
                    }
                }
                removed = incoming.size
            }

            MODE_INSERT -> {
                if (at < 0 || at > existing.size) {
                    return failure(
                        "\"$ARG_AT\" $at is outside ${codename.codename}, which has ${existing.size} " +
                            "frame${if (existing.size == 1) "" else "s"}.",
                    ) {
                        put(
                            "expected",
                            "0 to ${existing.size} for an insert: the new frames go BEFORE the frame that is " +
                                "at that index now, and ${existing.size} appends them at the end.",
                        )
                    }
                }
                removed = 0
            }

            else -> {
                if (existing.isEmpty()) {
                    return failure("${codename.codename} has no frames, so there is nothing to delete.")
                }
                if (at < 0 || at > existing.size - 1) {
                    return failure(outOfRange(at, codename, existing.size))
                }
                val asked = count ?: 1
                if (asked < 1) {
                    return failure("\"$ARG_COUNT\" is $asked.") {
                        put("expected", "At least 1, or null to delete the single frame at \"$ARG_AT\".")
                    }
                }
                if (at + asked > existing.size) {
                    return failure(
                        "Deleting $asked frames from index $at would run past the end: ${codename.codename} " +
                            "has ${existing.size} (0 to ${existing.size - 1}).",
                    ) {
                        put("expected", "At most ${existing.size - at} from index $at.")
                    }
                }
                removed = asked
            }
        }

        val updated = ArrayList<DesignFrame>(existing.size - removed + incoming.size)
        updated.addAll(existing.subList(0, at))
        updated.addAll(incoming)
        updated.addAll(existing.subList(at + removed, existing.size))

        if (updated.size > DesignCodec.MAX_FRAMES) {
            return failure(
                "That would leave ${codename.codename} with ${updated.size} frames: ${existing.size} now, " +
                    "$removed removed, ${incoming.size} added.",
            ) {
                put(
                    "expected",
                    "At most ${DesignCodec.MAX_FRAMES} per panel. Add at most " +
                        "${DesignCodec.MAX_FRAMES - existing.size + removed} here, or delete some frames first.",
                )
            }
        }

        var merged = ctx.design.copy(
            variants = ctx.design.variants + (codename.codename to DesignVariant(updated)),
        )
        val promoted = merged.kind == DesignKind.STATIC && updated.size > 1
        if (promoted) merged = merged.copy(kind = DesignKind.DYNAMIC)

        precisely(merged, ctx)?.let { return it.result }
        val design = when (val result = DesignCodec.validate(merged)) {
            is DesignCodec.Result.Ok -> result.design
            is DesignCodec.Result.Invalid -> return failure(result.reason)
        }

        val warnings = ArrayList<String>(2)
        if (updated.isEmpty()) {
            warnings.add(
                "${codename.codename} now has NO frames at all, so that panel will show nothing. If that " +
                    "was not the intention, put a frame back with \"$MODE_INSERT\".",
            )
        }
        if (promoted) {
            warnings.add(
                "This design was \"static\", which may hold only one frame, so it is now \"dynamic\" — " +
                    "otherwise ${updated.size} frames could not be stored. Tell the user; it changes how " +
                    "the design plays.",
            )
        }

        val from = (at - 1).coerceAtLeast(0)
        val to = (at + incoming.size).coerceAtMost(updated.size - 1)
        val shown = if (updated.isEmpty()) 0 else minOf(to - from + 1, MAX_PREVIEW_FRAMES)

        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", true)
                put(
                    "note",
                    "This is on the user's canvas now, and ONLY the frames listed below were touched — every " +
                        "other frame of ${codename.codename} is byte for byte what it was. Read the pictures: " +
                        "they include the frame either side of the change, so you can see that the animation " +
                        "still joins up.",
                )
                put("variant", codename.codename)
                put(ARG_MODE, mode)
                put(ARG_AT, at)
                put("removed", removed)
                put("inserted", incoming.size)
                put("frame_count_before", existing.size)
                put("frame_count_after", updated.size)
                put("kind", kindName(design.kind))
                if (promoted) put("kind_changed", true)
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                putJsonArray("frames") {
                    for (i in from until from + shown) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", updated[i].durationMs)
                                put("changed", i >= at && i < at + incoming.size)
                                put(
                                    "preview",
                                    GlyphAsciiPreview.renderCells(updated[i].cells, design.levels, codename)
                                        ?.let { JsonPrimitive(it) }
                                        ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                )
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
            design = design,
        )
    }

    private fun outOfRange(at: Int, codename: PokemonCodename, size: Int): String =
        "\"$ARG_AT\" $at is outside ${codename.codename}, which has $size " +
            "frame${if (size == 1) "" else "s"} (0 to ${size - 1})."

    private fun JsonObjectBuilder.putModes() {
        putJsonArray("modes") {
            add(MODE_REPLACE)
            add(MODE_INSERT)
            add(MODE_DELETE)
        }
        put(
            "expected",
            "\"$MODE_REPLACE\" swaps the frames starting at \"$ARG_AT\" for the ones you send, " +
                "\"$MODE_INSERT\" adds yours before the frame at \"$ARG_AT\" without removing anything, " +
                "and \"$MODE_DELETE\" removes \"$ARG_COUNT\" frames from \"$ARG_AT\".",
        )
    }

    private sealed interface Frames {
        data class Ok(val frames: List<DesignFrame>) : Frames
        data class Bad(val result: GlyphToolResult) : Frames
    }

    private fun readFrames(raw: JsonElement?, codename: PokemonCodename, mode: String): Frames {
        if (raw == null) {
            return Frames.Bad(
                failure("\"$ARG_MODE\" is \"$mode\" but there are no frames to write.") {
                    put("expected", framesExpected(codename))
                },
            )
        }
        if (raw !is JsonArray) {
            return Frames.Bad(
                failure("\"$ARG_FRAME_LIST\" is not an array.") { put("expected", framesExpected(codename)) },
            )
        }
        if (raw.isEmpty()) {
            return Frames.Bad(
                failure("\"$ARG_FRAME_LIST\" is empty, so a \"$mode\" would change nothing.") {
                    put(
                        "expected",
                        if (mode == MODE_REPLACE) {
                            "At least one frame. To remove frames rather than change them, use \"$MODE_DELETE\"."
                        } else {
                            framesExpected(codename)
                        },
                    )
                },
            )
        }
        val out = ArrayList<DesignFrame>(raw.size)
        for (i in raw.indices) {
            val entry = raw[i]
            when {
                entry is JsonPrimitive && entry.isString ->
                    out.add(DesignFrame(DEFAULT_FRAME_DURATION_MS, entry.content))

                entry is JsonObject -> {
                    val cells = (entry["cells"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return Frames.Bad(
                            failure("\"$ARG_FRAME_LIST\" entry $i has no \"cells\" string.") {
                                put("expected", framesExpected(codename))
                            },
                        )
                    val duration = when (val d = entry["durationMs"]) {
                        null, is JsonNull -> DEFAULT_FRAME_DURATION_MS
                        else -> (d as? JsonPrimitive)?.content?.toIntOrNull()
                            ?: return Frames.Bad(
                                failure("\"$ARG_FRAME_LIST\" entry $i has a durationMs that is not a whole number.") {
                                    put(
                                        "expected",
                                        "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} " +
                                            "milliseconds, or leave it out for $DEFAULT_FRAME_DURATION_MS.",
                                    )
                                },
                            )
                    }
                    out.add(DesignFrame(duration, cells))
                }

                else -> return Frames.Bad(
                    failure("\"$ARG_FRAME_LIST\" entry $i is neither a frame object nor a cells string.") {
                        put("expected", framesExpected(codename))
                    },
                )
            }
        }
        return Frames.Ok(out)
    }

    private fun framesExpected(codename: PokemonCodename): String =
        "An array of {\"durationMs\": <ms>, \"cells\": \"<${codename.cellCount} base36 characters>\"} — or " +
            "just the cells string on its own, which takes $DEFAULT_FRAME_DURATION_MS ms. " +
            "${codename.codename} is ${codename.size}x${codename.size}, so every cells string is exactly " +
            "${codename.cellCount} characters, row-major, corners included."

    private sealed interface Prepared {
        data class Ok(val design: Design) : Prepared
        data class Bad(val result: GlyphToolResult) : Prepared
    }

    private fun prepare(arguments: String, ctx: GlyphToolContext): Prepared {
        val args = parseObject(arguments)
            ?: return bad(
                "The tool arguments were not a JSON object.",
            ) { put("expected", "{\"$ARG_DESIGN\": \"<the whole glyph.design document as JSON text>\"}") }

        val raw = args[ARG_DESIGN]
            ?: return bad("Missing the \"$ARG_DESIGN\" argument.") {
                put("expected", "The complete glyph.design document, as JSON text.")
            }

        val root: JsonObject = when {
            raw is JsonPrimitive && raw.isString -> {
                val text = raw.content
                if (text.length > DesignCodec.MAX_CHARS) {
                    return bad("That document is ${text.length} characters.") {
                        put("expected", "At most ${DesignCodec.MAX_CHARS} characters.")
                    }
                }
                parseObject(text) ?: return bad(
                    "The \"$ARG_DESIGN\" argument is not valid JSON.",
                ) { put("expected", "A single JSON object: the whole glyph.design document.") }
            }

            raw is JsonObject -> raw

            else -> return bad("The \"$ARG_DESIGN\" argument is neither JSON text nor a JSON object.") {
                put("expected", "The complete glyph.design document, as JSON text.")
            }
        }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return bad("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val written = root[DesignKey.VARIANTS]
        if (written != null && written !is JsonObject) {
            return bad("\"variants\" is not a JSON object.") {
                put("expected", "An object keyed by panel codename, e.g. {\"${allowed.first().codename}\": {\"frames\": []}}.")
            }
        }
        val writtenVariants = (written as? JsonObject).orEmpty()
        for (key in writtenVariants.keys) {
            val codename = PokemonCodename.ofCodename(key)
            if (codename == null) {
                return bad(
                    "There is no panel called \"$key\".",
                ) { putAllowed(allowed) }
            }
            if (!allowed.contains(codename)) {
                return bad(
                    "This design carries no \"$key\" artwork, so you cannot write it. " +
                        "Only the user can add a panel to a design, from the editor.",
                ) { putAllowed(allowed) }
            }
        }

        val decoded: Design = try {
            LENIENT.decodeFromJsonElement(Design.serializer(), root)
        } catch (e: Exception) {
            return bad("A field of the document has the wrong type (${e.message ?: e.javaClass.simpleName}).") {
                put(
                    "expected",
                    "levels is an array of integers, loop is a boolean, frames is an array of " +
                        "{durationMs, cells}, durationMs is an integer and cells is a string.",
                )
            }
        }

        val merged = ctx.design.copy(
            format = DESIGN_FORMAT,
            name = if (root.supplies(DesignKey.NAME)) decoded.name else ctx.design.name,
            kind = if (root.supplies(DesignKey.KIND)) decoded.kind else ctx.design.kind,
            keyMode = if (root.supplies(DesignKey.KEY_MODE)) decoded.keyMode else ctx.design.keyMode,
            loop = if (root.supplies(DesignKey.LOOP)) decoded.loop else ctx.design.loop,
            levels = if (root.supplies(DesignKey.LEVELS)) decoded.levels else ctx.design.levels,
            variants = ctx.design.variants + writtenVariants.keys.associateWith {
                decoded.variants[it] ?: DesignVariant()
            },
        )

        precisely(merged, ctx)?.let { return it }

        return when (val result = DesignCodec.validate(merged)) {
            is DesignCodec.Result.Ok -> Prepared.Ok(result.design)
            is DesignCodec.Result.Invalid -> bad(result.reason)
        }
    }

    private fun precisely(design: Design, ctx: GlyphToolContext): Prepared.Bad? {
        if (design.name.length > DesignCodec.MAX_NAME_LENGTH) {
            return bad("The name is ${design.name.length} characters.") {
                put("expected", "At most ${DesignCodec.MAX_NAME_LENGTH} characters.")
            }
        }
        if (design.levels.isEmpty()) {
            return bad("\"levels\" is empty, so no cell could mean anything.") {
                put("expected", "At least one brightness, e.g. [0, 2048, 4095].")
            }
        }
        if (design.levels.size > DesignFrames.MAX_PALETTE) {
            return bad("\"levels\" has ${design.levels.size} entries.") {
                put("expected", "At most ${DesignFrames.MAX_PALETTE} — one base36 character addresses no more.")
            }
        }

        for (codename in ctx.allowedVariants) {
            val frames = design.variantFor(codename)?.frames ?: continue
            val where = "variants.${codename.codename}"

            if (frames.size > DesignCodec.MAX_FRAMES) {
                return bad("$where has ${frames.size} frames.") {
                    put("expected", "At most ${DesignCodec.MAX_FRAMES} frames per panel.")
                }
            }
            if (design.kind == DesignKind.STATIC && frames.size > 1) {
                return bad(
                    "\"kind\" is \"static\" but $where has ${frames.size} frames. A static design is " +
                        "exactly one frame, and the rest would never be shown.",
                ) {
                    put("expected", "Either set \"kind\" to \"dynamic\", or send a single frame.")
                }
            }

            for ((index, frame) in frames.withIndex()) {
                if (frame.durationMs < DesignCodec.MIN_DURATION_MS ||
                    frame.durationMs > DesignCodec.MAX_DURATION_MS
                ) {
                    return bad("$where frame $index has durationMs ${frame.durationMs}.") {
                        put(
                            "expected",
                            "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} " +
                                "inclusive (out of range is rejected, not clamped).",
                        )
                    }
                }
                if (frame.cells.length != codename.cellCount) {
                    return bad(
                        "$where frame $index has ${frame.cells.length} cells.",
                    ) {
                        put(
                            "expected",
                            "Exactly ${codename.cellCount} characters — ${codename.codename} is " +
                                "${codename.size}x${codename.size}, one base36 palette index per cell, " +
                                "row-major. The corner cells outside the disc must be present too; " +
                                "write '0' there.",
                        )
                    }
                }
                cellProblem(frame.cells, design.levels, codename)?.let { problem ->
                    return bad("$where frame $index ${problem.first}") { put("expected", problem.second) }
                }
            }
        }
        return null
    }

    private fun cellProblem(
        cells: String,
        levels: List<Int>,
        codename: PokemonCodename,
    ): Pair<String, String>? {
        val legal = legalChars(levels)
        for (i in cells.indices) {
            val c = cells[i]
            val index = base36(c)
            val at = "at position $i (column ${i % codename.size}, row ${i / codename.size})"
            if (index < 0) {
                return "uses the character '$c' $at, which is not a base36 digit." to
                    "A palette index in base36: '0'-'9' then 'a'-'z'. This design defines " +
                    "${levels.size} level${if (levels.size == 1) "" else "s"}, so the only legal " +
                    "characters are $legal."
            }
            if (index >= levels.size) {
                return "uses palette index $index ('$c') $at, but \"levels\" defines only " +
                    "${levels.size} entr${if (levels.size == 1) "y" else "ies"}." to
                    "Either use $legal, or add the level you meant to \"levels\"."
            }
        }
        return null
    }

    private fun legalChars(levels: List<Int>): String {
        val highest = levels.size - 1
        return if (highest <= 9) "'0'..'$highest'" else "'0'..'9' then 'a'..'${'a' + (highest - 10)}'"
    }

    private fun base36(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }

    private val LENIENT = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun parseObject(text: String): JsonObject? = try {
        LENIENT.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private object DesignKey {
        private val PROBE = Json { encodeDefaults = false }

        val NAME = keyOf("name") { it.copy(name = "probe") }
        val KIND = keyOf("kind") { it.copy(kind = DesignKind.DYNAMIC) }
        val KEY_MODE = keyOf("keyMode") { it.copy(keyMode = KeyMode.PLAY_ONCE) }
        val LOOP = keyOf("loop") { it.copy(loop = true) }
        val LEVELS = keyOf("levels") { it.copy(levels = listOf(1)) }
        val VARIANTS = keyOf("variants") { it.copy(variants = mapOf("probe" to DesignVariant())) }

        private fun keyOf(fallback: String, alter: (Design) -> Design): String = try {
            PROBE.encodeToJsonElement(Design.serializer(), alter(Design()))
                .jsonObject.keys.singleOrNull() ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun JsonObject.supplies(key: String): Boolean {
        val value = this[key]
        return value != null && value !is JsonNull
    }

    private fun success(
        obj: JsonObject,
        design: Design? = null,
        validated: Design? = null,
    ): GlyphToolResult =
        GlyphToolResult(json = obj.toString(), isError = false, design = design, validated = validated)

    private fun failure(message: String, extras: JsonObjectBuilder.() -> Unit = {}): GlyphToolResult =
        GlyphToolResult(
            json = buildJsonObject {
                put("ok", false)
                put("error", message)
                extras()
            }.toString(),
            isError = true,
        )

    private fun bad(message: String, extras: JsonObjectBuilder.() -> Unit = {}): Prepared.Bad =
        Prepared.Bad(failure(message, extras))

    private fun JsonObjectBuilder.putAllowed(allowed: List<PokemonCodename>) {
        putJsonArray("allowed_variants") { allowed.forEach { add(it.codename) } }
        put(
            "expected",
            "You may only write ${allowed.joinToString(" and ") { "\"${it.codename}\"" }} — " +
                "the panels this design already carries.",
        )
    }

    private fun JsonObjectBuilder.putSummary(design: Design, ctx: GlyphToolContext) {
        put("name", design.name)
        put("kind", kindName(design.kind))
        put("keyMode", keyModeName(design))
        put("loop", design.loop)
        putJsonArray("levels") { design.levels.forEach { add(it) } }
        putJsonArray("allowed_variants") { ctx.allowedVariants.forEach { add(it.codename) } }
        put("variants", variantsJson(design, ctx.allowedVariants, includeCells = false))
        put("legend", GlyphAsciiPreview.LEGEND)
    }

    private fun variantsJson(
        design: Design,
        allowed: List<PokemonCodename>,
        includeCells: Boolean,
    ): JsonObject = buildJsonObject {
        for (codename in allowed) {
            val frames = design.variantFor(codename)?.frames.orEmpty()
            putJsonObject(codename.codename) {
                put("size", codename.size)
                put("cells_length", codename.cellCount)
                put("live_leds", PanelMask.count(codename.size))
                put("frame_count", frames.size)
                val shown = minOf(frames.size, MAX_PREVIEW_FRAMES)
                if (shown < frames.size) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(
                    "frames",
                    buildJsonArray {
                        for (i in frames.indices) {
                            val frame = frames[i]
                            add(
                                buildJsonObject {
                                    put("index", i)
                                    put("durationMs", frame.durationMs)
                                    if (includeCells) put("cells", frame.cells)
                                    if (i < shown) {
                                        val preview = GlyphAsciiPreview.renderCells(
                                            frame.cells,
                                            design.levels,
                                            codename,
                                        )
                                        put(
                                            "preview",
                                            preview?.let { JsonPrimitive(it) }
                                                ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun kindName(kind: DesignKind): String =
        if (kind == DesignKind.STATIC) "static" else "dynamic"

    private fun keyModeName(design: Design): String =
        if (design.keyMode == KeyMode.PLAY_ONCE) "playOnce" else "playPause"

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private const val SPEC_GET_CURRENT_DESIGN =
        """{"type":"function","name":"get_current_design","description":"Returns the design exactly as it appears on the user's canvas right now, including edits they have not saved: its name, kind, keyMode, loop and levels, every panel it carries with each frame's cells, an ASCII rendering of each frame with the round panel mask applied, and which panel and frame the editor has open. Call this before your first edit, and again whenever the user may have drawn something since.","parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false},"strict":true}"""

    private const val SPEC_APPLY_DESIGN =
        """{"type":"function","name":"apply_design","description":"Replaces the user's design with the document you supply; it appears on their canvas immediately. Send the COMPLETE glyph.design document as JSON text - every frame you want to keep, not just the ones you changed. A panel you omit entirely is left exactly as it was, and so is any of name, kind, keyMode, loop or levels that you omit - leaving a key out means 'do not change this', never 'reset this'. You may only write panels the design already carries; you cannot add one. format, formatVersion, id, author, createdAt, createdWith and modifiedAt are managed by the app and ignored if you send them. The result contains an ASCII rendering of every frame that was applied: read it, because it is the only way to see whether your art is centred on the disc rather than clipped by it.","parameters":{"type":"object","properties":{"design":{"type":"string","description":"The complete glyph.design document, as JSON text."}},"required":["design"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_SCROLL_FRAMES =
        """{"type":"function","name":"scroll_frames","description":"Turns ONE wide bitmap into a scrolling animation, doing the windowing arithmetic for you. You draw the whole message once - as tall as your glyphs and as wide as the message - and this cuts a panel-width window out of it at each successive offset, pads the rows above and below with '0', works out how many frames the traverse takes, and returns every frame with an ASCII picture of it. USE THIS FOR ANY SCROLLING TEXT OR MOVING IMAGE. Windowing by hand is how a glyph shears apart (one row shifted a column further than the row above it), how frame 0 comes out blank, how an element changes brightness halfway through and how a marquee ends up with a third of the frames it needs; none of those four is expressible here. It changes NOTHING: read the pictures, then send the \"apply_this\" document it returns to apply_design exactly as it came back, without retyping the cells. The message scrolls right to left; to scroll it the other way, reverse the order of the frames before you apply them.","parameters":{"type":"object","properties":{"source_rows":{"type":"array","items":{"type":"string"},"description":"The whole message as ONE bitmap: equal-length strings, one per row, in the same base36 palette-index encoding as cells. Row 0 is the top row. \"HI\" is [\"1010111\",\"1010010\",\"1110010\",\"1010010\",\"1010111\"] - three columns for the H, one blank column, three for the I."},"variant":{"type":["string","null"],"description":"Which panel to build the frames for. null means the one the editor has open, or the only one the design carries."},"top_row":{"type":["integer","null"],"description":"The panel row source row 0 sits on. null centres the art in the band of rows that is live across every column, which is the only placement that keeps every cell at every horizontal offset. Art outside that band still works and is warned about."},"start_column":{"type":["integer","null"],"description":"The source column shown at the panel's LEFT edge in frame 0; negative means the message is still entering from the right. null starts so the message's leading column is already on the panel, which is what stops frame 0 being blank. A start that does produce blank frames is honoured and reported, not silently emitted."},"step":{"type":["integer","null"],"description":"Columns moved per frame. null means 1, which is smoothest. 2 halves the frame count and still reads."},"frames":{"type":["integer","null"],"description":"How many frames to generate. null means the full traverse - panel width + message width - 1 at one column per frame - which is what a marquee actually needs. Fewer stops the scroll mid-message and is warned about."},"duration_ms":{"type":["integer","null"],"description":"How long each frame is held. null means 120. 80-200 reads well for a scroll."}},"required":["source_rows","variant","top_row","start_column","step","frames","duration_ms"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_MARQUEE_TEXT =
        """{"type":"function","name":"marquee_text","description":"Scrolls a phrase right to left in the app's own full-height letterforms. USE THIS FOR ANY SCROLLING WORDS - prefer it over scroll_frames, because here you do not draw the letters at all: the app has a nine-row proportional alphabet built in, upper and lower case, so an S cannot come back looking like a 5 and a W cannot come back two columns too narrow to be a W. The letters fill the panel (9 of 13 rows at 13x13, 18 of 25 at 25x25) and the round rim cuts their tops and bottoms as they enter and leave, which is deliberate and is most of why they read as BIG. It changes NOTHING. Read \"strip\" first - it is the entire phrase as one nine-row picture, and it is the only place a wrong letter is actually visible - then send the \"apply_this\" document to apply_design EXACTLY as it came back, without retyping the cells. apply_this sets kind to dynamic and loop to true and writes one panel's frames; keyMode, levels, name and any other panel are left untouched. Around 40 characters fit inside the 240-frame limit, and a phrase that does not fit is refused with the longest prefix that does AND the step that would make the whole phrase fit, so a refusal is answerable in one move. Reach for scroll_frames instead only when the thing scrolling is a picture rather than words, or when you want letterforms of your own.","parameters":{"type":"object","properties":{"text":{"type":"string","description":"The phrase to scroll. Letters A-Z and a-z, digits 0-9, a space, and the printable ASCII symbols !\"#${'$'}%&'()*+,-./:;<=>?@[\\]^_`{|}~ . Both cases are drawn - the lower case has its own x-height, ascenders and descenders - and accents are dropped automatically (\"café\" scrolls as cafe). Leading or trailing spaces become a gap before the loop repeats. This is the only argument you have to think about."},"variant":{"type":["string","null"],"description":"Which panel to build the frames for. null means the one the editor has open, or the only one the design carries."},"scale":{"type":["integer","null"],"description":"How many panel cells one letter cell becomes. null means 1 at 13x13 and 2 at 25x25, so the letters fill the same fraction of either panel. Lowering it makes the letters smaller and lets a longer phrase fit."},"step":{"type":["integer","null"],"description":"Panel columns moved per frame. null means the scale - exactly one letter-cell - which is the smoothest step that is not wasted and gives the same frame count on both panels. Doubling it halves the frame count and still reads."},"duration_ms":{"type":["integer","null"],"description":"How long each frame is held. null means 80, a little over two letters a second. Raise it to slow the scroll down; Nothing's own big-letter marquee is slower than this and reads as sluggish."},"palette_index":{"type":["integer","null"],"description":"Which entry of this design's levels the letters are lit at, the same in every frame. null means the brightest one, which is what a marquee wants. 0 is the off level and is refused."}},"required":["text","variant","scale","step","duration_ms","palette_index"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_IMAGE_TO_GRID =
        """{"type":"function","name":"image_to_grid","description":"Converts an image the user attached to THIS message into one frame of art, doing the downscaling for you: the whole picture is scaled to fit the panel with its aspect ratio kept, box-averaged down to one value per cell, masked so nothing lands on a cell that has no LED, and quantised to this design's own levels. USE THIS FOR ANY 'put this photo/logo/screenshot on my panel' REQUEST - you can see the image, but you cannot say what it averages to at cell (7, 4), and hand-writing 169 base36 characters from a photograph is how a request for a picture takes fourteen attempts. It changes NOTHING: it returns the frame, its cells and an ASCII picture of it. LOOK AT THE PICTURE. If it reads, apply the \"apply_this\" document or put its cells into a frame with set_frames; if it does not, change threshold, contrast or invert and call again, or abandon the literal conversion and draw the silhouette yourself - at this size that is often the better answer. An attachment only travels with the message it was sent on, so this cannot reach a photo from an earlier turn.","parameters":{"type":"object","properties":{"image_index":{"type":["integer","null"],"description":"Which attached image to convert, counting from 0 in the order they were attached. null means 0, the first one."},"variant":{"type":["string","null"],"description":"Which panel to convert it for. null means the one the editor has open, or the only one the design carries."},"threshold":{"type":["number","null"],"description":"Where the cut between off and lit goes, 0.0 to 0.95, after the image has been stretched onto its own darkest and brightest cell. null picks the cut that best separates this image's light and dark cells, which is usually better than a number - the value used is reported back so you can nudge it. Higher keeps only the brightest cells; lower lights more of the picture."},"contrast":{"type":["number","null"],"description":"Gain around the mid-point, 0.25 to 4.0, applied after the image is normalised. null means 1.0, which changes nothing. Above 1 pushes light and dark apart, which is what a flat or hazy photograph needs."},"invert":{"type":["boolean","null"],"description":"Swap light and dark. null means false. Set it true when the subject is DARK on a LIGHT background - a logo on white paper, a screenshot, printed text - or the panel lights the background instead of the subject."}},"required":["image_index","variant","threshold","contrast","invert"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_SET_FRAMES =
        """{"type":"function","name":"set_frames","description":"Changes a RANGE of frames in one panel and leaves every other frame untouched - use this instead of apply_design whenever you are editing part of an animation. apply_design replaces the whole document, so changing frame 7 of a 240-frame design means re-sending every frame: slow, and every re-send is a chance to corrupt a frame that was already right. This applies IMMEDIATELY, like apply_design, and is checked by exactly the same rules (cells length, base36 palette indices, frame durations, the frame limit, and the panels this design carries). It returns pictures of the frames it wrote AND of the frame either side, so you can see that the animation still joins up. A static design that ends up with more than one frame is switched to dynamic, and the result says so.","parameters":{"type":"object","properties":{"variant":{"type":["string","null"],"description":"Which panel to change. null means the one the editor has open, or the only one the design carries."},"mode":{"type":"string","enum":["replace","insert","delete"],"description":"replace: swap the frames starting at 'at' for the ones you send, one for one. insert: add yours BEFORE the frame at 'at', removing nothing; 'at' equal to the frame count appends. delete: remove 'count' frames from 'at'."},"at":{"type":"integer","description":"The frame index the change starts at, counting from 0. For insert it may equal the current frame count, which appends."},"count":{"type":["integer","null"],"description":"How many frames to delete. Only for mode delete; null means 1. Send null for replace and insert - there the number of frames is however many you put in 'frames'."},"frames":{"type":["array","null"],"items":{"type":"object","properties":{"durationMs":{"type":["integer","null"]},"cells":{"type":"string"}},"required":["durationMs","cells"],"additionalProperties":false},"description":"The frames to write, for replace and insert. Each cells string is exactly size*size base36 palette indices, row-major, corners included. durationMs may be null for 120. Send null for mode delete."}},"required":["variant","mode","at","count","frames"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_VALIDATE_DESIGN =
        """{"type":"function","name":"validate_design","description":"Runs every check apply_design runs and changes NOTHING. Same arguments, same errors, same ASCII renderings - so it is a free look at what you are about to make, and it costs the user no undo. Use it whenever you are unsure about a document, then send the identical document to apply_design.","parameters":{"type":"object","properties":{"design":{"type":"string","description":"The complete glyph.design document, as JSON text."}},"required":["design"],"additionalProperties":false},"strict":true}"""

}
