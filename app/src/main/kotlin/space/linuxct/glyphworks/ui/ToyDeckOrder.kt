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
 * Selection follows a toy ID while its position changes. Reordering is a transaction:
 * [order] exposes temporary moves, and [finishReorder] either keeps them or restores the
 * original order. The caller decides when to persist a completed change.
 */
internal class ToyDeckOrder(initial: List<String>, selectedId: String) {
    private val items = initial.distinct().toMutableList()
    private var beforeReorder: List<String>? = null

    /** Detached snapshot: retaining or mutating a returned list cannot change this model. */
    val order: List<String> get() = items.toList()

    var selectedId: String = selectedId.takeIf { it in items } ?: items.firstOrNull().orEmpty()
        private set

    /** An empty deck has no selected index. */
    val selectedIndex: Int get() = items.indexOf(selectedId)

    val reordering: Boolean get() = beforeReorder != null

    /** Ignore selection changes while holding a card so it remains the same toy on drop. */
    fun select(index: Int) {
        if (reordering || items.isEmpty()) return
        selectedId = items[index.coerceIn(0, items.lastIndex)]
    }

    fun beginReorder(id: String): Boolean {
        if (reordering || id != selectedId || selectedIndex < 0) return false
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
        val from = selectedIndex
        if (from < 0) return false
        val to = from + step
        if (to !in items.indices) return false
        items.add(to, items.removeAt(from))
        return true
    }
}

/**
 * Slow drags settle on the nearest page. A fling advances one page from that nearest
 * anchor, or two at 4 pages/second; stronger velocities cannot skip further. Positive
 * velocity moves toward increasing indices. An empty deck returns the neutral target 0.
 */
internal fun toyDeckSnapTarget(position: Float, velocityPagesPerSecond: Float, count: Int): Int {
    if (count <= 1) return 0
    val last = count - 1
    val boundedPosition = if (position.isNaN()) 0f else position.coerceIn(0f, last.toFloat())
    val nearest = boundedPosition.roundToInt().coerceIn(0, last)
    val velocity = velocityPagesPerSecond.takeIf { it.isFinite() } ?: 0f
    val speed = abs(velocity)
    if (speed < 1.2f) return nearest
    val pages = if (speed >= 4f) 2 else 1
    val direction = if (velocity > 0f) 1 else -1
    return (nearest.toLong() + direction * pages).coerceIn(0L, last.toLong()).toInt()
}
