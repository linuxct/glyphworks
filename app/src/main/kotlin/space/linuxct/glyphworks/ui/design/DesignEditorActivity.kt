package space.linuxct.glyphworks.ui.design

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.ui.theme.fullContrastListItemColors
import space.linuxct.glyphworks.ui.theme.fullContrastToggleColors
import space.linuxct.glyphworks.ui.theme.fullContrastTopAppBarColors
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.AssistantAction
import space.linuxct.glyphworks.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.ui.MotionDialog
import space.linuxct.glyphworks.ui.dialogCardWidth
import space.linuxct.glyphworks.ui.displayNameRes
import space.linuxct.glyphworks.ui.homeCodename
import space.linuxct.glyphworks.ui.NoRipple
import space.linuxct.glyphworks.ui.offStateOutline
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.saveRespectingAuthor
import space.linuxct.glyphworks.ui.showDesignOnMatrix
import space.linuxct.glyphworks.ui.ShowOnMatrix
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DesignEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        val designId = intent.getStringExtra(EXTRA_DESIGN_ID).orEmpty()
        setContent {
            GlyphWorksTheme {
                DesignEditor(designId = designId, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_DESIGN_ID = "designId"

        fun intent(context: Context, designId: String): Intent =
            Intent(context, DesignEditorActivity::class.java).putExtra(EXTRA_DESIGN_ID, designId)
    }
}

@Composable
private fun DesignEditor(designId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Core.designStore }
    val openFailed = stringResource(R.string.editor_open_failed)

    var state by remember { mutableStateOf<EditorState?>(null) }
    LaunchedEffect(designId) {
        val design = withContext(Dispatchers.IO) { store.load(designId) }
        if (design == null) {
            Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
            onClose()
        } else {
            state = EditorState(design, openingCodename(design, homeCodename()))
        }
    }

    state?.let { EditorScaffold(it, store, onClose) }
}

private const val SAVE_DEBOUNCE_MS = 750L

internal enum class SaveOutcome {
    WRITTEN,

    UNCHANGED,

    FAILED,
}

