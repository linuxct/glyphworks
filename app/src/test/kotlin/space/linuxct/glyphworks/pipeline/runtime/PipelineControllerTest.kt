package space.linuxct.glyphworks.pipeline.runtime

import android.app.Application
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.pipeline.store.PipelineStore
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.pipeline.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PipelineControllerTest {
    @get:Rule val files = TemporaryFolder()
    private fun controller(h: TestHarness, store: PipelineStore = PipelineStore(files.newFolder())) = PipelineController(RuntimeEnvironment.getApplication(), h.prefs, h.ports, h.scheduler, h.size, store)

    @Test fun originalsRemainSelectableWithoutSavingOrOverwritingUserProjects() {
        val h = TestHarness()
        val store = PipelineStore(files.newFolder())
        val controller = controller(h, store)
        val originalSelection = controller.ambientId
        val customized = store.loadApplied(originalSelection)!!.document.copy(name = "My unfinished Ambient")
        assertTrue(store.saveDraft(customized) is PipelineStore.SaveResult.Saved)
        val savedDraft = store.loadDraft(originalSelection)!!
        controller.assignAmbient("builtin_ambient")
        assertEquals("builtin_ambient", controller.document("ambient")!!.id)
        assertNull(store.loadApplied("builtin_ambient"))
        assertEquals(savedDraft, store.loadDraft(originalSelection))
        controller.setController("controller_example")
        controller.setControllerEnabled(true)
        assertTrue(controller.controllerMode)
        assertEquals("controller_example", controller.document(PipelineController.ADVANCED_ID)!!.id)
        controller.setTrigger("clock", "face_down_clock")
        assertEquals("face_down_clock", controller.triggerFor("clock"))
        val restarted = controller(h, store)
        assertEquals("builtin_ambient", restarted.document("ambient")!!.id)
        assertEquals("controller_example", restarted.document(PipelineController.ADVANCED_ID)!!.id)
        assertEquals(savedDraft, store.loadDraft(originalSelection))
    }

    @Test fun legacyAmbientSettingsAndPersistentToyStateSurviveMigrationExactly() {
        for (flags in 0 until 32) {
            val h = TestHarness()
            val keys = listOf(PrefKeys.AMBIENT_AUTO_CYCLE, PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_NIGHT_VISIBLE, PrefKeys.AMBIENT_SHAKE_ACTIVATE)
            keys.forEachIndexed { bit, key -> h.prefs.putBoolean(key, flags and (1 shl bit) != 0) }
            val backgrounds = if (flags % 2 == 0) listOf(AmbientBackgrounds.WEATHER, AmbientBackgrounds.ANALOG_CLOCK) else emptyList()
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, backgrounds.joinToString(","))
            h.prefs.putInt(PrefKeys.COUNTER, 42)
            h.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 15_000)
            h.prefs.putString(PrefKeys.SCREEN_ORDER, "eyes,dino,ambient")
            h.prefs.putString(PrefKeys.CURRENT_SCREEN, "dino")
            val controller = controller(h)
            val ambient = controller.document("ambient")!!
            val parameters = ambient.entry()!!.parameters.associate { it.id to it.default }
            listOf("autoCycle", "showBackground", "showCharging", "showAtNight", "shakeToShow").forEachIndexed { bit, key -> assertEquals("$flags/$key", flags and (1 shl bit) != 0, parameters[key]!!.boolean()) }
            val options = ambient.routines.single { it.id == "ambient_background" }.blocks.single().body.single()
            assertEquals("flow.select", options.op)
            assertEquals(AmbientBackgrounds.readSelection(h.prefs), options.body.map { it.arguments.getValue("toy").value!!.text().removePrefix("background.") })
            assertFalse(parameters.containsKey("backgrounds"))
            assertEquals("eyes,dino,ambient", h.prefs.getString(PrefKeys.SCREEN_ORDER, ""))
            assertEquals("dino", h.prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
            val restored = Json.decodeFromString(Value.serializer(), h.prefs.getString(PipelineController.stateKey("builtin_counter", "builtin_counter", "native:main:${PrefKeys.COUNTER}"), ""))
            assertEquals(42.0, restored.number(), 0.0)
            assertFalse(controller.controllerMode)
            assertEquals(emptyList<Diagnostic>(), PipelineCodec.validate(ambient))
        }
    }

    @Test fun advancedControllerCannotBeReplacedByManualToyNavigation() {
        val h = TestHarness()
        val controller = controller(h)
        val manager = h.manager(controller.screens())
        controller.attachManager(manager)
        manager.startSession()
        val oldSelection = h.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
        controller.setControllerEnabled(true)
        assertEquals(PipelineController.ADVANCED_ID, manager.currentScreen().id)
        manager.next(); manager.selectScreen("dice"); manager.enterMenu(); manager.home()
        assertFalse(manager.inMenu)
        assertEquals(PipelineController.ADVANCED_ID, manager.currentScreen().id)
        assertEquals(oldSelection, h.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        controller.setControllerEnabled(false)
        assertEquals(oldSelection, manager.currentScreen().id)
        manager.stopSession(); controller.onSessionChanged(false)
    }

    @Test fun draftsDoNotChangeTheAppliedToyCatalogOrAutoEnableNewToys() {
        val h = TestHarness(); val store = PipelineStore(files.newFolder())
        val original = BuiltinPipelines.toy("dice").copy(id = "my_dice", name = "My dice")
        assertTrue(store.apply(original) is PipelineStore.SaveResult.Saved)
        val controller = controller(h, store)
        assertTrue(controller.screens().any { it.id == "pipeline_my_dice" })
        assertFalse(h.prefs.getBoolean(PrefKeys.screenEnabled("pipeline_my_dice"), true))
        val draft = original.copy(name = "Unapplied name", programs = original.programs.map { it.copy(scripts = emptyList()) })
        assertTrue(store.saveDraft(draft) is PipelineStore.SaveResult.Saved)
        assertEquals(original.programs, controller.document("pipeline_my_dice")!!.programs)
        assertEquals("My dice", controller.document("pipeline_my_dice")!!.name)
    }

    @Test fun emptyDemandReleasesSourcesAndReturnsNoStaleSensorValues() {
        val h = TestHarness()
        val input = PipelineInputs(RuntimeEnvironment.getApplication(), h.ports)
        input.demand(setOf("weather", "battery")); input.poll()
        assertTrue(h.weather.demands.last())
        assertEquals(80.0, input.snapshot["battery.level"]!!.number(), 0.0)
        input.close()
        assertFalse(h.weather.demands.last())
        assertTrue(input.snapshot.isEmpty())
    }

    @Test fun livePreviewPreservesTheRunningGameAndRestoresItsFrame() {
        val h = TestHarness()
        h.prefs.putString(PrefKeys.CURRENT_SCREEN, "dino")
        val controller = controller(h)
        val manager = h.manager(controller.screens()); controller.attachManager(manager)
        manager.startSession(); manager.dispatchGlyphEvent(Events.CHANGE)
        h.scheduler.advanceTime(100)
        val running = h.output.last().copyOf()
        manager.beginLivePreview(); manager.pushLivePreview(IntArray(h.size * h.size) { 777 })
        val preview = h.output.last().copyOf()
        h.scheduler.advanceTime(5000)
        assertArrayEquals(preview, h.output.last())
        manager.endLivePreview()
        assertArrayEquals(running, h.output.last())
        manager.stopSession(); controller.onSessionChanged(false)
    }

    @Test fun childTimerAlarmsUseIndependentStateAndRejectDuplicateCompletion() {
        val h = TestHarness()
        val alarms = PipelineTimerAlarms(RuntimeEnvironment.getApplication(), h.prefs, h.timer)
        val deadline = System.currentTimeMillis() - 1000
        fun state(child: String, key: String) = PipelineController.stateKey("timers", "timer_program", "instance:root/$child:native:main:$key")
        for (child in listOf("one", "two")) {
            h.prefs.putString(state(child, PrefKeys.TIMER_START), Json.encodeToString(Value.serializer(), number(deadline - 5000)))
            alarms.action("timers", "native.timer.schedule", mapOf("owner" to text("timer_program"), "instance" to text("root/$child:main"), "slot" to text("main"), "statePrefix" to text("instance:root/$child:"), "deadline" to number(deadline)))
        }
        alarms.fire("timers|timer_program|root/one:main|true", deadline)
        assertEquals(1, h.timer.chimeCount)
        assertTrue(h.prefs.contains(state("one", PrefKeys.TIMER_CHIMED_FOR)))
        assertFalse(h.prefs.contains(state("two", PrefKeys.TIMER_CHIMED_FOR)))
        alarms.fire("timers|timer_program|root/one:main|true", deadline)
        assertEquals(1, h.timer.chimeCount)
        alarms.fire("timers|timer_program|root/two:main|true", deadline)
        assertEquals(2, h.timer.chimeCount)
        alarms.cancelAll()
    }


    @Test fun inactiveChildProgramsDoNotDemandWeatherAndStoppingTheChildReleasesIt() {
        val h = TestHarness(); val store = PipelineStore(files.newFolder())
        fun block(op: String, vararg args: Pair<String, Expression>) = Block(op = op, arguments = args.toMap())
        val main = Program(id = "main", name = "Main", scripts = listOf(
            Script(blocks = listOf(block("display.toy", "toy" to Expression.str("dice")))),
            Script(trigger = Trigger("key.action"), blocks = listOf(block("program.run", "program" to Expression.str("forecast"), "slot" to Expression.str("forecast")), block("input.consume"))),
            Script(trigger = Trigger("key.double"), blocks = listOf(block("program.stop", "slot" to Expression.str("forecast")), block("input.consume"))),
        ))
        val child = Program(id = "forecast", name = "Forecast", scripts = listOf(Script(blocks = listOf(block("display.toy", "toy" to Expression.str("weather"))))))
        val document = PipelineDocument(id = "dynamic_demand", name = "Dynamic demand", entryPoint = main.id, programs = listOf(main, child))
        assertTrue(store.apply(document) is PipelineStore.SaveResult.Saved)
        h.prefs.putBoolean(PrefKeys.screenEnabled("pipeline_dynamic_demand"), true)
        h.prefs.putString(PrefKeys.CURRENT_SCREEN, "pipeline_dynamic_demand")
        val controller = controller(h, store); val manager = h.manager(controller.screens()); controller.attachManager(manager)
        manager.startSession()
        assertTrue(h.weather.demands.isEmpty())
        assertEquals("", h.prefs.getString(PipelinePrefs.STARTUP, ""))
        manager.dispatchGlyphEvent(Events.CHANGE)
        assertEquals(true, h.weather.demands.last())
        manager.dispatchGlyphEvent("key.double")
        assertEquals(false, h.weather.demands.last())
        manager.stopSession(); controller.onSessionChanged(false)
    }

    @Test fun interruptedStartupRecoversWithoutRerunningTheProject() {
        val h = TestHarness(); h.prefs.putString(PipelinePrefs.STARTUP, "interrupted_project")
        val controller = controller(h)
        assertTrue(controller.stopped)
        assertEquals("", h.prefs.getString(PipelinePrefs.STARTUP, "missing"))
        assertTrue(h.prefs.getString(PipelinePrefs.DIAGNOSTIC, "").contains("interrupted_project"))
        val manager = h.manager(controller.screens()); controller.attachManager(manager); manager.startSession()
        assertTrue(h.output.last().all { it == 0 })
        manager.stopSession(); controller.onSessionChanged(false)
    }

}
