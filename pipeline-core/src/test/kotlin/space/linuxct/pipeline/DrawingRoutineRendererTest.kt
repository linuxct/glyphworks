package space.linuxct.pipeline

import org.junit.Assert.*
import org.junit.Test

class DrawingRoutineRendererTest {
    private class Host(override val size: Int, val factory: ((String, NativeContext) -> NativeBehavior?)? = null) : PipelineHost {
        val diagnostics = mutableListOf<Diagnostic>()
        var outputCalls = 0
        var actionCalls = 0
        override val clock = object : PipelineClock { override fun elapsedMillis() = 100L; override fun wallMillis() = 1_790_000_000_000L }
        override val random = object : PipelineRandom { override fun nextInt(bound: Int) = 0; override fun nextDouble() = 0.0 }
        override fun inputs() = emptyMap<String, Value>()
        override fun createNative(type: String, context: NativeContext): NativeBehavior? = factory?.invoke(type, context) ?: error("Must never create native behaviors")
        override fun output(frame: IntArray) { outputCalls++ }
        override fun action(name: String, values: Map<String, Value>) { actionCalls++ }
        override fun diagnostic(diagnostic: Diagnostic) { diagnostics += diagnostic }
    }

    private fun document(routine: Routine): PipelineDocument {
        val program = Program(id = "main", name = "Drawing host", variables = listOf(Variable("counter", "Counter", initial = number(3))))
        return PipelineDocument(entryPoint = program.id, programs = listOf(program), routines = listOf(routine),
            bindings = mapOf("visual" to AssetBinding(routineId = routine.id)))
    }

    @Test fun drawingReceivesNativeStateAndFreshPrivateGlobalSnapshotsOnBothPanels() {
        val routine = Routine(id = "draw", name = "Draw", parameters = listOf(Parameter("level", "Brightness")), blocks = listOf(
            Block(op = "variable.change", arguments = mapOf("variable" to Expression.str("counter"), "by" to Expression.num(1))),
            Block(op = "scene.pixel", arguments = mapOf("x" to Expression.variable("counter"), "y" to Expression.event("row"), "level" to Expression.parameter("level"))),
        ))
        val doc = document(routine)
        assertTrue(PipelineCodec.validate(doc).toString(), PipelineCodec.validate(doc).isEmpty())
        for (size in listOf(13, 25)) {
            val host = Host(size)
            var counter = 4
            DrawingRoutineRenderer(doc, doc.entry()!!, host, variables = { mapOf("counter" to number(counter)) }).use { renderer ->
                val first = renderer.render("draw", mapOf("row" to number(6), "Brightness" to number(2048)))!!
                assertEquals(2048, first[6 * size + 5])
                assertEquals(1, first.count { it != 0 })
                assertArrayEquals(first, renderer.render("draw", mapOf("row" to number(6), "Brightness" to number(2048))))
                assertEquals(4, counter)
                counter = 7
                val changed = renderer.render("draw", mapOf("row" to number(6), "level" to number(4095)))!!
                assertEquals(4095, changed[6 * size + 8])
                assertEquals(0, changed[6 * size + 5])
                assertEquals(0, host.outputCalls)
                assertEquals(0, host.actionCalls)
                assertTrue(host.diagnostics.toString(), host.diagnostics.isEmpty())
            }
            assertEquals(0, host.outputCalls)
        }
    }

    @Test fun drawingResolvesImmutableOptionCountsOutsideItsPrivatePreparedProgram() {
        val draw = Routine("draw", "Draw", blocks = listOf(Block(op = "scene.pixel", arguments = mapOf(
            "x" to Expression("block.count", key = "options"), "y" to Expression.num(6), "level" to Expression.num(4095),
        ))))
        val original = document(draw)
        val doc = original.copy(programs = original.programs.map { it.copy(scripts = it.scripts + Script(trigger = Trigger("signal.unused"), blocks = listOf(
            Block(id = "options", op = "flow.select", body = listOf(Block(op = "display.off"), Block(op = "display.off"), Block(op = "display.off", enabled = false))),
        ))) })
        val host = Host(13)
        DrawingRoutineRenderer(doc, doc.entry()!!, host).use { renderer ->
            assertEquals(4095, renderer.render("draw", emptyMap())!![6 * 13 + 2])
            assertTrue(host.diagnostics.toString(), host.diagnostics.isEmpty())
        }
    }

