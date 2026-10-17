package space.linuxct.glyphworks.ui.pipeline

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.pipeline.*

class EditorDocumentTest {
    private val a = Block(id = "a", op = "flow.wait", arguments = mapOf("duration" to Expression.ms(100)))
    private val nested = Block(id = "nested", op = "flow.if", body = listOf(a), otherwise = listOf(Block(id = "else", op = "flow.wait")))
    private val b = Block(id = "b", op = "scene.present")
    private fun document() = PipelineDocument(id = "test", name = "Test", entryPoint = "program", programs = listOf(Program(id = "program", name = "Program", scripts = listOf(
        Script(id = "script", blocks = listOf(nested, b)), Script(id = "second"),
    ))), routines = listOf(Routine(id = "routine", name = "Routine")))

    @Test fun moveIntoElseAndUndoIsOneTransaction() {
        val original = document()
        val history = EditorHistory(original)
        history.change(EditorDocument.move(original, "b", BlockLocation("script", "nested", BlockBranch.OTHERWISE, 1)))
        assertEquals(listOf("else", "b"), EditorDocument.block(history.document, "nested")!!.otherwise.map { it.id })
        assertEquals(original, history.undo())
        assertEquals(listOf("else", "b"), EditorDocument.block(history.redo(), "nested")!!.otherwise.map { it.id })
    }

    @Test fun movingIntoSelfOrDescendantLeavesDocumentUntouched() {
        val original = document()
        assertSame(original, EditorDocument.move(original, "nested", BlockLocation("script", "nested", index = 0)))
        assertSame(original, EditorDocument.move(original, "nested", BlockLocation("script", "a", index = 0)))
    }

    @Test fun reorderWithinSameListCorrectsIndexAfterRemoval() {
        val original = document()
        val moved = EditorDocument.move(original, "nested", BlockLocation("script", index = 2))
        assertEquals(listOf("b", "nested"), moved.entry()!!.scripts.first().blocks.map { it.id })
        assertEquals(listOf("nested", "b"), EditorDocument.move(moved, "nested", BlockLocation("script", index = 0)).entry()!!.scripts.first().blocks.map { it.id })
    }

    @Test fun routineAndScriptBodiesAreIndependentMoveTargets() {
        val moved = EditorDocument.move(document(), "a", BlockLocation("routine"))
        assertTrue(EditorDocument.block(moved, "nested")!!.body.isEmpty())
        assertEquals(a, moved.routines.first().blocks.single())
        assertEquals(BlockLocation("routine"), EditorDocument.location(moved, "a"))
    }

    @Test fun duplicateRegeneratesAllIdsAndPreservesValues() {
        val duplicated = EditorDocument.duplicate(document(), "nested")
        val duplicate = duplicated.entry()!!.scripts.first().blocks[1]
        assertNotEquals(nested.id, duplicate.id)
        assertNotEquals(a.id, duplicate.body.single().id)
        assertNotEquals("else", duplicate.otherwise.single().id)
        assertEquals(a.arguments, duplicate.body.single().arguments)
    }

    @Test fun extractionLeavesARealCallToSharedRoutine() {
        val extracted = EditorDocument.extractRoutine(document(), "nested", "Menu")
        val call = extracted.entry()!!.scripts.first().blocks.first()
        assertEquals("routine.call", call.op)
        assertEquals("Menu", extracted.routines.first { it.id == call.arguments["routine"]!!.value.text() }.name)
        assertEquals(nested, extracted.routines.last().blocks.single())
    }

    @Test fun moveFollowingStackIsExplicitAndPreservesOrder() {
        val moved = EditorDocument.moveStack(document(), "nested", BlockLocation("second"))
        assertTrue(moved.entry()!!.scripts.first().blocks.isEmpty())
        assertEquals(listOf("nested", "b"), moved.entry()!!.scripts.last().blocks.map { it.id })
    }

