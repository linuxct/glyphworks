package space.linuxct.glyphworks.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.ui.HintText
import space.linuxct.glyphworks.ui.NoRipple
import space.linuxct.glyphworks.ui.TOGGLE_CONTAINER_SIZE
import space.linuxct.glyphworks.ui.offStateOutline
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val REORDER_THRESHOLD = 0.6f

private val THUMB_SIZE = 52.dp

private val THUMB_GAP = 4.dp

internal fun reorderShift(offsetPx: Float, itemWidthPx: Int, index: Int, lastIndex: Int): Int {
    if (itemWidthPx <= 0 || lastIndex <= 0) return 0
    val threshold = itemWidthPx * REORDER_THRESHOLD
    var offset = offsetPx
    var at = index
    var shift = 0
    while (offset > threshold && at < lastIndex) {
        at++
        shift++
        offset -= itemWidthPx
    }
    while (offset < -threshold && at > 0) {
        at--
        shift--
        offset += itemWidthPx
    }
    return shift
}

// Pointer deltas and translationX are raw physical pixels; list indices are not. Every offset
// in the reorder is logical, and this converts at the two boundaries.
internal fun dragSign(rtl: Boolean): Int = if (rtl) -1 else 1

internal fun <T> moveItem(list: MutableList<T>, from: Int, to: Int): Boolean {
    if (from == to || from !in list.indices || to !in list.indices) return false
    list.add(to, list.removeAt(from))
    return true
}

internal fun selectionAfterMove(selected: Int, from: Int, to: Int): Int = when {
    selected == from -> to
    from < selected && to >= selected -> selected - 1
    from > selected && to <= selected -> selected + 1
    else -> selected
}

internal fun selectionAfterDelete(selected: Int, removed: Int, sizeAfter: Int): Int {
    val shifted = if (removed < selected) selected - 1 else selected
    return shifted.coerceIn(0, (sizeAfter - 1).coerceAtLeast(0))
}

// A ladder, not a slider: the codec accepts 20 ms to 60 s. The first rung is
// DesignCodec.MIN_DURATION_MS and the last is MAX_DURATION_MS, so this control cannot produce
// a duration the codec would refuse.
internal val DURATION_STEPS: IntArray = intArrayOf(
    20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500, 750,
    1_000, 1_500, 2_000, 3_000, 5_000, 10_000, 20_000, 30_000, 60_000,
)

internal fun clampDuration(ms: Int): Int =
    ms.coerceIn(DesignCodec.MIN_DURATION_MS, DesignCodec.MAX_DURATION_MS)

internal fun stepDuration(ms: Int, up: Boolean): Int {
    val current = clampDuration(ms)
    return if (up) {
        DURATION_STEPS.firstOrNull { it > current } ?: DesignCodec.MAX_DURATION_MS
    } else {
        DURATION_STEPS.lastOrNull { it < current } ?: DesignCodec.MIN_DURATION_MS
    }
}

// Not a string resource: `ms` and `s` are SI symbols and read the same in every locale.
internal fun formatDurationValue(ms: Int): String {
    val v = clampDuration(ms)
    return when {
        v < 1_000 -> "$v ms"
        v % 1_000 == 0 -> "${v / 1_000} s"
        else -> "${v / 1_000}.${(v % 1_000) / 100} s"
    }
}

internal fun formatTotalValue(ms: Int): String {
    if (ms < 60_000) return formatDurationValue(ms)
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    return "${minutes}m ${seconds}s"
}

@Stable
internal class ThumbnailCache {
    private var bitmap: ImageBitmap? = null
    private var revision = Int.MIN_VALUE
    private var width = 0
    private var height = 0

    fun get(
        revision: Int,
        width: Int,
        height: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        draw: DrawScope.() -> Unit,
    ): ImageBitmap {
        val cached = bitmap
        if (cached != null && revision == this.revision && width == this.width && height == this.height) {
            return cached
        }
        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density,
            layoutDirection,
            GraphicsCanvas(target),
            Size(width.toFloat(), height.toFloat()),
            draw,
        )
        bitmap = target
        this.revision = revision
        this.width = width
        this.height = height
        return target
    }

    fun release() {
        bitmap = null
        revision = Int.MIN_VALUE
    }
}

@Stable
private class TimelineDragState {
    var draggingIndex by mutableIntStateOf(-1)

    var offsetX by mutableFloatStateOf(0f)

    var pushX by mutableFloatStateOf(0f)

    var itemWidthPx by mutableIntStateOf(0)

    var settlingIndex by mutableIntStateOf(-1)

    val settleOffset = Animatable(0f)
}

private fun applyReorder(state: EditorState, drag: TimelineDragState) {
    val width = drag.itemWidthPx
    val shift = reorderShift(drag.offsetX, width, drag.draggingIndex, state.frames.lastIndex)
    if (shift == 0) return
    val step = if (shift > 0) 1 else -1
    repeat(abs(shift)) {
        val from = drag.draggingIndex
        if (!state.moveFrame(from, from + step)) return
        drag.draggingIndex = from + step
        drag.offsetX -= step * width
    }
}

private const val EDGE_ZONE_FRACTION = 0.9f

