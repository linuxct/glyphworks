package space.linuxct.pipeline

import java.io.InputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PipelineCodecTest {
    private fun document(vararg blocks: Block, variables: List<Variable> = emptyList(), routines: List<Routine> = emptyList()): PipelineDocument {
        val p = Program(id = "program", name = "Program", variables = variables, scripts = listOf(Script(id = "start", blocks = blocks.toList())))
        return PipelineDocument(id = "project", name = "Project", entryPoint = p.id, programs = listOf(p), routines = routines)
    }
    private fun block(op: String, vararg arguments: Pair<String, Expression>) = Block(op = op, arguments = mapOf(*arguments))
    private fun errors(d: PipelineDocument) = PipelineCodec.validate(d)
    private fun assertInvalid(d: PipelineDocument) = assertTrue(errors(d).toString(), errors(d).isNotEmpty())

    @Test fun optionCountsResolveStableContainerIdsAcrossProgramsAndRoutines() {
        val options = Block(id = "options", op = "flow.select", body = listOf(Block(op = "display.off")))
        val read = block("variable.set", "variable" to Expression.str("count"), "value" to Expression("block.count", key = options.id))
        val source = document(read, variables = listOf(Variable("count", "Count")), routines = listOf(Routine("optionsRoutine", "Options", blocks = listOf(options))))
        assertTrue(errors(source).toString(), errors(source).isEmpty())
        assertInvalid(source.copy(routines = emptyList()))
        assertInvalid(source.copy(routines = listOf(source.routines.single().copy(blocks = listOf(options.copy(op = "display.off", body = emptyList()))))))
    }

    @Test fun explicitFormatAndVersionCannotBeOmittedOrCoerced() {
        for (input in listOf("{}", "[]", "{\"format\":\"glyph.pipeline\"}", "{\"format\":\"glyph.pipeline\",\"formatVersion\":2}")) {
            assertTrue(input, PipelineCodec.decodeDraft(input) is PipelineCodec.Result.Invalid)
        }
    }

    @Test fun duplicateJsonFieldsIncludingEscapedSpellingsAreRejected() {
        val good = PipelineCodec.encode(document())
        val duplicate = good.replaceFirst("\"format\":", "\"format\":\"glyph.pipeline\",\"for\\u006dat\":")
        assertTrue(PipelineCodec.decode(duplicate) is PipelineCodec.Result.Invalid)
    }

    @Test fun oversizedStreamIsReadOnlyUpToTheHardBound() {
        var reads = 0
        val endless = object : InputStream() { override fun read(): Int { reads++; return ' '.code } }
        assertTrue(PipelineCodec.decode(endless) is PipelineCodec.Result.Invalid)
        assertEquals(PipelineCodec.MAX_BYTES + 1, reads)
    }

    @Test fun deeplyNestedAndExcessivelyWideJsonFailsBeforeModelDeserialization() {
        assertTrue(PipelineCodec.decode("[".repeat(113) + "]".repeat(113)) is PipelineCodec.Result.Invalid)
        val wide = "[" + "{},".repeat(PipelineCodec.MAX_JSON_NODES / 2 + 1) + "{}]"
        assertTrue(PipelineCodec.decode(wide) is PipelineCodec.Result.Invalid)
    }

    @Test fun semanticHolesCanBeSavedAsDraftsButNotApplied() {
        val incomplete = document(block("flow.wait", "duration" to Expression.literal(Value.Unavailable("Choose duration"))))
        assertTrue(PipelineCodec.decodeDraft(PipelineCodec.encode(incomplete)) is PipelineCodec.Result.Ok)
        assertInvalid(incomplete)
    }

    @Test fun expressionsEnforceArityTypesAndPhysicalUnits() {
        assertInvalid(document(block("flow.wait", "duration" to Expression.operation("add", Expression.ms(1000)))))
        assertInvalid(document(block("flow.if", "condition" to Expression.operation("add", Expression.num(1), Expression.num(2)))))
        assertInvalid(document(block("flow.wait", "duration" to Expression.operation("multiply", Expression.str("hello"), Expression.num(2)))))
        assertInvalid(document(block("flow.if", "condition" to Expression.operation("greater", Expression.input("sensor.light"), Expression.input("orientation.pitch")))))
        val valid = document(block("flow.wait", "duration" to Expression.operation("multiply", Expression.ms(1000), Expression.num(2))))
        assertTrue(errors(valid).toString(), errors(valid).isEmpty())
    }

    @Test fun changeRejectsTextButAcceptsAnExplicitDurationIncrement() {
        val variable = Variable("elapsed", "Elapsed", ValueType.DURATION, duration(0))
        assertInvalid(document(block("variable.change", "variable" to Expression.str("elapsed"), "by" to Expression.str("no")), variables = listOf(variable)))
        val good = document(block("variable.change", "variable" to Expression.str("elapsed"), "by" to Expression.ms(50)), variables = listOf(variable))
        assertTrue(errors(good).toString(), errors(good).isEmpty())
    }

    @Test fun previewArtworkMustContainTheSelectedFrameForEveryDeclaredPanel() {
        val source = document().copy(panels = setOf(13), designs = mapOf("asset" to artwork()), preview = PreviewSettings("asset", 0, 700, true))
        assertTrue(errors(source).toString(), errors(source).isEmpty())
        assertInvalid(source.copy(preview = source.preview.copy(thumbnailFrame = 1)))
        assertInvalid(source.copy(preview = source.preview.copy(thumbnailAssetId = "missing")))
        assertInvalid(source.copy(preview = source.preview.copy(elapsedMs = 60_001)))
        assertInvalid(source.copy(panels = setOf(13, 25)))
    }

    @Test fun referencedArtworkMustCoverEveryDeclaredPanel() {
        val source = document(block("display.frame", "asset" to Expression.str("asset"))).copy(designs = mapOf("asset" to artwork()))
        assertInvalid(source)
        assertTrue(errors(source.copy(panels = setOf(13))).toString(), errors(source.copy(panels = setOf(13))).isEmpty())
    }

    @Test fun routineParametersBoundsAndReturnAssignmentsAreChecked() {
        val routine = Routine(id = "delay", name = "Delay", parameters = listOf(Parameter("duration", "Duration", ValueType.DURATION, duration(1000), minimum = 100.0, maximum = 10000.0)), returns = ValueType.TEXT,
            blocks = listOf(block("flow.return", "value" to Expression.str("done"))))
        val wrongArg = document(block("routine.call", "routine" to Expression.str("delay"), "arg:duration" to Expression.ms(20)), routines = listOf(routine))
        assertInvalid(wrongArg)
        val wrongResult = document(block("routine.call", "routine" to Expression.str("delay"), "resultVariable" to Expression.str("number")), variables = listOf(Variable("number", "Number")), routines = listOf(routine))
        assertInvalid(wrongResult)
    }

    @Test fun sharedRoutineIsCheckedInEachActualCallerContext() {
        val routine = Routine(id = "shared", name = "Shared", blocks = listOf(block("flow.wait", "duration" to Expression.variable("duration"))))
        fun p(id: String, value: Value, type: ValueType) = Program(id = id, name = id, variables = listOf(Variable("duration", "Duration", type, value)), scripts = listOf(Script(blocks = listOf(block("routine.call", "routine" to Expression.str("shared"))))))
        val first = p("first", duration(1000), ValueType.DURATION)
        val second = p("second", text("no"), ValueType.TEXT)
        val d = PipelineDocument(entryPoint = first.id, programs = listOf(first, second), routines = listOf(routine))
        assertInvalid(d)
        val shadowed = routine.copy(variables = listOf(Variable("duration", "Local duration", ValueType.DURATION, duration(2000))))
        assertTrue(errors(d.copy(routines = listOf(shadowed))).toString(), errors(d.copy(routines = listOf(shadowed))).isEmpty())
    }

    @Test fun missingReferencesCyclesAndUnknownExecutableContentFailClosed() {
        assertInvalid(document(block("routine.call", "routine" to Expression.str("missing"))))
        assertInvalid(document(block("arbitrary.execute")))
        assertInvalid(document(block("display.off", "arbitrary" to Expression.str("ignored?"))))
        val recursive = Routine("loop", "Loop", blocks = listOf(block("routine.call", "routine" to Expression.str("loop"))))
        assertInvalid(document(block("routine.call", "routine" to Expression.str("loop")), routines = listOf(recursive)))
    }

    @Test fun assetIdentityPaletteGeometryAndMalformedValuesNeverThrow() {
        val malformed = buildJsonObject { put("format", buildJsonObject {}); put("formatVersion", 1) }
        assertInvalid(document().copy(designs = mapOf("asset" to malformed)))
        val d = document().copy(designs = mapOf("asset" to artwork()))
        assertTrue(errors(d).toString(), errors(d).isEmpty())
        assertInvalid(d.copy(bindings = mapOf("bad" to AssetBinding("asset", variants = mapOf("bellsprout" to SpriteGeometry(x = 12, width = 3))))))
        val cells = artwork(cells = "z".repeat(169))
        assertInvalid(d.copy(designs = mapOf("asset" to cells)))
        assertInvalid(d.copy(designs = mapOf("asset" to artwork(duration = 1))))
    }

    @Test fun anAssetBundleHasAnAggregateDecodedPixelBudget() {
        val frames = List(240) { buildJsonObject { put("cells", "0".repeat(625)); put("durationMs", 120) } }
        val designs = (0..13).associate { index ->
            val id = "asset$index"
            id to buildJsonObject {
                put("format", "glyph.design"); put("formatVersion", 1); put("id", id)
                put("variants", buildJsonObject { put("arbok", buildJsonObject { put("frames", JsonArray(frames)) }) })
            }
        }
        assertInvalid(document().copy(designs = designs))
    }

    @Test fun calendarSchedulesRequireValidDistinctWeekdaysAndEventSemantics() {
        fun scheduled(trigger: Trigger): PipelineDocument = document().let { d -> d.copy(programs = d.programs.map { p -> p.copy(scripts = listOf(Script(id = "calendar", trigger = trigger))) }) }
        assertTrue(errors(scheduled(Trigger("calendar.daily", calendar = CalendarTrigger()))).isEmpty())
        assertInvalid(scheduled(Trigger("calendar.daily")))
        assertInvalid(scheduled(Trigger("start", calendar = CalendarTrigger())))
        assertInvalid(scheduled(Trigger("calendar.daily", calendar = CalendarTrigger(hour = 24))))
        assertInvalid(scheduled(Trigger("calendar.daily", calendar = CalendarTrigger(weekdays = listOf(1, 1)))))
        assertInvalid(scheduled(Trigger("calendar.daily", edge = TriggerEdge.RISING, condition = Expression.bool(true), calendar = CalendarTrigger())))
    }

    @Test fun dynamicRecordKeysAreLiteralUniqueAndNonempty() {
        fun source(vararg args: Expression) = document(block("signal.emit", "values" to Expression.operation("record", *args)))
        assertTrue(errors(source(Expression.str("x"), Expression.operation("random", Expression.num(1), Expression.num(6)))).isEmpty())
        assertInvalid(source(Expression.str("x")))
        assertInvalid(source(Expression.str(""), Expression.num(1)))
        assertInvalid(source(Expression.str("x"), Expression.num(1), Expression.str("x"), Expression.num(2)))
        assertInvalid(source(Expression.input("weather.condition"), Expression.num(1)))
    }

    @Test fun newRangeAndChoiceExpressionsCheckTheirInputs() {
        assertInvalid(document(block("flow.if", "condition" to Expression.operation("between", Expression.str("x"), Expression.num(0), Expression.num(5)))))
        assertInvalid(document(block("flow.if", "condition" to Expression.operation("time.range", Expression.ms(1), Expression.num(0), Expression.num(5)))))
        assertInvalid(document(block("scene.text", "text" to Expression.operation("random.choice", Expression.num(1)))))
    }

    @Test fun disabledUnknownBlocksAndBadRequirementVersionsRemainInvalid() {
        assertInvalid(document(block("unknown.op").copy(enabled = false)))
        assertInvalid(document().copy(requires = listOf(Requirement("native.clock", 0))))
    }

    private fun artwork(cells: String = "0".repeat(169), duration: Int = 120) = buildJsonObject {
        put("format", "glyph.design"); put("formatVersion", 1); put("id", "asset")
        put("levels", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(4095))))
        put("variants", buildJsonObject { put("bellsprout", buildJsonObject { put("frames", JsonArray(listOf(buildJsonObject { put("cells", cells); put("durationMs", duration) }))) }) })
    }
}