@Stable
private class SaveScheduler(
    private val scope: CoroutineScope,
    private val save: suspend () -> Boolean,
) {
    private var pending: Job? = null

    fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            withContext(NonCancellable) { save() }
        }
    }

    fun flush(onDone: (Boolean) -> Unit = {}) {
        pending?.cancel()
        pending = null
        scope.launch { onDone(save()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScaffold(
    state: EditorState,
    store: DesignStore,
    onClose: () -> Unit,
    demo: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.create_save_failed)
    val unnamed = stringResource(R.string.pref_custom_unnamed)

    val saver = remember(state, store, demo) {
        SaveScheduler(scope) {
            if (demo) return@SaveScheduler true
            when (state.saveIfDirty(store)) {
                SaveOutcome.WRITTEN -> {
                    Core.scheduler.run { Core.screenManager.refreshCurrentScreen() }
                    true
                }
                SaveOutcome.UNCHANGED -> true
                SaveOutcome.FAILED -> {
                    Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    val claimed = remember(state) { mutableStateOf(false) }

    fun edit() {
        claimed.value = true
        saver.schedule()
    }

    var settingsOpen by remember { mutableStateOf(false) }

    var marqueeOpen by rememberSaveable { mutableStateOf(false) }

    val transform = remember { CanvasTransform() }

    var playing by remember(state) { mutableStateOf(false) }
    val dynamic = state.design.kind == DesignKind.DYNAMIC
    LaunchedEffect(dynamic) { if (!dynamic) playing = false }

    var previewShown by rememberSaveable(state) { mutableStateOf(true) }

    if (!demo) {
        LiveMatrixPreview(state, playing = playing, onRest = { playing = false })

        LaunchedEffect(state, claimed.value) {
            if (!claimed.value) return@LaunchedEffect
            when (showDesignOnMatrix(state.design)) {
                is ShowOnMatrix.NoArt -> Unit
                ShowOnMatrix.Shown, ShowOnMatrix.ShownWhenEnabled -> Unit
            }
        }
    }

    fun saveAndClose() = saver.flush { ok -> if (ok) onClose() }

    LifecycleStartEffect(state) {
        onStopOrDispose { saver.flush() }
    }

    BackHandler { saveAndClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditorTopBar(
                title = state.design.name.ifBlank { unnamed },
                state = state,
                dynamic = dynamic,
                playing = playing,
                previewShown = previewShown,
                onClose = { saveAndClose() },
                onPlayToggle = {
                    playing = !playing
                    claimed.value = true
                },
                onPreviewToggle = { previewShown = !previewShown },
                onEdit = { edit() },
                onSettings = { settingsOpen = true },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            EditorBody(
                state = state,
                transform = transform,
                dynamic = dynamic,
                onEdit = { edit() },
                onMarquee = { marqueeOpen = true },
                onVariantSwitched = {
                    edit()
                    playing = false
                },
            )
            if (dynamic && previewShown) FloatingLivePreview(state)
        }
    }

    if (settingsOpen) {
        DesignSettings(
            state = state,
            onChanged = { edit() },
            onDismiss = { settingsOpen = false },
        )
    }

    if (marqueeOpen) {
        MarqueeDialog(
            state = state,
            onDismiss = { marqueeOpen = false },
            onGenerated = {
                edit()
                playing = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    state: EditorState,
    dynamic: Boolean,
    playing: Boolean,
    previewShown: Boolean,
    onClose: () -> Unit,
    onPlayToggle: () -> Unit,
    onPreviewToggle: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.editor_close),
                )
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.demoTarget(DemoTarget.TOP_BAR),
            ) {
                if (dynamic) {
                    PlayAction(playing, onPlayToggle)
                    PreviewVisibilityAction(previewShown, onPreviewToggle)
                }
                AssistantAction(state, onEdit = onEdit)
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.demoTarget(DemoTarget.SETTINGS_ACTION),
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.editor_settings),
                    )
                }
            }
        },
        colors = fullContrastTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun PlayAction(playing: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.editor_pause else R.string.editor_play,
            ),
        )
    }
}

@Composable
private fun PreviewVisibilityAction(previewShown: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (previewShown) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = stringResource(
                if (previewShown) R.string.editor_preview_hide else R.string.editor_preview_show,
            ),
        )
    }
}

// Not scrollable: a vertical scroller would compete with the paint drag for the same gesture.
// Everything is sized to fit, and the canvas takes what is left, so every permanent row here
// comes off the cell pitch. A 100 dp row costs about 4.5 dp of pitch at 13x13.
@Composable
private fun EditorBody(
    state: EditorState,
    transform: CanvasTransform,
    dynamic: Boolean,
    onEdit: () -> Unit,
    onMarquee: () -> Unit,
    onVariantSwitched: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        EditorCanvas(
            state = state,
            transform = transform,
            onStrokeEnd = onEdit,
            modifier = Modifier.fillMaxWidth().weight(1f).demoTarget(DemoTarget.CANVAS),
        )
        Spacer(Modifier.height(CANVAS_PALETTE_GAP))
        PaletteRow(state)
        Spacer(Modifier.height(4.dp))
        ToolRow(
            state,
            transform,
            dynamic = dynamic,
            onChanged = onEdit,
            onMarquee = onMarquee,
        )
        if (state.variantsPresent.size > 1) {
            Spacer(Modifier.height(8.dp))
            VariantRow(state, onSwitched = onVariantSwitched)
        }
        if (state.design.kind == DesignKind.DYNAMIC) {
            Spacer(Modifier.height(4.dp))
            Timeline(state, onChanged = onEdit)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DesignSettings(state: EditorState, onChanged: () -> Unit, onDismiss: () -> Unit) {
    MotionDialog(onDismiss) { dismiss ->
        DesignSettingsCard(state, onChanged, onClose = dismiss)
    }
}

@Composable
internal fun DesignSettingsCard(state: EditorState, onChanged: () -> Unit, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.width(dialogCardWidth()),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.editor_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.design.kind == DesignKind.DYNAMIC) {
                Spacer(Modifier.height(16.dp))
                PlaybackRow(state, onChanged)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.editor_variant_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.missingVariant?.let { missing ->
                TextButton(
                    onClick = {
                        if (state.addVariant(missing)) onChanged()
                        onClose()
                    },
                    modifier = Modifier.demoTarget(DemoTarget.ADD_VARIANT),
                    contentPadding = ADD_VARIANT_PADDING,
                ) {
                    Text(
                        stringResource(
                            R.string.editor_add_variant,
                            stringResource(missing.displayNameRes()),
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.tut_close)) }
            }
        }
    }
}

