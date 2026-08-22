package space.linuxct.glyphworks.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import kotlin.math.min
import kotlin.math.roundToInt

internal fun nextPlaybackFrame(index: Int, count: Int, loop: Boolean): Int? = when {
    count <= 1 -> null
    index < count - 1 -> index + 1
    loop -> 0
    else -> null
}

internal fun designRepeats(loop: Boolean, keyMode: KeyMode): Boolean =
    loop && keyMode == KeyMode.PLAY_PAUSE

// The floor matters: the codec allows a 20 ms frame, and pushing at 50 Hz means 50 blocking
// binder calls a second for a panel that cannot show the difference.
internal fun playbackHoldMs(durationMs: Int): Long =
    durationMs.toLong().coerceAtLeast(PREVIEW_INTERVAL_MS)

internal const val PREVIEW_REST_MS = 600L

internal val PREVIEW_MORPH_SPEC: FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

internal fun previewHoldMs(durationMs: Int, last: Boolean, loop: Boolean): Long =
    playbackHoldMs(durationMs) + if (last && !loop) PREVIEW_REST_MS else 0L

@Stable
internal class PreviewPlayback {

    var frameIndex by mutableIntStateOf(0)
        private set

    private var nextAt = Long.MIN_VALUE

    fun reset() {
        frameIndex = 0
        nextAt = Long.MIN_VALUE
    }

    fun tick(nowMs: Long, count: Int, loop: Boolean, durationMsOf: (Int) -> Int) {
        if (count <= 0) return
        val index = frameIndex.coerceIn(0, count - 1)
        if (index != frameIndex) frameIndex = index
        if (nextAt == Long.MIN_VALUE) {
            nextAt = nowMs + hold(index, count, loop, durationMsOf)
            return
        }
        if (nowMs < nextAt) return
        val next = (index + 1) % count
        frameIndex = next
        nextAt = nowMs + hold(next, count, loop, durationMsOf)
    }

    private fun hold(index: Int, count: Int, loop: Boolean, durationMsOf: (Int) -> Int): Long =
        previewHoldMs(durationMsOf(index), last = index == count - 1, loop = loop)
}

private val FLOATING_PREVIEW_SMALL = 72.dp

private const val FLOATING_PREVIEW_SMALL_FRACTION = 0.28f

private const val FLOATING_PREVIEW_LARGE_FRACTION = 0.86f

private val FLOATING_PREVIEW_LARGE_MAX = 440.dp

private val FLOATING_PREVIEW_MARGIN = 12.dp

private const val FLOATING_PREVIEW_RASTER_PX = 320

private const val FLOATING_PREVIEW_CACHE_FRAMES = 16

internal fun floatingPreviewDiameter(available: DpSize, expanded: Boolean): Dp {
    if (!available.isSpecified) return FLOATING_PREVIEW_SMALL
    val shorter = minOf(available.width, available.height)
    if (shorter <= 0.dp) return FLOATING_PREVIEW_SMALL
    val small = minOf(FLOATING_PREVIEW_SMALL, shorter * FLOATING_PREVIEW_SMALL_FRACTION)
    if (!expanded) return small
    return maxOf(small, minOf(shorter * FLOATING_PREVIEW_LARGE_FRACTION, FLOATING_PREVIEW_LARGE_MAX))
}

@Stable
internal class FramePreviewCaches(private val capacity: Int = FLOATING_PREVIEW_CACHE_FRAMES) {

    private val caches = object : LinkedHashMap<Long, ThumbnailCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThumbnailCache>): Boolean {
            if (size <= capacity) return false
            eldest.value.release()
            return true
        }
    }

    fun of(id: Long): ThumbnailCache = caches.getOrPut(id) { ThumbnailCache() }

    val size: Int get() = caches.size

    fun release() {
        for (cache in caches.values) cache.release()
        caches.clear()
    }
}

private suspend fun runPreviewClock(state: EditorState, playback: PreviewPlayback) {
    while (true) {
        withFrameMillis { now ->
            playback.tick(
                nowMs = now,
                count = state.frames.size,
                loop = designRepeats(state.design.loop, state.design.keyMode),
            ) { state.frames[it].durationMs }
        }
    }
}

