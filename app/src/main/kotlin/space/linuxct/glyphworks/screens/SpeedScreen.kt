package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.SpeedPort
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/** Download speed, from the delta of the cumulative RX byte counter each second. */
class SpeedScreen : GlyphScreen {
    override val id = "speed"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private var lastTotal = -1L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        lastTotal = -1L
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val total = c.ports.speed.totalRxBytes()
        val delta = if (lastTotal < 0) 0L else (total - lastTotal).coerceAtLeast(0)
        lastTotal = total
        c.pushFrame(renderFrame(c.size, delta))
    }

    companion object {
        const val TICK_MS = 1000L

        private const val BYTES_PER_KB = 1_000L
        private const val BYTES_PER_MB = 1_000_000L
        private const val BYTES_PER_TENTH_MB = 100_000L
        private const val TENTHS_PER_UNIT = 10

        private const val MAX_KB = 100
        private const val MAX_DECIMAL_MB_BYTES = 10_000_000L
        private const val MAX_MB = 99L

        /** Kept to three or four glyphs, the most that fits 13 columns. */
        fun formatSpeed(bytesPerSec: Long): String {
            val kb = bytesPerSec / BYTES_PER_KB
            return when {
                kb < MAX_KB -> "${kb}K"
                bytesPerSec < MAX_DECIMAL_MB_BYTES -> {
                    val tenths = bytesPerSec / BYTES_PER_TENTH_MB
                    "${tenths / TENTHS_PER_UNIT}.${tenths % TENTHS_PER_UNIT}M"
                }
                else -> "${(bytesPerSec / BYTES_PER_MB).coerceAtMost(MAX_MB)}M"
            }
        }

        fun renderFrame(size: Int, bytesPerSec: Long): IntArray {
            val canvas = MatrixCanvas(size)
            val center = size / 2
            val arrowTop = if (size >= 25) 3 else 0
            val arrowLen = if (size >= 25) 5 else 3
            for (y in arrowTop until arrowTop + arrowLen - 1) canvas.light(center, y, ARROW)
            val tipY = arrowTop + arrowLen - 1
            canvas.light(center - 1, tipY - 1, ARROW)
            canvas.light(center + 1, tipY - 1, ARROW)
            canvas.light(center, tipY, ARROW)

            val textY = if (size >= 25) 12 else 6
            Font3x5.drawStringCentered(canvas, formatSpeed(bytesPerSec), textY, MAX_BRIGHTNESS)
            return canvas.copyOut()
        }

        private const val ARROW = 2200
    }
}