private val ADD_VARIANT_PADDING = PaddingValues(horizontal = 0.dp, vertical = 8.dp)

internal fun openingCodename(design: Design, home: PokemonCodename): PokemonCodename = when {
    design.variantFor(home) != null -> home
    else -> PokemonCodename.entries.firstOrNull { design.variantFor(it) != null } ?: home
}

internal const val PREVIEW_INTERVAL_MS = 33L

@Composable
private fun LiveMatrixPreview(state: EditorState, playing: Boolean, onRest: () -> Unit) {
    val previewable = Core.glyphLink.isSupported && state.codename.size == Core.glyphLink.size
    if (!previewable) return

    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(state) {
        Core.arbiter.setPreviewActive(true)
        Core.scheduler.run { Core.screenManager.beginLivePreview() }
        resumed = true
        onPauseOrDispose {
            resumed = false
            Core.scheduler.run { Core.screenManager.endLivePreview() }
            Core.arbiter.setPreviewActive(false)
        }
    }

    LaunchedEffect(state, resumed, playing) {
        if (!resumed) return@LaunchedEffect
        if (playing) {
            var index = 0
            while (true) {
                val frames = state.frames
                if (frames.isEmpty()) break
                if (index > frames.lastIndex) index = 0
                val entry = frames[index]
                val cells = entry.frame.copyOfCells()
                Core.scheduler.run { Core.screenManager.pushLivePreview(cells) }
                delay(playbackHoldMs(entry.durationMs))
                val repeats = designRepeats(state.design.loop, state.design.keyMode)
                index = nextPlaybackFrame(index, state.frames.size, repeats) ?: break
            }
            onRest()
            return@LaunchedEffect
        }
        snapshotFlow { state.previewToken() }
            .conflate()
            .collect {
                val frame = state.copyOfSelectedCells()
                Core.scheduler.run { Core.screenManager.pushLivePreview(frame) }
                delay(PREVIEW_INTERVAL_MS)
            }
    }
}

private val CANVAS_PALETTE_GAP = 16.dp

private const val ZOOM_TARGET = 0.35f

private const val STROKE_STEP_FRACTION = 0.5f

internal const val MAX_CANVAS_SCALE = 4f

@Stable
internal class CanvasTransform {

    var scale by mutableFloatStateOf(1f)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    val offset: Offset get() = Offset(offsetX, offsetY)

    fun onGesture(centroid: Offset, pan: Offset, zoomChange: Float, canvas: Size) {
        if (canvas.width <= 0f || canvas.height <= 0f) return
        val next = (scale * zoomChange).coerceIn(1f, MAX_CANVAS_SCALE)
        val applied = if (scale > 0f) next / scale else 1f
        val x = centroid.x + pan.x - (centroid.x - offsetX) * applied
        val y = centroid.y + pan.y - (centroid.y - offsetY) * applied
        scale = next
        val minX = (canvas.width - canvas.width * next).coerceAtMost(0f)
        val minY = (canvas.height - canvas.height * next).coerceAtMost(0f)
        offsetX = x.coerceIn(minX, 0f)
        offsetY = y.coerceIn(minY, 0f)
    }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
}

