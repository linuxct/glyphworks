package space.linuxct.glyphworks.pipeline.store

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import space.linuxct.pipeline.*

class PipelineReferencesTest {
    @Test fun optionCountsRetainAndRemapReferencedOwnersIncludingTriggerExpressions() {
        val options = Block(id = "options", op = "flow.select", body = listOf(Block(op = "display.off")))
        val count = Expression("block.count", key = "options")
        val source = PipelineDocument(entryPoint = "main", programs = listOf(
            Program("main", "Main", scripts = listOf(Script(id = "when", trigger = Trigger(condition = Expression.operation("greater", count, Expression.num(0))),
                blocks = listOf(Block(id = "read", op = "flow.repeat", arguments = mapOf("count" to count)))))),
            Program("spare", "Spare")), routines = listOf(Routine("menu", "Menu", blocks = listOf(options))))
        val subset = PipelineReferences.closure(source)
        assertEquals(listOf("main"), subset.programs.map { it.id })
        assertEquals(listOf("menu"), subset.routines.map { it.id })
        val mapped = PipelineReferences.remap(subset)
        val optionId = mapped.routines.single().blocks.single().id
        assertEquals(optionId, mapped.entry()!!.scripts.single().trigger.condition!!.args[0].key)
        assertEquals(optionId, mapped.entry()!!.scripts.single().blocks.single().arguments.getValue("count").key)
        assertEquals(setOf("when", "read"), PipelineReferences.usages(source, "options").toSet())
    }

    @Test fun previewArtworkIsRetainedAndRemappedEvenWithoutAnExecutableReference() {
        val source = PipelineDocument(entryPoint = "main", programs = listOf(Program("main", "Main")),
            designs = mapOf("thumb" to buildJsonObject { put("id", "thumb") }, "unused" to buildJsonObject {}), preview = PreviewSettings(thumbnailAssetId = "thumb"))
        val subset = PipelineReferences.closure(source)
        assertEquals(setOf("thumb"), subset.designs.keys)
        val mapped = PipelineReferences.remap(subset)
        assertEquals(mapped.designs.keys.single(), mapped.preview.thumbnailAssetId)
        assertTrue(PipelineReferences.usages(source, "thumb").contains(source.id))
    }

    @Test fun drawingRoutineBindingParticipatesInClosureAndIdentityRemapping() {
        val source = PipelineDocument(entryPoint = "main", programs = listOf(Program("main", "Main")),
            routines = listOf(Routine("draw", "Draw", blocks = listOf(Block(op = "scene.pixel")))),
            bindings = mapOf("face" to AssetBinding(routineId = "draw")))
        val subset = PipelineReferences.closure(source)
        assertEquals("draw", subset.routines.single().id)
        val mapped = PipelineReferences.remap(subset)
        assertEquals(mapped.routines.single().id, mapped.bindings.values.single().routineId)
        assertEquals("", mapped.bindings.values.single().assetId)
        assertTrue(PipelineReferences.usages(source, "draw").contains("face"))
    }

    @Test fun remappingChangesReferencesWithoutChangingUserTextOrSignals() {
        val source = PipelineDocument(id = "project", entryPoint = "toy", name = "toy", programs = listOf(
            Program(id = "toy", name = "toy", template = "dino", parameters = listOf(Parameter("speed", "Speed")),
                variables = listOf(Variable("count", "Count")), scripts = listOf(Script(id = "start", blocks = listOf(
                    Block(id = "call", op = "routine.call", arguments = mapOf("routine" to Expression.str("routine"), "arg:duration" to Expression.ms(500))),
                    Block(id = "write", op = "variable.set", arguments = mapOf("variable" to Expression.str("count"), "value" to Expression.variable("count"))),
                    Block(id = "draw", op = "display.animation", arguments = mapOf("asset" to Expression.str("art"))),
                    Block(id = "native", op = "display.toy", arguments = mapOf("toy" to Expression.str("dino"), "binding:jump" to Expression.str("jump"))),
                    Block(id = "message", op = "signal.emit", arguments = mapOf("name" to Expression.str("routine"))),
                )))),
        ), routines = listOf(Routine(id = "routine", name = "Routine", parameters = listOf(Parameter("duration", "Duration", ValueType.DURATION, duration(500))))),
            designs = mapOf("art" to buildJsonObject { put("id", "art"); put("name", "toy") }),
            bindings = mapOf("jump" to AssetBinding("art")),
        )
        var index = 0
        val copy = PipelineReferences.remap(source) { "fresh${++index}" }
        val p = copy.programs.single()
        val blocks = p.scripts.single().blocks
        assertEquals("toy", copy.name)
        assertEquals("toy", p.name)
        assertNull(p.template)
        assertEquals(copy.routines.single().id, blocks[0].arguments["routine"]!!.value.text())
        assertTrue(blocks[0].arguments.containsKey("arg:" + copy.routines.single().parameters.single().id))
        assertEquals(p.variables.single().id, blocks[1].arguments["variable"]!!.value.text())
        assertEquals(p.variables.single().id, blocks[1].arguments["value"]!!.key)
        assertEquals(copy.designs.keys.single(), blocks[2].arguments["asset"]!!.value.text())
        assertEquals(copy.bindings.keys.single(), blocks[3].arguments["binding:jump"]!!.value.text())
        assertEquals(copy.designs.keys.single(), copy.bindings.values.single().assetId)
        assertEquals("routine", blocks[4].arguments["name"]!!.value.text())
    }

    @Test fun dependencyClosureFollowsNestedCallsAndProgramsTransitively() {
        fun call(id: String) = Block(op = "routine.call", arguments = mapOf("routine" to Expression.str(id)))
        val source = PipelineDocument(entryPoint = "entry", programs = listOf(
            Program(id = "entry", name = "Entry", scripts = listOf(Script(blocks = listOf(
                Block(op = "flow.if", body = listOf(call("first"))),
            )))),
            Program(id = "child", name = "Child"), Program(id = "unused", name = "Unused"),
        ), routines = listOf(
            Routine(id = "first", name = "First", blocks = listOf(call("second"))),
            Routine(id = "second", name = "Second", blocks = listOf(
                Block(op = "program.run", arguments = mapOf("program" to Expression.str("child"))),
                Block(op = "display.frame", arguments = mapOf("asset" to Expression.str("art"))),
            )), Routine(id = "unusedRoutine", name = "Unused"),
        ), designs = mapOf("art" to buildJsonObject {}, "unusedArt" to buildJsonObject {}))
        val result = PipelineReferences.closure(source)
        assertEquals(setOf("entry", "child"), result.programs.map { it.id }.toSet())
        assertEquals(setOf("first", "second"), result.routines.map { it.id }.toSet())
        assertEquals(setOf("art"), result.designs.keys)
    }
}
