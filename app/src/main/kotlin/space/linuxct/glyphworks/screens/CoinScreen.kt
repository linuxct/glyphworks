package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.cos

/**
 * Coin Flip. The coin is an ellipse whose height follows |cos| through [ROTATIONS]
 * turns, so it goes edge-on at each zero crossing.
 */
class CoinScreen : GlyphScreen {
    override val id = "coin"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var heads = true
    private var flipStartedAt = 0L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        flipStartedAt = 0L
        pushResult()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.CHANGE || event == Events.SHAKE) startFlip()
    }

    private fun startFlip() {
        val c = ctx ?: return
        flipStartedAt = c.ports.clock.nowMillis()
        c.scheduler.setTicker(TICK_MS) { tickFlip() }
    }

    private fun tickFlip() {
        val c = ctx ?: return
        val elapsed = c.ports.clock.nowMillis() - flipStartedAt
        if (elapsed >= FLIP_MS) {
            heads = c.ports.random.nextInt(COIN_FACES) == 0
            flipStartedAt = 0L
            c.scheduler.clearTicker()
            pushResult()
            return
        }
        val t = elapsed.toFloat() / FLIP_MS
        val squash = abs(cos(t * ROTATIONS * RADIANS_PER_TURN))
        val canvas = MatrixCanvas(c.size)
        val center = (c.size - 1) / 2f
        val radius = c.size / 2f - COIN_INSET
        var deg = 0
        while (deg < DEGREES_PER_TURN) {
            val rad = Math.toRadians(deg.toDouble())
            val x = center + radius * Math.sin(rad)
            val y = center - radius * squash * Math.cos(rad)
            canvas.light(Math.round(x).toInt(), Math.round(y).toInt(), MAX_BRIGHTNESS)
            deg += ELLIPSE_STEP_DEG
        }
        c.pushFrame(canvas.copyOut())
    }

    private fun pushResult() {
        val c = ctx ?: return
        c.pushFrame(renderResult(c.size, heads, c.prefs.getInt(PrefKeys.COIN_DESIGN, PrefKeys.COIN_DESIGN_DEF)))
    }

    companion object {
        const val TICK_MS = 33L
        const val FLIP_MS = 1000L
        const val ROTATIONS = 3

        private const val COIN_FACES = 2
        private const val DEGREES_PER_TURN = 360
        private val RADIANS_PER_TURN = 2f * Math.PI.toFloat()
        private const val ELLIPSE_STEP_DEG = 5

        private const val COIN_INSET = 0.8f
        private const val RING_INNER_INSET = 1.4f
        private const val RING_OUTER_INSET = 0.4f
        private const val RING_BRIGHT = 2200

        /** Result designs, matching the order of the settings dialog's choices. */
        const val DESIGN_LETTERS = 0
        const val DESIGN_ART = 1

        // The art is at most 7 rows and 7 columns: any bigger and it touches the ring,
        // whose inner radius is 5.1.
        private val HEADS_13 = listOf(
            ".....###.....", // crown
            "....#####....", // forehead
            "....#####....", // brow / eye level
            "....######...", // nose
            "....####.....", // mouth, recessed behind the nose
            "....#####....", // chin
            ".....###.....", // neck
        )

        private val TAILS_13 = listOf(
            "......##.....", // apex
            ".....###.....", // flag, angling down-left
            "....####.....",
            "......##.....", // stem
            "......##.....",
            "......##.....",
            ".....####....", // foot bar
        )

        private val HEADS_25 = listOf(
            "..........#####..........", // crown
            "........#########........",
            ".......###########.......",
            ".......###########.......", // forehead
            ".......#########.#.......", // brow, with the eye notch
            ".......##########........", // eye socket, recessed
            ".......############......", // nose
            ".......##########........", // under the nose
            ".......#########.........", // mouth, recessed
            ".......###########.......", // chin
            "........#########........",
            ".........#######.........", // jaw
            ".........#######.........", // neck
        )

        private val TAILS_25 = listOf(
            "...........###...........", // apex
            "..........####...........", // flag
            ".........#####...........",
            "........######...........",
            "...........###...........", // stem
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            ".........#######.........", // foot bar
            ".........#######.........",
        )

        fun renderResult(size: Int, heads: Boolean, design: Int = DESIGN_LETTERS): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(
                center, center,
                size / 2f - RING_INNER_INSET, size / 2f - RING_OUTER_INSET,
                RING_BRIGHT,
            )
            if (design == DESIGN_ART) {
                val big = size >= 25
                val art = when {
                    heads && big -> HEADS_25
                    heads -> HEADS_13
                    big -> TAILS_25
                    else -> TAILS_13
                }
                canvas.blit(art, 0, (size - art.size) / 2, MAX_BRIGHTNESS)
            } else {
                val letterY = size / 2 - Font3x5.HEIGHT / 2
                val letter = if (heads) "H" else "T"
                Font3x5.drawStringCentered(canvas, letter, letterY, MAX_BRIGHTNESS)
            }
            return canvas.copyOut()
        }
    }
}
