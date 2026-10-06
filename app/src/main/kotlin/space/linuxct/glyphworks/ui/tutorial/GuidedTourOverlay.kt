package space.linuxct.glyphworks.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import space.linuxct.glyphworks.ui.theme.dialogSurface
import space.linuxct.glyphworks.ui.theme.glyphCorner
import space.linuxct.glyphworks.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Consuming on PointerEventPass.Initial is what makes this a swallow, not a fallback.
internal fun Modifier.swallowTourTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

private val SPOTLIGHT_PADDING = 10.dp

private val SPOTLIGHT_RADIUS = 20.dp

private val CAPTION_GAP = 16.dp

private val GHOST_RADIUS = 20.dp

@Composable
internal fun <T> GuidedTourOverlay(
    caption: Int,
    at: Int,
    total: Int,
    target: T?,
    targetIndex: Int? = null,
    targets: TourTargets<T>,
    ghost: TourGhost,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    // Composed after the editor's own BackHandler, so the dispatcher reaches this one first.
    BackHandler(onBack = onSkip)

    val density = LocalDensity.current
    val padPx = with(density) { SPOTLIGHT_PADDING.toPx() }
    val insets = WindowInsets.safeDrawing
    val topInset = insets.getTop(density)
    val bottomInset = insets.getBottom(density)

    var focus by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(target, targetIndex, targets) {
        if (target == null) {
            focus = null
            return@LaunchedEffect
        }
        snapshotFlow {
            targetIndex?.let { targets.boundsOf(target, it) } ?: targets.unionOf(target)
        }.collect { focus = it }
    }

    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val holeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    LaunchedEffect(hole) {
        var placed = false
        snapshotFlow { focus }.collectLatest { rect ->
            if (rect == null) {
                placed = false
                return@collectLatest
            }
            val padded = rect.inflate(padPx)
            if (placed) hole.animateTo(padded, holeSpec) else hole.snapTo(padded)
            placed = true
        }
    }

    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val ring = MaterialTheme.colorScheme.surface
    val radiusPx = with(density) { SPOTLIGHT_RADIUS.toPx() }
    val ringPx = with(density) { 2.dp.toPx() }
    val ghostPx = with(density) { GHOST_RADIUS.toPx() }

    // Two Canvases on purpose. BlendMode.Clear needs a full-window offscreen layer, so this
    // one must only redraw when the spotlight moves. The ghost gets a plain Canvas above it.
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRect(scrim)
        if (focus != null) {
            val spot = hole.value.normalized()
            drawRoundRect(
                Color.Black,
                topLeft = spot.topLeft,
                size = spot.size,
                cornerRadius = CornerRadius(radiusPx),
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                ring,
                topLeft = spot.topLeft,
                size = spot.size,
                cornerRadius = CornerRadius(radiusPx),
                style = Stroke(width = ringPx),
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        ghost.position?.let { drawGhost(it, ghost.press, ghostPx) }
    }

    Box(Modifier.fillMaxSize()) {
        DemoCaption(
            caption = caption,
            at = at,
            total = total,
            onBack = onBack,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier.placedAround(focus, topInset, bottomInset),
        )
    }
}

private const val SCRIM_ALPHA = 0.72f

private fun Rect.normalized(): Rect = Rect(
    left = min(left, right),
    top = min(top, bottom),
    right = max(left, right),
    bottom = max(top, bottom),
)

private fun DrawScope.drawGhost(point: Offset, press: Float, radius: Float) {
    drawCircle(GHOST_FILL, radius = radius, center = point)
    drawCircle(
        GHOST_RING,
        radius = radius,
        center = point,
        style = Stroke(width = radius * GHOST_RING_WIDTH_FRACTION),
    )
    if (press > 0f) {
        val pressRadius = radius * GHOST_PRESS_RADIUS_FRACTION
        drawArc(
            GHOST_PRESS_ARC,
            startAngle = -90f,
            sweepAngle = 360f * press.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(point.x - pressRadius, point.y - pressRadius),
            size = Size(pressRadius * 2f, pressRadius * 2f),
            style = Stroke(width = radius * GHOST_PRESS_WIDTH_FRACTION),
        )
    }
}

private val GHOST_FILL = Color.White.copy(alpha = 0.34f)
private val GHOST_RING = Color.Black.copy(alpha = 0.55f)
private val GHOST_PRESS_ARC = Color.White.copy(alpha = 0.9f)
private const val GHOST_RING_WIDTH_FRACTION = 0.12f
private const val GHOST_PRESS_RADIUS_FRACTION = 1.35f
private const val GHOST_PRESS_WIDTH_FRACTION = 0.16f

@Composable
private fun DemoCaption(
    caption: Int,
    at: Int,
    total: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = at == total - 1
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = CAPTION_MAX_WIDTH),
            shape = glyphCorner(24.dp, 32.dp),
            color = dialogSurface(),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    stringResource(R.string.demo_step_of, at + 1, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(caption), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.demo_skip)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack, enabled = at > 0) {
                        Text(stringResource(R.string.demo_back))
                    }
                    TextButton(onClick = onNext) {
                        Text(stringResource(if (last) R.string.demo_done else R.string.demo_next))
                    }
                }
            }
        }
    }
}

private val CAPTION_MAX_WIDTH = 420.dp

private fun Modifier.placedAround(spotlight: Rect?, topInset: Int, bottomInset: Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val available = constraints.maxHeight
        val gap = CAPTION_GAP.toPx()
        val fitsBelow = spotlight != null &&
            available - spotlight.bottom - bottomInset >= placeable.height + gap
        val y = when {
            spotlight == null -> (available - placeable.height) / 2
            fitsBelow -> (spotlight.bottom + gap).roundToInt()
            else -> (spotlight.top - gap - placeable.height).roundToInt()
        }
        val lowest = (available - bottomInset - placeable.height).coerceAtLeast(0)
        layout(placeable.width, available) {
            placeable.place(0, y.coerceIn(min(topInset, lowest), lowest))
        }
    }