private const val EDGE_SCROLL_PX_PER_MS = 1.2f

private const val EDGE_SCROLL_MAX_STEP = 60f

private fun edgeDirection(listState: LazyListState, drag: TimelineDragState): Int {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == drag.draggingIndex } ?: return 0
    val start = item.offset + drag.offsetX
    val end = start + item.size
    val zone = item.size * EDGE_ZONE_FRACTION
    return when {
        drag.pushX > 0f && end > info.viewportEndOffset - zone -> 1
        drag.pushX < 0f && start < info.viewportStartOffset + zone -> -1
        else -> 0
    }
}

// Each step scrolls the list and adds what was consumed back onto the drag offset. That keeps
// the frame under a still finger and walks it past every neighbour the scroll brings under it.
private suspend fun autoScrollAtEdges(
    listState: LazyListState,
    state: EditorState,
    drag: TimelineDragState,
) {
    var previous = 0L
    while (currentCoroutineContext().isActive) {
        val now = withFrameNanos { it }
        val elapsed = if (previous == 0L) 0f else (now - previous) / 1_000_000f
        previous = now
        val direction = edgeDirection(listState, drag)
        if (direction == 0 || elapsed <= 0f) continue
        val step = (elapsed * EDGE_SCROLL_PX_PER_MS).coerceAtMost(EDGE_SCROLL_MAX_STEP)
        val consumed = listState.scrollBy(direction * step)
        if (consumed != 0f) {
            drag.offsetX += consumed
            applyReorder(state, drag)
        }
    }
}

private fun isItemFullyVisible(listState: LazyListState, index: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

@Composable
internal fun Timeline(state: EditorState, onChanged: () -> Unit) {
    val drag = remember { TimelineDragState() }
    val listState = rememberLazyListState()
    val sign = dragSign(LocalLayoutDirection.current == LayoutDirection.Rtl)

    val dragging by remember { derivedStateOf { drag.draggingIndex >= 0 } }
    LaunchedEffect(dragging) {
        if (dragging) autoScrollAtEdges(listState, state, drag)
    }

    LaunchedEffect(state.selectedIndex, state.frames.size) {
        val draggingNow = drag.draggingIndex >= 0
        if (!draggingNow && !isItemFullyVisible(listState, state.selectedIndex)) {
            listState.animateScrollToItem(state.selectedIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(state.frames, key = { _, entry -> entry.id }) { index, entry ->
            val placement = if (drag.draggingIndex == index) {
                Modifier
            } else {
                Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                )
            }
            FrameThumbnail(
                state = state,
                entry = entry,
                index = index,
                drag = drag,
                sign = sign,
                placement = placement,
                onChanged = onChanged,
            )
        }
    }

    FrameActionRow(state, onChanged)

    val hint = if (state.frames.size <= 1) {
        stringResource(R.string.editor_timeline_single)
    } else {
        stringResource(
            R.string.editor_timeline_status,
            state.selectedIndex + 1,
            state.frames.size,
            formatTotalValue(state.totalDurationMs),
        )
    }
    HintText(hint)
}

@Composable
private fun FrameThumbnail(
    state: EditorState,
    entry: TimelineEntry,
    index: Int,
    drag: TimelineDragState,
    sign: Int,
    placement: Modifier,
    onChanged: () -> Unit,
) {
    val dragging = drag.draggingIndex == index
    val settling = drag.settlingIndex == index
    val selected = state.selectedIndex == index
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val label = stringResource(R.string.editor_frame_thumb, index + 1)

    DisposableEffect(entry) {
        onDispose { entry.thumbnail.release() }
    }

    fun release() {
        val released = drag.draggingIndex
        val from = drag.offsetX
        drag.draggingIndex = -1
        drag.offsetX = 0f
        drag.pushX = 0f
        onChanged()
        scope.launch {
            drag.settlingIndex = released
            try {
                drag.settleOffset.snapTo(from)
                drag.settleOffset.animateTo(0f, settleSpec)
            } finally {
                drag.settlingIndex = -1
            }
        }
    }

    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "frameBorderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "frameBorderColor",
    )
    val shadow by animateDpAsState(
        targetValue = if (dragging) 6.dp else 0.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "frameShadow",
    )

    Column(
        modifier = Modifier
            .then(placement)
            .demoTarget(DemoTarget.FRAME, index)
            .zIndex(if (dragging || settling) 1f else 0f)
            .graphicsLayer {
                translationX = sign * when {
                    dragging -> drag.offsetX
                    settling -> drag.settleOffset.value
                    else -> 0f
                }
            }
            // Before the padding, so the recorded width is the full item pitch. That is what
            // the reorder threshold and the offset rebase are measured in.
            .onSizeChanged { drag.itemWidthPx = it.width }
            .padding(horizontal = THUMB_GAP, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NoRipple {
            Surface(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { state.select(index) },
                    )
                    .semantics { contentDescription = label }
                    .reorderDragGesture(state, entry, drag, sign, onRelease = ::release),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(borderWidth.coerceAtLeast(0.dp), borderColor),
                shadowElevation = shadow.coerceAtLeast(0.dp),
            ) {
                FrameThumbnailArt(entry)
            }
        }
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// Press and hold to drag, so a plain tap still selects. The long-press detector leaves the
// down alone and consumes the moves, which cancels the pending click.
private fun Modifier.reorderDragGesture(
    state: EditorState,
    entry: TimelineEntry,
    drag: TimelineDragState,
    sign: Int,
    onRelease: () -> Unit,
): Modifier = pointerInput(entry.id) {
    detectDragGesturesAfterLongPress(
        onDragStart = {
            // By id, not a captured index: the strip may have been reordered since this item
            // was composed.
            val at = state.frames.indexOfFirst { it.id == entry.id }
            if (at >= 0) {
                drag.draggingIndex = at
                drag.offsetX = 0f
                drag.pushX = 0f
                state.select(at)
            }
        },
        onDrag = { change, amount ->
            change.consume()
            val logicalDelta = amount.x * sign
            drag.offsetX += logicalDelta
            drag.pushX += logicalDelta
            applyReorder(state, drag)
        },
        onDragEnd = onRelease,
        onDragCancel = onRelease,
    )
}

@Composable
private fun FrameThumbnailArt(entry: TimelineEntry) {
    Canvas(Modifier.size(THUMB_SIZE)) {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) return@Canvas
        val image = entry.thumbnail.get(
            revision = entry.frame.revisionForDraw(),
            width = width,
            height = height,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(size.width, size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            drawMatrix(center, radius, entry.frame.size, entry.frame.cellsForDraw())
        }
        drawImage(image)
    }
}

@Composable
private fun FrameActionRow(state: EditorState, onChanged: () -> Unit) {
    val duration = state.selected.durationMs
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (state.setSelectedDuration(stepDuration(duration, up = false))) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.DURATION, 0),
                enabled = duration > DesignCodec.MIN_DURATION_MS,
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.editor_duration_shorter))
            }
            Text(
                text = formatDurationValue(duration),
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(
                onClick = { if (state.setSelectedDuration(stepDuration(duration, up = true))) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.DURATION, 1),
                enabled = duration < DesignCodec.MAX_DURATION_MS,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.editor_duration_longer))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (state.addFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 0),
                enabled = !state.atFrameLimit,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.editor_frame_add))
            }
            IconButton(
                onClick = { if (state.duplicateFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 1),
                enabled = !state.atFrameLimit,
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.editor_frame_duplicate))
            }
            IconButton(
                onClick = { if (state.deleteFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 2),
                enabled = state.frames.size > 1,
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.editor_frame_delete))
            }
        }
    }
}

