package space.linuxct.glyphworks.ui.pipeline.tutorial

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.ui.pipeline.*
import space.linuxct.glyphworks.ui.design.EditorState
import space.linuxct.glyphworks.ui.design.demoCellCenter
import space.linuxct.glyphworks.ui.tutorial.TourActor
import space.linuxct.pipeline.*

/** Chapter selection lives outside the tour, just as the editor tutorial's entry point does. */
enum class PipelineChapter(val title: Int) {
    CANVAS(R.string.pipeline_tutorial_canvas), EVENTS(R.string.pipeline_tutorial_events),
    AMBIENT(R.string.pipeline_tutorial_ambient), TOY(R.string.pipeline_tutorial_toy),
    REUSE(R.string.pipeline_tutorial_reuse), MENU(R.string.pipeline_tutorial_menu),
    SAFETY(R.string.pipeline_tutorial_safety), INSPECT(R.string.pipeline_tutorial_inspect),
}

internal enum class PipelineDemoStage { EDITOR, ASSET_EDITOR, SETTINGS, LOCKED_TOYS }
internal class PipelineDemoStep(
    val caption: Int,
    val target: String? = null,
    val act: suspend TourActor<String>.(PipelineDemoSandbox) -> Unit = {},
)

/** No store, Core, permissions, alarms, session lease or hardware renderer is reachable here. */
@Stable
internal class PipelineDemoSandbox(val chapter: PipelineChapter) {
    var document by mutableStateOf(initialDocument(chapter))
        private set
    var controller by mutableStateOf(PipelineEditorController())
        private set
    var stage by mutableStateOf(PipelineDemoStage.EDITOR)
    var customControls by mutableStateOf(false)
    var stopped by mutableStateOf(false)
    var panelSize by mutableIntStateOf(13)
    var previewSequence by mutableIntStateOf(0)
    var previewTime by mutableLongStateOf(0)
    var previewInputs by mutableStateOf<Map<String, Value>>(emptyMap())
    var previewEvents by mutableStateOf<List<Pair<Long, PipelineEvent>>>(emptyList())
    var assetState by mutableStateOf<EditorState?>(null)
    var applied = 0
    var history = EditorHistory(document)
        private set

    fun reset() {
        document = initialDocument(chapter)
        history = EditorHistory(document)
        controller = PipelineEditorController()
        stage = PipelineDemoStage.EDITOR
        customControls = false; stopped = false; panelSize = 13; assetState = null
        previewSequence = 0; previewTime = 0; previewInputs = emptyMap(); previewEvents = emptyList()
        applied = 0
    }
    fun edit(next: PipelineDocument) { document = history.change(next) }
    fun undo() { document = history.undo() }
    fun redo() { document = history.redo() }
    fun program(change: (Program) -> Program) = edit(document.copy(programs = document.programs.map { if (it.id == document.entryPoint) change(it) else it }))
    fun script(id: String, event: String, blocks: List<Block>, condition: Expression? = null, priority: Int = 0) {
        val script = Script(id, name = EventCatalog.all.find { it.name == event }?.title ?: event, trigger = Trigger(event, condition), blocks = blocks, priority = priority)
        val p = document.entry()!!
        val existing = p.scripts.find { it.id == id }
        edit(if (existing == null) EditorDocument.addScript(document, p.id, script, Position(28f + p.scripts.size * 340f, 28f)) else EditorDocument.script(document, script))
        focusOwner(id)
    }
    fun insert(owner: String, block: Block, parent: String? = null, index: Int = Int.MAX_VALUE) {
        edit(EditorDocument.insert(document, BlockLocation(owner, parent, index = index), block))
        focusOwner(owner)
    }
    fun focusOwner(owner: String) {
        if (document.routines.any { it.id == owner }) controller.selectedRoutine = owner
        else {
            controller.selectedRoutine = null
            document.editor.positions[owner]?.let { controller.viewportOverride = Viewport(28f - it.x, 28f - it.y, 1f) }
        }
    }
    fun open(panel: EditorPanel, block: String? = null) {
        controller.panel = panel; controller.selectedBlock = block
        block?.let { EditorDocument.location(document, it)?.ownerId }?.let(::focusOwner)
    }
    fun input(key: String, value: Value) { previewInputs = previewInputs + (key to value); previewSequence++ }
    fun event(name: String, values: Map<String, Value> = emptyMap()) {
        controller.previewExpanded = true
        previewEvents = previewEvents + (previewTime to PipelineEvent(name, values, previewTime, name.startsWith("key.")))
        previewSequence++
    }
    fun advance(ms: Long) { previewTime += ms; previewSequence++ }
}

