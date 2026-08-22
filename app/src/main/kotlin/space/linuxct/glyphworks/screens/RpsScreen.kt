package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Anim
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Rock Paper Scissors. The throws are abstract shapes, not hands: at 13x13 a fist and
 * an open hand are the same blob, so each shape differs on two axes at once, round
 * against straight and solid against hollow. The banner spells "SET" because [Font3x5]
 * has no R or Y.
 */
class RpsScreen : GlyphScreen {
    override val id = "rps"
    override val interactive = true

    private enum class Phase { IDLE, PLAYING, REVEAL }

    private var ctx: ScreenContext? = null
    private var phase = Phase.IDLE
    private var startedAt = 0L
    private var thrown = ROCK

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        phase = Phase.IDLE
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE && event != Events.SHAKE) return
        val c = ctx ?: return
        thrown = c.ports.random.nextInt(THROWS)
        startedAt = c.ports.clock.nowMillis()
        phase = Phase.PLAYING
        c.scheduler.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        if (phase != Phase.PLAYING) {
            c.scheduler.clearTicker()
            return
        }
        val elapsed = c.ports.clock.nowMillis() - startedAt
        if (elapsed < BANNER_MS) {
            c.pushFrame(renderBanner(c.size))
            return
        }
        val intoCount = elapsed - BANNER_MS
        val step = (intoCount / COUNT_MS).toInt()
        if (step < COUNT_STEPS) {
            val numeral = COUNT_STEPS - step
            c.pushFrame(renderCountdown(c.size, numeral, (intoCount / BOB_MS).toInt()))
            return
        }
        phase = Phase.REVEAL
        c.scheduler.clearTicker()
        c.pushFrame(renderThrow(c.size, thrown))
    }

    companion object {
        const val TICK_MS = 70L

        const val BANNER_MS = 700L
        const val COUNT_MS = 700L
        const val COUNT_STEPS = 3

        const val BOB_MS = 140L
        private const val BOB_STATES = 3
        private const val BOB_MID = 1

        const val ROCK = 0
        const val PAPER = 1
        const val SCISSORS = 2
        const val THROWS = 3

        val SEQUENCE_MS: Long = BANNER_MS + COUNT_STEPS * COUNT_MS

        private fun unit(size: Int) = if (size >= 25) 2 else 1

        private fun mid(size: Int) = (size - 1) / 2f

        /** Every symbol fits the same box: the matrix less a one-cell dark margin. */
        private const val INSET = 1

        private fun discRadius(size: Int) = size / 2f - INSET

        // discSoft, not the integer circle, which at these radii leaves a one-cell
        // spike at each compass point.
        private fun drawRock(canvas: MatrixCanvas, v: Int) {
            val c = mid(canvas.size)
            canvas.discSoft(c, c, discRadius(canvas.size), v)
        }

        private fun drawPaper(canvas: MatrixCanvas, v: Int) {
            val size = canvas.size
            for (d in 0 until unit(size)) {
                val a = INSET + d
                canvas.rect(a, a, size - 2 * a, size - 2 * a, v)
            }
        }

        private fun drawScissors(canvas: MatrixCanvas, v: Int) {
            val a = INSET
            val b = canvas.size - 1 - a
            for (d in 0 until unit(canvas.size)) {
                canvas.line(a + d, a, b, b - d, v) // "\" shifted right
                canvas.line(a, a + d, b - d, b, v) // "\" shifted down
                canvas.line(b - d, a, a, b - d, v) // "/" shifted left
                canvas.line(b, a + d, a + d, b, v) // "/" shifted down
            }
        }

        private fun tokenRadius(size: Int) = if (size >= 25) 6f else 3f

        private fun isCheckerCell(x: Int, y: Int) = (x + y) % CHECKER_PERIOD == 0

        fun renderThrow(size: Int, throwId: Int): IntArray {
            val canvas = MatrixCanvas(size)
            when (throwId) {
                PAPER -> drawPaper(canvas, SYMBOL)
                SCISSORS -> drawScissors(canvas, SYMBOL)
                else -> drawRock(canvas, SYMBOL)
            }
            return canvas.copyOut()
        }

        fun renderIdle(size: Int): IntArray = renderThrow(size, ROCK)

        fun renderBanner(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val big = size >= 25
            val bandTop = if (big) 12 else 3
            val bandHeight = if (big) 9 else 7
            val textY = if (big) 14 else 4
            val tokenCy = if (big) 12f else 3f
            val ditherTop = if (big) 22 else 11

            canvas.discSoft(mid(size), tokenCy, tokenRadius(size), SYMBOL)

            // The band overwrites what it covers, so set rather than light.
            for (y in bandTop until (bandTop + bandHeight).coerceAtMost(size)) {
                for (x in 0 until size) canvas.set(x, y, BAND)
            }
            // Font3x5 max-blends, so it can only add light. To knock the word out dark,
            // draw it on a scratch surface and punch the cells it lit back to black.
            val scratch = MatrixCanvas(size)
            Font3x5.drawStringCentered(scratch, BANNER_WORD, textY, MAX_BRIGHTNESS)
            for (y in 0 until size) for (x in 0 until size) {
                if (scratch.get(x, y) > 0) canvas.set(x, y, DARK)
            }
            for (y in ditherTop until (ditherTop + DITHER_BAR_ROWS).coerceAtMost(size)) {
                for (x in 0 until size) if (isCheckerCell(x, y)) canvas.set(x, y, DITHER)
            }
            return canvas.copyOut()
        }

        fun renderCountdown(size: Int, numeral: Int, bobStep: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val u = unit(size)
            val big = size >= 25
            val bob = (Anim.pingPong(bobStep, BOB_STATES) - BOB_MID) * u
            val tokenCx = if (big) 8f else 4f
            val tokenCy = (if (big) 10 else 5) + bob
            canvas.discSoft(tokenCx, tokenCy.toFloat(), tokenRadius(size), SYMBOL)

            // Flush right, the only way to keep a dark gutter at 13 columns.
            val digitX = size - Font3x5.width('0')
            Font3x5.draw(canvas, ('0' + numeral), digitX, if (big) 10 else 4, SYMBOL)

            val shadowTop = if (big) 19 else 10
            val lift = if (bob < 0) u else 0
            val shadowLeft = (if (big) 2 else 0) + lift
            val shadowRight = (if (big) 15 else 8) - lift
            val shadowRows = SHADOW_ROW_UNITS * u
            for (y in shadowTop until (shadowTop + shadowRows).coerceAtMost(size)) {
                for (x in shadowLeft..shadowRight) {
                    if (isCheckerCell(x, y)) canvas.light(x, y, DITHER)
                }
            }
            return canvas.copyOut()
        }

        private const val BANNER_WORD = "SET"

        private const val CHECKER_PERIOD = 2
        private const val DITHER_BAR_ROWS = 2
        private const val SHADOW_ROW_UNITS = 2

        private const val SYMBOL = MAX_BRIGHTNESS
        private const val BAND = MAX_BRIGHTNESS
        private const val DITHER = 1800
        private const val DARK = 0
    }
}