    @Test fun drawingCannotWaitRunNativeBehaviorsOrSendHostActions() {
        for (op in listOf("flow.wait", "flow.forever", "display.toy", "signal.emit", "host.chime", "program.run")) {
            val routine = Routine(id = "draw", name = "Draw", blocks = listOf(Block(op = op)))
            assertTrue(op, DrawingRoutineRenderer.validate(document(routine), routine.id).isNotEmpty())
            assertTrue(op, PipelineCodec.validate(document(routine)).isNotEmpty())
        }
    }

    @Test fun excessiveFiniteLoopStopsWithinOneFrameBudget() {
        val routine = Routine(id = "draw", name = "Draw", blocks = listOf(Block(op = "flow.repeat", arguments = mapOf("count" to Expression.num(1_000_000)),
            body = listOf(Block(op = "scene.pixel")))))
        val doc = document(routine)
        val host = Host(13)
        DrawingRoutineRenderer(doc, doc.entry()!!, host, limits = RuntimeLimits(maxInstructionsPerTurn = 64)).use { renderer ->
            assertNull(renderer.render("draw", emptyMap()))
            assertTrue(host.diagnostics.any { it.fatal })
            assertNull(renderer.render("draw", emptyMap()))
        }
        assertEquals(0, host.outputCalls)
        assertEquals(0, host.actionCalls)
    }

    @Test fun nativeInstancesBindTheSameRoutineUsingTheirOwnProgramVariables() {
        val draw = Routine(id = "draw", name = "Draw", blocks = listOf(Block(op = "scene.pixel", arguments = mapOf(
            "x" to Expression.variable("counter"), "y" to Expression.num(6), "level" to Expression.num(4095),
        ))))
        val child = Program(id = "child", name = "Child", parameters = listOf(Parameter("seed", "Seed")), variables = listOf(Variable("counter", "Counter")), scripts = listOf(Script(blocks = listOf(
            Block(op = "variable.set", arguments = mapOf("variable" to Expression.str("counter"), "value" to Expression.parameter("seed"))),
            Block(op = "display.toy", arguments = mapOf("toy" to Expression.str("fixture"), "binding:frame" to Expression.str("visual"))),
        ))))
        val main = Program(id = "main", name = "Main", scripts = listOf(Script(blocks = listOf(3, 8).map { seed ->
            Block(op = "program.run", arguments = mapOf("program" to Expression.str("child"), "slot" to Expression.str("child$seed"), "arg:seed" to Expression.num(seed)))
        })))
        val doc = PipelineDocument(entryPoint = main.id, programs = listOf(main, child), routines = listOf(draw), bindings = mapOf("visual" to AssetBinding(routineId = "draw")))
        val rendered = mutableMapOf<String, IntArray>()
        val host = Host(13) { _, context -> object : NativeBehavior {
            override fun start() {
                val frame = context.renderDrawing(context.bindings.getValue("frame").routineId!!, emptyMap())!!
                rendered[context.instanceId] = frame
                context.emitFrame(frame)
            }
            override fun close() = Unit
        } }
        PipelineRuntime(doc, host).use { runtime ->
            runtime.start()
            assertTrue(host.diagnostics.toString(), host.diagnostics.isEmpty())
            assertEquals(2, rendered.size)
            assertEquals(setOf(3, 8), rendered.values.map { frame -> frame.indices.single { frame[it] > 0 } % 13 }.toSet())
        }
    }
}
