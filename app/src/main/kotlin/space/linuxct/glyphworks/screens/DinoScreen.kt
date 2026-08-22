package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.RandomPort
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt

class DinoScreen : GlyphScreen {
    override val id = "dino"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var game: DinoGame? = null
    private var blinkOn = true

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        game = null
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
        game = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        val run = game
        when {
            run == null -> start(c)
            run.state == DinoGame.State.RUNNING -> run.jump()
            else -> start(c)
        }
    }

    private fun start(c: ScreenContext) {
        game = DinoGame(c.size, c.ports.random)
        blinkOn = true
        c.scheduler.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        val run = game ?: return
        if (run.state == DinoGame.State.RUNNING) {
            run.step()
            if (run.state == DinoGame.State.RUNNING) {
                c.pushFrame(
                    renderRun(c.size, run.jumpCells(), run.legPhase(), run.groundPhase(), run.obstacleCells()),
                )
            } else {
                // setTicker fires straight away, so the blink starts on the lit frame.
                blinkOn = false
                c.scheduler.setTicker(BLINK_MS) { tick() }
            }
            return
        }
        blinkOn = !blinkOn
        c.pushFrame(renderGameOver(c.size, run.score, blinkOn))
    }

    companion object {
        const val TICK_MS = 50L
        const val BLINK_MS = 300L

        data class Obst(val x: Int, val w: Int, val h: Int)

        fun unit(size: Int): Int = if (size >= 25) 2 else 1

        fun groundRow(size: Int): Int = size - 1

        fun standRow(size: Int): Int = size - 2

        fun charX(size: Int): Int = CHAR_X_UNITS * unit(size)

        fun charW(size: Int): Int = CHAR_W_UNITS * unit(size)

        fun charH(size: Int): Int = CHAR_H_UNITS * unit(size)

        private val CHAR_13_STAND = listOf(".##", "###", "#.#")
        private val CHAR_13_RUN_A = listOf(".##", "###", "##.")
        private val CHAR_13_RUN_B = listOf(".##", "###", ".##")
        private val CHAR_13_JUMP = listOf(".##", "###", ".#.")

        private val CHAR_25_STAND = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            ".#..#.",
        )
        private val CHAR_25_RUN_A = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            "#...##",
        )
        private val CHAR_25_RUN_B = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            ".##..#",
        )
        private val CHAR_25_JUMP = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            "..##..",
        )

        const val LEG_PHASE_AIRBORNE = -1
        const val LEG_PHASE_STRIDE_A = 0
        const val LEG_PHASE_STRIDE_B = 1
        const val LEG_PHASE_STANDING = 2

        private fun charArt(size: Int, legPhase: Int): List<String> = if (size >= 25) {
            when (legPhase) {
                LEG_PHASE_AIRBORNE -> CHAR_25_JUMP
                LEG_PHASE_STRIDE_A -> CHAR_25_RUN_A
                LEG_PHASE_STRIDE_B -> CHAR_25_RUN_B
                else -> CHAR_25_STAND
            }
        } else {
            when (legPhase) {
                LEG_PHASE_AIRBORNE -> CHAR_13_JUMP
                LEG_PHASE_STRIDE_A -> CHAR_13_RUN_A
                LEG_PHASE_STRIDE_B -> CHAR_13_RUN_B
                else -> CHAR_13_STAND
            }
        }

        fun renderIdle(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val ground = groundRow(size)
            for (x in 0 until size) canvas.light(x, ground, GROUND_IDLE)
            val u = unit(size)
            val trackRow = ground - TRACK_ROWS_ABOVE_GROUND
            var x = charX(size) + charW(size) + u
            while (x < size) {
                canvas.light(x, trackRow, TRACK)
                x += TRACK_DOT_SPACING_UNITS * u
            }
            canvas.blit(
                charArt(size, LEG_PHASE_STANDING),
                charX(size),
                standRow(size) - charH(size) + 1,
                CHAR,
            )
            return canvas.copyOut()
        }

        fun renderRun(
            size: Int,
            jumpCells: Int,
            legPhase: Int,
            groundPhase: Int,
            obstacles: List<Obst>,
        ): IntArray {
            val canvas = MatrixCanvas(size)
            val ground = groundRow(size)
            val u = unit(size)
            val period = GROUND_PERIOD_UNITS * u
            val dashLength = GROUND_DASH_UNITS * u
            for (x in 0 until size) {
                val phase = ((x + groundPhase) % period + period) % period
                canvas.light(x, ground, if (phase < dashLength) GROUND_DASH else GROUND_GAP)
            }
            obstacles.forEach { o ->
                canvas.fillRect(o.x, standRow(size) - o.h + 1, o.w, o.h, OBSTACLE)
            }
            canvas.blit(
                charArt(size, legPhase),
                charX(size),
                standRow(size) - jumpCells - charH(size) + 1,
                CHAR,
            )
            return canvas.copyOut()
        }

        fun renderGameOver(size: Int, score: Int, on: Boolean): IntArray {
            val canvas = MatrixCanvas(size)
            if (on) {
                val text = score.coerceIn(0, DinoGame.MAX_SCORE).toString()
                Font3x5.drawStringCentered(canvas, text, size / 2 - Font3x5.HEIGHT / 2, CHAR)
            }
            return canvas.copyOut()
        }

        private const val CHAR_X_UNITS = 2
        private const val CHAR_W_UNITS = 3
        private const val CHAR_H_UNITS = 3

        private const val GROUND_PERIOD_UNITS = 3
        private const val GROUND_DASH_UNITS = 2
        private const val TRACK_ROWS_ABOVE_GROUND = 1
        private const val TRACK_DOT_SPACING_UNITS = 2

        private const val CHAR = MAX_BRIGHTNESS
        private const val OBSTACLE = MAX_BRIGHTNESS
        private const val GROUND_DASH = 1800
        private const val GROUND_GAP = 500
        private const val GROUND_IDLE = 900
        private const val TRACK = 500
    }
}

