package space.linuxct.glyphworks.ui.pipeline

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
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
import java.io.File

/** Exercise real property editing, including units and explicit native overrides, without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h880dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineFieldsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun namedNativeOverridePickerEditsOnlyTheChosenSetting() = withBlock(
        Block(id = "selected", op = "display.toy", arguments = mapOf("toy" to Expression.str("dino"))),
    ) { current ->
        compose.onNodeWithText("Block settings").assertExists()
        compose.onNodeWithText("Toy options").assertIsDisplayed()
        save("dino-expanded-inspector")
        compose.onNodeWithText("Toy options").performClick()
        compose.onNodeWithText("Customize a setting").performScrollTo().performClick()
        save("dino-setting-picker", dialog = true)
        compose.onNodeWithText("Jump velocity", substring = false).performClick()
        compose.onNode(hasSetTextAction() and hasText("Value")).performScrollTo().performTextReplacement("0,8")
        compose.waitForIdle()
        val fields = (current().arguments.getValue("parameters").value as Value.Record).fields
        assertEquals(setOf("jumpVelocity"), fields.keys)
        assertEquals(number(0.8), fields["jumpVelocity"])
        compose.onNode(hasSetTextAction() and hasText("Value")).assertTextContains("0,8")
        compose.onNodeWithText("1 customized setting").assertExists()
        save("dino-customized-inspector")
        compose.onNodeWithContentDescription("Use the toy’s setting").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue((current().arguments.getValue("parameters").value as Value.Record).fields.isEmpty())
    }

    @Test fun durationUnitsChangeTheEditorWithoutChangingStoredMilliseconds() = withBlock(
        Block(id = "selected", op = "flow.wait", arguments = mapOf("duration" to Expression.ms(1500))),
    ) { current ->
        fun input() = compose.onNode(hasSetTextAction() and hasText("Duration"))
        input().performTextReplacement("2.5")
        compose.waitForIdle()
        assertEquals(duration(2500), current().arguments.getValue("duration").value)
        compose.onNodeWithText("s", substring = false).assertIsDisplayed()
        save("duration-seconds-inspector")
        compose.onNodeWithText("Unit").performClick()
        compose.onNodeWithText("ms", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(duration(2500), current().arguments.getValue("duration").value)
        input().assertTextContains("2500")
        input().performTextReplacement("750")
        compose.waitForIdle()
        assertEquals(duration(750), current().arguments.getValue("duration").value)
        compose.onNodeWithText("Unit").performClick()
        compose.onNodeWithText("minutes", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(duration(750), current().arguments.getValue("duration").value)
        input().performTextReplacement("1.5")
        compose.waitForIdle()
        assertEquals(duration(90_000), current().arguments.getValue("duration").value)
        compose.onNodeWithText("min", substring = false).assertIsDisplayed()
        save("duration-minutes-inspector")
    }

    @Test fun longChoicesOpenAtSelectionAndSearchReturnsAccessibleOptions() = withBlock(
        Block(id = "selected", op = "display.toy", arguments = mapOf("toy" to Expression.str("weather"))),
    ) { current ->
        compose.onNodeWithText("Behavior", substring = false).performClick()
        compose.onNode(hasText("Weather", substring = false) and isSelectable()).assertIsDisplayed().assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText("Search")).performTextReplacement("no matching toy")
        compose.onNodeWithText("No matching options.").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Search")).performTextReplacement("Clock")
        compose.onNode(hasText("Clock", substring = false) and isSelectable()).assertIsDisplayed().assertIsNotSelected().performClick()
        compose.waitForIdle()
        assertEquals(Expression.str("clock"), current().arguments.getValue("toy"))
    }

    @Test fun summariesKeepScopeNamesAndCompactNestedConditionsWithoutClippingWords() {
        val condition = Expression.operation("greaterEqual", Expression.variable("position"), Expression.parameter("minimum"))
        val block = Block(id = "check", op = "flow.if", arguments = mapOf("condition" to condition))
        val routine = Routine(id = "routine", name = "Cycle", parameters = listOf(Parameter("minimum", "Minimum visible hold", ValueType.NUMBER, number(0))), variables = listOf(Variable("position", "Background rotation position")), blocks = listOf(block))
        val document = PipelineDocument(name = "Labels", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "Labels")), routines = listOf(routine))
        assertEquals("Condition: Background rotation position ≥ Minimum visible hold", blockDisplaySummary(block, document))
        val four = Expression.operation("and", Expression.operation("and", condition, condition), Expression.operation("and", condition, condition))
        assertEquals("Condition: All 4 conditions", blockDisplaySummary(block.copy(arguments = mapOf("condition" to four)), document))
        val choices = Block(op = "flow.select", arguments = mapOf("index" to Expression.num(2), "wrap" to Expression.bool(true)))
        assertEquals("Selected option: 2", blockDisplaySummary(choices, document))
        assertTrue(blockDisplaySummary(choices.copy(arguments = choices.arguments + ("wrap" to Expression.bool(false))), document).contains("Wrap around: Off"))
    }

    @Test fun routinePickersResolveLocalShadowingBeforeTypeFilters() {
        val set = Block(id = "set_local", op = "variable.set", arguments = mapOf("variable" to Expression.str("global_counter"), "value" to Expression.num(2)))
        val append = Block(id = "append_global", op = "list.add", arguments = mapOf("variable" to Expression.str("global_list"), "value" to Expression.num(3)))
        val result = Block(id = "return_text", op = "flow.return", arguments = mapOf("value" to Expression.parameter("global_caption")))
        val routine = Routine(id = "local_scope", name = "Local scope", returns = ValueType.TEXT,
            variables = listOf(Variable("shared_variable", "Local counter", ValueType.NUMBER, number(1))),
            parameters = listOf(Parameter("shared_setting", "Local caption", ValueType.TEXT, text("Ready"))),
            blocks = listOf(set, append, result))
        val program = Program(id = "toy", name = "Scoped toy",
            variables = listOf(Variable("shared_variable", "Hidden global list", ValueType.LIST, Value.Items()), Variable("global_list", "Global list", ValueType.LIST, Value.Items()), Variable("global_counter", "Global counter")),
            parameters = listOf(Parameter("shared_setting", "Hidden global number", ValueType.NUMBER, number(5)), Parameter("global_caption", "Global caption", ValueType.TEXT, text("Hello"))),
            scripts = listOf(Script(id = "start", blocks = listOf(Block(op = "routine.call", arguments = mapOf("routine" to Expression.str(routine.id)))))))
        val document = PipelineDocument(id = "shadow_fixture", name = "Scoped choices", entryPoint = program.id, programs = listOf(program), routines = listOf(routine))
        assertEquals(emptyList<Diagnostic>(), PipelineCodec.validate(document))
        assertEquals(listOf("Local counter", "Global counter"), scopedEditorVariables(program, routine).filter { it.type == ValueType.NUMBER }.map { it.name })
        assertEquals(listOf("Global list"), scopedEditorVariables(program, routine).filter { it.type == ValueType.LIST }.map { it.name })
        assertEquals(listOf("Local caption", "Global caption"), scopedEditorParameters(program, routine).filter { it.type == ValueType.TEXT }.map { it.name })
        assertTrue(scopedEditorParameters(program, routine).none { it.type == ValueType.NUMBER })
        withDocument(document, set.id) { controller, current ->
            compose.onNodeWithText("Variable", substring = false).performClick()
            compose.onNodeWithText("Hidden global list").assertDoesNotExist()
            compose.onNodeWithText("Local counter").performClick()
            compose.waitForIdle()
            assertEquals(Expression.str("shared_variable"), EditorDocument.block(current(), set.id)?.arguments?.get("variable"))
            compose.runOnIdle { controller.selectedBlock = append.id }
            compose.onNodeWithText("List variable", substring = false).performClick()
            compose.onNodeWithText("Hidden global list").assertDoesNotExist()
            compose.onNodeWithText("Local counter").assertDoesNotExist()
            compose.onNodeWithText("Close", substring = false).performClick()
            compose.runOnIdle { controller.selectedBlock = result.id }
            compose.onNodeWithText("Global caption", substring = false).performClick()
            compose.onNodeWithText("Hidden global number").assertDoesNotExist()
            compose.onNodeWithText("Local caption", substring = false).performClick()
            compose.waitForIdle()
            assertEquals(Expression.parameter("shared_setting"), EditorDocument.block(current(), result.id)?.arguments?.get("value"))
            assertEquals(emptyList<Diagnostic>(), PipelineCodec.validate(current()))
        }
    }

    private fun withBlock(block: Block, action: (() -> Block) -> Unit) {
        val document = PipelineDocument(id = "fields_fixture", name = "My toy", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "My toy", scripts = listOf(Script(id = "start", blocks = listOf(block))))))
        withDocument(document, block.id) { _, current -> action { requireNotNull(EditorDocument.block(current(), block.id)) } }
    }

    @Suppress("DEPRECATION")
    private fun withDocument(initial: PipelineDocument, blockId: String, action: (PipelineEditorController, () -> PipelineDocument) -> Unit) {
        val fixture = TestHarness(13)
        fixture.prefs.putBoolean(PrefKeys.LUCENT_ENABLED, true)
        Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, fixture.prefs)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        val controller = PipelineEditorController().apply {
            selectedBlock = blockId
            selectedRoutine = initial.routines.firstOrNull { it.id == EditorDocument.location(initial, blockId)?.ownerId }?.id
            panel = EditorPanel.BLOCK
        }
        var document by mutableStateOf(initial)
        try {
            activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity.setup()
            activity.get().setContent { GlyphWorksTheme { PipelineEditor(document, { document = it }, {}, {}, {}, controller = controller) } }
            compose.waitForIdle()
            action(controller) { document }
        } finally {
            activity.close()
            Core::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun save(name: String, dialog: Boolean = false) {
        val output = File("build/reports/pipeline/editor/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        node.captureToImage().asAndroidBitmap().let { bitmap -> output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
