package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/** Guided breathing. Glyph Touch toggles it. */
class BreathingScreen : GlyphScreen {
    override val id = "breathing"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var running = false
    private var step = 0

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        running = false
        step = 0
        pushIdle()
    }

    override fun onDeactivate() {
        running = false
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        running = !running
        if (running) {
            step = 0
            val pace = c.prefs.getString(PrefKeys.BREATHING_PACE, PrefKeys.BREATHING_PACE_DEF)
                .toIntOrNull()?.coerceIn(MIN_PACE, MAX_PACE) ?: DEFAULT_PACE
            c.scheduler.setTicker(pace * MS_PER_PACE_UNIT) { tick() }
        } else {
            c.scheduler.clearTicker()
            pushIdle()
        }
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderStep(c.size, step))
        step++
    }

    private fun pushIdle() {
        val c = ctx ?: return
        val canvas = MatrixCanvas(c.size)
        val center = (c.size - 1) / 2f
        canvas.discSoft(center, center, minRadius(c.size), IDLE_DISC)
        c.pushFrame(canvas.copyOut())
    }

    companion object {
        const val STEPS = 12
        private const val HOLD = 2

        private const val MIN_PACE = 1
        private const val MAX_PACE = 20
        private const val DEFAULT_PACE = 4
        private const val MS_PER_PACE_UNIT = 125L

        private const val IDLE_DISC = 1500

        private fun minRadius(size: Int) = if (size >= 25) 2.5f else 1.5f
        private fun maxRadius(size: Int) = if (size >= 25) 11.5f else 5.8f

        fun radiusIndexFor(step: Int): Int {
            val period = 2 * STEPS + 2 * HOLD
            val m = ((step % period) + period) % period
            return when {
                m < STEPS -> m
                m < STEPS + HOLD -> STEPS - 1
                m < 2 * STEPS + HOLD -> 2 * STEPS + HOLD - 1 - m
                else -> 0
            }
        }

        fun renderStep(size: Int, step: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            val idx = radiusIndexFor(step)
            val r = minRadius(size) +
                (maxRadius(size) - minRadius(size)) * idx / (STEPS - 1)
            canvas.discSoft(center, center, r, MAX_BRIGHTNESS)
            return canvas.copyOut()
        }
    }
}
