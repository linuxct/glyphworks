package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spin the Bottle. The bottle is a sprite drawn only upright, since a 1-cell outline
 * cannot rasterise legibly at an odd angle on 13x13; anything angled is the
 * procedural arrow of [drawPointer] instead.
 */
class BottleScreen : GlyphScreen {
    override val id = "bottle"
    override val interactive = true

    private enum class Phase { REST, SPINNING, BURST }

    private var ctx: ScreenContext? = null
    private var phase = Phase.REST
    private var restAngle = 0f
    private var spinStartAngle = 0f
    private var spinDelta = 0
    private var spinStartedAt = 0L
    private var burstFrames = 0

    private var showingSprite = true
    private var ghostFrames = 0

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        phase = Phase.REST
        restAngle = 0f
        showingSprite = true
        ghostFrames = 0
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE && event != Events.SHAKE) return
        val c = ctx ?: return
        spinStartAngle = restAngle
        spinDelta = c.ports.random.nextInt(DEGREES_PER_TURN)
        spinStartedAt = c.ports.clock.nowMillis()
        ghostFrames = if (showingSprite) GHOST_V.size else 0
        showingSprite = false
        phase = Phase.SPINNING
        c.scheduler.setTicker(SPIN_TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        when (phase) {
            Phase.SPINNING -> {
                val elapsed = c.ports.clock.nowMillis() - spinStartedAt
                val angle = spinStartAngle + spinAngleAt(elapsed, spinDelta)
                if (elapsed >= SPIN_MS) {
                    restAngle = norm(angle)
                    phase = Phase.BURST
                    burstFrames = 0
                    c.scheduler.setTicker(BURST_MS) { tick() }
                    return
                }
                val ghost = if (ghostFrames > 0) GHOST_V[GHOST_V.size - ghostFrames--] else GHOST_GONE
                c.pushFrame(renderSpin(c.size, angle, ghost))
            }
            Phase.BURST -> {
                if (burstFrames >= BURST_FRAMES) {
                    phase = Phase.REST
                    c.scheduler.clearTicker()
                    c.pushFrame(renderPointer(c.size, restAngle))
                    return
                }
                c.pushFrame(renderResult(c.size, restAngle, burstFrames % BURST_PHASES == 0))
                burstFrames++
            }
            Phase.REST -> c.scheduler.clearTicker()
        }
    }

    companion object {
        const val SPIN_TICK_MS = 40L
        const val BURST_MS = 230L
        const val BURST_FRAMES = 10
        private const val BURST_PHASES = 2

        const val EASE_MS = 300L
        const val FAST_MS = 1300L

        val RATCHET_DEG = intArrayOf(23, 21, 19, 17)
        val RATCHET_DWELL_MS = longArrayOf(250, 300, 350, 400)

        const val REVS = 4

        val SPIN_MS: Long = EASE_MS + FAST_MS + RATCHET_DWELL_MS.sum()

        private val RATCHET_TOTAL_DEG = RATCHET_DEG.sum()

        private const val DEGREES_PER_TURN = 360
        private const val HALF_TURN_DEG = 180f
        private const val MILLIS_PER_SECOND = 1000f

        fun spinTotalDeg(restDelta: Int): Float = DEGREES_PER_TURN.toFloat() * REVS + restDelta

        fun spinAngleAt(elapsedMs: Long, restDelta: Int): Float {
            val total = spinTotalDeg(restDelta)
            if (elapsedMs >= SPIN_MS) return total
            val t = elapsedMs.coerceAtLeast(0L)
            val easeS = EASE_MS / MILLIS_PER_SECOND
            val fastS = FAST_MS / MILLIS_PER_SECOND
            // The fast rate absorbs the remainder: the ease-in covers rate * easeS / 2
            // and the fast phase covers rate * fastS.
            val rate = (total - RATCHET_TOTAL_DEG) / (easeS / 2f + fastS)
            val easeDeg = rate * easeS / 2f
            if (t < EASE_MS) {
                val progress = t / EASE_MS.toFloat()
                return easeDeg * progress * progress
            }
            if (t < EASE_MS + FAST_MS) {
                return easeDeg + rate * ((t - EASE_MS) / MILLIS_PER_SECOND)
            }
            var cursor = EASE_MS + FAST_MS
            var deg = easeDeg + rate * fastS
            for (i in RATCHET_DEG.indices) {
                deg += RATCHET_DEG[i]
                cursor += RATCHET_DWELL_MS[i]
                if (t < cursor) return deg
            }
            return total
        }

        private fun norm(deg: Float): Float {
            val m = deg % DEGREES_PER_TURN
            return if (m < 0f) m + DEGREES_PER_TURN else m
        }

        private val BOTTLE_25 = listOf(
            ".....###.....",
            ".....###.....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "...#.....#...",
            "..#.......#..",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            "..#.......#..",
            "...#######...",
        )

        private val BOTTLE_13 = listOf(
            "..###..",
            "..###..",
            "..#.#..",
            "..#.#..",
            "..#.#..",
            ".#...#.",
            "#.....#",
            "#.....#",
            "#.....#",
            "#.....#",
            "#.....#",
            ".#...#.",
            "..###..",
        )

        private const val LIQUID_ROW_25 = 16
        private const val LIQUID_ROW_13 = 9

        private const val MIN_LIQUID_SPAN = 2
        private const val CHECKER_PERIOD = 2

        private fun art(size: Int) = if (size >= 25) BOTTLE_25 else BOTTLE_13

        private fun liquidRow(size: Int) = if (size >= 25) LIQUID_ROW_25 else LIQUID_ROW_13

        private fun isCheckerCell(x: Int, y: Int) = (x + y) % CHECKER_PERIOD == 0

        private val cellCache = HashMap<Int, List<Pair<Int, Int>>>()

        fun bottleCells(size: Int): List<Pair<Int, Int>> = cellCache.getOrPut(size) {
            val rows = art(size)
            val originX = (size - rows[0].length) / 2
            val originY = (size - rows.size) / 2
            val cells = LinkedHashSet<Pair<Int, Int>>()
            rows.forEachIndexed { ry, row ->
                row.forEachIndexed { rx, ch ->
                    if (ch == '#') cells += (originX + rx) to (originY + ry)
                }
            }
            for (ry in liquidRow(size) until rows.size) {
                val row = rows[ry]
                val left = row.indexOf('#')
                val right = row.lastIndexOf('#')
                if (left < 0 || right - left < MIN_LIQUID_SPAN) continue
                val y = originY + ry
                for (rx in left + 1 until right) {
                    val x = originX + rx
                    if (isCheckerCell(x, y)) cells += x to y
                }
            }
            cells.toList()
        }

        fun renderIdle(size: Int): IntArray =
            drawBottle(MatrixCanvas(size), BOTTLE).copyOut()

        fun renderPointer(size: Int, angleDeg: Float): IntArray =
            drawPointer(MatrixCanvas(size), angleDeg).copyOut()

        fun renderSpin(size: Int, angleDeg: Float, ghost: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (ghost > GHOST_GONE) drawBottle(canvas, ghost)
            return drawPointer(canvas, angleDeg).copyOut()
        }

        fun renderResult(size: Int, angleDeg: Float, burstOn: Boolean): IntArray {
            val canvas = MatrixCanvas(size)
            if (burstOn) {
                val centre = (size - 1) / 2
                val radius = size * BURST_RADIUS_NUM / BURST_RADIUS_DEN
                for (y in 0 until size) for (x in 0 until size) {
                    val insideDiamond = abs(x - centre) + abs(y - centre) <= radius
                    if (insideDiamond && isCheckerCell(x, y)) canvas.light(x, y, BURST)
                }
            }
            return drawPointer(canvas, angleDeg).copyOut()
        }

        private fun drawBottle(canvas: MatrixCanvas, v: Int): MatrixCanvas {
            bottleCells(canvas.size).forEach { (x, y) -> canvas.light(x, y, v) }
            return canvas
        }

        // The head is filled by testing every cell against the triangle in the rotated
        // frame, where `along` and `lateral` run along and across the direction. That
        // keeps it solid and symmetric at odd angles, with no rounding holes.
        private fun drawPointer(canvas: MatrixCanvas, angleDeg: Float): MatrixCanvas {
            val size = canvas.size
            val centre = size / 2
            val big = size >= 25
            val tip = if (big) TIP_25 else TIP_13
            val depth = if (big) HEAD_DEPTH_25 else HEAD_DEPTH_13
            val tail = if (big) TAIL_LEN_25 else TAIL_LEN_13

            canvas.ray(centre, centre, angleDeg + HALF_TURN_DEG, tail, TAIL)
            canvas.ray(centre, centre, angleDeg, tip, POINTER)

            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            for (y in 0 until size) for (x in 0 until size) {
                val ox = (x - centre).toFloat()
                val oy = (y - centre).toFloat()
                val along = ox * dx + oy * dy
                if (along < tip - depth || along > tip + CELL_HALF_WIDTH) continue
                val lateral = abs(-ox * dy + oy * dx)
                if (lateral <= (tip - along) * HEAD_HALF_WIDTH_PER_CELL + CELL_HALF_WIDTH) {
                    canvas.light(x, y, POINTER)
                }
            }
            return canvas
        }

        // Pointer geometry, in cells from the matrix centre.
        private const val TIP_13 = 5.2f
        private const val HEAD_DEPTH_13 = 2.6f
        private const val TAIL_LEN_13 = 2.2f

        private const val TIP_25 = 10.4f
        private const val HEAD_DEPTH_25 = 3.4f
        private const val TAIL_LEN_25 = 4.4f

        private const val HEAD_HALF_WIDTH_PER_CELL = 0.8f

        private const val CELL_HALF_WIDTH = 0.5f

        private const val BURST_RADIUS_NUM = 3
        private const val BURST_RADIUS_DEN = 4

        // The ghost cuts out while still visible, which at SPIN_TICK_MS a frame reads
        // as a dissolve rather than a fade.
        val GHOST_V = intArrayOf(2400, 1500, 800)
        private const val GHOST_GONE = 0

        private const val BOTTLE = MAX_BRIGHTNESS
        private const val POINTER = MAX_BRIGHTNESS
        private const val TAIL = 1800
        private const val BURST = 1400
    }
}