internal fun editorCamera(canvas: Size): Camera {
    val radius = min(canvas.width, canvas.height) * ZOOM_TARGET
    return Camera(zoom = radius / DeviceBack.matrix.radius, focus = DeviceBack.matrix.center)
}

internal fun baseDisc(canvas: Size): MatrixDisc = editorCamera(canvas).matrixDisc(canvas)

private fun editorCamera(canvas: Size, transform: CanvasTransform): Camera =
    editorCamera(canvas).transformedBy(transform.scale, transform.offset, canvas)

internal fun demoCellCenter(canvasBounds: Rect, state: EditorState, x: Int, y: Int): Offset {
    val disc = baseDisc(canvasBounds.size)
    val local = matrixCellCenter(disc.center, disc.radius, state.selected.frame.size, x, y)
    return local + canvasBounds.topLeft
}

@Composable
private fun EditorCanvas(
    state: EditorState,
    transform: CanvasTransform,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.onSurface
    val canvasDescription = stringResource(R.string.editor_canvas)
    Canvas(
        modifier
            .clipToBounds()
            .semantics { contentDescription = canvasDescription }
            .pointerInput(state, transform) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val canvas = size.toSize()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var painting = false
                    var transforming = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2 || transforming) {
                            if (painting) {
                                painting = false
                                if (state.endStroke()) onStrokeEnd()
                            }
                            transforming = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                transform.onGesture(centroid, pan, zoom, canvas)
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                        if (!painting) {
                            if ((change.position - down.position).getDistance() <= slop) continue
                            painting = true
                            state.beginStroke()
                            paintSegment(state, transform, canvas, down.position, change.position)
                        } else {
                            paintSegment(state, transform, canvas, last, change.position)
                        }
                        last = change.position
                        change.consume()
                    }

                    when {
                        painting -> if (state.endStroke()) onStrokeEnd()
                        !transforming -> {
                            state.beginStroke()
                            paintSegment(state, transform, canvas, down.position, down.position)
                            if (state.endStroke()) onStrokeEnd()
                        }
                    }
                }
            },
    ) {
        val camera = editorCamera(size, transform)
        val disc = drawDeviceBack(base, camera)
        val cells = state.cellsForDraw()
        drawMatrix(disc.center, disc.radius, state.frameSizeForDraw(), cells)
        state.onionCellsForDraw()?.let { ghost ->
            drawMatrixGhost(disc.center, disc.radius, state.frameSizeForDraw(), ghost, cells)
        }
    }
}

private fun paintSegment(
    state: EditorState,
    transform: CanvasTransform,
    canvas: Size,
    from: Offset,
    to: Offset,
) {
    val disc = editorCamera(canvas, transform).matrixDisc(canvas)
    val size = state.selected.frame.size
    val pitch = matrixCellPitch(disc.radius, size)
    if (pitch <= 0f) return
    val steps = max(1, ceil((to - from).getDistance() / (pitch * STROKE_STEP_FRACTION)).toInt())
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val point = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        val cell = matrixCellAt(point, disc.center, disc.radius, size) ?: continue
        state.paint(cell.x, cell.y)
    }
}

private val SWATCH_SIZE = 52.dp

private val SELECTION_RING = 2.dp

private val HAIRLINE = 1.dp

private const val LIGHT_SWATCH_GLASS = 0.62f
private const val LIGHT_SWATCH_LED = 0.29f

private const val DARK_SWATCH_LED = 0.46f

