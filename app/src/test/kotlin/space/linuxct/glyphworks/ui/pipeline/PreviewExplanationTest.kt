package space.linuxct.glyphworks.ui.pipeline

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.After
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
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h933dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewExplanationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun clearThemeFixture() {
        Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
    }

    @Test fun allTraceStatesAndSuppliedTrailRenderWithoutDerivingAnOutcome() {
        val state = mutableStateOf(PreviewExplanationState.NO_EVENT)
        val explanation = PreviewExplanation(
            state = PreviewExplanationState.NO_EVENT,
            summary = "The preview is waiting for an event.",
            reason = "No matching event has been sent in this session.",
            blockId = "script-1",
            steps = listOf(PreviewExplanationStep("Trigger", "Waiting for key.action", "trigger-1")),
        )
        val simulation = PipelineSimulation(BuiltinPipelines.toy("dino"))
        try {
            val activity = withActivity {
                PipelineSimulationPanel(
                    simulation = simulation,
                    autoRun = false,
                    explanation = explanation.copy(state = state.value),
                )
            }
            activity.use {
                compose.onNodeWithText(explanation.summary).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Why?").performScrollTo().performClick()
                compose.onNodeWithText(explanation.reason!!).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Waiting for key.action").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Block · script-1").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Block · trigger-1").performScrollTo().assertIsDisplayed()

                val states = mapOf(
                    PreviewExplanationState.NO_EVENT to "No event",
                    PreviewExplanationState.HANDLED to "Handled",
                    PreviewExplanationState.CONDITION_SKIPPED to "Condition skipped",
                    PreviewExplanationState.DISPLAYED to "Displayed",
                    PreviewExplanationState.COMPETING_ACTION_WON to "Another action won",
                    PreviewExplanationState.MISSING_INPUT to "Input unavailable",
                    PreviewExplanationState.ERROR to "Runtime error",
                )
                states.forEach { (traceState, label) ->
                    compose.runOnIdle { state.value = traceState }
                    compose.waitForIdle()
                    compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                }
            }
        } finally {
            simulation.close()
        }
    }

    @Test fun nullExplanationLeavesTheExistingPreviewWithoutAnExplanationCard() {
        val simulation = PipelineSimulation(BuiltinPipelines.toy("dino"))
        try {
            withActivity { PipelineSimulationPanel(simulation = simulation, autoRun = false) }.use {
                compose.onNodeWithText("Preview").performScrollTo().assertIsDisplayed()
                assertTrue(compose.onAllNodesWithText("Latest decision").fetchSemanticsNodes().isEmpty())
            }
        } finally {
            simulation.close()
        }
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