private const val MAIN = "tutorialProgram"
private const val START = "tutorialStart"
private const val PRESS = "tutorialPress"
private const val COUNT = "tutorialCount"
private const val ART = "tutorialArt"
private const val FIXED_TIME = "2026-10-06T00:00:00Z"

private fun initialDocument(chapter: PipelineChapter): PipelineDocument = PipelineDocument(
    id = "tutorialProject", name = "Tutorial project", createdAt = FIXED_TIME, modifiedAt = FIXED_TIME,
    createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}", entryPoint = MAIN,
    programs = listOf(Program(id = MAIN, name = "Tutorial project", kind = when (chapter) {
        PipelineChapter.AMBIENT -> ProgramKind.AMBIENT
        PipelineChapter.MENU, PipelineChapter.SAFETY -> ProgramKind.CONTROLLER
        else -> ProgramKind.TOY
    })),
)

private fun block(id: String, op: String, vararg values: Pair<String, Expression>, body: List<Block> = emptyList(), otherwise: List<Block> = emptyList()): Block =
    Block(id, op, BlockCatalog[op]?.arguments.orEmpty().associate { it.name to it.default } + values, body, otherwise)
private fun toy(id: String, toy: String, slot: String = "main", priority: Int = 0, duration: Long = 0) =
    block(id, "display.toy", "toy" to Expression.str(toy), "slot" to Expression.str(slot), "priority" to Expression.num(priority), "duration" to Expression.ms(duration))
private fun wait(id: String, ms: Long) = block(id, "flow.wait", "duration" to Expression.ms(ms))
private fun release(id: String, slot: String) = block(id, "display.release", "slot" to Expression.str(slot))
private fun timer(id: String, ms: Long) = block(id, "timer.start", "name" to Expression.str("music-return"), "duration" to Expression.ms(ms))
private fun literal(value: String) = Expression.str(value)

private suspend fun TourActor<String>.open(sandbox: PipelineDemoSandbox, panel: EditorPanel, target: String) {
    tap(target); sandbox.open(panel); beat(300)
}
private suspend fun TourActor<String>.finish(sandbox: PipelineDemoSandbox) { sandbox.open(EditorPanel.NONE); hide() }

internal fun pipelineDemoSteps(chapter: PipelineChapter): List<PipelineDemoStep> {
    val steps = when (chapter) {
        PipelineChapter.CANVAS -> canvasSteps()
        PipelineChapter.EVENTS -> eventsSteps()
        PipelineChapter.AMBIENT -> ambientSteps()
        PipelineChapter.TOY -> toySteps()
        PipelineChapter.REUSE -> reuseSteps()
        PipelineChapter.MENU -> menuSteps()
        PipelineChapter.SAFETY -> safetySteps()
        PipelineChapter.INSPECT -> inspectSteps()
    }
    return steps + PipelineDemoStep(R.string.pipeline_tutorial_done) { finish(it) }
}

