package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/** Dice. A D6 shows pips; every other die shows the number in a border. */
class DiceScreen : GlyphScreen {
    override val id = "dice"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var face = D6_SIDES
    private var rollStartedAt = 0L

    private fun sides(c: ScreenContext): Int =
        c.prefs.getString(PrefKeys.SELECTED_DICE, PrefKeys.SELECTED_DICE_DEF)
            .removePrefix("D").toIntOrNull()?.coerceIn(MIN_SIDES, MAX_SIDES) ?: D6_SIDES

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        rollStartedAt = 0L
        face = face.coerceAtMost(sides(ctx))
        pushFace()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.CHANGE || event == Events.SHAKE) startRoll()
    }

    private fun startRoll() {
        val c = ctx ?: return
        rollStartedAt = c.ports.clock.nowMillis()
        c.scheduler.setTicker(TICK_MS) { tickRoll() }
    }

    private fun tickRoll() {
        val c = ctx ?: return
        val elapsed = c.ports.clock.nowMillis() - rollStartedAt
        if (elapsed >= ROLL_MS) {
            face = c.ports.random.nextInt(sides(c)) + 1
            rollStartedAt = 0L
            c.scheduler.clearTicker()
            pushFace()
            return
        }
        val canvas = MatrixCanvas(c.size)
        val cells = pipCenters(c.size)
        val count = TUMBLE_MIN_PIPS + c.ports.random.nextInt(TUMBLE_PIP_SPREAD)
        repeat(count) { i ->
            val p = cells[c.ports.random.nextInt(cells.size)]
            val v = TUMBLE_MIN + c.ports.random.nextInt(MAX_BRIGHTNESS - TUMBLE_MIN + 1)
            // Panel brightness scales the whole frame, so without one pip pinned at the
            // peak the tumble flickers frame to frame.
            drawPip(canvas, c.size, p, if (i == 0) MAX_BRIGHTNESS else v)
        }
        c.pushFrame(canvas.copyOut())
    }

    private fun pushFace() {
        val c = ctx ?: return
        c.pushFrame(renderFace(c.size, face, sides(c)))
    }

    companion object {
        const val TICK_MS = 33L
        const val ROLL_MS = 800L

        private const val D6_SIDES = 6
        private const val MIN_SIDES = 2
        private const val MAX_SIDES = 99

        private const val TUMBLE_MIN = 1500
        private const val TUMBLE_MIN_PIPS = 3
        private const val TUMBLE_PIP_SPREAD = 4

        private const val PIP_GRID = 3
        private const val BORDER = 700

        /** The 3x3 grid of pip centres: quarter, half and three-quarters of the panel. */
        private fun pipCenters(size: Int): List<Pair<Int, Int>> {
            val spacing = size / (PIP_GRID + 1)
            val positions = (1..PIP_GRID).map { it * spacing }
            return positions.flatMap { y -> positions.map { x -> x to y } }
        }

        private fun drawPip(canvas: MatrixCanvas, size: Int, center: Pair<Int, Int>, v: Int) {
            val (cx, cy) = center
            if (size >= 25) {
                canvas.fillRect(cx - 1, cy - 1, 3, 3, v)
            } else {
                canvas.fillRect(cx - 1, cy - 1, 2, 2, v)
            }
        }

        fun renderFace(size: Int, face: Int, sides: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (sides == D6_SIDES) {
                val spacing = size / (PIP_GRID + 1)
                val l = spacing
                val m = 2 * spacing
                val r = 3 * spacing
                val pips: List<Pair<Int, Int>> = when (face) {
                    1 -> listOf(m to m)
                    2 -> listOf(l to l, r to r)
                    3 -> listOf(l to l, m to m, r to r)
                    4 -> listOf(l to l, r to l, l to r, r to r)
                    5 -> listOf(l to l, r to l, m to m, l to r, r to r)
                    else -> listOf(l to l, r to l, l to m, r to m, l to r, r to r)
                }
                pips.forEach { drawPip(canvas, size, it, MAX_BRIGHTNESS) }
            } else {
                canvas.rect(0, 0, size, size, BORDER)
                val textTop = size / 2 - Font3x5.HEIGHT / 2
                Font3x5.drawStringCentered(canvas, face.toString(), textTop, MAX_BRIGHTNESS)
            }
            return canvas.copyOut()
        }
    }
}
