package space.linuxct.glyphworks.ui.pipeline.tutorial

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.ui.pipeline.CustomControlsSettingsContent
import space.linuxct.glyphworks.ui.pipeline.PipelineSimulationPanel
import space.linuxct.glyphworks.ui.pipeline.PipelineEditor
import space.linuxct.glyphworks.ui.pipeline.PipelineEditorController
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.TutorialTab
import space.linuxct.pipeline.*

/** Production editor + shared design-tour shell, rendered entirely in Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h933dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineTutorialRenderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun clearThemeFixture() {
        Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
    }

    @Test fun prefetchedTutorialTabMeasuresBeforeNavigationAndAllChaptersRemainReachable() {
        withActivity {
            HorizontalPager(
                state = rememberPagerState(pageCount = { 4 }),
                beyondViewportPageCount = 3,
                modifier = Modifier.fillMaxSize().testTag("main-tab-pager"),
            ) { page ->
                if (page == 3) TutorialTab(PaddingValues(top = 80.dp, bottom = 120.dp), rememberScrollState())
                else androidx.compose.material3.Text("Page $page")
            }
        }.use { activity ->
            // MainActivity premeasures Tutorials even when it opens on Toys.
            compose.waitForIdle()
            repeat(3) { compose.onNodeWithTag("main-tab-pager").performTouchInput { swipeLeft() }; compose.waitForIdle() }
            compose.onNodeWithText(activity.get().getString(space.linuxct.glyphworks.R.string.pipeline_tutorial_title)).performScrollTo().performClick()
            compose.waitForIdle()
            val popup = File("build/reports/pipeline/tutorial/chapter-picker.png")
            popup.parentFile.mkdirs()
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap -> popup.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val lastChapter = activity.get().getString(PipelineChapter.INSPECT.title)
            compose.onNodeWithText(lastChapter).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(PipelineTutorialActivity::class.java.name, shadowOf(activity.get()).nextStartedActivity.component?.className)
        }
    }

    @Test fun navigationMatchesDesignTourIncludingMidAnimationBackAndSkip() {
        var closed = 0
        withActivity {
            PipelineDemoTour(PipelineChapter.CANVAS, { closed++ })
        }.use { activity ->
            compose.onNodeWithText("Back").assertIsNotEnabled()
            compose.onNodeWithText("Step 1 of 10").assertExists()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("Step 2 of 10").assertExists()
            // The user is never required to finish a pointer demonstration before moving on.
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Step 2 of 10").assertExists()
            compose.onNodeWithText("Skip").performClick()
            compose.runOnIdle { assertEquals(1, closed) }
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            assertEquals(2, closed)
        }
    }

    @Test fun finalStepUsesDoneAndReturnsWithoutSaving() {
        var closed = 0
        withActivity { PipelineDemoTour(PipelineChapter.SAFETY, { closed++ }, initialStep = 4) }.use {
            compose.onNodeWithText("Done").assertExists().performClick()
            compose.runOnIdle { assertEquals(1, closed) }
        }
    }

    @Test fun canvasSpotlightPng() = render(PipelineChapter.CANVAS, 4, "tutorial-canvas")
    @Test fun ambientSpotlightPng() = render(PipelineChapter.AMBIENT, 2, "tutorial-ambient")
    @Test fun menuSpotlightPng() = render(PipelineChapter.MENU, 2, "tutorial-menu")
    @Test fun lockedToysSpotlightPng() = render(PipelineChapter.SAFETY, 1, "tutorial-locked-toys")
    @Test fun recoverySpotlightPng() = render(PipelineChapter.SAFETY, 2, "tutorial-recovery")
    @Test fun artworkEditorSpotlightPng() = render(PipelineChapter.TOY, 2, "tutorial-artwork")

    @Test fun simulatorFroggerProPng() = renderSimulation(13)
    @Test fun simulatorMetroidPng() = renderSimulation(25)
    @Test fun sampleEventFieldsReachTheSameInterpreter() {
        val program = Program("sample", "Sample", variables = listOf(Variable("score", "Score")), scripts = listOf(Script(trigger = Trigger("key.action"), blocks = listOf(
            Block(op = "variable.set", arguments = mapOf("variable" to Expression.str("score"), "value" to Expression.event("amount"))),
        ))))
        PipelineSimulation(PipelineDocument(entryPoint = program.id, programs = listOf(program))).use { simulation ->
            withActivity { PipelineSimulationPanel(simulation, autoRun = false) }.use {
                compose.onNodeWithText("Sample inputs").performScrollTo().performClick()
                compose.onNodeWithText("Event fields").performScrollTo().performClick()
                compose.onNodeWithText("New field name").performScrollTo().performTextInput("amount")
                compose.onNodeWithText("Add field").performScrollTo().performClick()
                compose.onNode(hasSetTextAction() and hasText("0")).performScrollTo().performTextReplacement("7")
                compose.onNodeWithText("Send event").performScrollTo().performClick()
                compose.runOnIdle { assertEquals(7.0, simulation.values.values.single().number(), 0.0) }
            }
        }
    }

    @Test fun customControlsSettingsPng() {
        withActivity {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CustomControlsSettingsContent(true, "My menu · Running", {}, {}, {})
                }
            }
        }.use { save("custom-controls-settings") }
    }

    @Test fun inspectorRemainsBehindTheTourAndDoesNotReceiveTouches() {
        val finished = AtomicInteger(-1)
        withActivity { PipelineDemoTour(PipelineChapter.CANVAS, {}, initialStep = 5, onStepFinished = finished::set) }.use {
            compose.waitUntil(timeoutMillis = 15_000) { finished.get() == 5 }
            // A ModalBottomSheet window would be above the tour and make this focus the field.
            val field = compose.onAllNodes(hasSetTextAction()).onFirst()
            field.performTouchInput { click() }
            field.assertIsNotFocused()
            compose.onNodeWithText("Next").assertIsDisplayed().performClick()
            compose.onNodeWithText("Step 7 of 10").assertExists()
        }
    }

    private fun render(chapter: PipelineChapter, step: Int, name: String) {
        val finished = AtomicInteger(-1)
        withActivity { PipelineDemoTour(chapter, {}, initialStep = step, onStepFinished = finished::set) }.use {
            compose.waitUntil(timeoutMillis = 15_000) { finished.get() == step }
            // The production design editor continuously animates its floating frame preview.
            // Freeze its clock for this still capture; waiting for that clock to finish is impossible.
            if (chapter == PipelineChapter.TOY && step == 2) compose.mainClock.autoAdvance = false
            compose.waitForIdle()
            if (chapter == PipelineChapter.SAFETY && step == 2) compose.onNodeWithText("Resume pipelines").assertIsDisplayed()
            save(name)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun renderSimulation(size: Int) {
        PipelineSimulation(BuiltinPipelines.toy("dino"), size).use { simulation ->
            simulation.dispatch(PipelineEvent("key.action")); simulation.advanceBy(100)
            withActivity {
                val controller = remember { PipelineEditorController().apply { previewExpanded = true } }
                PipelineEditor(simulation.document, {}, {}, {}, {}, controller = controller,
                    previewContent = { PipelineSimulationPanel(simulation, autoRun = false, panelSize = size) })
            }.use {
                save("simulator-$size")
                compose.onNodeWithText("Inspect").performScrollTo().performClick()
                compose.onNodeWithText("Inspector", ignoreCase = true).performScrollTo()
                save("simulator-inspector-$size")
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/reports/pipeline/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun withActivity(content: @androidx.compose.runtime.Composable () -> Unit): org.robolectric.android.controller.ActivityController<ComponentActivity> {
        val fixture = TestHarness(13)
        fixture.prefs.putBoolean(PrefKeys.LUCENT_ENABLED, true)
        Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, fixture.prefs)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        activity.get().setContent { GlyphWorksTheme(content = content) }
        return activity
    }
}