private fun canvasSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_intro, "canvas") { s -> s.script(START, "start", emptyList()); hide() },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_pan, "canvas") { s ->
        centerOf("canvas", 0)?.let { point ->
            glideTo(point); holdOn("canvas"); glideTo(point + Offset(-90f, 65f), 650)
        }
        s.controller.viewportOverride = Viewport(-70f, 60f, .85f); release(); beat(550)
        s.controller.viewportOverride = Viewport(); beat(350)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_event, "add-event") { s -> tap("add-event"); s.script(PRESS, "key.action", emptyList()); s.controller.selectedScript = PRESS; beat(300) },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_display, "canvas") { s ->
        s.focusOwner(START)
        open(s, EditorPanel.PALETTE, "blocks"); holdOn("palette:display.toy")
        centerOf("script:$START", 0)?.let { glideTo(it + Offset(0f, 64f), 650) }
        s.insert(START, toy("demoClock", "clock")); release(); s.open(EditorPanel.NONE)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_nest, "canvas") { s ->
        s.insert(START, block("demoRepeat", "flow.repeat", "count" to Expression.num(3)), index = 0)
        holdOn("block:demoClock"); centerOf("block:demoRepeat", 0)?.let { glideTo(it, 650) }
        s.edit(EditorDocument.move(s.document, "demoClock", BlockLocation(START, "demoRepeat")))
        s.insert(START, wait("demoWait", 1000), "demoRepeat"); release()
    },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_value, "inspector") { s ->
        tap("block:demoWait"); s.open(EditorPanel.BLOCK, "demoWait"); beat(500)
        s.edit(EditorDocument.update(s.document, wait("demoWait", 2000))); beat(400)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_undo, "toolbar") { s -> s.open(EditorPanel.NONE); tap("undo"); s.undo(); beat(550); tap("redo"); s.redo() },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_preview, "preview") { s -> tap("preview"); s.event("key.action"); s.advance(250) },
    PipelineDemoStep(R.string.pipeline_tutorial_canvas_apply, "apply") { s -> s.controller.previewExpanded = false; tap("apply"); beat(300) },
)

private fun eventsSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_events_edge, "canvas") { s ->
        s.script(START, "start", listOf(toy("eventClock", "clock")))
        s.script(PRESS, "key.action", emptyList())
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_condition, "inspector") { s ->
        val condition = Expression.operation("and", Expression.input("music.playing"), Expression.operation("not", Expression.input("screen.on")))
        s.insert(PRESS, block("eventIf", "flow.if", "condition" to condition, body = listOf(toy("eventMusic", "visualizer")), otherwise = listOf(toy("eventFallback", "clock"))))
        tap("block:eventIf"); s.open(EditorPanel.BLOCK, "eventIf"); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_variable, "inspector") { s ->
        open(s, EditorPanel.VARIABLES, "variables"); s.program { it.copy(variables = listOf(Variable(COUNT, "Presses"))) }
        s.insert(PRESS, block("increment", "variable.change", "variable" to literal(COUNT)), index = 0); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_loop, "canvas") { s ->
        s.open(EditorPanel.NONE)
        s.insert(START, block("repeatPulse", "flow.repeat", "count" to Expression.num(3), body = listOf(toy("pulse", "eyes"), wait("pausePulse", 250))))
        tap("block:repeatPulse")
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_random, "inspector") { s ->
        s.insert(PRESS, block("randomCount", "variable.set", "variable" to literal(COUNT), "value" to Expression.operation("random", Expression.num(1), Expression.num(6))))
        s.open(EditorPanel.BLOCK, "randomCount"); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_timeout, "canvas") { s ->
        s.open(EditorPanel.NONE); s.insert(PRESS, timer("eventTimer", 3000)); s.event("key.action"); s.advance(2000); s.event("key.action"); s.advance(1000)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_events_cancel, "canvas") { s ->
        s.script("cancelPress", "key.double", listOf(block("cancelTimer", "timer.cancel", "name" to literal("music-return"))))
        s.event("key.double")
    },
)

