package space.linuxct.glyphworks.ui.pipeline.tutorial

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.ui.tutorial.*
import space.linuxct.pipeline.PipelineCodec

class PipelineDemoTest {
    private fun replay(chapter: PipelineChapter, count: Int): PipelineDemoSandbox = runBlocking {
        val sandbox = PipelineDemoSandbox(chapter)
        val actor = TourActor(TourGhost(), TourTargets<String>(), instant = true)
        pipelineDemoSteps(chapter).take(count).forEach { it.act(actor, sandbox) }
        sandbox
    }

    @Test fun everyChapterUsesCaptionsAndEndsWithAnUnfocusedDoneStep() {
        PipelineChapter.entries.forEach { chapter ->
            val steps = pipelineDemoSteps(chapter)
            assertTrue(chapter.name, steps.size >= 5)
            assertTrue(steps.all { it.caption != 0 })
            assertNull(steps.last().target)
            assertEquals(steps.size, steps.map { it.caption }.toSet().size)
        }
    }

    @Test fun instantReplayDoesNotNeedAFrameClockAndIsDeterministic() {
        PipelineChapter.entries.forEach { chapter ->
            val steps = pipelineDemoSteps(chapter)
            for (at in steps.indices) {
                val a = replay(chapter, at)
                val b = replay(chapter, at)
                assertEquals("$chapter at $at", a.document, b.document)
                assertEquals(a.previewEvents, b.previewEvents)
                assertEquals(a.previewTime, b.previewTime)
                assertEquals(a.stage, b.stage)
            }
        }
    }

    @Test fun everyFinishedChapterLeavesAWellFormedDemonstration() {
        PipelineChapter.entries.forEach { chapter ->
            val sandbox = replay(chapter, pipelineDemoSteps(chapter).size)
            val diagnostics = PipelineCodec.validateDraft(sandbox.document)
            assertTrue("$chapter: $diagnostics", diagnostics.isEmpty())
            if (chapter != PipelineChapter.SAFETY) {
                val errors = PipelineCodec.validate(sandbox.document)
                assertTrue("$chapter: $errors", errors.isEmpty())
            }
        }
    }

    @Test fun dinoArtworkDemonstrationContainsBothPanelVariantsAndASeparateBinding() {
        val sandbox = replay(PipelineChapter.TOY, pipelineDemoSteps(PipelineChapter.TOY).size)
        assertEquals(setOf("bellsprout", "arbok"), (sandbox.document.designs.values.single()["variants"] as kotlinx.serialization.json.JsonObject).keys)
        assertEquals(1, sandbox.document.bindings.size)
        assertEquals(sandbox.document.designs.keys.single(), sandbox.document.bindings.values.single().assetId)
    }

    @Test fun safetyChapterOnlyChangesItsOwnStateAndReturnsToStandard() {
        val sandbox = replay(PipelineChapter.SAFETY, pipelineDemoSteps(PipelineChapter.SAFETY).size)
        assertFalse(sandbox.customControls)
        assertFalse(sandbox.stopped)
        assertEquals("tutorialProject", sandbox.document.id)
        assertTrue(sandbox.document.programs.single().scripts.isEmpty())
    }
}
