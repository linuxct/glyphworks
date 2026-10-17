package space.linuxct.glyphworks.pipeline.store

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.linuxct.pipeline.*
import java.io.File
import kotlinx.serialization.json.*

class PipelineStoreTest {
    @get:Rule val files = TemporaryFolder()
    private fun store() = PipelineStore(files.newFolder())
    private fun document(id: String = "example") = PipelineDocument(
        id = id, name = "My clock", createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z",
        entryPoint = "clockProgram", programs = listOf(Program(id = "clockProgram", name = "Clock", scripts = listOf(
            Script(id = "startScript", blocks = listOf(Block(id = "displayBlock", op = "display.off"))),
        ))),
    )
    private fun saved(result: PipelineStore.SaveResult) = (result as PipelineStore.SaveResult.Saved).snapshot

    @Test fun incompleteDraftNeverReplacesAppliedRevision() {
        val store = store()
        val original = saved(store.apply(document()))
        val incomplete = document().copy(programs = document().programs.map { it.copy(scripts = emptyList()) })
        val draft = saved(store.saveDraft(incomplete, original.generation))
        assertEquals(incomplete.programs, store.loadDraft("example")!!.document.programs)
        assertEquals(document().programs, store.loadApplied("example")!!.document.programs)
        assertTrue(draft.generation > original.generation)
        assertTrue(store.list().single().hasDraft)
    }

    @Test fun quickSettingAndEditorSavesDetectConflictingRevisions() {
        val store = store()
        val first = saved(store.apply(document()))
        val quick = saved(store.apply(document().copy(name = "Changed elsewhere"), first.generation))
        val stale = store.saveDraft(document().copy(name = "Old editor"), first.generation)
        assertEquals(PipelineStore.SaveResult.Conflict(quick.generation), stale)
        assertEquals("Changed elsewhere", store.loadApplied("example")!!.document.name)
    }

    @Test fun importWithMatchingIdentityAlwaysCreatesAnotherInactiveCopy() {
        val store = store()
        saved(store.apply(document()))
        val imported = saved(store.importDocument(document()))
        assertNotEquals("example", imported.document.id)
        assertNotEquals("clockProgram", imported.document.entryPoint)
        assertEquals(2, store.list().size)
        assertEquals("clockProgram", store.loadApplied("example")!!.document.entryPoint)
        assertTrue(imported.document.programs.all { it.template == null })
    }

    @Test fun interruptedPointerReplacementRecoversLastWholeProject() {
        val dir = files.newFolder()
        val store = PipelineStore(dir)
        saved(store.apply(document()))
        val pointer = File(dir, "example/applied-head.json")
        assertTrue(pointer.renameTo(File(pointer.parentFile, pointer.name + ".bak")))
        File(pointer.parentFile, "revision-2.json").writeText("{incomplete")
        val restored = PipelineStore(dir).loadApplied("example")!!
        assertEquals(1, restored.revision)
        assertEquals("My clock", restored.document.name)
    }

    @Test fun staleDraftIsIgnoredAfterAtomicApplyEvenIfItsPointerSurvives() {
        val dir = files.newFolder()
        val store = PipelineStore(dir)
        val first = saved(store.saveDraft(document()))
        val applied = saved(store.apply(first.document, first.generation))
        assertNull(store.loadDraft("example"))
        assertFalse(store.list().single().hasDraft)
        assertEquals(applied.document, PipelineStore(dir).loadApplied("example")!!.document)
    }

    @Test fun revisionsAreBoundedAndRestoringCreatesANewRevision() {
        val store = store()
        repeat(15) { saved(store.apply(document().copy(name = "Revision $it"))) }
        assertEquals(PipelineStore.HISTORY_LIMIT, store.revisions("example").size)
        val restored = saved(store.restoreRevision("example", 5))
        assertEquals("Revision 4", restored.document.name)
        assertEquals(16, restored.revision)
    }

    @Test fun identifiersCannotEscapeStorage() {
        val store = store()
        listOf("../outside", "a/b", "..", "", "a\\b", "a.json").forEach { id ->
            assertTrue(id, store.saveDraft(document(id)) is PipelineStore.SaveResult.Invalid)
            assertNull(store.loadApplied(id))
            assertFalse(store.delete(id))
        }
    }

    @Test fun deletingAndRecreatingDoesNotResurrectDraftOrRevisionBackups() {
        val store = store()
        saved(store.apply(document()))
        saved(store.saveDraft(document().copy(name = "Draft")))
        assertTrue(store.delete("example"))
        assertNull(store.loadApplied("example"))
        assertNull(store.loadDraft("example"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun completeSharedBundleKeepsBothPanelsRoutineArgumentsBindingsAndRequirements() {
        val art = buildJsonObject {
            put("format", "glyph.design"); put("formatVersion", 1); put("id", "jump")
            put("name", "Jump"); put("kind", "dynamic")
            put("createdAt", "2026-10-06T00:00:00Z"); put("modifiedAt", "2026-10-06T00:00:00Z")
            put("levels", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(4095))))
            put("variants", buildJsonObject {
                listOf("bellsprout" to 13, "arbok" to 25).forEach { (name, size) ->
                    put(name, buildJsonObject { put("frames", JsonArray(listOf(buildJsonObject {
                        put("cells", "1".repeat(size * size)); put("durationMs", 140)
                    }))) })
                }
            })
        }
        val routine = Routine(id = "routine", name = "Show and return", parameters = listOf(Parameter("amount", "Amount")),
            returns = ValueType.NUMBER, blocks = listOf(
                Block(op = "display.animation", arguments = mapOf("asset" to Expression.str("jump"))),
                Block(op = "display.toy", arguments = mapOf("toy" to Expression.str("dino"), "binding:jump" to Expression.str("jumpBinding"))),
                Block(op = "flow.return", arguments = mapOf("value" to Expression.parameter("amount"))),
            ))
        val program = Program(id = "main", name = "Custom runner", variables = listOf(Variable("score", "Score")), scripts = listOf(
            Script(blocks = listOf(Block(op = "routine.call", arguments = mapOf("routine" to Expression.str("routine"),
                "arg:amount" to Expression.num(7), "resultVariable" to Expression.str("score")))))
        ))
        val source = document().copy(entryPoint = "main", programs = listOf(program), routines = listOf(routine),
            designs = mapOf("jump" to art), bindings = mapOf("jumpBinding" to AssetBinding("jump")), requires = listOf(Requirement("native.dino")))
        val decoded = (PipelineCodec.decode(PipelineCodec.encode(source)) as PipelineCodec.Result.Ok).document
        val store = store()
        val imported = saved(store.importDocument(decoded)).document
        val importedAgain = saved(store.importDocument(decoded)).document
        assertNotEquals(imported.id, importedAgain.id)
        assertEquals(setOf("bellsprout", "arbok"), (imported.designs.values.single()["variants"] as JsonObject).keys)
        assertEquals(imported.designs.keys.single(), imported.bindings.values.single().assetId)
        val call = imported.programs.single().scripts.single().blocks.single()
        assertEquals(imported.routines.single().id, call.arguments.getValue("routine").value.text())
        assertEquals(number(7), call.arguments.getValue("arg:" + imported.routines.single().parameters.single().id).value)
        assertEquals(imported.programs.single().variables.single().id, call.arguments.getValue("resultVariable").value.text())
        assertEquals(source.requires, imported.requires)
        assertEquals(imported, store.loadApplied(imported.id)!!.document)
    }
}
