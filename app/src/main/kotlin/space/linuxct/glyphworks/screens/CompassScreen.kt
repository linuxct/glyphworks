package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt

/** Compass. The bright end of the needle points north, inside a ring of cardinal ticks. */
class CompassScreen : GlyphScreen {
    override val id = "compass"
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
        c.pushFrame(renderFrame(c.size, c.ports.azimuth.azimuthDegrees()))
    }

    companion object {
        const val TICK_MS = 33L

        private const val DEGREES_PER_TURN = 360
        private const val TICK_STEP_DEG = 45
        private const val HALF_TURN_DEG = 180f
        private const val AZIMUTH_QUANTUM_DEG = 5

        private const val NORTH_DEG = 0
        private const val EAST_DEG = 90
        private const val SOUTH_DEG = 180
        private const val WEST_DEG = 270

        private const val NORTH_TICK = MAX_BRIGHTNESS
        private const val CARDINAL_TICK = 1500
        private const val INTERCARDINAL_TICK = 500
        private const val NEEDLE_HEAD = MAX_BRIGHTNESS
        private const val NEEDLE_TAIL = 900
        private const val NEEDLE_HUB = 2200
        private const val NO_READING = 1500

        fun renderFrame(size: Int, azimuthDeg: Float?): IntArray {
            val canvas = MatrixCanvas(size)
            val center = size / 2
            val ringR = (size / 2).toFloat()

            for (deg in 0 until DEGREES_PER_TURN step TICK_STEP_DEG) {
                val v = when (deg) {
                    NORTH_DEG -> NORTH_TICK
                    EAST_DEG, SOUTH_DEG, WEST_DEG -> CARDINAL_TICK
                    else -> INTERCARDINAL_TICK
                }
                canvas.polar(center, center, deg.toFloat(), ringR, v)
            }

            if (azimuthDeg == null) {
                val textTop = size / 2 - Font3x5.HEIGHT / 2
                Font3x5.drawStringCentered(canvas, "?", textTop, NO_READING)
                return canvas.copyOut()
            }

            val quantum = AZIMUTH_QUANTUM_DEG
            val rounded = ((azimuthDeg / quantum).roundToInt() * quantum % DEGREES_PER_TURN)
            // Turning the device by the azimuth puts north at -azimuth on the display.
            val northAngle = (DEGREES_PER_TURN - rounded).toFloat() % DEGREES_PER_TURN
            val headLen = if (size >= 25) 9f else 4.6f
            val tailLen = if (size >= 25) 5f else 2.6f
            canvas.ray(center, center, northAngle, headLen, NEEDLE_HEAD)
            canvas.ray(center, center, northAngle + HALF_TURN_DEG, tailLen, NEEDLE_TAIL)
            canvas.set(center, center, NEEDLE_HUB)
            return canvas.copyOut()
        }
    }
}
