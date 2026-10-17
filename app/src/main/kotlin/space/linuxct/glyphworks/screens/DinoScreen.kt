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
    override val instantAction = true

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

        /**
         * The disc narrows towards the ground — the stand row is cols 2..10 at 13 and 7..17 at
         * 25 — so this is per panel rather than a unit multiple, or the dino stands with a foot
         * off the panel.
         */
        fun charX(size: Int): Int = if (size >= 25) CHAR_X_25 else CHAR_X_13

        fun charW(size: Int): Int = CHAR_W_UNITS * unit(size)

        /**
         * The sprite is wider and taller than the hitbox: the head and tail sit above every
         * cactus, so only the feet can meet one. [charX]/[charW] stay the feet, which keeps the
         * jump exactly as hard as it was while the dino grew.
         */
        private fun spriteX(size: Int): Int = charX(size) - FEET_INSET_UNITS * unit(size)

        // Head, neck, body and hips; only the feet row changes as it runs.
        private val BODY_13 = listOf("...###", "...###", "...#..", "####..", "####..", ".###..")
        private val CHAR_13_STAND = BODY_13 + ".#.#.."
        private val CHAR_13_RUN_A = BODY_13 + ".#...."
        private val CHAR_13_RUN_B = BODY_13 + "...#.."
        private val CHAR_13_JUMP = BODY_13 + ".#.#.."

        // [BODY_13] at two cells per pixel, so both panels show the same dino.
        private val BODY_25 = BODY_13.flatMap { row ->
            val doubled = row.flatMap { listOf(it, it) }.joinToString("")
            listOf(doubled, doubled)
        }

        private fun feet25(row: String): List<String> {
            val doubled = row.flatMap { listOf(it, it) }.joinToString("")
            return listOf(doubled, doubled)
        }

        private val CHAR_25_STAND = BODY_25 + feet25(".#.#..")
        private val CHAR_25_RUN_A = BODY_25 + feet25(".#....")
        private val CHAR_25_RUN_B = BODY_25 + feet25("...#..")
        private val CHAR_25_JUMP = BODY_25 + feet25(".#.#..")

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

        fun renderIdle(size: Int, drawCharacter: Boolean = true): IntArray {
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
            if (drawCharacter) blitChar(canvas, size, LEG_PHASE_STANDING, 0)
            return canvas.copyOut()
        }

        private fun blitChar(canvas: MatrixCanvas, size: Int, legPhase: Int, jumpCells: Int) {
            val art = charArt(size, legPhase)
            canvas.blit(art, spriteX(size), standRow(size) - jumpCells - art.size + 1, CHAR)
        }

        fun renderRun(
            size: Int,
            jumpCells: Int,
            legPhase: Int,
            groundPhase: Int,
            obstacles: List<Obst>,
            drawCharacter: Boolean = true,
            drawObstacles: Boolean = true,
            drawGround: Boolean = true,
        ): IntArray {
            val canvas = MatrixCanvas(size)
            val ground = groundRow(size)
            val u = unit(size)
            val period = GROUND_PERIOD_UNITS * u
            val dashLength = GROUND_DASH_UNITS * u
            for (x in 0 until size) {
                val phase = ((x + groundPhase) % period + period) % period
                if (drawGround) canvas.light(x, ground, if (phase < dashLength) GROUND_DASH else GROUND_GAP)
            }
            if (drawObstacles) obstacles.forEach { o ->
                canvas.fillRect(o.x, standRow(size) - o.h + 1, o.w, o.h, OBSTACLE)
            }
            if (drawCharacter) blitChar(canvas, size, legPhase, jumpCells)
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

        private const val CHAR_X_13 = 2
        private const val CHAR_X_25 = 7
        private const val CHAR_W_UNITS = 3
        private const val FEET_INSET_UNITS = 1

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
class DinoGame(
    val size: Int,
    private val random: RandomPort,
    val tuning: Tuning = Tuning(),
    /** Optional explicit sprite hitbox, in panel cells relative to the foot anchor. */
    private val hitbox: (() -> Hitbox?)? = null,
) {
    data class Tuning(
        val jumpVelocity: Float = JUMP_V0,
        val gravity: Float = GRAVITY,
        val startSpeed: Float = START_SPEED,
        val maxSpeed: Float = MAX_SPEED,
        val speedRamp: Float = SPEED_RAMP,
        val minimumGap: Int = MIN_GAP_UNITS,
        val gapSpread: Int = GAP_SPREAD_UNITS,
    )
    data class Hitbox(val x: Float, val y: Float, val width: Float, val height: Float)
    fun height(): Float = heightCells
    fun verticalVelocity(): Float = verticalSpeed


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
        verticalSpeed = tuning.jumpVelocity * u
        isAirborne = true
    }

    fun step() {
        if (state != State.RUNNING) return
        ticks++

        if (isAirborne) {
            heightCells += verticalSpeed
            verticalSpeed -= tuning.gravity * u
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
        ((tuning.startSpeed + score * tuning.speedRamp).coerceAtMost(tuning.maxSpeed)) * u

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
        val explicit = hitbox?.invoke()
        if (explicit != null && explicit.width > 0 && explicit.height > 0) {
            val left = DinoScreen.charX(size) + explicit.x
            val top = DinoScreen.standRow(size) - jumpCells() + explicit.y
            return obstacles.any { o ->
                val obstacleTop = DinoScreen.standRow(size) - o.h + 1
                left < o.x.roundToInt() + o.w && left + explicit.width > o.x.roundToInt() &&
                    top < obstacleTop + o.h && top + explicit.height > obstacleTop
            }
        }
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
        (tuning.minimumGap + random.nextInt(tuning.gapSpread + 1)) * u

    private val cellsPerUnit: Int get() = DinoScreen.unit(size)

    private val u: Float get() = cellsPerUnit.toFloat()

    companion object {
        // Integrated the way step() does, the arc peaks at 5 units and lasts 30 ticks,
        // about a second and a half, leaving a jump window of 3 units at every speed.
        const val JUMP_V0 = 0.66f
        const val GRAVITY = 0.045f

        const val START_SPEED = 0.30f
        const val MAX_SPEED = 0.50f
        const val SPEED_RAMP = 0.013f

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

        // Cactus width to height, in units. All one unit wide: a wider cactus stays under
        // the dino long enough that clearing it needs a press the click window cannot deliver.
        val VARIANTS = listOf(1 to 1, 1 to 2, 1 to 3)

        val MAX_OBSTACLE_H = VARIANTS.maxOf { it.second }

        val MAX_OBSTACLE_W = VARIANTS.maxOf { it.first }
    }
}
