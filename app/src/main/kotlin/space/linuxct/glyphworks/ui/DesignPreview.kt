package space.linuxct.glyphworks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.ui.design.MATRIX_DISC_COLOR
import space.linuxct.glyphworks.ui.design.ThumbnailCache
import space.linuxct.glyphworks.ui.design.drawMatrix
import kotlin.math.min
import kotlin.math.roundToInt

private val DESIGN_CELL_TARGET_WIDTH = 120.dp

internal const val DESIGN_GRID_MIN_COLUMNS = 3
internal const val DESIGN_GRID_MAX_COLUMNS = 6

internal fun designGridColumns(available: Dp): Int {
    if (!available.isSpecified || available <= 0.dp) return DESIGN_GRID_MIN_COLUMNS
    val fittedColumns = (available / DESIGN_CELL_TARGET_WIDTH).toInt()
    return fittedColumns.coerceIn(DESIGN_GRID_MIN_COLUMNS, DESIGN_GRID_MAX_COLUMNS)
}

internal val DESIGN_GRID_OUTER_MARGIN = 12.dp
internal val DESIGN_GRID_GUTTER = 8.dp

// Measured: at a 14 dp top inset the overflow button's dots crossed the disc on a
// 360 dp window.
internal val DESIGN_DISC_SIDE_INSET = 18.dp
internal val DESIGN_DISC_TOP_INSET = 18.dp

internal fun designCellInsetWidth(column: Int, columns: Int): Dp =
    (if (column == 0) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2) +
        (if (column == columns - 1) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2)

internal fun designWidestCellInset(columns: Int): Dp =
    if (columns <= 1) DESIGN_GRID_OUTER_MARGIN * 2 else DESIGN_GRID_OUTER_MARGIN + DESIGN_GRID_GUTTER / 2

/** Keeps `cellInsetWidth(c, n) + 2 * discSideInset(c, n)` equal for every column. */
internal fun designDiscSideInset(column: Int, columns: Int): Dp =
    DESIGN_DISC_SIDE_INSET + (designWidestCellInset(columns) - designCellInsetWidth(column, columns)) / 2

internal data class PreviewStep(val frameIndex: Int, val holdMs: Int)

internal const val PREVIEW_MAX_STEPS = 8
internal const val PREVIEW_MIN_HOLD_MS = 90
internal const val PREVIEW_MAX_HOLD_MS = 1_500
internal const val PREVIEW_MAX_SAMPLED_HOLD_MS = 600

/** A design longer than [maxSteps] frames is sampled into that many even spans. */
internal fun previewSteps(
    durationsMs: List<Int>,
    dynamic: Boolean,
    maxSteps: Int = PREVIEW_MAX_STEPS,
): List<PreviewStep> {
    if (durationsMs.isEmpty()) return emptyList()
    if (!dynamic || durationsMs.size == 1 || maxSteps <= 1) {
        return listOf(PreviewStep(0, previewHold(durationsMs[0].toLong(), spanned = 1)))
    }
    val frames = durationsMs.size
    val stepCount = min(frames, maxSteps)
    return List(stepCount) { i ->
        val spanStart = (i.toLong() * frames / stepCount).toInt().coerceIn(0, frames - 1)
        val spanEnd = ((i + 1).toLong() * frames / stepCount)
            .toInt().coerceIn(spanStart + 1, frames)
        var spanTotalMs = 0L
        for (f in spanStart until spanEnd) spanTotalMs += durationsMs[f]
        PreviewStep(spanStart, previewHold(spanTotalMs, spanned = spanEnd - spanStart))
    }
}

private fun previewHold(totalMs: Long, spanned: Int): Int {
    val ceiling = if (spanned <= 1) PREVIEW_MAX_HOLD_MS else PREVIEW_MAX_SAMPLED_HOLD_MS
    return totalMs.coerceIn(PREVIEW_MIN_HOLD_MS.toLong(), ceiling.toLong()).toInt()
}

// Indexed by step, never by the design's own frame number. `decoded` is written only
// from the draw lambda, on the UI thread.
@Immutable
internal class DesignPreviewArt(
    val size: Int,
    val steps: List<PreviewStep>,
    private val sources: List<String>,
    private val levels: List<Int>,
    private val cellCount: Int,
) {
    private val decoded = arrayOfNulls<IntArray>(sources.size)

    val frameCount: Int get() = sources.size

    fun frame(index: Int): IntArray? {
        if (index < 0 || index >= sources.size) return null
        decoded[index]?.let { return it }
        val cells = DesignFrames.decode(sources[index], levels, size) ?: IntArray(cellCount)
        decoded[index] = cells
        return cells
    }

    val decodedCount: Int get() = decoded.count { it != null }

    companion object {
        val Empty = DesignPreviewArt(0, emptyList(), emptyList(), emptyList(), 0)
    }
}

