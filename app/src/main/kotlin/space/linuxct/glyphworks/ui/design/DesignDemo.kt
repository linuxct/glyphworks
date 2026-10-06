package space.linuxct.glyphworks.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.ui.generateDesignName
import space.linuxct.glyphworks.ui.seedVariants

internal enum class DemoTarget {
    FAB,
    DIALOG_KIND,
    DIALOG_CREATE,
    CANVAS,
    PALETTE,
    TOOLS,
    FRAME,
    FRAME_ACTIONS,
    DURATION,
    SETTINGS_ACTION,
    KEY_MODE,
    LOOP,
    ADD_VARIANT,
    LIVE_PREVIEW,
    TOP_BAR,
}

internal typealias DemoTargets = space.linuxct.glyphworks.ui.tutorial.TourTargets<DemoTarget>

internal val LocalDemoTargets = compositionLocalOf<DemoTargets?> { null }

@Composable
internal fun Modifier.demoTarget(target: DemoTarget, index: Int = 0): Modifier {
    val targets = LocalDemoTargets.current ?: return this
    DisposableEffect(targets, target, index) {
        onDispose { targets.forget(target, index) }
    }
    return onGloballyPositioned { targets.report(target, index, it.boundsInRoot()) }
}

internal typealias DemoGhost = space.linuxct.glyphworks.ui.tutorial.TourGhost
internal typealias DemoActor = space.linuxct.glyphworks.ui.tutorial.TourActor<DemoTarget>

internal fun demoDesign(home: PokemonCodename, name: String): Design = Design(
    format = DESIGN_FORMAT,
    formatVersion = DESIGN_FORMAT_VERSION,
    id = "",
    name = name,
    author = "",
    createdAt = nowIsoUtc(),
    modifiedAt = nowIsoUtc(),
    createdWith = "",
    kind = DesignKind.DYNAMIC,
    keyMode = KeyMode.PLAY_PAUSE,
    loop = true,
    levels = DEFAULT_LEVELS,
    variants = seedVariants(setOf(home), home),
)

@Stable
internal class DemoSandbox(private val home: PokemonCodename) {

    private val suggestedName = generateDesignName(emptySet())

    var name by mutableStateOf(suggestedName)

    var dynamic by mutableStateOf(false)

    var target by mutableStateOf(setOf(home))

    var state by mutableStateOf(EditorState(demoDesign(home, suggestedName), home))
        private set

    var applied = 0

    fun reset() {
        name = suggestedName
        dynamic = false
        target = setOf(home)
        state = EditorState(demoDesign(home, suggestedName), home)
        applied = 0
    }
}

internal enum class DemoStage {
    CREATE,
    DIALOG,
    EDITOR,
    SETTINGS,
}

internal class DemoStep(
    val caption: Int,
    val stage: DemoStage,
    val target: DemoTarget? = null,
    val targetIndex: Int? = null,
    val act: suspend DemoActor.(DemoSandbox) -> Unit = {},
)

private const val KIND_DYNAMIC = 1
private const val PALETTE_GREY = 1
private const val PALETTE_WHITE = 2

// Far enough below the 2048 it starts at to read as a different shade at swatch size.
private const val SHADE_DEMO_LEVEL = 1000
private const val TOOL_UNDO = 0
private const val TOOL_REDO = 1
private const val FRAME_ACTION_ADD = 0
private const val FRAME_ACTION_DUPLICATE = 1
private const val DURATION_LONGER = 1
private const val KEY_MODE_PLAY_ONCE = 0
private const val KEY_MODE_PLAY_PAUSE = 1