@Composable
private fun PaletteRow(state: EditorState) {
    val ring = MaterialTheme.colorScheme.onSurface
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val hairline = MaterialTheme.colorScheme.outline
    val off = stringResource(R.string.editor_brush_off)
    NoRipple {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
            horizontalArrangement = Arrangement.Center,
        ) {
            state.brushIndices.forEach { index ->
                val level = state.levelAt(index)
                val selected = index == state.brushIndex
                val percent = (level * 100f / DesignFrames.MAX_BRIGHTNESS).roundToInt()
                val label = if (level == 0) off else stringResource(R.string.editor_brush_level, percent)
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .size(SWATCH_SIZE)
                        .demoTarget(DemoTarget.PALETTE, index)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { state.brushIndex = index },
                        )
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        val glass = if (light) radius * LIGHT_SWATCH_GLASS else radius
                        val led = radius * if (light) LIGHT_SWATCH_LED else DARK_SWATCH_LED
                        if (light) {
                            drawCircle(Color.White, radius = radius, center = center)
                            drawCircle(
                                hairline,
                                radius = radius - HAIRLINE.toPx() / 2f,
                                center = center,
                                style = Stroke(width = HAIRLINE.toPx()),
                            )
                        }
                        drawCircle(MATRIX_DISC_COLOR, radius = glass, center = center)
                        val alpha = level / DesignFrames.MAX_BRIGHTNESS.toFloat()
                        drawCircle(
                            Color.White.copy(alpha = if (level == 0) UNLIT_SWATCH_ALPHA else alpha),
                            radius = led,
                            center = center,
                        )
                        if (selected) {
                            val half = SELECTION_RING.toPx()
                            drawCircle(
                                ring,
                                radius = radius - half,
                                center = center,
                                style = Stroke(width = half * 2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val UNLIT_SWATCH_ALPHA = 0.10f

@Composable
private fun ToolRow(
    state: EditorState,
    transform: CanvasTransform,
    dynamic: Boolean,
    onChanged: () -> Unit,
    onMarquee: () -> Unit,
) {
    val zoomed by remember(transform) { derivedStateOf { transform.scale > 1f } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { if (state.undo()) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 0),
            enabled = state.canUndo,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.editor_undo))
        }
        IconButton(
            onClick = { if (state.redo()) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 1),
            enabled = state.canRedo,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.editor_redo))
        }
        IconButton(
            onClick = { if (state.fillAll(0)) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 2),
        ) {
            Icon(Icons.Outlined.FormatColorReset, contentDescription = stringResource(R.string.editor_clear))
        }
        IconButton(
            onClick = { if (state.fillAll(state.brushValue())) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 3),
        ) {
            Icon(Icons.Outlined.FormatColorFill, contentDescription = stringResource(R.string.editor_fill))
        }
        if (dynamic) {
            IconButton(
                onClick = onMarquee,
                modifier = Modifier.demoTarget(DemoTarget.TOOLS, 4),
            ) {
                Icon(Icons.Outlined.TextFields, contentDescription = stringResource(R.string.marquee_tool))
            }
        }
        if (zoomed) {
            IconButton(onClick = { transform.reset() }) {
                Icon(
                    Icons.Outlined.ZoomOutMap,
                    contentDescription = stringResource(R.string.editor_zoom_reset),
                )
            }
        }
        if (state.canOnionSkin) {
            NoRipple {
                FilledIconToggleButton(
                    colors = fullContrastToggleColors(),
                    checked = state.onionSkin,
                    onCheckedChange = { state.onionSkin = it },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.offStateOutline(state.onionSkin),
                ) {
                    Icon(
                        Icons.Outlined.Layers,
                        contentDescription = stringResource(
                            if (state.onionSkin) R.string.editor_onion_on else R.string.editor_onion_off,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantRow(state: EditorState, onSwitched: () -> Unit) {
    val present = state.variantsPresent
    NoRipple {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            present.forEachIndexed { i, codename ->
                SegmentedButton(
                    selected = codename == state.codename,
                    onClick = { if (state.switchTo(codename)) onSwitched() },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = present.size),
                ) {
                    SegmentLabel(stringResource(codename.displayNameRes()))
                }
            }
        }
    }
}

private const val UNDO_DEPTH = 32

private const val DOCUMENT_UNDO_DEPTH = 4

@Stable
internal class EditorFrame(val size: Int, private val cells: IntArray) {

    private var revision by mutableIntStateOf(0)

    @Suppress("UNUSED_EXPRESSION")
    fun cellsForDraw(): IntArray {
        revision
        return cells
    }

    fun revisionForDraw(): Int = revision

    fun revisionForSnapshot(): Int = revision

    fun copyOfCells(): IntArray = cells.copyOf()

    fun set(x: Int, y: Int, value: Int): Boolean {
        if (x < 0 || y < 0 || x >= size || y >= size) return false
        val i = y * size + x
        if (cells[i] == value) return false
        cells[i] = value
        revision++
        return true
    }

    fun fill(value: Int): Boolean {
        if (cells.all { it == value }) return false
        cells.fill(value)
        revision++
        return true
    }

    fun restore(snapshot: IntArray): Boolean {
        if (snapshot.size != cells.size || snapshot.contentEquals(cells)) return false
        snapshot.copyInto(cells)
        revision++
        return true
    }
}

@Stable
internal class TimelineEntry(
    val id: Long,
    val frame: EditorFrame,
    durationMs: Int = DEFAULT_FRAME_DURATION_MS,
) {
    var durationMs by mutableIntStateOf(clampDuration(durationMs))
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    val thumbnail = ThumbnailCache()

    private val undoStack = ArrayDeque<IntArray>()
    private val redoStack = ArrayDeque<IntArray>()

    fun setDuration(ms: Int): Boolean {
        val next = clampDuration(ms)
        if (next == durationMs) return false
        durationMs = next
        return true
    }

    fun pushUndo(snapshot: IntArray) {
        undoStack.addLast(snapshot)
        if (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        refreshFlags()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(frame.copyOfCells())
        if (redoStack.size > UNDO_DEPTH) redoStack.removeFirst()
        frame.restore(previous)
        refreshFlags()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(frame.copyOfCells())
        if (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        frame.restore(next)
        refreshFlags()
        return true
    }

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}

@Stable
internal class EditorState(design: Design, codename: PokemonCodename) {

    var design by mutableStateOf(design)
        private set

    var codename by mutableStateOf(codename)
        private set

    private var nextFrameId = 0L

    val frames = mutableStateListOf<TimelineEntry>().apply {
        addAll(loadFrames(design, codename))
    }

    var selectedIndex by mutableIntStateOf(0)
        private set

    var onionSkin by mutableStateOf(false)

    var brushIndex by mutableIntStateOf(design.levels.lastIndex.coerceIn(0, MAX_SWATCHES - 1))

    private var strokeBase: IntArray? = null
    private var strokeChanged = false

    private var dirty = false

    private val documentUndo = ArrayDeque<Design>()
    private val documentRedo = ArrayDeque<Design>()
    private var documentUndoDepth by mutableIntStateOf(0)
    private var documentRedoDepth by mutableIntStateOf(0)

    private val saveMutex = Mutex()

    val selected: TimelineEntry get() = frames[selectedIndex.coerceIn(0, frames.lastIndex)]

    val canUndo: Boolean get() = selected.canUndo || documentUndoDepth > 0

    val canRedo: Boolean get() = selected.canRedo || documentRedoDepth > 0

    val canOnionSkin: Boolean
        get() = design.kind == DesignKind.DYNAMIC && frames.size > 1

    val atFrameLimit: Boolean get() = frames.size >= DesignCodec.MAX_FRAMES

    val totalDurationMs: Int get() = frames.sumOf { it.durationMs }

    val brushIndices: List<Int> get() = (0 until min(design.levels.size, MAX_SWATCHES)).toList()

    fun levelAt(index: Int): Int =
        design.levels.getOrElse(index) { 0 }.coerceIn(0, DesignFrames.MAX_BRIGHTNESS)

    fun brushValue(): Int = levelAt(brushIndex)

    fun cellsForDraw(): IntArray = selected.frame.cellsForDraw()

    fun frameSizeForDraw(): Int = selected.frame.size

    fun onionCellsForDraw(): IntArray? {
        if (!onionSkin || !canOnionSkin) return null
        val previous = if (selectedIndex > 0) {
            frames[selectedIndex - 1]
        } else if (design.loop) {
            frames.last()
        } else {
            return null
        }
        return previous.frame.cellsForDraw()
    }

    fun previewToken(): Pair<Long, Int> {
        val entry = selected
        return entry.id to entry.frame.revisionForSnapshot()
    }

    fun copyOfSelectedCells(): IntArray = selected.frame.copyOfCells()

    fun beginStroke() {
        strokeBase = selected.frame.copyOfCells()
        strokeChanged = false
    }

    fun paint(x: Int, y: Int) {
        val entry = selected
        if (!entry.frame.set(x, y, brushValue())) return
        strokeBase?.let { base ->
            strokeBase = null
            entry.pushUndo(base)
        }
        strokeChanged = true
        markEdited()
    }

    fun endStroke(): Boolean {
        strokeBase = null
        val changed = strokeChanged
        strokeChanged = false
        return changed
    }

    fun fillAll(value: Int): Boolean {
        val entry = selected
        val base = entry.frame.copyOfCells()
        if (!entry.frame.fill(value)) return false
        entry.pushUndo(base)
        markEdited()
        return true
    }

    fun undo(): Boolean {
        if (selected.undo()) {
            dirty = true
            return true
        }
        val previous = documentUndo.removeLastOrNull() ?: return false
        push(documentRedo, composed())
        adopt(previous)
        refreshDocumentFlags()
        return true
    }

    fun redo(): Boolean {
        if (selected.redo()) {
            dirty = true
            return true
        }
        val next = documentRedo.removeLastOrNull() ?: return false
        push(documentUndo, composed())
        adopt(next)
        refreshDocumentFlags()
        return true
    }

    private fun markEdited() {
        dirty = true
        if (documentRedo.isNotEmpty()) {
            documentRedo.clear()
            refreshDocumentFlags()
        }
    }

    private fun push(stack: ArrayDeque<Design>, step: Design) {
        stack.addLast(step)
        if (stack.size > DOCUMENT_UNDO_DEPTH) stack.removeFirst()
    }

    private fun refreshDocumentFlags() {
        documentUndoDepth = documentUndo.size
        documentRedoDepth = documentRedo.size
    }

    fun select(index: Int) {
        selectedIndex = index.coerceIn(0, frames.lastIndex)
    }

    fun setSelectedDuration(ms: Int): Boolean {
        if (!selected.setDuration(ms)) return false
        markEdited()
        return true
    }

    fun addFrame(): Boolean {
        if (atFrameLimit) return false
        val at = selectedIndex + 1
        frames.add(
            at,
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(codename.size, IntArray(codename.cellCount)),
                durationMs = selected.durationMs,
            ),
        )
        selectedIndex = at
        markEdited()
        return true
    }

    fun duplicateFrame(): Boolean {
        if (atFrameLimit) return false
        val source = selected
        val at = selectedIndex + 1
        frames.add(
            at,
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(source.frame.size, source.frame.copyOfCells()),
                durationMs = source.durationMs,
            ),
        )
        selectedIndex = at
        markEdited()
        return true
    }

    fun deleteFrame(): Boolean {
        if (frames.size <= 1) return false
        val removed = selectedIndex
        frames.removeAt(removed)
        selectedIndex = selectionAfterDelete(selectedIndex, removed, frames.size)
        markEdited()
        return true
    }

    fun moveFrame(from: Int, to: Int): Boolean {
        if (!moveItem(frames, from, to)) return false
        selectedIndex = selectionAfterMove(selectedIndex, from, to)
        markEdited()
        return true
    }

    fun setLoop(loop: Boolean): Boolean {
        if (design.loop == loop) return false
        design = design.copy(loop = loop)
        markEdited()
        return true
    }

    fun setKeyMode(mode: KeyMode): Boolean {
        if (design.keyMode == mode) return false
        design = design.copy(keyMode = mode)
        markEdited()
        return true
    }

    val variantsPresent: List<PokemonCodename>
        get() = PokemonCodename.entries.filter { design.variantFor(it) != null }

    val missingVariant: PokemonCodename?
        get() = PokemonCodename.entries.firstOrNull { design.variantFor(it) == null }

    fun addVariant(target: PokemonCodename): Boolean {
        if (design.variantFor(target) != null) return false
        design = design.copy(variants = design.variants + (target.codename to DesignVariant()))
        markEdited()
        return true
    }

    fun switchTo(target: PokemonCodename): Boolean {
        if (target == codename) return false
        design = composed()
        dirty = true
        documentUndo.clear()
        documentRedo.clear()
        refreshDocumentFlags()
        codename = target
        frames.clear()
        frames.addAll(loadFrames(design, target))
        selectedIndex = 0
        return true
    }

    fun replaceDesign(incoming: Design, recordUndo: Boolean = false): Design? {
        if (incoming.variants.isEmpty()) return null
        val previous = composed()
        if (recordUndo) {
            push(documentUndo, previous)
            documentRedo.clear()
        } else {
            documentUndo.clear()
            documentRedo.clear()
        }
        refreshDocumentFlags()
        adopt(
            incoming.copy(
                format = previous.format,
                formatVersion = previous.formatVersion,
                id = previous.id,
                author = previous.author,
                createdAt = previous.createdAt,
                createdWith = previous.createdWith,
            ),
        )
        return previous
    }

    private fun adopt(document: Design) {
        val next = document.copy(modifiedAt = nowIsoUtc())
        design = next
        if (next.variantFor(codename) == null) codename = openingCodename(next, codename)
        frames.clear()
        frames.addAll(loadFrames(next, codename))
        selectedIndex = 0
        brushIndex = brushIndex.coerceIn(0, max(0, min(next.levels.size, MAX_SWATCHES) - 1))
        dirty = true
    }

    suspend fun saveIfDirty(store: DesignStore): SaveOutcome = saveMutex.withLock {
        if (!dirty) return@withLock SaveOutcome.UNCHANGED
        val snapshot = composed()
        dirty = false
        val ok = withContext(Dispatchers.IO) { saveRespectingAuthor(store, snapshot) }
        if (ok) design = snapshot else dirty = true
        if (ok) SaveOutcome.WRITTEN else SaveOutcome.FAILED
    }

    fun composed(): Design {
        val encoded = ArrayList<DesignFrame>(frames.size)
        for (entry in frames) {
            val cells = DesignFrames.encode(entry.frame.copyOfCells(), design.levels, codename.size)
                ?: return design
            encoded.add(DesignFrame(durationMs = entry.durationMs, cells = cells))
        }
        val variant = design.variantFor(codename) ?: DesignVariant()
        return design.copy(
            modifiedAt = nowIsoUtc(),
            variants = design.variants + (codename.codename to variant.copy(frames = encoded)),
        )
    }

    private fun loadFrames(design: Design, codename: PokemonCodename): List<TimelineEntry> {
        val stored = design.variantFor(codename)?.frames.orEmpty()
        val loaded = stored.map { saved ->
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(
                    codename.size,
                    DesignFrames.decode(saved.cells, design.levels, codename.size)
                        ?: IntArray(codename.cellCount),
                ),
                durationMs = saved.durationMs,
            )
        }
        return loaded.ifEmpty {
            listOf(
                TimelineEntry(
                    id = nextFrameId++,
                    frame = EditorFrame(codename.size, IntArray(codename.cellCount)),
                ),
            )
        }
    }

    private companion object {
        const val MAX_SWATCHES = 3
    }
}