/** This phone's artwork if the design has any, else whichever panel it does have. */
internal fun previewCodename(design: Design, home: PokemonCodename?): PokemonCodename? {
    if (home != null && design.variantFor(home)?.frames?.isNotEmpty() == true) return home
    return PokemonCodename.entries.firstOrNull { design.variantFor(it)?.frames?.isNotEmpty() == true }
}

// Runs inside the card's `remember`, so it decodes nothing and copies nothing.
internal fun designPreviewArt(design: Design, home: PokemonCodename?): DesignPreviewArt {
    val codename = previewCodename(design, home) ?: return DesignPreviewArt.Empty
    val frames = design.variantFor(codename)?.frames.orEmpty()
    if (frames.isEmpty()) return DesignPreviewArt.Empty
    val steps = previewSteps(frames.map { it.durationMs }, design.kind == DesignKind.DYNAMIC)
    return DesignPreviewArt(
        size = codename.size,
        steps = steps,
        sources = steps.map { frames[it.frameIndex].cells },
        levels = design.levels,
        cellCount = codename.cellCount,
    )
}

// One step per tick, never catching up: the clock stops while the app is paused, and
// replaying every missed hold would fast-forward the animation on the way back.
@Stable
internal class PreviewPlayer(private val steps: List<PreviewStep>) {

    var step by mutableIntStateOf(0)
        private set

    private var nextAt = Long.MIN_VALUE

    val animated: Boolean get() = steps.size > 1

    fun advance(nowMs: Long) {
        if (!animated) return
        if (nextAt == Long.MIN_VALUE) {
            nextAt = nowMs + steps[step].holdMs
            return
        }
        if (nowMs < nextAt) return
        val next = (step + 1) % steps.size
        step = next
        nextAt = nowMs + steps[next].holdMs
    }
}

// `players` is a plain list, not snapshot state: `advance` runs from a frame callback,
// and a snapshot list would invalidate readers as cells scroll into view.
@Stable
internal class PreviewClock {

    private val players = mutableListOf<PreviewPlayer>()

    var animating by mutableStateOf(false)
        private set

    fun register(player: PreviewPlayer) {
        players += player
        if (player.animated) animating = true
    }

    fun unregister(player: PreviewPlayer) {
        players -= player
        if (player.animated) animating = players.any { it.animated }
    }

    fun advance(nowMs: Long) {
        for (i in players.indices) players[i].advance(nowMs)
    }
}

/** Unregisters on disposal, so only the cards `LazyVerticalGrid` keeps will animate. */
@Composable
internal fun rememberPreviewPlayer(art: DesignPreviewArt, clock: PreviewClock): PreviewPlayer {
    val player = remember(art) { PreviewPlayer(art.steps) }
    DisposableEffect(player, clock) {
        clock.register(player)
        onDispose { clock.unregister(player) }
    }
    return player
}

// A screenful of dynamic cards at a 3x density 264 px disc holds about 33 MB of
// bitmaps. 160 px brings that to about 12 MB, and the upscale does not show.
private const val PREVIEW_RASTER_MAX_PX = 160

// A cache holds one frame of one design, and the art is replaced whole when it changes.
private const val SINGLE_FRAME_REVISION = 0

@Composable
internal fun DesignPreviewDisc(
    art: DesignPreviewArt,
    player: PreviewPlayer,
    modifier: Modifier = Modifier,
) {
    val caches = remember(art) { List(art.frameCount.coerceAtLeast(1)) { ThumbnailCache() } }
    DisposableEffect(caches) {
        onDispose { caches.forEach { it.release() } }
    }
    Canvas(modifier) {
        val side = min(size.width, size.height).roundToInt()
        if (side <= 0) return@Canvas
        val rasterPx = min(side, PREVIEW_RASTER_MAX_PX)
        val index = player.step.coerceIn(0, caches.lastIndex)
        val image = caches[index].get(
            revision = SINGLE_FRAME_REVISION,
            width = rasterPx,
            height = rasterPx,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(this.size.width, this.size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            val cells = art.frame(index)
            if (cells != null) drawMatrix(center, radius, art.size, cells)
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