@Composable
internal fun FloatingLivePreview(state: EditorState, modifier: Modifier = Modifier) {
    val dynamic = state.design.kind == DesignKind.DYNAMIC
    val frameCount = state.frames.size

    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    val playback = remember(state) { PreviewPlayback() }
    val caches = remember(state) { FramePreviewCaches() }
    DisposableEffect(caches) { onDispose { caches.release() } }

    val animating = dynamic && frameCount > 1 && resumed
    LaunchedEffect(animating, state) {
        if (animating) runPreviewClock(state, playback)
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val morphSpring by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = PREVIEW_MORPH_SPEC,
        label = "floatingPreview",
    )

    // Clamped because every geometric property below is a lerp on it, and lerp extrapolates.
    // The spring runs past 1.0 and dips below 0.0, which would push the disc out of its corner.
    val fraction = morphSpring.coerceIn(0f, 1f)

    val label = stringResource(
        if (expanded) R.string.editor_preview_collapse else R.string.editor_preview_expand,
    )
    val scrim = MaterialTheme.colorScheme.scrim

    BoxWithConstraints(modifier.fillMaxSize()) {
        val window = DpSize(maxWidth, maxHeight)
        val diameter = lerp(
            floatingPreviewDiameter(window, expanded = false),
            floatingPreviewDiameter(window, expanded = true),
            fraction,
        )
        val badgeAlpha = (1f - fraction).coerceIn(0f, 1f)
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim.copy(alpha = SCRIM_ALPHA * fraction))
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = false }
                    },
            )
        }
        Column(
            modifier = Modifier
                .align(
                    BiasAlignment(
                        horizontalBias = lerp(1f, 0f, fraction),
                        verticalBias = lerp(-1f, 0f, fraction),
                    ),
                )
                .padding(lerp(FLOATING_PREVIEW_MARGIN, 0.dp, fraction).coerceAtLeast(0.dp))
                .demoTarget(DemoTarget.LIVE_PREVIEW),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .size(diameter.coerceAtLeast(0.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = !expanded }
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        onClick(label) {
                            expanded = !expanded
                            true
                        }
                    },
                shape = CircleShape,
                color = MATRIX_DISC_COLOR,
                border = BorderStroke(FLOATING_PREVIEW_BORDER, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = lerp(FLOATING_PREVIEW_REST_SHADOW, FLOATING_PREVIEW_OPEN_SHADOW, fraction)
                    .coerceAtLeast(0.dp),
            ) {
                FloatingPreviewArt(state, playback, caches, dynamic)
            }
            FloatingPreviewBadge(alpha = badgeAlpha)
        }
    }
}

private val FLOATING_PREVIEW_BADGE_GAP = 6.dp

private val FLOATING_PREVIEW_BADGE_PADDING_H = 8.dp
private val FLOATING_PREVIEW_BADGE_PADDING_V = 3.dp

@Composable
private fun FloatingPreviewBadge(alpha: Float) {
    Text(
        text = stringResource(R.string.editor_preview_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = Modifier
            .padding(top = FLOATING_PREVIEW_BADGE_GAP)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.background, CircleShape)
            .padding(
                horizontal = FLOATING_PREVIEW_BADGE_PADDING_H,
                vertical = FLOATING_PREVIEW_BADGE_PADDING_V,
            )
            .clearAndSetSemantics {},
    )
}

private const val SCRIM_ALPHA = 0.55f

private val FLOATING_PREVIEW_BORDER = 1.dp

private val FLOATING_PREVIEW_REST_SHADOW = 4.dp
private val FLOATING_PREVIEW_OPEN_SHADOW = 12.dp

@Composable
private fun FloatingPreviewArt(
    state: EditorState,
    playback: PreviewPlayback,
    caches: FramePreviewCaches,
    dynamic: Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        val entries = state.frames
        if (entries.isEmpty()) return@Canvas
        val index = if (dynamic) playback.frameIndex.coerceIn(0, entries.lastIndex) else 0
        val entry = entries[index]
        val side = min(size.width, size.height).roundToInt()
        if (side <= 0) return@Canvas
        val raster = min(side, FLOATING_PREVIEW_RASTER_PX)
        val image = caches.of(entry.id).get(
            revision = entry.frame.revisionForDraw(),
            width = raster,
            height = raster,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(this.size.width, this.size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            drawMatrix(center, radius, entry.frame.size, entry.frame.cellsForDraw())
        }
        drawImage(
            image = image,
            dstOffset = IntOffset(
                ((size.width - side) / 2f).roundToInt(),
                ((size.height - side) / 2f).roundToInt(),
            ),
            dstSize = IntSize(side, side),
        )
    }
}
