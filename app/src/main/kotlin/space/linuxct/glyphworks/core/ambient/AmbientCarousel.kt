package space.linuxct.glyphworks.core.ambient

/**
 * One in-process selection, driven solely by monotonic elapsed time. Hidden backgrounds
 * receive a fresh interval when shown, and a delayed tick advances at most once.
 */
class AmbientCarousel {
    var currentId: String? = null
        private set

    private var selection = emptyList<String>()
    private var automatic = false
    private var visible = false
    private var intervalStartedAt: Long? = null

    fun configure(enabled: List<String>, automatic: Boolean, nowMs: Long) {
        val unique = enabled.distinct()
        if (selection == unique && this.automatic == automatic) return
        selection = unique
        this.automatic = automatic
        if (currentId !in selection) currentId = selection.firstOrNull()
        restartInterval(nowMs)
    }

    /** Call before rendering, even when an overlay, gate, or empty selection hides it. */
    fun update(backgroundVisible: Boolean, nowMs: Long): String? {
        val show = backgroundVisible && currentId != null
        if (show != visible) {
            visible = show
            restartInterval(nowMs)
        }
        val startedAt = intervalStartedAt
        if (show && automatic && selection.size > 1 && startedAt != null) {
            when {
                nowMs < startedAt -> restartInterval(nowMs)
                nowMs - startedAt >= INTERVAL_MS -> advance(nowMs)
            }
        }
        return currentId
    }

    /** Manual presses also change the next background while music/charging covers it. */
    fun advance(nowMs: Long) {
        if (selection.isNotEmpty()) {
            currentId = selection[(selection.indexOf(currentId) + 1) % selection.size]
        }
        restartInterval(nowMs)
    }

    /** Pause without forgetting the selected ID when Ambient is deactivated. */
    fun pause() {
        visible = false
        intervalStartedAt = null
    }

    private fun restartInterval(nowMs: Long) {
        intervalStartedAt = if (visible) nowMs else null
    }

    companion object {
        const val INTERVAL_MS = 15_000L
    }
}
