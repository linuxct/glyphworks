package space.linuxct.glyphworks.ui.tutorial

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.delay

private data class TourTargetKey<T>(val target: T, val index: Int)

@Stable
internal class TourTargets<T> {

    private val bounds = mutableStateMapOf<TourTargetKey<T>, Rect>()

    fun report(target: T, index: Int, rect: Rect) {
        val key = TourTargetKey(target, index)
        if (bounds[key] != rect) bounds[key] = rect
    }

    fun forget(target: T, index: Int) {
        bounds.remove(TourTargetKey(target, index))
    }

    fun boundsOf(target: T, index: Int): Rect? = bounds[TourTargetKey(target, index)]

    fun centerOf(target: T, index: Int): Offset? = boundsOf(target, index)?.center

    fun unionOf(target: T): Rect? {
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

@Stable
internal class TourGhost {

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
internal class TourActor<T>(
    private val ghost: TourGhost,
    private val targets: TourTargets<T>,
    private val instant: Boolean,
) {

    fun centerOf(target: T, index: Int = 0): Offset? = targets.centerOf(target, index)

    fun boundsOf(target: T, index: Int = 0): Rect? = targets.boundsOf(target, index)

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

    suspend fun tap(target: T, index: Int = 0) {
        val point = centerOf(target, index) ?: return
        glideTo(point)
        if (instant) return
        animate(TAP_MS) { t -> ghost.pressTo(if (t < 0.5f) t * 2f else (1f - t) * 2f) }
        ghost.pressTo(0f)
        beat(TAP_SETTLE_MS)
    }

    suspend fun holdOn(target: T, index: Int = 0) {
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
        val scale = kotlin.coroutines.coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
        if (scale <= 0f) { block(1f); return }
        val span = (ms * scale).toLong().coerceAtLeast(1L)
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

