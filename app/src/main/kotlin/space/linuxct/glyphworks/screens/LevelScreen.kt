package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.InclinePort
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.hypot

/**
 * A bubble level. It ticks this fast because the inclination sensor unregisters itself
 * after about 5 s without a poll.
 */
class LevelScreen : GlyphScreen {
    override val id = "level"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, c.ports.incline.pitchDegrees(), c.ports.incline.rollDegrees()))
    }

    companion object {
        const val TICK_MS = 66L

        /** Inclination that pins the ball against the matrix edge. */
        const val MAX_TILT_DEG = 30f

        /** Combined pitch and roll magnitude that still counts as flat. */
        const val TOLERANCE_DEG = 4f

        private const val CELL_HALF_WIDTH = 0.5f

        /** Follows [InclinePort]: positive means that edge of the device is the low one. */
        fun renderFrame(size: Int, pitchDeg: Float?, rollDeg: Float?): IntArray {
            val canvas = MatrixCanvas(size)
            val centre = (size - 1) / 2f
            val centreCell = size / 2

            canvas.light(centreCell, 0, EDGE)
            canvas.light(centreCell, size - 1, EDGE)
            canvas.light(0, centreCell, EDGE)
            canvas.light(size - 1, centreCell, EDGE)

            if (pitchDeg == null || rollDeg == null) {
                canvas.ring(centre, centre, ringInner(size), ringOuter(size), TARGET_IDLE)
                // Brighter than the idle ring, or the two blur together at 13 columns.
                val textTop = size / 2 - Font3x5.HEIGHT / 2
                Font3x5.drawStringCentered(canvas, "?", textTop, NO_READING)
                return canvas.copyOut()
            }

            val target = if (isLevel(pitchDeg, rollDeg)) TARGET_LEVEL else TARGET_IDLE
            canvas.ring(centre, centre, ringInner(size), ringOuter(size), target)

            // Positive roll means the right edge is low, so the ball goes to +x. Positive
            // pitch means the top edge is low, and rows grow downward, so it goes to -y.
            //
            // The sensor never reads exactly 0, so inside the tolerance the ball pins to
            // the centre cell. Outside it, the tolerance comes off the magnitude and the
            // remaining travel spreads over TOLERANCE_DEG..MAX_TILT_DEG, so the ball
            // eases out yet still reaches the edge at MAX_TILT_DEG.
            val radius = ballRadius(size)
            val reach = centre - radius - CELL_HALF_WIDTH
            val tiltDeg = hypot(pitchDeg.toDouble(), rollDeg.toDouble()).toFloat()
            val cellsPerDegree = if (tiltDeg <= TOLERANCE_DEG) {
                0f
            } else {
                val travel = (tiltDeg - TOLERANCE_DEG) / (MAX_TILT_DEG - TOLERANCE_DEG)
                clampUnit(travel) * reach / tiltDeg
            }
            val dx = rollDeg * cellsPerDegree
            val dy = -pitchDeg * cellsPerDegree
            canvas.discSoft(centre + dx, centre + dy, radius, BALL)
            return canvas.copyOut()
        }

        private fun clampUnit(v: Float) = v.coerceIn(-1f, 1f)

        private fun ballRadius(size: Int) = if (size >= 25) 3.2f else 1.8f

        /** The target ring sits just outside the ball, so level reads as a snug fit. */
        private fun ringInner(size: Int) = if (size >= 25) 4.6f else 2.6f

        private fun ringOuter(size: Int) = if (size >= 25) 5.4f else 3.2f

        // Panel brightness multiplies the finished frame, so every state needs one
        // element at MAX_BRIGHTNESS or the whole state looks dim.
        private const val BALL = MAX_BRIGHTNESS
        private const val TARGET_LEVEL = MAX_BRIGHTNESS
        private const val NO_READING = MAX_BRIGHTNESS
        private const val TARGET_IDLE = 900
        private const val EDGE = 500

        fun isLevel(pitchDeg: Float, rollDeg: Float): Boolean =
            hypot(pitchDeg.toDouble(), rollDeg.toDouble()) <= TOLERANCE_DEG
    }
}