internal val DEMO_STEPS: List<DemoStep> = listOf(
    DemoStep(
        caption = R.string.demo_cap_fab,
        stage = DemoStage.CREATE,
        target = DemoTarget.FAB,
    ) {
        beat(500)
        tap(DemoTarget.FAB)
    },
    DemoStep(
        caption = R.string.demo_cap_new,
        stage = DemoStage.DIALOG,
        target = DemoTarget.DIALOG_KIND,
    ) { sandbox ->
        beat(500)
        tap(DemoTarget.DIALOG_KIND, index = KIND_DYNAMIC)
        sandbox.dynamic = true
        beat(600)
        tap(DemoTarget.DIALOG_CREATE)
    },
    DemoStep(
        caption = R.string.demo_cap_draw,
        stage = DemoStage.EDITOR,
        target = DemoTarget.CANVAS,
    ) { sandbox ->
        beat(300)
        tap(DemoTarget.PALETTE, index = PALETTE_GREY)
        sandbox.state.brushIndex = 1
        beat(260)
        tap(DemoTarget.PALETTE, index = PALETTE_WHITE)
        sandbox.state.brushIndex = 2
        paintStroke(sandbox, SMILE)
        tap(DemoTarget.TOOLS, index = TOOL_UNDO)
        sandbox.state.undo()
        beat(600)
        tap(DemoTarget.TOOLS, index = TOOL_REDO)
        sandbox.state.redo()
    },
    DemoStep(
        caption = R.string.demo_cap_shade,
        stage = DemoStage.EDITOR,
        target = DemoTarget.PALETTE,
        targetIndex = PALETTE_GREY,
    ) { sandbox ->
        beat(300)
        holdOn(DemoTarget.PALETTE, index = PALETTE_GREY)
        beat(700)
        release()
        // The brush stays on white, so everything drawn after this step is unchanged; only the
        // swatch that was held moves, which is the whole point being made.
        sandbox.state.setBrushLevel(PALETTE_GREY, SHADE_DEMO_LEVEL)
        beat(500)
    },
    DemoStep(
        caption = R.string.demo_cap_frames,
        stage = DemoStage.EDITOR,
        target = DemoTarget.FRAME,
    ) { sandbox ->
        beat(300)
        tap(DemoTarget.FRAME_ACTIONS, index = FRAME_ACTION_DUPLICATE)
        sandbox.state.duplicateFrame()
        beat(240)
        paintStroke(sandbox, BLINK)
        tap(DemoTarget.FRAME_ACTIONS, index = FRAME_ACTION_ADD)
        sandbox.state.addFrame()
        beat(400)
        val from = sandbox.state.frames.lastIndex
        holdOn(DemoTarget.FRAME, index = from)
        beat(240)
        centerOf(DemoTarget.FRAME, index = 0)?.let { glideTo(it, ms = 700) }
        sandbox.state.moveFrame(from, 0)
        release()
        beat(300)
        tap(DemoTarget.DURATION, index = DURATION_LONGER)
        sandbox.state.setSelectedDuration(
            stepDuration(sandbox.state.selected.durationMs, up = true),
        )
    },
    DemoStep(
        caption = R.string.demo_cap_preview,
        stage = DemoStage.EDITOR,
        target = DemoTarget.LIVE_PREVIEW,
    ) {
        hide()
    },
    DemoStep(
        caption = R.string.demo_cap_top_bar,
        stage = DemoStage.EDITOR,
        target = DemoTarget.TOP_BAR,
    ) {
        beat(1200)
        tap(DemoTarget.SETTINGS_ACTION)
    },
    DemoStep(
        caption = R.string.demo_cap_key_mode,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.KEY_MODE,
    ) { sandbox ->
        beat(500)
        tap(DemoTarget.KEY_MODE, index = KEY_MODE_PLAY_ONCE)
        sandbox.state.setKeyMode(KeyMode.PLAY_ONCE)
        beat(900)
        tap(DemoTarget.KEY_MODE, index = KEY_MODE_PLAY_PAUSE)
        sandbox.state.setKeyMode(KeyMode.PLAY_PAUSE)
    },
    DemoStep(
        caption = R.string.demo_cap_loop,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.LOOP,
    ) { sandbox ->
        beat(500)
        tap(DemoTarget.LOOP)
        sandbox.state.setLoop(false)
        beat(1100)
        tap(DemoTarget.LOOP)
        sandbox.state.setLoop(true)
    },
    DemoStep(
        caption = R.string.demo_cap_add_variant,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.ADD_VARIANT,
    ) {
        hide()
    },
    DemoStep(
        caption = R.string.demo_cap_done,
        stage = DemoStage.EDITOR,
    ) {
        hide()
    },
)

private suspend fun DemoActor.paintStroke(sandbox: DemoSandbox, path: List<Pair<Int, Int>>) {
    val state = sandbox.state
    val canvas = boundsOf(DemoTarget.CANVAS)
    state.beginStroke()
    for ((index, cell) in path.withIndex()) {
        val (x, y) = cell
        if (canvas != null) {
            val point = demoCellCenter(canvas, state, x, y)
            glideTo(point, ms = if (index == 0) GLIDE_TO_CANVAS_MS else STROKE_STEP_MS)
        }
        state.paint(x, y)
    }
    state.endStroke()
    beat(240)
}

private const val GLIDE_TO_CANVAS_MS = 460L
private const val STROKE_STEP_MS = 90L

// Every cell of both is well inside PanelMask's rim, so the panel really has it.
private val SMILE = listOf(3 to 5, 4 to 6, 5 to 7, 6 to 7, 7 to 7, 8 to 6, 9 to 5)

private val BLINK = listOf(4 to 4, 8 to 4)