private fun ambientSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_role, "canvas") { s -> s.script(START, "start", listOf(toy("ambientClock", "clock", "background"))) },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_cycle, "canvas") { s ->
        s.script(START, "start", listOf(block("ambientCycle", "flow.forever", body = listOf(
            toy("ambientClock", "clock", "background"), wait("ambientClockWait", 15000),
            toy("ambientWeather", "weather", "background"), wait("ambientWeatherWait", 15000),
        ))))
        holdOn("block:ambientWeather"); centerOf("block:ambientClock", 0)?.let { glideTo(it, 650) }; release()
        s.edit(EditorDocument.move(s.document, "ambientWeather", BlockLocation(START, "ambientCycle", index = 0)))
        s.edit(EditorDocument.move(s.document, "ambientWeatherWait", BlockLocation(START, "ambientCycle", index = 1)))
    },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_interval, "inspector") { s ->
        s.program { it.copy(parameters = listOf(Parameter("interval", "Cycle interval", ValueType.DURATION, duration(15000), quickSetting = true))) }
        listOf("ambientClockWait", "ambientWeatherWait").forEach { id -> s.edit(EditorDocument.update(s.document, block(id, "flow.wait", "duration" to Expression.parameter("interval")))) }
        s.open(EditorPanel.PARAMETERS); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_priority, "canvas") { s ->
        s.open(EditorPanel.NONE)
        s.script("musicRule", "music.changed", listOf(block("musicIf", "flow.if", "condition" to Expression.input("music.playing"),
            body = listOf(toy("ambientMusic", "visualizer", "music", 10)), otherwise = listOf(release("releaseMusic", "music")))))
        s.script("chargeRule", "battery.changed", listOf(block("chargeIf", "flow.if", "condition" to Expression.input("battery.charging"),
            body = listOf(toy("ambientCharge", "battery", "charge", 5)), otherwise = listOf(release("releaseCharge", "charge")))))
        s.input("music.playing", boolean(true)); s.event("music.changed")
    },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_override, "canvas") { s ->
        s.script(PRESS, "key.action", listOf(release("interruptMusic", "music"), timer("returnTimer", 5000)))
        s.script("timeoutRule", "timer", listOf(block("returnIf", "flow.if", "condition" to Expression.input("music.playing"), body = listOf(toy("returnMusic", "visualizer", "music", 10)))))
        s.event("key.action"); s.advance(3000)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_restart, "preview") { s -> s.event("key.action"); s.advance(4000); beat(450); s.advance(1000) },
    PipelineDemoStep(R.string.pipeline_tutorial_ambient_assign, "apply") { s -> s.controller.previewExpanded = false; tap("apply") },
)

private fun demoDesign(): JsonObject {
    val variants = PokemonCodename.entries.associate { panel ->
        panel.codename to DesignVariant((0..2).map { phase ->
            val cells = CharArray(panel.cellCount) { '0' }
            val x = panel.size / 2; val y = panel.size / 2 - phase
            for (dx in -1..1) for (dy in -1..1) cells[(y + dy) * panel.size + x + dx] = if (dx == 1 && dy == -1) '1' else '2'
            DesignFrame(140, String(cells))
        })
    }
    return Json.parseToJsonElement(DesignCodec.encode(Design(id = ART, name = "Jump", createdAt = FIXED_TIME, modifiedAt = FIXED_TIME,
        createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}", kind = DesignKind.DYNAMIC, variants = variants))) as JsonObject
}

private fun toySteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_toy_role, "canvas") { s -> s.script(START, "start", emptyList()); s.script(PRESS, "key.action", emptyList()) },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_art, "inspector") { s -> open(s, EditorPanel.ASSETS, "assets"); s.edit(s.document.copy(designs = mapOf(ART to demoDesign()))); beat(500) },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_draw, "asset-canvas") { s ->
        val design = (DesignCodec.decode(s.document.designs.getValue(ART).toString()) as DesignCodec.Result.Ok).design
        val state = EditorState(design, PokemonCodename.BELLSPROUT)
        s.assetState = state; s.stage = PipelineDemoStage.ASSET_EDITOR
        beat(600)
        state.brushIndex = 2
        state.beginStroke()
        for ((x, y) in listOf(5 to 7, 6 to 7, 7 to 7)) {
            boundsOf("asset-canvas")?.let { glideTo(demoCellCenter(it, state, x, y), 200) }
            state.paint(x, y)
        }
        state.endStroke(); beat(600)
        s.edit(s.document.copy(designs = s.document.designs + (ART to (Json.parseToJsonElement(DesignCodec.encode(state.composed().copy(modifiedAt = FIXED_TIME))) as JsonObject))))
    },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_press, "canvas") { s -> s.stage = PipelineDemoStage.EDITOR; s.assetState = null; s.open(EditorPanel.NONE); s.insert(PRESS, block("jumpAnimation", "display.animation", "asset" to literal(ART), "loop" to Expression.bool(false))); s.event("key.action"); s.advance(140) },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_copy, "canvas") { s -> s.script(START, "start", listOf(toy("myDino", "dino"))); s.controller.previewExpanded = false },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_jump, "inspector") { s ->
        s.edit(s.document.copy(bindings = mapOf("jumpBinding" to AssetBinding(ART, AnimationFit.STRETCH_TO_PHASE))))
        s.edit(EditorDocument.update(s.document, toy("myDino", "dino").copy(arguments = toy("myDino", "dino").arguments + ("binding:jump" to literal("jumpBinding")))))
        open(s, EditorPanel.ASSETS, "assets"); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_geometry, "inspector") { s ->
        s.edit(s.document.copy(bindings = s.document.bindings.mapValues { (_, binding) -> binding.copy(variants = mapOf(
            "bellsprout" to SpriteGeometry(5, 3, 3, 5, 1.0, 3.0, 0.0, 0.0, 3.0, 3.0),
            "arbok" to SpriteGeometry(11, 9, 3, 5, 1.0, 3.0, 0.0, 0.0, 3.0, 3.0),
        )) })); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_toy_panels, "preview") { s -> s.open(EditorPanel.NONE); s.controller.previewExpanded = true; s.panelSize = 13; s.event("key.action"); s.advance(180); beat(500); s.panelSize = 25; s.previewSequence++ },
)