/**
 * Positions and heights are in cells, time is in ticks of [DinoScreen.TICK_MS], and
 * the tuning constants are written in units: 1 unit is 1 cell at 13x13 and 2 at 25x25.
 */
class DinoGame(val size: Int, private val random: RandomPort) {

    enum class State { RUNNING, OVER }

    var state = State.RUNNING
        private set

    var score = 0
        private set

    private var ticks = 0

    private var heightCells = 0f
    private var verticalSpeed = 0f

    var isAirborne = false
        private set

    private var scrolledCells = 0f
    private val obstacles = ArrayList<Obstacle>()

    // A distance rather than a deadline, so the spacing is measured spawn to spawn and
    // an obstacle leaving the matrix cannot let the next one in early.
    private var cellsUntilSpawn = 0f

    private class Obstacle(var x: Float, val w: Int, val h: Int)

    init {
        val lead = INITIAL_LEAD_UNITS * u
        obstacles += newObstacle(lead)
        // The countdown measures from the right edge, so the `lead - size` term makes
        // the second cactus wait out the first one's head start and stay behind it.
        cellsUntilSpawn = (lead - size) + spawnGap()
    }

    fun jump() {
        if (state != State.RUNNING || isAirborne) return
        verticalSpeed = JUMP_V0 * u
        isAirborne = true
    }

    fun step() {
        if (state != State.RUNNING) return
        ticks++

        if (isAirborne) {
            heightCells += verticalSpeed
            verticalSpeed -= GRAVITY * u
            if (heightCells <= 0f) {
                heightCells = 0f
                verticalSpeed = 0f
                isAirborne = false
            }
        }

        val cellsPerTick = speed()
        scrolledCells += cellsPerTick
        obstacles.forEach { it.x -= cellsPerTick }
        val scoredCount = obstacles.count { it.x + it.w - 1 < OFF_LEFT_EDGE_X }
        if (scoredCount > 0) {
            obstacles.subList(0, scoredCount).clear()
            score = (score + scoredCount).coerceAtMost(MAX_SCORE)
        }
        cellsUntilSpawn -= cellsPerTick
        if (cellsUntilSpawn <= 0f) {
            obstacles += newObstacle(size.toFloat())
            // Accumulate rather than reset, so the average spacing stays exact.
            cellsUntilSpawn += spawnGap()
        }

        if (collides()) state = State.OVER
    }

    fun speed(): Float =
        ((START_SPEED + score * SPEED_RAMP).coerceAtMost(MAX_SPEED)) * u

    fun jumpCells(): Int = heightCells.roundToInt()

    fun legPhase(): Int =
        if (isAirborne) {
            DinoScreen.LEG_PHASE_AIRBORNE
        } else {
            (ticks / STRIDE_TICKS) % STRIDE_POSES
        }

    fun groundPhase(): Int = -scrolledCells.toInt()

    fun obstacleCells(): List<DinoScreen.Companion.Obst> =
        obstacles.map { DinoScreen.Companion.Obst(it.x.roundToInt(), it.w, it.h) }

    fun collides(): Boolean {
        val charLeft = DinoScreen.charX(size)
        val charRight = charLeft + DinoScreen.charW(size) - 1
        val charBottomRow = DinoScreen.standRow(size) - jumpCells()
        return obstacles.any { o ->
            val left = o.x.roundToInt()
            val right = left + o.w - 1
            if (right < charLeft || left > charRight) return@any false
            charBottomRow >= DinoScreen.standRow(size) - o.h + 1
        }
    }

    private fun newObstacle(x: Float): Obstacle {
        val (widthUnits, heightUnits) = VARIANTS[random.nextInt(VARIANTS.size)]
        return Obstacle(x, widthUnits * cellsPerUnit, heightUnits * cellsPerUnit)
    }

    private fun spawnGap(): Float =
        (MIN_GAP_UNITS + random.nextInt(GAP_SPREAD_UNITS + 1)) * u

    private val cellsPerUnit: Int get() = DinoScreen.unit(size)

    private val u: Float get() = cellsPerUnit.toFloat()

    companion object {
        // Integrated the way step() does, the arc peaks at 5 units and lasts 20 ticks,
        // about a second, leaving a jump window of at least 3 units at every speed.
        const val JUMP_V0 = 0.95f
        const val GRAVITY = 0.10f

        const val START_SPEED = 0.45f
        const val MAX_SPEED = 0.75f
        const val SPEED_RAMP = 0.02f

        const val STRIDE_TICKS = 2
        const val STRIDE_POSES = 2

        // A whole jump covers 15 units at MAX_SPEED, so the floor always leaves room
        // to land between two cacti.
        const val MIN_GAP_UNITS = 20
        const val GAP_SPREAD_UNITS = 10

        const val INITIAL_LEAD_UNITS = 36

        const val MAX_SCORE = 999

        /** An obstacle scores once its rounded right edge passes column 0. */
        private const val OFF_LEFT_EDGE_X = -0.5f

        // Cactus width to height, in units. None is both widest and tallest: that one
        // is not clearable at the slowest scroll on 13 columns.
        val VARIANTS = listOf(1 to 1, 1 to 2, 2 to 1)

        val MAX_OBSTACLE_H = VARIANTS.maxOf { it.second }

        val MAX_OBSTACLE_W = VARIANTS.maxOf { it.first }
    }
}
