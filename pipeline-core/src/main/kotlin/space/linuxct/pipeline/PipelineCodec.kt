package space.linuxct.pipeline

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Bounded, versioned interchange. Validation never executes a program or asks a host for data. */
object PipelineCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_DECODED_PIXELS = 2_000_000
    const val MAX_JSON_NODES = 262_144
    private val json = Json { encodeDefaults = true; prettyPrint = true; classDiscriminator = "type"; ignoreUnknownKeys = false }
    sealed interface Result {
        data class Ok(val document: PipelineDocument) : Result
        data class Invalid(val diagnostics: List<Diagnostic>) : Result
    }
    fun encode(document: PipelineDocument): String = json.encodeToString(PipelineDocument.serializer(), document)
    fun decode(text: String): Result = read(text, strict = true)
    fun decodeDraft(text: String): Result = read(text, strict = false)
    fun decode(input: InputStream): Result = readStream(input, true)
    fun decodeDraft(input: InputStream): Result = readStream(input, false)
    private fun readStream(input: InputStream, strict: Boolean): Result = try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
        }
        if (output.size() > MAX_BYTES) invalid("Pipeline exceeds the 8 MiB file limit") else read(output.toString(Charsets.UTF_8.name()), strict)
    } catch (_: Exception) { invalid("Cannot read pipeline") }
    private fun invalid(message: String) = Result.Invalid(listOf(Diagnostic(message, fatal = true)))
    private fun read(text: String, strict: Boolean): Result {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return invalid("Pipeline exceeds the 8 MiB file limit")
        return try {
            preflight(text)?.let { return invalid(it) }
            val root = json.parseToJsonElement(text) as? JsonObject ?: return invalid("Expected a pipeline document")
            if ((root["format"] as? JsonPrimitive)?.contentOrNull != PIPELINE_FORMAT ||
                (root["formatVersion"] as? JsonPrimitive)?.intOrNull != PIPELINE_FORMAT_VERSION) return invalid("Unsupported pipeline format or version")
            val doc = json.decodeFromJsonElement(PipelineDocument.serializer(), root)
            val errors = validateDraft(doc).ifEmpty { if (strict) semantic(doc) else emptyList() }
            if (errors.isEmpty()) Result.Ok(doc) else Result.Invalid(errors)
        } catch (_: SerializationException) { invalid("Invalid pipeline JSON or unsupported field") }
          catch (_: IllegalArgumentException) { invalid("Invalid pipeline data") }
    }

    /** Bound allocation before the JSON parser, and reject duplicate (including escaped) object keys. */
    private fun preflight(text: String): String? {
        val objects = ArrayDeque<MutableSet<String>?>()
        var nodes = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    if (objects.size >= 112 || ++nodes > MAX_JSON_NODES) return "Pipeline JSON exceeds its complexity limit"
                    objects.addLast(if (text[index] == '{') mutableSetOf() else null)
                }
                '}', ']' -> if (objects.isNotEmpty()) objects.removeLast()
                ',' -> if (++nodes > MAX_JSON_NODES) return "Pipeline JSON exceeds its complexity limit"
                '"' -> {
                    val start = index++
                    var escaped = false
                    while (index < text.length) {
                        val c = text[index]
                        if (!escaped && c == '"') break
                        if (escaped) escaped = false else if (c == '\\') escaped = true
                        index++
                    }
                    if (index >= text.length) return "Unterminated JSON text"
                    var next = index + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        if (index - start > 1024) return "A JSON field name is too long"
                        val key = json.decodeFromString<String>(text.substring(start, index + 1))
                        if (objects.lastOrNull()?.add(key) == false) return "Duplicate JSON field: ${key.take(80)}"
                    }
                    if (++nodes > MAX_JSON_NODES) return "Pipeline JSON exceeds its complexity limit"
                }
            }
            index++
        }
        return null
    }

    fun validate(document: PipelineDocument): List<Diagnostic> = validateDraft(document).ifEmpty { semantic(document) }
    fun validateDraft(document: PipelineDocument): List<Diagnostic> = Structural().run(document)
    private val idPattern = Regex("[A-Za-z0-9_.:-]{1,160}")
    private val assetIdPattern = Regex("[A-Za-z0-9_-]{1,64}")

    private class Errors {
        val values = mutableListOf<Diagnostic>()
        fun add(message: String, id: String? = null) { if (values.size < 100) values += Diagnostic(message, id, true) }
        fun check(valid: Boolean, message: String, id: String? = null) { if (!valid) add(message, id) }
    }

    private class Structural {
        val errors = Errors()
        val ids = mutableSetOf<String>()
        var blocks = 0
        var expressions = 0
        var values = 0
        var decodedPixels = 0L
        fun id(id: String) { errors.check(idPattern.matches(id) && ids.add(id), "Invalid or duplicate ID: ${id.take(160)}") }
        fun label(label: String, limit: Int = 256) { errors.check(label.length <= limit, "Text exceeds $limit characters") }
        fun timestamp(value: String) {
            if (value.isNotBlank()) errors.check(value.length <= 40 && runCatching { Instant.parse(value) }.isSuccess, "Invalid ISO timestamp")
        }
        fun value(value: Value, depth: Int = 0) {
            if (++values > 65_536 || depth > 16) { errors.add("Data exceeds its complexity limit"); return }
            when (value) {
                is Value.Bool -> Unit
                is Value.Number -> errors.check(value.value.isFinite() && kotlin.math.abs(value.value) <= 1e15, "Number is outside the supported range")
                is Value.Vector -> errors.check(listOf(value.x, value.y, value.z).all { it.isFinite() && kotlin.math.abs(it) <= 1e15 }, "Invalid vector")
                is Value.Text -> label(value.value, 4096)
                is Value.Unavailable -> label(value.reason, 4096)
                is Value.Items -> {
                    errors.check(value.values.size <= 1024, "List exceeds 1024 items")
                    value.values.take(1025).forEach { value(it, depth + 1) }
                }
                is Value.Record -> {
                    errors.check(value.fields.size <= 128, "Record exceeds 128 fields")
                    value.fields.entries.take(129).forEach { label(it.key, 160); value(it.value, depth + 1) }
                }
            }
        }
        fun expression(expression: Expression, depth: Int = 0) {
            if (++expressions > 65_536 || depth > 32) { errors.add("Expression exceeds its complexity limit"); return }
            label(expression.op, 160); label(expression.key, 160)
            value(expression.value)
            errors.check(expression.args.size <= 1024, "Expression has too many operands")
            expression.args.take(1025).forEach { expression(it, depth + 1) }
        }
        fun blocks(items: List<Block>, depth: Int = 0) {
            if (depth > 32) { errors.add("Blocks exceed nesting limit"); return }
            for (block in items) {
                if (++blocks > 4096) { errors.add("Pipeline exceeds 4096 blocks"); return }
                id(block.id); label(block.op, 160); label(block.comment, 4096)
                errors.check(block.arguments.size <= 128, "Block has too many inputs", block.id)
                block.arguments.entries.take(129).forEach { label(it.key, 160); expression(it.value) }
                blocks(block.body, depth + 1); blocks(block.otherwise, depth + 1)
            }
        }
        fun parameters(parameters: List<Parameter>) {
            errors.check(parameters.size <= 128, "Too many parameters")
            val seen = mutableSetOf<String>()
            parameters.take(129).forEach { p ->
                errors.check(idPattern.matches(p.id) && seen.add(p.id), "Invalid or duplicate parameter ID")
                label(p.name); label(p.description, 4096); value(p.default)
                errors.check(p.default.matches(p.type), "Invalid default for ${p.name}")
                errors.check(p.choices.size <= 128, "Too many choices")
                p.choices.take(129).forEach { value(it); errors.check(it.matches(p.type), "Invalid choice for ${p.name}") }
                errors.check(p.minimum?.isFinite() != false && p.maximum?.isFinite() != false && !(p.minimum != null && p.maximum != null && p.minimum > p.maximum), "Invalid range for ${p.name}")
                if (p.minimum != null || p.maximum != null) errors.check(p.type in setOf(ValueType.NUMBER, ValueType.DURATION), "Only number and duration parameters have numeric bounds")
                checkParameterValue(p, p.default, errors)
            }
        }
        fun variables(variables: List<Variable>) {
            errors.check(variables.size <= 256, "Too many variables")
            val seen = mutableSetOf<String>()
            variables.take(257).forEach { v ->
                errors.check(idPattern.matches(v.id) && seen.add(v.id) && v.initial.matches(v.type), "Invalid variable ${v.name}")
                label(v.name); value(v.initial)
            }
        }
        fun run(d: PipelineDocument): List<Diagnostic> {
            errors.check(d.format == PIPELINE_FORMAT && d.formatVersion == PIPELINE_FORMAT_VERSION, "Unsupported pipeline format or version")
            errors.check(idPattern.matches(d.id), "Invalid document ID")
            label(d.name); label(d.author); label(d.createdWith); timestamp(d.createdAt); timestamp(d.modifiedAt)
            d.preview.thumbnailAssetId?.let { errors.check(assetIdPattern.matches(it), "Invalid thumbnail artwork identity") }
            errors.check(d.preview.thumbnailFrame in 0..239 && d.preview.elapsedMs in 0..60_000, "Invalid preview frame or time")
            errors.check(d.programs.size <= 128 && d.routines.size <= 128 && d.designs.size <= 128 && d.bindings.size <= 1024, "Pipeline exceeds library limits")
            errors.check(d.panels.isNotEmpty() && d.panels.all { it == 13 || it == 25 }, "Only 13×13 and 25×25 panels are supported")
            errors.check(d.editor.positions.size <= 4096 && d.editor.collapsed.size <= 4096, "Editor layout is too large")
            errors.check(listOf(d.editor.viewport.scale, d.editor.viewport.x, d.editor.viewport.y).all(Float::isFinite) && d.editor.viewport.scale > 0, "Invalid editor viewport")
            d.editor.positions.entries.take(4097).forEach { (key, p) -> errors.check(idPattern.matches(key) && p.x.isFinite() && p.y.isFinite(), "Invalid editor position") }
            d.programs.take(129).forEach { p ->
                id(p.id); label(p.name); label(p.description, 4096); label(p.template.orEmpty(), 160)
                errors.check(p.templateVersion > 0, "Invalid template version")
                parameters(p.parameters); variables(p.variables)
                errors.check(p.values.size <= 128, "Too many parameter values")
                p.values.entries.take(129).forEach { label(it.key, 160); value(it.value) }
                errors.check(p.scripts.size <= 128, "Too many event stacks")
                p.scripts.take(129).forEach { s ->
                    id(s.id); label(s.name); label(s.trigger.event, 160)
                    errors.check(s.trigger.stableForMs in 0..86_400_000, "Invalid condition debounce time", s.id)
                    s.trigger.calendar?.let { calendar ->
                        errors.check(calendar.hour in 0..23 && calendar.minute in 0..59 && calendar.weekdays.isNotEmpty() && calendar.weekdays.size <= 7 && calendar.weekdays.all { it in 1..7 } && calendar.weekdays.distinct().size == calendar.weekdays.size, "Invalid calendar schedule", s.id)
                    }
                    s.trigger.condition?.let(::expression); blocks(s.blocks)
                }
            }
            d.routines.take(129).forEach { r -> id(r.id); label(r.name); parameters(r.parameters); variables(r.variables); blocks(r.blocks); errors.check(r.revision > 0, "Invalid routine revision") }
            d.bindings.entries.take(1025).forEach { (key, binding) ->
                errors.check(idPattern.matches(key) && binding.variants.size <= 2, "Invalid artwork binding")
                label(binding.assetId, 64); binding.routineId?.let { errors.check(idPattern.matches(it), "Invalid drawing routine identity") }
                binding.variants.forEach { (panel, g) ->
                    val size = when (panel) { "bellsprout" -> 13; "arbok" -> 25; else -> 0 }
                    errors.check(size > 0 && g.x in 0..size && g.y in 0..size && g.width in 0..size && g.height in 0..size && g.x + g.width <= size && g.y + g.height <= size, "Artwork crop is outside its panel")
                    errors.check(listOf(g.anchorX, g.anchorY, g.hitX, g.hitY, g.hitWidth, g.hitHeight).all { it.isFinite() && kotlin.math.abs(it) <= 10_000 } && g.hitWidth >= 0 && g.hitHeight >= 0, "Invalid artwork geometry")
                }
            }
            errors.check(d.requires.size <= 128, "Too many required capabilities")
            val capabilities = mutableSetOf<String>()
            d.requires.take(129).forEach { errors.check(idPattern.matches(it.capability) && capabilities.add(it.capability) && it.version > 0, "Invalid or duplicate capability requirement") }
            d.designs.entries.take(129).forEach { design(it.key, it.value) }
            errors.check(decodedPixels <= MAX_DECODED_PIXELS, "Embedded artwork exceeds the decoded pixel budget")
            return errors.values
        }
        private fun design(id: String, d: JsonObject) {
            fun primitive(key: String) = d[key] as? JsonPrimitive
            errors.check(assetIdPattern.matches(id) && primitive("id")?.contentOrNull == id, "Invalid embedded artwork identity")
            errors.check(primitive("format")?.contentOrNull == "glyph.design" && primitive("formatVersion")?.intOrNull == 1, "Unsupported embedded artwork format")
            listOf("name", "author", "createdWith").forEach { label(primitive(it)?.contentOrNull.orEmpty(), 64) }
            listOf("createdAt", "modifiedAt").forEach { timestamp(primitive(it)?.contentOrNull.orEmpty()) }
            primitive("kind")?.contentOrNull?.let { errors.check(it in setOf("static", "dynamic"), "Unknown artwork kind") }
            primitive("keyMode")?.contentOrNull?.let { errors.check(it in setOf("playOnce", "playPause"), "Unknown artwork key mode") }
            val palette = (d["levels"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } ?: listOf(0, 2048, 4095)
            errors.check(palette.size in 1..36 && palette.all { it != null && it in 0..4095 }, "Invalid artwork palette")
            val variants = d["variants"] as? JsonObject
            if (variants == null || variants.isEmpty() || variants.size > 2) { errors.add("Missing or invalid artwork variants"); return }
            variants.forEach { (panel, variant) ->
                val size = when (panel) { "bellsprout" -> 13; "arbok" -> 25; else -> 0 }
                if (size == 0) { errors.add("Unknown artwork panel: ${panel.take(64)}"); return@forEach }
                val frames = (variant as? JsonObject)?.get("frames") as? JsonArray
                if (frames == null || frames.size > 240) { errors.add("Invalid artwork frames"); return@forEach }
                frames.forEach { raw ->
                    val frame = raw as? JsonObject
                    val cells = (frame?.get("cells") as? JsonPrimitive)?.contentOrNull
                    val duration = (frame?.get("durationMs") as? JsonPrimitive)?.intOrNull ?: 120
                    errors.check(duration in 20..60_000, "Artwork frame duration is outside 20 ms to 60 s")
                    errors.check(cells != null && cells.length == size * size && cells.all { c ->
                        val index = when (c) { in '0'..'9' -> c - '0'; in 'a'..'z' -> c - 'a' + 10; in 'A'..'Z' -> c - 'A' + 10; else -> -1 }
                        index in palette.indices
                    }, "Artwork cells do not match their panel or palette")
                    decodedPixels += size * size
                }
            }
        }
    }

    private fun checkParameterValue(p: Parameter, value: Value, errors: Errors, block: String? = null) {
        errors.check(value.matches(p.type), "${p.name} needs ${p.type.name.lowercase()}", block)
        if (value is Value.Number) errors.check((p.minimum == null || value.value >= p.minimum) && (p.maximum == null || value.value <= p.maximum), "${p.name} is outside its allowed range", block)
        if (p.choices.isNotEmpty()) errors.check(value in p.choices, "${p.name} needs one of its listed choices", block)
    }

    private data class Type(val kind: ValueType, val unit: UnitKind? = null, val literal: Boolean = false, val unknown: Boolean = false)
    private val anyType = Type(ValueType.ANY, unknown = true)
    private fun type(value: Value, literal: Boolean = false): Type = when (value) {
        is Value.Number -> Type(if (value.unit == UnitKind.MILLISECONDS) ValueType.DURATION else ValueType.NUMBER, value.unit, literal)
        is Value.Bool -> Type(ValueType.BOOLEAN, literal = literal)
        is Value.Text -> Type(ValueType.TEXT, literal = literal)
        is Value.Vector -> Type(ValueType.VECTOR, value.unit, literal)
        is Value.Items -> Type(ValueType.LIST, literal = literal)
        is Value.Record -> Type(ValueType.RECORD, literal = literal)
        is Value.Unavailable -> anyType
    }
    private fun type(kind: ValueType, initial: Value? = null): Type = if (kind == ValueType.ANY) anyType else
        Type(kind, if (kind == ValueType.DURATION) UnitKind.MILLISECONDS else (initial as? Value.Number)?.unit ?: (initial as? Value.Vector)?.unit)

    private fun semantic(d: PipelineDocument): List<Diagnostic> = Semantic(d).run()
    private class Semantic(val document: PipelineDocument) {
        val errors = Errors()
        val programs = document.programs.associateBy { it.id }
        val routines = document.routines.associateBy { it.id }
        val authoredBlocks = buildMap<String, Block> {
            fun index(blocks: List<Block>) { blocks.forEach { put(it.id, it); index(it.body); index(it.otherwise) } }
            document.programs.forEach { p -> p.scripts.forEach { index(it.blocks) } }
            document.routines.forEach { index(it.blocks) }
        }
        val graph = mutableMapOf<String, MutableSet<String>>()
        val checkedContexts = mutableSetOf<String>()
        data class Environment(val variables: Map<String, Type>, val parameters: Map<String, Type>, val program: Program, val routine: Routine? = null)
        fun compatible(actual: Type, expected: Type): Boolean {
            if (actual.unknown || expected.unknown || expected.kind == ValueType.ANY) return true
            if (actual.kind != expected.kind) return false
            return actual.unit == null || expected.unit == null || actual.unit == expected.unit || (actual.literal && actual.unit == UnitKind.SCALAR)
        }
        fun expect(actual: Type, expected: Type, block: String, label: String) {
            errors.check(compatible(actual, expected), "$label needs ${expected.kind.name.lowercase()}${expected.unit?.takeUnless { it == UnitKind.SCALAR }?.let { " (${it.name.lowercase()})" }.orEmpty()}", block)
        }
        fun numeric(t: Type): Boolean = t.unknown || t.kind in setOf(ValueType.NUMBER, ValueType.DURATION)
        fun likeUnits(a: Type, b: Type): Boolean = a.unknown || b.unknown || a.unit == b.unit || a.unit == null || b.unit == null || (a.literal && a.unit == UnitKind.SCALAR) || (b.literal && b.unit == UnitKind.SCALAR)
        fun expression(e: Expression, env: Environment, block: String): Type {
            val spec = ExpressionCatalog[e.op]
            if (spec == null) { errors.add("Unknown expression ${e.op}", block); return anyType }
            val count = if (e.op in setOf("list", "record")) e.args.size else spec.arguments.size
            errors.check(e.args.size == count, "${spec.title} has the wrong number of operands", block)
            val args = e.args.map { expression(it, env, block) }
            fun a(i: Int) = args.getOrElse(i) { anyType }
            fun numbers() { args.forEach { errors.check(numeric(it), "${spec.title} needs numeric operands", block) } }
            return when (e.op) {
                "literal" -> type(e.value, literal = true)
                "variable" -> env.variables[e.key] ?: anyType.also { errors.add("Unknown variable ${e.key}", block) }
                "block.count" -> {
                    val target = authoredBlocks[e.key]
                    errors.check(e.key.isNotBlank() && target != null, "Choose an existing option block", block)
                    if (target != null) errors.check(BlockCatalog[target.op]?.body == true, "Option count needs a block with nested options", block)
                    Type(ValueType.NUMBER, UnitKind.SCALAR)
                }
                "parameter" -> env.parameters[e.key] ?: anyType.also { errors.add("Unknown parameter ${e.key}", block) }
                "input" -> InputCatalog[e.key]?.let { Type(it.type, if (it.type == ValueType.DURATION) UnitKind.MILLISECONDS else it.unit) } ?: anyType.also { errors.add("Unknown input ${e.key}", block) }
                "event", "native.value", "sprite.value" -> {
                    if (e.op in setOf("native.value", "sprite.value")) expect(a(0), Type(ValueType.TEXT), block, spec.title)
                    anyType
                }
                "field" -> {
                    errors.check(a(0).unknown || a(0).kind in setOf(ValueType.VECTOR, ValueType.RECORD), "Field needs a record or vector", block)
                    if (a(0).kind == ValueType.VECTOR) {
                        errors.check(e.key in setOf("x", "y", "z"), "Unknown vector component", block)
                        Type(ValueType.NUMBER, a(0).unit)
                    } else (e.args.firstOrNull()?.value as? Value.Record)?.fields?.get(e.key)?.let { type(it) } ?: anyType
                }
                "choose" -> { expect(a(0), Type(ValueType.BOOLEAN), block, spec.title); errors.check(compatible(a(1), a(2)) || compatible(a(2), a(1)), "Both choices must have compatible types", block); if (a(1).unknown) a(2) else a(1) }
                "and", "or", "not" -> { args.forEach { expect(it, Type(ValueType.BOOLEAN), block, spec.title) }; Type(ValueType.BOOLEAN) }
                "equal", "notEqual" -> { errors.check(compatible(a(0), a(1)) || compatible(a(1), a(0)), "Compared values need compatible types and units", block); Type(ValueType.BOOLEAN) }
                "greater", "greaterEqual", "less", "lessEqual" -> { numbers(); errors.check(likeUnits(a(0), a(1)), "Compared values need compatible units", block); Type(ValueType.BOOLEAN) }
                "add", "subtract", "min", "max", "modulo" -> { numbers(); errors.check(likeUnits(a(0), a(1)), "${spec.title} needs compatible units", block); if (a(0).literal && a(0).unit == UnitKind.SCALAR) a(1).copy(literal = false) else a(0).copy(literal = false) }
                "multiply", "divide" -> {
                    numbers()
                    errors.check(a(0).unit == UnitKind.SCALAR || a(1).unit == UnitKind.SCALAR || a(0).unit == null || a(1).unit == null || e.op == "divide" && likeUnits(a(0), a(1)), "Cannot combine these physical units", block)
                    if (e.op == "divide" && a(0).unit == a(1).unit) Type(ValueType.NUMBER, UnitKind.SCALAR)
                    else if (a(0).unit == UnitKind.SCALAR && e.op == "multiply") a(1).copy(literal = false) else a(0).copy(literal = false)
                }
                "random" -> { numbers(); errors.check(args.all { it.unknown || it.kind == ValueType.NUMBER && it.unit in setOf(null, UnitKind.SCALAR) }, "Random bounds are scalar numbers", block); Type(ValueType.NUMBER, UnitKind.SCALAR) }
                "abs", "negate", "round", "floor", "ceil" -> { numbers(); a(0).copy(literal = false) }
                "sin", "cos" -> { numbers(); errors.check(a(0).unknown || a(0).unit in setOf(null, UnitKind.SCALAR, UnitKind.DEGREES), "Trigonometry takes degrees", block); Type(ValueType.NUMBER, UnitKind.SCALAR) }
                "clamp" -> { numbers(); errors.check(likeUnits(a(0), a(1)) && likeUnits(a(0), a(2)), "Clamp bounds need compatible units", block); a(0).copy(literal = false) }
                "list" -> Type(ValueType.LIST)
                "record" -> {
                    errors.check(e.args.size % 2 == 0 && e.args.size <= 256, "Record needs pairs of field names and values (up to 128)", block)
                    val names = mutableSetOf<String>()
                    e.args.filterIndexed { index, _ -> index % 2 == 0 }.forEach { key ->
                        val name = (key.value as? Value.Text)?.value
                        errors.check(key.op == "literal" && name != null && name.isNotBlank() && name.length <= 160 && names.add(name), "Record field names must be unique literal text", block)
                    }
                    Type(ValueType.RECORD)
                }
                "between" -> { numbers(); errors.check(likeUnits(a(0), a(1)) && likeUnits(a(0), a(2)), "Range bounds need compatible units", block); Type(ValueType.BOOLEAN) }
                "time.range" -> { args.forEach { expect(it, Type(ValueType.NUMBER, UnitKind.SCALAR), block, "Minutes of day") }; Type(ValueType.BOOLEAN) }
                "random.choice" -> { expect(a(0), Type(ValueType.LIST), block, "Random choice"); anyType }
                "item" -> { expect(a(0), Type(ValueType.LIST), block, "List"); expect(a(1), Type(ValueType.NUMBER, UnitKind.SCALAR), block, "Index"); anyType }
                "length" -> { errors.check(a(0).unknown || a(0).kind in setOf(ValueType.LIST, ValueType.TEXT), "Length needs text or a list", block); Type(ValueType.NUMBER, UnitKind.SCALAR) }
                "format" -> { numbers(); Type(ValueType.TEXT) }
                "join" -> Type(ValueType.TEXT)
                "available" -> Type(ValueType.BOOLEAN)
                "timer.running", "sprite.touching", "sprite.edge" -> { args.forEach { expect(it, Type(ValueType.TEXT), block, spec.title) }; Type(ValueType.BOOLEAN) }
                "timer.remaining" -> Type(ValueType.DURATION, UnitKind.MILLISECONDS)
                else -> type(spec.result)
            }
        }
        fun blocks(blocks: List<Block>, env: Environment, owner: String, inLoop: Boolean = false, depth: Int = 0) {
            if (depth > 32) { errors.add("Routine nesting exceeds 32 levels"); return }
            for (b in blocks) {
                val spec = BlockCatalog[b.op]
                if (spec == null) { errors.add("Unknown block ${b.op}", b.id); continue }
                if (!b.enabled) continue
                errors.check((spec.body || b.body.isEmpty()) && (spec.otherwise || b.otherwise.isEmpty()), "This block cannot contain these nested blocks", b.id)
                val supplied = b.arguments.mapValues { expression(it.value, env, b.id) }
                b.arguments.forEach { (key, _) ->
                    val known = spec.arguments.any { it.name == key } || key.startsWith("arg:") && b.op in setOf("routine.call", "program.run") || key.startsWith("binding:") && b.op == "display.toy" || key == "binding" && b.op == "sprite.create"
                    errors.check(known, "Unknown input $key for ${b.op}", b.id)
                }
                spec.arguments.forEach { arg ->
                    val actual = supplied[arg.name] ?: type(arg.default.value, true)
                    if (!(b.op == "variable.change" && arg.name == "by")) expect(actual, type(arg.type), b.id, arg.label)
                    val entered = b.arguments[arg.name]
                    if (arg.type != ValueType.ANY && entered?.op == "literal" && entered.value is Value.Unavailable) errors.add("Choose ${arg.label.lowercase()}", b.id)
                    if (arg.type == ValueType.DURATION && entered?.op == "literal" && entered.value is Value.Number) errors.check((entered.value as Value.Number).value >= 0, "${arg.label} cannot be negative", b.id)
                    if (arg.choices.isNotEmpty() && b.arguments[arg.name]?.op == "literal") errors.check(b.arguments[arg.name]!!.value.text() in arg.choices, "${arg.label} needs a listed choice", b.id)
                    arg.reference?.let { reference ->
                        val direct = b.arguments[arg.name] ?: arg.default
                        val spriteBinding = if (b.op == "sprite.create" && arg.name == "asset") b.arguments["binding"]?.takeIf { it.op == "literal" }?.value?.text()?.let { document.bindings[it] } else null
                        val e = spriteBinding?.takeIf { it.routineId == null }?.let { Expression.str(it.assetId) } ?: direct
                        val target = (e.value as? Value.Text)?.value
                        if (e.op != "literal" || target.isNullOrBlank()) errors.add("Choose ${arg.label.lowercase()}", b.id)
                        else {
                            val exists = when (reference) {
                                ReferenceKind.ROUTINE -> target in routines; ReferenceKind.PROGRAM -> target in programs
                                ReferenceKind.ASSET -> target in document.designs; ReferenceKind.BINDING -> target in document.bindings
                                ReferenceKind.VARIABLE -> target in env.variables
                            }
                            errors.check(exists, "Missing ${arg.label.lowercase()}: $target", b.id)
                            if (reference == ReferenceKind.ASSET && exists) artworkPanels(target, b.id)
                            if (reference in setOf(ReferenceKind.ROUTINE, ReferenceKind.PROGRAM)) graph.getOrPut(owner) { mutableSetOf() } += target
                        }
                    }
                }
                b.arguments.filterKeys { it.startsWith("binding:") || it == "binding" }.forEach { (_, e) ->
                    errors.check(e.op == "literal" && e.value.text() in document.bindings, "Missing artwork binding", b.id)
                    document.bindings[e.value.text()]?.let { binding ->
                        if (binding.routineId == null) artworkPanels(binding.assetId, b.id)
                        else {
                            errors.check(b.op == "display.toy", "Drawing routines are native visual slots; sprites use design assets", b.id)
                            routines[binding.routineId]?.let { routine -> checkRoutine(routine, env.program, depth + 1); graph.getOrPut(owner) { mutableSetOf() } += routine.id }
                        }
                    }
                }
                fun value(name: String) = b.arguments[name]?.value?.text().orEmpty()
                when (b.op) {
                    "routine.call", "program.run" -> {
                        val routine = if (b.op == "routine.call") routines[value("routine")] else null
                        val program = if (b.op == "program.run") programs[value("program")] else null
                        val parameters = routine?.parameters ?: program?.parameters ?: emptyList()
                        b.arguments.filterKeys { it.startsWith("arg:") }.forEach { (key, e) ->
                            val parameter = parameters.find { it.id == key.removePrefix("arg:") }
                            if (parameter == null) errors.add("Unknown call parameter ${key.removePrefix("arg:")}", b.id)
                            else { expect(supplied.getValue(key), type(parameter.type, parameter.default), b.id, parameter.name); if (e.op == "literal") checkParameterValue(parameter, e.value, errors, b.id) }
                        }
                        if (routine != null) {
                            val result = value("resultVariable")
                            if (result.isNotEmpty()) {
                                val variable = env.variables[result]
                                if (variable == null) errors.add("Unknown result variable $result", b.id) else expect(type(routine.returns), variable, b.id, "Routine result")
                            }
                            checkRoutine(routine, env.program, depth + 1)
                        }
                    }
                    "variable.set" -> env.variables[value("variable")]?.let { expect(supplied["value"] ?: Type(ValueType.NUMBER, UnitKind.SCALAR), it, b.id, "Variable value") }
                    "variable.change" -> env.variables[value("variable")]?.let { variable -> errors.check(numeric(variable), "Change needs a number or duration variable", b.id); errors.check(numeric(supplied["by"] ?: Type(ValueType.NUMBER, UnitKind.SCALAR, true)), "Change needs a numeric amount", b.id); errors.check(likeUnits(variable, supplied["by"] ?: Type(ValueType.NUMBER, UnitKind.SCALAR, true)), "Change needs compatible units", b.id) }
                    "list.add", "list.set", "list.remove" -> env.variables[value("variable")]?.let { expect(it, Type(ValueType.LIST), b.id, "List variable") }
                    "flow.return" -> env.routine?.let { expect(supplied["value"] ?: Type(ValueType.NUMBER, UnitKind.SCALAR), type(it.returns), b.id, "Return value") }
                    "flow.break" -> errors.check(inLoop, "Break must be inside a loop", b.id)
                }
                val loop = inLoop || b.op in setOf("flow.repeat", "flow.forever", "flow.while", "flow.until")
                blocks(b.body, env, owner, loop, depth); blocks(b.otherwise, env, owner, loop, depth)
            }
        }
        fun artworkPanels(id: String, block: String) {
            val variants = document.designs[id]?.get("variants") as? JsonObject ?: return
            document.panels.forEach { panel ->
                val key = if (panel == 13) "bellsprout" else "arbok"
                val frames = (variants[key] as? JsonObject)?.get("frames") as? JsonArray
                errors.check(frames != null && frames.isNotEmpty(), "Artwork $id has no frames for ${panel}×$panel; add the variant or change project panels", block)
            }
        }
        fun environment(p: Program, r: Routine? = null) = Environment(
            p.variables.associate { it.id to type(it.type, it.initial) } + r?.variables.orEmpty().associate { it.id to type(it.type, it.initial) },
            p.parameters.associate { it.id to type(it.type, it.default) } + r?.parameters.orEmpty().associate { it.id to type(it.type, it.default) }, p, r,
        )
        fun checkRoutine(r: Routine, p: Program, depth: Int) {
            if (checkedContexts.add("${p.id}/${r.id}")) blocks(r.blocks, environment(p, r), r.id, depth = depth)
        }
        fun run(): List<Diagnostic> {
            errors.check(document.entryPoint in programs, "Choose an entry program")
            document.preview.thumbnailAssetId?.let { asset ->
                errors.check(asset in document.designs, "Thumbnail artwork is missing")
                artworkPanels(asset, document.id)
                val variants = document.designs[asset]?.get("variants") as? JsonObject
                document.panels.forEach { panel ->
                    val key = if (panel == 13) "bellsprout" else "arbok"
                    val frames = (variants?.get(key) as? JsonObject)?.get("frames") as? JsonArray
                    errors.check(frames != null && document.preview.thumbnailFrame in frames.indices, "Thumbnail frame is missing for ${panel}×$panel", document.id)
                }
            }
            for (p in document.programs) {
                val env = environment(p)
                p.values.forEach { (id, value) -> p.parameters.find { it.id == id }?.let { checkParameterValue(it, value, errors) } ?: errors.add("Unknown parameter value $id") }
                p.scripts.forEach { script ->
                    val event = script.trigger.event
                    errors.check(EventCatalog.all.any { it.name == event } || event.startsWith("signal.") || event.startsWith("timer."), "Unknown event $event", script.id)
                    errors.check((event == "calendar.daily") == (script.trigger.calendar != null), "Calendar events need a schedule; other events must not carry one", script.id)
                    if (script.trigger.calendar != null) errors.check(script.trigger.edge == TriggerEdge.EVENT, "Calendar schedules use event triggers", script.id)
                    script.trigger.condition?.let { expect(expression(it, env, script.id), Type(ValueType.BOOLEAN), script.id, "Trigger condition") }
                    if (script.trigger.edge != TriggerEdge.EVENT) errors.check(script.trigger.condition != null, "An edge trigger needs a condition", script.id)
                    blocks(script.blocks, env, p.id)
                }
            }
            // Uncalled library routines may intentionally declare project globals; validate with
            // each actual caller above. Still check executable vocabulary for unreferenced routines.
            document.routines.filter { r -> checkedContexts.none { it.endsWith("/${r.id}") } }.forEach { r ->
                val p = document.entry() ?: document.programs.firstOrNull()
                if (p != null) checkRoutine(r, p, 0)
            }
            document.bindings.values.forEach { binding ->
                errors.check((binding.routineId == null) != binding.assetId.isBlank(), "A binding needs exactly one design or drawing routine")
                if (binding.routineId == null) errors.check(binding.assetId in document.designs, "Binding refers to missing artwork")
                else {
                    errors.check(binding.routineId in routines, "Binding refers to a missing drawing routine")
                    DrawingRoutineRenderer.validate(document, binding.routineId).forEach { errors.add(it.message, it.blockId) }
                }
            }
            val visited = mutableSetOf<String>(); val visiting = mutableSetOf<String>()
            fun visit(id: String, depth: Int = 0) {
                if (id in visiting) { errors.add("Recursive call cycle at $id"); return }
                if (id in visited) return
                if (depth > 32) { errors.add("Calls exceed depth limit"); return }
                visiting += id; graph[id]?.forEach { visit(it, depth + 1) }; visiting -= id; visited += id
            }
            (programs.keys + routines.keys).forEach { visit(it) }
            return errors.values
        }
    }
}
