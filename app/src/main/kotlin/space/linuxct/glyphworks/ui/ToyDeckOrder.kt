package space.linuxct.glyphworks.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/** Retain saved IDs in their chosen order, followed by newly available toys. */
internal fun normalizeToyOrder(stored: String, known: List<String>): List<String> {
    val available = known.filter { it.isNotBlank() }.distinct()
    val knownIds = available.toHashSet()
    val result = linkedSetOf<String>()
    stored.split(',').forEach { entry ->
        val id = entry.trim()
        if (id in knownIds) result += id
    }
    result += available
    return result.toList()
}

/**
 * Selection follows a card ID while its position changes. [cards] includes a fixed trailing
 * request card; [order] contains only real toys suitable for persistence. Reordering is a transaction:
 * [order] exposes temporary moves, and [finishReorder] either keeps them or restores the
 * original order. The caller decides when to persist a completed change.
 */
internal class ToyDeckOrder(initial: List<String>, selectedId: String) {
    private val items = initial.filter { it != ToyRequest.ID }.distinct().toMutableList()
    private var beforeReorder: List<String>? = null

    /** Detached snapshot: retaining or mutating a returned list cannot change this model. */
    val order: List<String> get() = items.toList()

    /** Browsable cards, with the request card pinned after every real toy. */
    val cards: List<String> get() = items + ToyRequest.ID

    var selectedId: String = selectedId.takeIf { it == ToyRequest.ID || it in items }
        ?: items.firstOrNull() ?: ToyRequest.ID
        private set

    /** Even an empty toy roster has the request card at index zero. */
    val selectedIndex: Int get() = if (selectedId == ToyRequest.ID) items.size else items.indexOf(selectedId)

    val reordering: Boolean get() = beforeReorder != null

    /** Ignore selection changes while holding a card so it remains the same toy on drop. */
    fun select(index: Int) {
        if (reordering) return
        val target = index.coerceIn(0, items.size)
        selectedId = if (target == items.size) ToyRequest.ID else items[target]
    }

    fun beginReorder(id: String): Boolean {
        if (reordering || id != selectedId || id !in items) return false
        beforeReorder = order
        return true
    }

    fun moveHeld(direction: Int): Boolean = reordering && moveAdjacent(direction)

    fun finishReorder(commit: Boolean): List<String> {
        val original = beforeReorder ?: return order
        if (!commit) {
            items.clear()
            items.addAll(original)
        }
        beforeReorder = null
        return order
    }

    /** Move one adjacent slot for an accessibility action, only outside a drag. */
    fun moveSelected(direction: Int): Boolean = !reordering && moveAdjacent(direction)

    private fun moveAdjacent(direction: Int): Boolean {
        val step = when {
            direction > 0 -> 1
            direction < 0 -> -1
            else -> return false
        }
        val from = items.indexOf(selectedId)
        if (from < 0) return false
        val to = from + step
        if (to !in items.indices) return false
        items.add(to, items.removeAt(from))
        return true
    }
}

/**
 * Slow drags settle on the nearest page. A fling adds one page, or two at 4 pages/second,
 * within three slots of the card visible at gesture start. A longer direct drag still
 * follows the finger but gets no extra travel beyond that limit. Positive velocity moves
 * toward increasing indices. An empty deck returns the neutral target 0.
 */
internal fun toyDeckSnapTarget(
    position: Float,
    velocityPagesPerSecond: Float,
    count: Int,
    swipeStartPosition: Float,
): Int {
    if (count <= 1) return 0
    val last = count - 1
    val boundedPosition = if (position.isNaN()) 0f else position.coerceIn(0f, last.toFloat())
    val nearest = boundedPosition.roundToInt().coerceIn(0, last)
    val velocity = velocityPagesPerSecond.takeIf { it.isFinite() } ?: 0f
    val speed = abs(velocity)
    if (speed < 1.2f) return nearest
    val pages = if (speed >= 4f) 2 else 1
    val direction = if (velocity > 0f) 1 else -1
    val start = if (swipeStartPosition.isNaN()) nearest else
        swipeStartPosition.coerceIn(0f, last.toFloat()).roundToInt().coerceIn(0, last)
    val proposed = nearest.toLong() + direction * pages
    val limited = if (direction > 0) {
        minOf(proposed, maxOf(nearest.toLong(), start.toLong() + 3))
    } else {
        maxOf(proposed, minOf(nearest.toLong(), start.toLong() - 3))
    }
    return limited.coerceIn(0L, last.toLong()).toInt()
}

/**
 * Release speed comes from recent drag motion, excluding the final 24 ms where losing
 * finger contact can produce a spurious jump. Averaging over 120 ms preserves deliberate
 * flicks without amplifying a single lift-off sample. A pause before release cancels momentum.
 */
internal class ToyDeckVelocity {
    private data class Sample(val time: Long, val x: Float)
    private val samples = ArrayDeque<Sample>()

    fun reset(time: Long, x: Float) {
        samples.clear()
        add(time, x)
    }

    fun add(time: Long, x: Float) {
        if (!x.isFinite() || samples.lastOrNull()?.let { time <= it.time } == true) return
        samples.addLast(Sample(time, x))
        while (samples.isNotEmpty() && samples.first().time < time - 200) samples.removeFirst()
    }

    fun atRelease(time: Long): Float {
        val end = time - 24
        val recent = samples.filter { it.time in (end - 120)..end }
        val first = recent.firstOrNull() ?: return 0f
        val last = recent.last()
        val duration = last.time - first.time
        if (duration < 16 || time - last.time > 80) return 0f
        return (last.x - first.x) * 1000f / duration
    }
}