private fun reuseSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_extract, "inspector") { s ->
        val routine = Routine("showClock", "Show clock", blocks = listOf(toy("routineClock", "clock"), wait("routineWait", 1000)))
        s.edit(s.document.copy(routines = listOf(routine)))
        s.script(START, "start", listOf(block("firstCall", "routine.call", "routine" to literal(routine.id))))
        open(s, EditorPanel.ROUTINES, "routines"); beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_parameter, "routines") { s ->
        val routine = s.document.routines.single().copy(parameters = listOf(Parameter("stay", "Stay visible", ValueType.DURATION, duration(1000))))
        s.edit(EditorDocument.routine(s.document, routine))
        s.edit(EditorDocument.update(s.document, block("routineWait", "flow.wait", "duration" to Expression.parameter("stay"))))
        s.controller.selectedRoutine = routine.id; beat(500)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_call, "canvas") { s ->
        s.open(EditorPanel.NONE); s.controller.selectedRoutine = null
        s.script(PRESS, "key.action", listOf(block("secondCall", "routine.call", "routine" to literal("showClock"), "arg:stay" to Expression.ms(5000))))
        s.edit(EditorDocument.update(s.document, block("firstCall", "routine.call", "routine" to literal("showClock"), "arg:stay" to Expression.ms(2000))))
    },
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_result, "routines") { s ->
        s.edit(EditorDocument.routine(s.document, s.document.routines.single().copy(returns = ValueType.NUMBER,
            variables = listOf(Variable("localCount", "Calls in this invocation")), blocks = s.document.routines.single().blocks + block("routineReturn", "flow.return", "value" to Expression.num(1)))))
        s.program { it.copy(variables = listOf(Variable(COUNT, "Last result"))) }
        val original = EditorDocument.block(s.document, "secondCall")!!
        s.edit(EditorDocument.update(s.document, original.copy(arguments = original.arguments + ("resultVariable" to literal(COUNT)))))
        open(s, EditorPanel.ROUTINES, "routines")
    },
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_revision, "routines") { s -> s.edit(EditorDocument.routine(s.document, s.document.routines.single().copy(revision = 2))); beat(400); s.open(EditorPanel.NONE) },
    PipelineDemoStep(R.string.pipeline_tutorial_reuse_export, "toolbar") { s -> s.open(EditorPanel.PROJECT); beat(500); s.open(EditorPanel.NONE) },
)

