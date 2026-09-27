package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
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

        /** Compact decimal SI units: K = kB/s and M = MB/s. */
        fun formatSpeed(bytesPerSec: Long): String {
            val speed = bytesPerSec.coerceAtLeast(0)
            val kb = speed / BYTES_PER_KB
            return when {
                kb < MAX_KB -> "${kb}K"
                speed < MAX_DECIMAL_MB_BYTES -> {
                    val tenths = speed / BYTES_PER_TENTH_MB
                    "${tenths / TENTHS_PER_UNIT}.${tenths % TENTHS_PER_UNIT}M"
                }
                else -> "${(speed / BYTES_PER_MB).coerceAtMost(MAX_MB)}M"
            }
        }

        fun renderFrame(size: Int, bytesPerSec: Long): IntArray {
            val canvas = MatrixCanvas(size)
            val large = size >= 25
            val text = formatSpeed(bytesPerSec)
            // The full "2.3M" is 13 columns wide, but the circle narrows at the bottom
            // of the text. Separate the number and unit so every glyph stays complete.
            InformationDrawing.text(canvas, text.dropLast(1), if (large) 4 else 1, if (large) 2 else 1)

            val footerTop = if (large) 17 else 7
            val gap = if (large) 3 else 1
            val footerLeft = (size - (3 + gap + Font3x5.width(text.last()))) / 2
            // Two stem pixels above a filled, three-wide arrowhead and its tip.
            canvas.blit(listOf(".#.", ".#.", "###", ".#."), footerLeft, footerTop, ARROW)
            Font3x5.draw(canvas, text.last(), footerLeft + 3 + gap, footerTop, UNIT)
            return canvas.copyOut()
        }

        private const val ARROW = 2200
        private const val UNIT = 2600
    }
}
