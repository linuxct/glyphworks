package space.linuxct.glyphworks.ui.pipeline

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.pipeline.runtime.PipelineController
import space.linuxct.glyphworks.pipeline.runtime.PipelinePrefs
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.pipeline.store.*
import space.linuxct.glyphworks.ui.design.EditorScaffold
import space.linuxct.glyphworks.ui.design.EditorState
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.pipeline.*
import java.io.File

/** Exercise the production library, Android document roundtrip and embedded artwork editor locally. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h880dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineAuthoringFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun createToyExportAndImportThroughActualLibraryRemainInactive() = withActivity { activity, fixture, store, _ ->
        var opened: String? = null
        activity.setContent { GlyphWorksTheme { PipelineLibraryScreen(onOpen = { opened = it }) } }
        save("library-empty")
        compose.onNodeWithText("New pipeline", substring = true).performClick()
        save("library-new", dialog = true)
        compose.onNodeWithText("Name").performTextInput("My new toy")
        compose.onNodeWithText("Create", substring = false).performClick()
        compose.waitUntil(10_000) { opened != null }
        val original = store.loadDraft(opened!!)!!
        assertEquals(ProgramKind.TOY, original.document.entry()!!.kind)
        assertNull(store.loadApplied(opened!!))
        assertFalse(fixture.prefs.getBoolean(PipelinePrefs.CONTROLLER_ENABLED, false))
        val ready = original.document.copy(programs = original.document.programs.map { p -> p.copy(scripts = p.scripts.map { it.copy(blocks = listOf(Block(op = "display.off"))) }) })
        assertTrue(store.apply(ready, original.generation) is PipelineStore.SaveResult.Saved)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for My new toy").performClick()
        compose.onNodeWithText("Export JSON").performClick()
        compose.waitUntil(10_000) { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val exporting = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, exporting.intent.action)
        val file = File(activity.cacheDir, "roundtrip.glyph.pipeline.json")
        compose.runOnIdle { activity.activityResultRegistry.dispatchResult(exporting.requestCode, Activity.RESULT_OK, Intent().setData(file.toUri())) }
        compose.waitUntil(10_000) { file.isFile && file.length() > 0 }
        assertTrue(PipelineCodec.decode(file.readText()) is PipelineCodec.Result.Ok)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.waitUntil(10_000) { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val importing = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, importing.intent.action)
        opened = null
        compose.runOnIdle { activity.activityResultRegistry.dispatchResult(importing.requestCode, Activity.RESULT_OK, Intent().setData(file.toUri())) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Import copy").fetchSemanticsNodes().isNotEmpty() }
        save("library-import", dialog = true)
        compose.onNodeWithText("Import copy").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertNotEquals(original.document.id, opened)
        assertEquals(2, store.list().size)
        assertFalse(fixture.prefs.getBoolean(PipelinePrefs.CONTROLLER_ENABLED, false))
        assertEquals("", fixture.prefs.getString(PipelinePrefs.CONTROLLER_ID, ""))
        assertTrue(fixture.frames.isEmpty())
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.waitUntil(10_000) { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val invalidRequest = shadowOf(activity).nextStartedActivityForResult
        val invalidFile = File(activity.cacheDir, "invalid.glyph.pipeline.json").apply { writeText("{}") }
        compose.runOnIdle { activity.activityResultRegistry.dispatchResult(invalidRequest.requestCode, Activity.RESULT_OK, Intent().setData(invalidFile.toUri())) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty() }
        save("library-import-error", dialog = true)
        assertEquals(2, store.list().size)
    }

    @Test fun ambientPopupRendersAppliedStatusAndDeclaredTypedSettings() = withActivity { activity, _, store, _ ->
        val program = Program(id = "ambient", name = "My Ambient", kind = ProgramKind.AMBIENT, parameters = listOf(
            Parameter("style", "Clock style", ValueType.TEXT, text("Digital"), choices = listOf(text("Digital"), text("Analog")), quickSetting = true),
            Parameter("duration", "Time per background", ValueType.DURATION, duration(15000), minimum = 1000.0, maximum = 60000.0, quickSetting = true),
            Parameter("sequence", "Backgrounds", ValueType.LIST, Value.Items(listOf(text("clock"), text("weather"))), quickSetting = true),
        ), scripts = listOf(Script(blocks = listOf(Block(op = "display.toy")))))
        val document = PipelineDocument(name = "My Ambient", entryPoint = program.id, programs = listOf(program))
        assertTrue(store.apply(document) is PipelineStore.SaveResult.Saved)
        Core.pipeline.assignAmbient(document.id)
        activity.setContent { GlyphWorksTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { AmbientPipelineSettings() } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Clock style").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Applied · revision 1").assertExists()
        compose.onNodeWithText("Digital", substring = false).assertExists()
        save("ambient-typed-settings")
    }

    @Test fun migratedAmbientPopupRendersTheShippedPipelineSettings() = withActivity { activity, _, store, _ ->
        val document = BuiltinPipelines.ambient(Core.prefs)
        assertTrue(store.apply(document) is PipelineStore.SaveResult.Saved)
        Core.pipeline.assignAmbient(document.id)
        activity.setContent { GlyphWorksTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { AmbientPipelineSettings() } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cycle automatically").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open Pipeline Builder").assertIsDisplayed()
        compose.onNodeWithText("Applied · revision 1").assertExists()
        assertTrue(document.entry()!!.parameters.none { it.quickSetting && it.type == ValueType.LIST })
        save("ambient-settings")
    }

    @Test fun standardModeCanRecoverAndStopPipelinesWithoutEnablingCustomControls() = withActivity { activity, fixture, _, _ ->
        fixture.prefs.putBoolean(PipelinePrefs.STOPPED, true)
        fixture.prefs.putString(PipelinePrefs.DIAGNOSTIC, "A script exceeded its execution limit.")
        activity.setContent { GlyphWorksTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PipelineAdvancedSettings() } } } }
        compose.onNodeWithText("Resume pipelines").assertIsDisplayed()
        save("standard-pipeline-recovery")
        compose.onNodeWithText("Resume pipelines").performClick()
        compose.waitUntil(5_000) { !fixture.prefs.getBoolean(PipelinePrefs.STOPPED, false) }
        compose.onNodeWithText("Stop pipelines").performClick()
        compose.waitUntil(5_000) { fixture.prefs.getBoolean(PipelinePrefs.STOPPED, false) }
        assertFalse(fixture.prefs.getBoolean(PipelinePrefs.CONTROLLER_ENABLED, false))
        assertTrue(fixture.frames.isEmpty())
    }

    @Test fun embeddedDesignEditorReturnsPaintedArtworkWithoutSelectingOrSavingGlobalDesign() = withActivity { activity, fixture, _, designs ->
        val state = EditorState(artwork(), PokemonCodename.BELLSPROUT)
        var saved: Design? = null
        var closed = false
        activity.setContent { GlyphWorksTheme { EditorScaffold(state, designs, onClose = { closed = true }, onSaveAsset = { saved = it; true }) } }
        compose.runOnIdle { state.beginStroke(); state.paint(6, 6); state.endStroke() }
        compose.onNodeWithContentDescription(activity.getString(R.string.editor_close)).performClick()
        compose.waitUntil(10_000) { closed }
        assertNotNull(saved)
        assertEquals('2', saved!!.variants.getValue("bellsprout").frames.single().cells[6 * 13 + 6])
        assertFalse(designs.exists(state.design.id))
        assertTrue(fixture.frames.isEmpty())
    }

    @Test fun customArtworkExportsThroughStructuralBindingAndSurvivesImportRemap() {
        val fixture = TestHarness(13)
        val original = PipelineDocument(id = "custom_source", name = "My animation", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "My animation", scripts = listOf(Script(blocks = listOf(Block(id = "custom_block", op = "display.toy", arguments = mapOf("toy" to Expression.str("custom")))))))))
        val exported = portablePipeline(original, fixture.prefs, ::artwork) { null }
        val block = exported.entry()!!.scripts.single().blocks.single()
        val bindingId = block.arguments.getValue("binding:design").value!!.text()
        val embedded = exported.bindings.getValue(bindingId).assetId
        assertEquals(artwork().id, embedded)
        assertTrue(exported.designs.containsKey(embedded))
        val remapped = PipelineReferences.remap(exported)
        val newBinding = remapped.entry()!!.scripts.single().blocks.single().arguments.getValue("binding:design").value!!.text()
        assertNotEquals(bindingId, newBinding)
        assertTrue(remapped.designs.containsKey(remapped.bindings.getValue(newBinding).assetId))
        assertTrue(PipelineStore.validateArtwork(remapped).isEmpty())
        assertTrue(PipelineCodec.validate(remapped).isEmpty())
    }

    @Test fun explicitEmptyCustomArtworkStaysEmptyWhenExporting() {
        val fixture = TestHarness(13)
        val block = Block(op = "display.toy", arguments = mapOf("toy" to Expression.str("custom"), "parameters" to Expression.literal(Value.Record(mapOf("asset" to text(""))))))
        val original = PipelineDocument(id = "empty_art", name = "Empty", entryPoint = "toy", programs = listOf(Program(id = "toy", name = "Empty", scripts = listOf(Script(blocks = listOf(block))))))
        val exported = portablePipeline(original, fixture.prefs, { error("Explicitly empty artwork must not inherit local selection") }) { null }
        assertTrue(exported.designs.isEmpty())
        assertTrue(exported.bindings.isEmpty())
    }

    private fun artwork() = Design(id = "embedded_art", name = "Artwork", createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z", variants = PokemonCodename.entries.associate { it.codename to DesignVariant(listOf(DesignFrame(cells = DesignFrames.blank(it)))) })

    private fun save(name: String, dialog: Boolean = false) {
        val file = File("build/reports/pipeline/editor/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun withActivity(test: (ComponentActivity, TestHarness, PipelineStore, DesignStore) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        val fixture = TestHarness(13)
        fixture.prefs.putInt(PipelinePrefs.MIGRATION_VERSION, 1)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        fun set(name: String, value: Any?) { Core::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value) }
        val dir = File(activity.get().cacheDir, "authoring-${pipelineId()}")
        val store = PipelineStore(dir)
        val designs = DesignStore(activity.get())
        set("prefs", fixture.prefs); set("pipelineStore", store); set("designStore", designs)
        set("pipeline", PipelineController(activity.get(), fixture.prefs, fixture.ports, fixture.scheduler, 13, store))
        try {
            activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity.setup()
            test(activity.get(), fixture, store, designs)
        } finally {
            activity.close()
            Core.pipeline.stop()
            listOf("pipeline", "pipelineStore", "designStore", "prefs").forEach { set(it, null) }
            dir.deleteRecursively()
        }
    }
}
