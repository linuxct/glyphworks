package space.linuxct.glyphworks.ui.pipeline

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.pipeline.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import java.io.File

/** Production Compose editing and local PNGs; no Core initialization or physical display. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h880dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineEditorUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun sample() = PipelineDocument(id = "editor_fixture", name = "My background", entryPoint = "ambient", programs = listOf(
        Program(id = "ambient", name = "My background", kind = ProgramKind.AMBIENT,
            variables = listOf(Variable("position", "Position", initial = number(0))), scripts = listOf(
                Script(id = "start", name = "When started", blocks = listOf(
                    Block(id = "forever", op = "flow.forever", body = listOf(
                        Block(id = "clock", op = "display.toy", arguments = mapOf("toy" to Expression.str("clock"))),
                        Block(id = "wait", op = "flow.wait", arguments = mapOf("duration" to Expression.ms(15000))),
                    )),
                )),
                Script(id = "press", name = "Action key", trigger = Trigger("key.action"), blocks = listOf(Block(op = "variable.change", arguments = mapOf("variable" to Expression.str("position"), "by" to Expression.num(1))))),
            )),
        ), editor = EditorMetadata(viewport = Viewport(0f, 12f, .9f)))

    @Test fun rendersRealWorkspaceLucent() = withEditor(sample(), lucent = true) { _, _ -> save("workspace-lucent") }
    @Test fun rendersAmbientBackgroundRoutineAndPalette() = withEditor(BuiltinPipelines.ambient()) { controller, current ->
        compose.runOnIdle { controller.selectedRoutine = current().routines.first().id }
        compose.waitForIdle()
        save("ambient-background-routine")
        compose.onNodeWithText("Blocks", substring = false).performClick()
        save("block-library")
    }
    @Test fun rendersRealWorkspaceLegacy() = withEditor(sample(), lucent = false) { _, _ -> save("workspace-legacy") }
    @Test @Config(qualifiers = "w360dp-h720dp-night-xxhdpi") fun rendersCompactDarkWorkspace() = withEditor(sample(), lucent = true) { _, _ -> save("workspace-compact-dark") }
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xxhdpi") fun largeFontsKeepWorkspaceAndScrolledToolbarReachable() = withEditor(sample(), fontScale = 1.5f) { _, _ ->
        compose.onNodeWithTag("pipeline-canvas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Editor menu").assertIsDisplayed()
        save("workspace-large-font")
        compose.onNodeWithText("Routines").performScrollTo().performClick()
        compose.onNodeWithText("New routine").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xxhdpi") fun previewAndPaletteLeaveCanvasSpaceAtLargeFontSizes() = withEditor(sample(), fontScale = 1.5f, withPreview = true) { controller, _ ->
        compose.onNodeWithTag("pipeline-canvas").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("pipeline-canvas").fetchSemanticsNode().boundsInRoot.height > 300f)
        save("preview-compact-large-font")
        compose.onNodeWithText("Sample inputs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Blocks", substring = false).performClick()
        compose.waitForIdle()
        assertFalse(controller.previewExpanded)
        compose.onNodeWithTag("pipeline-palette").assertIsDisplayed()
        compose.onNodeWithTag("pipeline-canvas").assertIsDisplayed()
        save("palette-compact-large-font")
    }

    @Test fun addBlockUndoRedoAndOpenRoutineUseProductionCommands() {
        val doc = PipelineDocument(id = "blank_fixture", name = "New toy", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "New toy", scripts = listOf(Script(id = "start")))))
        withEditor(doc) { controller, current ->
            compose.onNodeWithText("Blocks", substring = true).performClick()
            compose.onNodeWithText("Find a block").performTextInput("Wait")
            compose.onNode(hasText("Wait", substring = false) and !hasSetTextAction() and hasAnyAncestor(hasTestTag("pipeline-palette"))).performClick()
            compose.waitForIdle()
            assertEquals("flow.wait", current().entry()!!.scripts.single().blocks.single().op)
            compose.onNodeWithContentDescription("Undo").performClick()
            compose.waitForIdle()
            assertTrue(current().entry()!!.scripts.single().blocks.isEmpty())
            compose.onNodeWithContentDescription("Redo").performClick()
            compose.waitForIdle()
            assertEquals(1, current().entry()!!.scripts.single().blocks.size)
            compose.onNodeWithText("Routines").performClick()
            compose.onNodeWithText("New routine").performClick()
            compose.waitForIdle()
            assertEquals(current().routines.single().id, controller.selectedRoutine)
            assertEquals(EditorPanel.NONE, controller.panel)
            save("routine-workspace")
        }
    }

    @Test fun contextualInsertionUsesTheSelectedEventAndNewEventsHaveTheirOwnSpace() {
        withEditor(sample()) { controller, current ->
            val originalStart = current().entry()!!.scripts.first()
            compose.runOnIdle { controller.focusRequest = current().entry()!!.scripts.last().blocks.single().id }
            compose.waitForIdle()
            compose.onNodeWithText("Blocks", substring = false).performClick()
            compose.onNodeWithText("Find a block").performTextInput("Wait")
            compose.onNode(hasText("Wait", substring = false) and !hasSetTextAction() and hasAnyAncestor(hasTestTag("pipeline-palette"))).performClick()
            compose.waitForIdle()
            assertEquals(originalStart, current().entry()!!.scripts.first())
            assertEquals(listOf("variable.change", "flow.wait"), current().entry()!!.scripts.last().blocks.map { it.op })
            compose.onNodeWithText("New event").performClick()
            compose.waitForIdle()
            val added = current().entry()!!.scripts.last()
            assertEquals(added.id, controller.selectedScript)
            val position = current().editor.positions.getValue(added.id)
            assertTrue("New event must clear the visible event column", position.x >= 704f)
            compose.onNodeWithText("Event settings").assertIsDisplayed()
            save("event-settings")
        }
    }

    @Test fun choosingAnotherEventClearsTheOldBlockInsertionContext() = withEditor(sample()) { controller, current ->
        val originalStart = current().entry()!!.scripts.first()
        compose.runOnIdle {
            controller.selectedBlock = "clock"
            controller.insertion = BlockLocation("start", "forever", index = 0)
        }
        compose.onNodeWithContentDescription("Fit all").performClick()
        compose.onNodeWithTag("pipeline-script:press").performClick()
        compose.waitForIdle()
        assertEquals("press", controller.selectedScript)
        assertNull(controller.selectedBlock)
        assertNull(controller.insertion)
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithText("Blocks", substring = false).performClick()
        compose.onNodeWithText("Find a block").performTextInput("Wait")
        compose.onNode(hasText("Wait", substring = false) and !hasSetTextAction() and hasAnyAncestor(hasTestTag("pipeline-palette"))).performClick()
        compose.waitForIdle()
        assertEquals(originalStart, current().entry()!!.scripts.first())
        assertEquals(listOf("variable.change", "flow.wait"), current().entry()!!.scripts.last().blocks.map { it.op })
    }

    @Test fun nativeDinoArtworkSlotsAndOverridesHaveActualControls() {
        val doc = sample().let { original -> EditorDocument.update(original, EditorDocument.block(original, "clock")!!.copy(arguments = mapOf("toy" to Expression.str("dino")))) }
        withEditor(doc) { controller, _ ->
            compose.runOnIdle { controller.selectedBlock = "clock"; controller.panel = EditorPanel.BLOCK }
            compose.waitForIdle()
            compose.onNodeWithText("Block settings").assertExists()
            compose.onNodeWithText("Toy options", substring = false).assertExists()
            save("dino-properties")
        }
    }

    @Test fun longPressMovesBlockIntoOtherwiseAndUndoRestoresOriginalTree() {
        val doc = PipelineDocument(id = "drag_fixture", name = "Branches", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "Branches", scripts = listOf(Script(id = "start", blocks = listOf(
            Block(id = "condition", op = "flow.if", arguments = mapOf("condition" to Expression.bool(true))),
            Block(id = "wait", op = "flow.wait", arguments = mapOf("duration" to Expression.ms(1000))),
        ))))), editor = EditorMetadata(viewport = Viewport(0f, 0f, .85f)))
        withEditor(doc) { _, current ->
            val start = compose.onNodeWithTag("pipeline-block:wait").fetchSemanticsNode().boundsInRoot.center
            val destination = compose.onNodeWithTag("pipeline-insertion:start:condition:OTHERWISE:0").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveBy(Offset.Zero); moveTo(destination, 250); up() }
            compose.waitForIdle()
            assertEquals(listOf("condition"), current().entry()!!.scripts.single().blocks.map { it.id })
            assertEquals("wait", EditorDocument.block(current(), "condition")!!.otherwise.single().id)
            compose.onNodeWithContentDescription("Undo").performClick()
            compose.waitForIdle()
            assertEquals(doc.programs, current().programs)
        }
    }

    @Test fun cancelledDragDoesNotChangeDocumentAndRepeatedStackDragsAccumulate() {
        withEditor(sample()) { _, current ->
            val before = current().programs
            val start = compose.onNodeWithTag("pipeline-block:clock").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveBy(Offset(12f, 30f)); cancel() }
            compose.waitForIdle()
            assertEquals(before, current().programs)
            fun moveStack() {
                val center = compose.onNodeWithTag("pipeline-script:start").fetchSemanticsNode().boundsInRoot.center
                compose.onRoot().performTouchInput { down(center); advanceEventTime(700); moveBy(Offset.Zero); moveTo(center + Offset(45f, 60f), 200); up() }
                compose.waitForIdle()
            }
            moveStack()
            val first = current().editor.positions.getValue("start")
            assertTrue(first.x > 24f && first.y > 24f)
            moveStack()
            val second = current().editor.positions.getValue("start")
            assertTrue(second.x > first.x && second.y > first.y)
            assertEquals(before, current().programs)
        }
    }

    @Test fun blankCanvasPansAndPinchChangesZoomWithoutEditingBlocks() {
        withEditor(sample()) { _, current ->
            val before = current().programs
            val canvas = compose.onNodeWithTag("pipeline-canvas")
            val original = current().editor.viewport
            canvas.performTouchInput { down(Offset(width * .5f, height * .9f)); moveTo(Offset(width * .65f, height * .8f), 300) }
            compose.waitForIdle()
            assertEquals("Movement must not rewrite the pipeline while the finger is down", original, current().editor.viewport)
            canvas.performTouchInput { up() }
            compose.waitForIdle()
            assertNotEquals(original.x, current().editor.viewport.x)
            assertNotEquals(original.y, current().editor.viewport.y)
            val oldScale = current().editor.viewport.scale
            canvas.performTouchInput {
                val center = Offset(width * .5f, height * .8f)
                pinch(start0 = center - Offset(45f, 0f), end0 = center - Offset(120f, 0f), start1 = center + Offset(45f, 0f), end1 = center + Offset(120f, 0f), durationMillis = 400)
            }
            compose.waitForIdle()
            assertTrue(current().editor.viewport.scale > oldScale)
            assertTrue(current().editor.viewport.x.isFinite() && current().editor.viewport.y.isFinite())
            assertEquals(before, current().programs)
        }
    }

    @Test fun pinchOverBlocksAndEventHeadersZoomsWithoutOpeningSettings() = withEditor(sample()) { controller, current ->
        val programs = current().programs
        val positions = current().editor.positions
        for (tag in listOf("pipeline-block:clock", "pipeline-script:start")) {
            compose.runOnIdle { controller.focusRequest = tag.substringAfter(':') }
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val center = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
            val before = current().editor.viewport.scale
            val endSpan = if (before > 1f) 25f else 200f
            compose.onRoot().performTouchInput {
                pinch(start0 = center - Offset(100f, 0f), end0 = center - Offset(endSpan, 0f),
                    start1 = center + Offset(100f, 0f), end1 = center + Offset(endSpan, 0f), durationMillis = 350)
            }
            compose.waitForIdle()
            assertNotEquals("Pinch must work over $tag", before, current().editor.viewport.scale)
            assertEquals(EditorPanel.NONE, controller.panel)
            assertEquals(programs, current().programs)
            assertEquals(positions, current().editor.positions)
        }
    }

    @Test fun secondFingerTakesOverHeldBlockForZoomWithoutMovingIt() = withEditor(sample()) { controller, current ->
        val before = current()
        val center = compose.onNodeWithTag("pipeline-block:clock").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(0, center - Offset(80f, 0f)); advanceEventTime(700); moveBy(Offset.Zero) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(1, center + Offset(80f, 0f))
            moveTo(0, center - Offset(150f, 0f), delayMillis = 80)
            moveTo(1, center + Offset(150f, 0f), delayMillis = 80)
            up(1); up(0)
        }
        compose.waitForIdle()
        assertTrue(current().editor.viewport.scale > before.editor.viewport.scale)
        assertEquals(before.programs, current().programs)
        assertEquals(before.editor.positions, current().editor.positions)
        assertEquals(EditorPanel.NONE, controller.panel)
    }

    @Suppress("DEPRECATION")
    private fun withEditor(initial: PipelineDocument, lucent: Boolean = true, fontScale: Float = 1f, withPreview: Boolean = false, test: (PipelineEditorController, () -> PipelineDocument) -> Unit) {
        val fixture = TestHarness(13)
        fixture.prefs.putBoolean(PrefKeys.LUCENT_ENABLED, lucent)
        Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, fixture.prefs)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        val controller = PipelineEditorController().apply { previewExpanded = withPreview }
        val simulation = if (withPreview) PipelineSimulation(initial) else null
        var document by mutableStateOf(initial)
        val resources = activity.get().resources
        val originalConfiguration = android.content.res.Configuration(resources.configuration)
        try {
            resources.updateConfiguration(android.content.res.Configuration(originalConfiguration).apply { this.fontScale = fontScale }, resources.displayMetrics)
            activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity.setup()
            activity.get().setContent {
                GlyphWorksTheme {
                    PipelineEditor(document, { document = it }, {}, {}, {}, controller = controller,
                        previewContent = simulation?.let { { PipelineSimulationPanel(it, autoRun = false) } })
                }
            }
            compose.waitForIdle()
            test(controller) { document }
        } finally {
            activity.close()
            simulation?.close()
            resources.updateConfiguration(originalConfiguration, resources.displayMetrics)
            Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun save(name: String) {
        val output = File("build/reports/pipeline/editor/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