@Composable
internal fun PlaybackRow(state: EditorState, onChanged: () -> Unit) {
    Text(
        stringResource(R.string.editor_key_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    NoRipple {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KEY_MODES.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = state.design.keyMode == mode,
                    onClick = { if (state.setKeyMode(mode)) onChanged() },
                    modifier = Modifier.demoTarget(DemoTarget.KEY_MODE, i),
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = KEY_MODES.size),
                ) {
                    SegmentLabel(stringResource(label))
                }
            }
        }
    }
    if (state.design.keyMode == KeyMode.PLAY_PAUSE) {
        Spacer(Modifier.height(8.dp))
        LoopRow(state, onChanged)
    }
}

@Composable
private fun LoopRow(state: EditorState, onChanged: () -> Unit) {
    val loop = state.design.loop
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.onSurface
                .copy(alpha = LOOP_ROW_PRESS_ALPHA)
                .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopRowContainer",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .demoTarget(DemoTarget.LOOP)
            .toggleable(
                value = loop,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = { if (state.setLoop(it)) onChanged() },
            )
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(LOOP_ROW_CORNER),
        color = container,
        shadowElevation = LOOP_ROW_ELEVATION,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoopIndicator(loop)
            Text(
                text = stringResource(if (loop) R.string.editor_loop_on else R.string.editor_loop_off),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val LOOP_ROW_CORNER = 16.dp

private val LOOP_ROW_ELEVATION = 2.dp

private const val LOOP_ROW_PRESS_ALPHA = 0.12f

@Composable
private fun LoopIndicator(loop: Boolean) {
    val colors = IconButtonDefaults.filledIconToggleButtonColors()
    val container by animateColorAsState(
        targetValue = if (loop) colors.checkedContainerColor else colors.containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopContainer",
    )
    val corner by animateDpAsState(
        targetValue = if (loop) LOOP_CHECKED_CORNER else TOGGLE_CONTAINER_SIZE / 2,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopCorner",
    )
    Surface(
        // offStateOutline sets the size as well as the off-state ring, so do not add a size().
        modifier = Modifier.offStateOutline(loop),
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = container,
        contentColor = if (loop) colors.checkedContentColor else colors.contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Repeat, contentDescription = stringResource(R.string.editor_loop))
        }
    }
}

private val LOOP_CHECKED_CORNER = 12.dp

@Composable
internal fun SegmentLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 3,
    )
}

private val KEY_MODES = listOf(
    KeyMode.PLAY_ONCE to R.string.editor_key_once,
    KeyMode.PLAY_PAUSE to R.string.editor_key_toggle,
)