    @Test fun invalidDestinationDoesNotDeleteAnything() {
        val original = document()
        assertSame(original, EditorDocument.move(original, "a", BlockLocation("missing")))
        assertSame(original, EditorDocument.insert(original, BlockLocation("missing"), Block(op = "flow.wait")))
        assertSame(original, EditorDocument.insert(original, BlockLocation("second"), a))
    }

    @Test fun editingDeeplyNestedBlockDoesNotChangeSiblingBranches() {
        val updated = EditorDocument.update(document(), a.copy(arguments = mapOf("duration" to Expression.ms(500))))
        assertEquals(500.0, EditorDocument.block(updated, "a")!!.arguments["duration"]!!.value.number(), 0.0)
        assertEquals(nested.otherwise, EditorDocument.block(updated, "nested")!!.otherwise)
        assertEquals(b, EditorDocument.block(updated, "b"))
    }

    @Test fun reuseImportsDependenciesWithoutChangingEntryOrStartingThem() {
        val source = document().copy(routines = listOf(Routine(id = "shared", name = "Shared", blocks = listOf(Block(id = "routine_block", op = "flow.wait")))))
        val target = document().copy(id = "target")
        val merged = EditorDocument.merge(target, source)
        assertEquals(target.entryPoint, merged.entryPoint)
        assertEquals(target.entry(), merged.entry())
        assertEquals(2, merged.programs.size)
        assertNotEquals(source.programs.single().id, merged.programs.last().id)
        assertNotEquals("shared", merged.routines.last().id)
        assertNotEquals("routine_block", merged.routines.last().blocks.single().id)
    }

    @Test fun metadataChangesDoNotCreateUndoSteps() {
        val history = EditorHistory(document())
        history.change(history.document.copy(editor = EditorMetadata(viewport = Viewport(500f, -800f, .6f))), record = false)
        assertFalse(history.canUndo)
        history.change(EditorDocument.remove(history.document, "a"))
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(Viewport(500f, -800f, .6f), history.document.editor.viewport)
        assertEquals(a, EditorDocument.block(history.document, "a"))
    }

    @Test fun everyCatalogBlockHasAValidatedPortableExampleAndPurpose() {
        BlockCatalog.all.forEach { spec ->
            val example = requireNotNull(PipelineBlockReference.example(spec.op))
            assertTrue(spec.op + ": " + PipelineCodec.validate(example).joinToString { it.message }, PipelineCodec.validate(example).isEmpty())
            assertTrue(spec.op, space.linuxct.glyphworks.pipeline.store.PipelineStore.validateArtwork(example).isEmpty())
            assertTrue(spec.op, PipelineBlockReference.purpose(spec).length > spec.title.length)
        }
    }

    @Test fun quickSettingsRespectTypedChoicesBoundsAndUnits() {
        val choice = Parameter("mode", "Mode", ValueType.TEXT, text("one"), choices = listOf(text("one"), text("two")), quickSetting = true)
        assertNull(parameterValueError(choice, text("two")))
        assertNotNull(parameterValueError(choice, text("three")))
        val time = Parameter("delay", "Delay", ValueType.DURATION, duration(1000), minimum = 100.0, maximum = 5000.0)
        assertNull(parameterValueError(time, duration(2000)))
        assertNotNull(parameterValueError(time, duration(50)))
        assertNotNull(parameterValueError(time, number(2000)))
        assertNull(parameterValueError(Parameter("list", "List", ValueType.LIST, Value.Items()), Value.Items(listOf(text("clock")))))
    }
    @Test fun copiedSubtreeRemapsItsOwnBlockCountReferences() {
        val source = Block(id = "options", op = "flow.select", arguments = mapOf("index" to Expression("block.count", key = "options")), body = listOf(a))
        val copy = EditorDocument.fresh(source)
        assertEquals(copy.id, copy.arguments.getValue("index").key)
        assertNotEquals(source.body.single().id, copy.body.single().id)
    }

}