private fun menuSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_menu_role, "canvas") { s -> s.script(START, "start", emptyList()) },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_index, "variables") { s ->
        s.program { it.copy(variables = listOf(Variable(COUNT, "Selected item"))) }
        s.script(PRESS, "key.single", listOf(block("nextItem", "variable.set", "variable" to literal(COUNT),
            "value" to Expression.operation("modulo", Expression.operation("add", Expression.variable(COUNT), Expression.num(1)), Expression.num(2)))))
        open(s, EditorPanel.VARIABLES, "variables"); beat(400); s.open(EditorPanel.NONE)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_draw, "canvas") { s ->
        s.script("drawMenu", "tick", listOf(block("menuScene", "scene.create"), block("menuClear", "scene.clear"), block("menuLabel", "scene.text", "text" to Expression.variable(COUNT), "x" to Expression.num(4), "y" to Expression.num(4)), block("menuPresent", "scene.present")))
        s.event("key.single"); s.advance(50)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_confirm, "canvas") { s ->
        s.script("confirmMenu", "key.double", listOf(block("menuChoice", "flow.if", "condition" to Expression.operation("equal", Expression.variable(COUNT), Expression.num(0)),
            body = listOf(toy("chosenClock", "clock", "selected", 5)), otherwise = listOf(toy("chosenEyes", "eyes", "selected", 5)))))
        s.script("backMenu", "key.triple", listOf(release("backToMenu", "selected")))
        s.event("key.double"); s.advance(100); beat(500); s.event("key.triple")
    },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_global, "canvas") { s ->
        s.script("shakeWeather", "shake", listOf(toy("menuWeather", "weather", "interruption", 20, 30000)))
        s.script("faceDown", "orientation.changed", listOf(block("faceCondition", "flow.if", "condition" to Expression.input("orientation.faceDown"),
            body = listOf(toy("faceClock", "clock", "orientation", 10)), otherwise = listOf(release("releaseOrientation", "orientation")))))
        s.event("shake")
    },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_return, "preview") { s -> s.advance(30000); s.event("key.single") },
    PipelineDemoStep(R.string.pipeline_tutorial_menu_enable, "toolbar") { s -> s.controller.previewExpanded = false; hide() },
)

private fun safetySteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_safety_settings, "custom-settings") { s -> s.stage = PipelineDemoStage.SETTINGS; s.customControls = false; beat(400); tap("custom-enable"); s.customControls = true },
    PipelineDemoStep(R.string.pipeline_tutorial_safety_locked, "custom-locked") { s -> s.stage = PipelineDemoStage.LOCKED_TOYS; hide() },
    PipelineDemoStep(R.string.pipeline_tutorial_safety_stop, "custom-settings") { s -> s.stage = PipelineDemoStage.SETTINGS; tap("custom-stop"); s.stopped = true },
    PipelineDemoStep(R.string.pipeline_tutorial_safety_standard, "custom-settings") { s -> tap("custom-enable"); s.customControls = false; s.stopped = false },
)

private fun inspectSteps() = listOf(
    PipelineDemoStep(R.string.pipeline_tutorial_inspect_pause, "preview") { s ->
        s.script(START, "start", listOf(toy("inspectClock", "clock"), wait("inspectWait", 3000), toy("inspectEyes", "eyes")))
        s.controller.previewExpanded = true; s.advance(50)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_inspect_wait, "preview") { s -> s.advance(1000); beat(500); s.advance(2000) },
    PipelineDemoStep(R.string.pipeline_tutorial_inspect_priority, "preview") { s ->
        s.script("inspectShake", "shake", listOf(toy("inspectWeather", "weather", "overlay", 10, 3000)))
        s.event("shake"); s.advance(50)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_inspect_error, "inspector") { s ->
        s.controller.previewExpanded = false
        s.edit(EditorDocument.update(s.document, block("inspectWait", "flow.wait", "duration" to Expression.literal(Value.Unavailable("Choose a duration")))))
        tap("block:inspectWait"); s.open(EditorPanel.BLOCK, "inspectWait"); beat(700)
    },
    PipelineDemoStep(R.string.pipeline_tutorial_inspect_share, "inspector") { s -> s.edit(EditorDocument.update(s.document, wait("inspectWait", 3000))); s.open(EditorPanel.PROJECT); beat(500) },
)
