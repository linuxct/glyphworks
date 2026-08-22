package space.linuxct.glyphworks.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
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

private data class DemoKey(val target: DemoTarget, val index: Int)

@Stable
internal class DemoTargets {

    private val bounds = mutableStateMapOf<DemoKey, Rect>()

    fun report(target: DemoTarget, index: Int, rect: Rect) {
        val key = DemoKey(target, index)
        if (bounds[key] != rect) bounds[key] = rect
    }

    fun forget(target: DemoTarget, index: Int) {
        bounds.remove(DemoKey(target, index))
    }

    fun boundsOf(target: DemoTarget, index: Int): Rect? = bounds[DemoKey(target, index)]

    fun centerOf(target: DemoTarget, index: Int): Offset? = boundsOf(target, index)?.center

    fun unionOf(target: DemoTarget): Rect? {
        var union: Rect? = null
        for ((key, rect) in bounds) {
            if (key.target != target) continue
            union = union?.let {
                Rect(
                    left = minOf(it.left, rect.left),
                    top = minOf(it.top, rect.top),
                    right = maxOf(it.right, rect.right),
                    bottom = maxOf(it.bottom, rect.bottom),
                )
            } ?: rect
        }
        return union
    }
}

internal val LocalDemoTargets = compositionLocalOf<DemoTargets?> { null }

@Composable
internal fun Modifier.demoTarget(target: DemoTarget, index: Int = 0): Modifier {
    val targets = LocalDemoTargets.current ?: return this
    DisposableEffect(targets, target, index) {
        onDispose { targets.forget(target, index) }
    }
    return onGloballyPositioned { targets.report(target, index, it.boundsInRoot()) }
}

@Stable
internal class DemoGhost {

    var position by mutableStateOf<Offset?>(null)
        private set

    var press by mutableFloatStateOf(0f)
        private set

    fun moveTo(point: Offset) {
        position = point
    }

    fun pressTo(fraction: Float) {
        press = fraction.coerceIn(0f, 1f)
    }

    fun hide() {
        position = null
        press = 0f
    }
}

/**
 * Played, a step animates. Replayed with [instant], every wait and glide returns at once.
 * An instant path must never call `delay` or `withFrameNanos`: a replay runs inside an effect
 * that may already be cancelled, and suspending there would throw.
 */
@Stable
internal class DemoActor(
    private val ghost: DemoGhost,
    private val targets: DemoTargets,
    private val instant: Boolean,
) {

    fun centerOf(target: DemoTarget, index: Int = 0): Offset? = targets.centerOf(target, index)

    fun boundsOf(target: DemoTarget, index: Int = 0): Rect? = targets.boundsOf(target, index)

    suspend fun beat(ms: Long) {
        if (!instant) delay(ms)
    }

    suspend fun glideTo(point: Offset, ms: Long = GLIDE_MS) {
        if (instant) return
        val from = ghost.position
        if (from == null) {
            ghost.moveTo(point)
            return
        }
        animate(ms) { t ->
            val e = ease(t)
            ghost.moveTo(Offset(from.x + (point.x - from.x) * e, from.y + (point.y - from.y) * e))
        }
    }

    suspend fun tap(target: DemoTarget, index: Int = 0) {
        val point = centerOf(target, index) ?: return
        glideTo(point)
        if (instant) return
        animate(TAP_MS) { t -> ghost.pressTo(if (t < 0.5f) t * 2f else (1f - t) * 2f) }
        ghost.pressTo(0f)
        beat(TAP_SETTLE_MS)
    }

    suspend fun holdOn(target: DemoTarget, index: Int = 0) {
        val point = centerOf(target, index) ?: return
        glideTo(point)
        if (instant) return
        animate(HOLD_MS) { t -> ghost.pressTo(t) }
        ghost.pressTo(1f)
    }

    suspend fun release() {
        if (instant) return
        ghost.pressTo(0f)
        beat(TAP_SETTLE_MS)
    }

    fun hide() = ghost.hide()

    private suspend inline fun animate(ms: Long, block: (Float) -> Unit) {
        val span = ms.coerceAtLeast(1L)
        val t0 = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { now -> ((now - t0) / 1_000_000f) / span }
            block(t.coerceIn(0f, 1f))
            if (t >= 1f) return
        }
    }

    private companion object {
        const val GLIDE_MS = 460L
        const val TAP_MS = 220L
        const val TAP_SETTLE_MS = 260L
        const val HOLD_MS = 520L

        fun ease(t: Float): Float = t * t * (3f - 2f * t)
    }
}

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
