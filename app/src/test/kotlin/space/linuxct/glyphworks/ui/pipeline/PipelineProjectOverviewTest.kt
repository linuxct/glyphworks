package space.linuxct.glyphworks.ui.pipeline

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h760dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineProjectOverviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun emptyProjectOffersAddAndCanvasActions() = withOverview(
        PipelineProjectOverviewPresentation(title = "", mode = "Toy", behaviors = emptyList(), onAddBehavior = {}, onEditBehavior = {}, onPreviewBehavior = {}, onOpenCanvas = {}),
    ) { actions ->
        compose.onNodeWithText("Start with a behavior").assertIsDisplayed()
        compose.onNodeWithText("Untitled project").assertIsDisplayed()
        compose.onNodeWithText("Add behavior").performClick()
        compose.onNodeWithText("Open full canvas").performScrollTo().performClick()
        assertEquals(listOf("add", "canvas"), actions)
    }

    @Test fun populatedProjectShowsHierarchyDisabledStatusAndPerBehaviorActions() = withOverview(
        PipelineProjectOverviewPresentation(
            title = "Long named pipeline for a narrow phone",
            mode = "Ambient",
            behaviors = listOf(
                PipelineProjectOverviewPresentation.Behavior(
                    title = "Low battery warning",
                    whenSummary = "Battery level changes",
                    ifSummary = "Level is below 20 percent",
                    thenSummary = "Show the charging glyph",
                    enabled = false,
                ),
            ),
            routines = listOf(PipelineProjectOverviewPresentation.ReusableSummary("Pulse routine", "Repeats a short animation")),
            artwork = listOf(PipelineProjectOverviewPresentation.ReusableSummary("Battery icon")),
            onAddBehavior = {}, onEditBehavior = {}, onPreviewBehavior = {}, onOpenCanvas = {},
        ),
    ) { actions ->
        compose.onNodeWithText("WHEN").assertIsDisplayed()
        compose.onNodeWithText("Battery level changes").assertIsDisplayed()
        compose.onNodeWithText("IF").assertIsDisplayed()
        compose.onNodeWithText("Level is below 20 percent").assertIsDisplayed()
        compose.onNodeWithText("THEN SHOW / DO").assertIsDisplayed()
        compose.onNodeWithText("Show the charging glyph").assertIsDisplayed()
        compose.onNodeWithText("Disabled").assertIsDisplayed()
        compose.onNodeWithText("Pulse routine").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Battery icon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        compose.onNodeWithText("Add").performScrollTo().performClick()
        assertEquals(listOf("edit:0", "preview:0", "add"), actions)
    }

    private fun withOverview(
        presentation: PipelineProjectOverviewPresentation,
        block: (MutableList<String>) -> Unit,
    ) {
        val actions = mutableListOf<String>()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val wired = presentation.copy(
            onAddBehavior = { actions += "add" },
            onEditBehavior = { actions += "edit:$it" },
            onPreviewBehavior = { actions += "preview:$it" },
            onOpenCanvas = { actions += "canvas" },
        )
        host.setContent {
            MaterialTheme {
                Surface {
                    PipelineProjectOverview(wired)
                }
            }
        }
        compose.waitForIdle()
        block(actions)
        host.finish()
    }
}
