package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Two eyes with wandering pupils. Each eye is a rim and a pupil with nothing lit in
 * between, and the rim is taller than it is wide because a circular eye reads as a disc.
 */
class EyesScreen : GlyphScreen {
    override val id = "eyes"
    override val interactive = false

    private var ctx: ScreenContext? = null

    private var pupilX = 0f
    private var pupilY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var nextWanderAt = 0L
    private var nextBlinkAt = 0L
    private var blinkPhase = BLINK_OPEN

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        val now = ctx.ports.clock.nowMillis()
        nextWanderAt = now + WANDER_MIN_MS
        nextBlinkAt = now + BLINK_MIN_MS
        blinkPhase = BLINK_OPEN
        pupilX = 0f; pupilY = 0f; targetX = 0f; targetY = 0f
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val now = c.ports.clock.nowMillis()

        if (now >= nextWanderAt) {
            targetX = (c.ports.random.nextInt(GAZE_STATES) - GAZE_MID).toFloat()
            targetY = (c.ports.random.nextInt(GAZE_STATES) - GAZE_MID).toFloat()
            nextWanderAt = now + WANDER_MIN_MS + c.ports.random.nextInt(WANDER_SPREAD_MS)
        }
        pupilX += (targetX - pupilX) * PUPIL_EASE
        pupilY += (targetY - pupilY) * PUPIL_EASE

        if (blinkPhase == BLINK_OPEN && now >= nextBlinkAt) blinkPhase = 0

        c.pushFrame(renderFrame(c.size, pupilX, pupilY, blinkPhase))

        if (blinkPhase != BLINK_OPEN) {
            blinkPhase++
            if (blinkPhase >= BLINK_STEPS) {
                blinkPhase = BLINK_OPEN
                nextBlinkAt = now + BLINK_MIN_MS + c.ports.random.nextInt(BLINK_SPREAD_MS)
            }
        }
    }

    companion object {
        const val TICK_MS = 50L

        private const val WANDER_MIN_MS = 1500L
        private const val WANDER_SPREAD_MS = 2000
        private const val BLINK_MIN_MS = 2500L
        private const val BLINK_SPREAD_MS = 3000

        private const val GAZE_STATES = 3
        private const val GAZE_MID = 1

        private const val PUPIL_EASE = 0.25f

        private const val BLINK_OPEN = -1
        private const val BLINK_STEPS = 6

        const val PUPIL = MAX_BRIGHTNESS
        const val RIM = 2600

        private fun eyeGap(size: Int) = if (size >= 25) 3 else 1

        // Drawn by hand: a generic ellipse gives lumpy caps at these sizes. The contour
        // must be 8-connected so the outline closes and the inside stays hollow, and
        // every row must be the same length.
        private fun eyeStencil(size: Int): List<String> = if (size >= 25) EYE_25 else EYE_13

        private val EYE_13 = listOf(
            " ### ",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            " ### ",
        )

        private val EYE_25 = listOf(
            "   ###   ",
            "  #   #  ",
            " #     # ",
            " #     # ",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            " #     # ",
            " #     # ",
            "  #   #  ",
            "   ###   ",
        )

        /** Square at the eye's height, so columns past its width stay dark and unread. */
        private fun eyeMask(stencil: List<String>): MatrixCanvas {
            val m = MatrixCanvas(stencil.size)
            m.blit(stencil, 0, 0, RIM)
            return m
        }

        fun renderFrame(size: Int, pupilX: Float, pupilY: Float, blinkPhase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val stencil = eyeStencil(size)
            val w = stencil[0].length
            val h = stencil.size
            val gap = eyeGap(size)
            val leftX = (size - (2 * w + gap)) / 2
            val topY = (size - h) / 2
            val mask = eyeMask(stencil)

            // Lid travel in rows down from the top of the eye. drawEye() raises the
            // lower lid at half this rate, so the gap closes toward the middle row.
            // Measured against the eye's height, so both sizes squint the same.
            val cover = when (blinkPhase) {
                0, 4 -> h / 4
                1, 3 -> h / 2
                2 -> h
                else -> 0
            }
            val gazeStep = if (size >= 25) 2 else 1
            val pupilRadius = if (size >= 25) 1 else 0
            var offX = Math.round(pupilX) * gazeStep
            var offY = Math.round(pupilY) * gazeStep
            // A diagonal look is pulled back in until the pupil clears the rim.
            while ((offX != 0 || offY != 0) &&
                pupilHitsRim(mask, w, h, w / 2 + offX, h / 2 + offY, pupilRadius)
            ) {
                offX -= Integer.signum(offX)
                offY -= Integer.signum(offY)
            }

            for (eyeX in intArrayOf(leftX, leftX + w + gap)) {
                drawEye(canvas, mask, eyeX, topY, w, h, cover, offX, offY, pupilRadius)
            }
            return canvas.copyOut()
        }

        private fun pupilHitsRim(
            mask: MatrixCanvas,
            w: Int,
            h: Int,
            cx: Int,
            cy: Int,
            pupilRadius: Int,
        ): Boolean {
            for (yy in cy - pupilRadius..cy + pupilRadius) {
                for (xx in cx - pupilRadius..cx + pupilRadius) {
                    if (xx !in 0 until w || yy !in 0 until h) return true
                    if (mask.get(xx, yy) > 0) return true
                }
            }
            return false
        }

        /**
         * The lid clips the outline to the visible band and caps it with a line the
         * eye's width at that row. Fully closed leaves the single widest chord.
         */
        private fun drawEye(
            canvas: MatrixCanvas,
            mask: MatrixCanvas,
            ox: Int,
            oy: Int,
            w: Int,
            h: Int,
            cover: Int,
            offX: Int,
            offY: Int,
            pupilRadius: Int,
        ) {
            val firstRow = cover
            val lastRow = h - 1 - cover / 2
            if (firstRow >= lastRow) {
                lidLine(canvas, mask, ox, oy, w, h / 2)
                return
            }
            for (yy in firstRow..lastRow) for (xx in 0 until w) {
                if (mask.get(xx, yy) > 0) canvas.light(ox + xx, oy + yy, RIM)
            }
            if (cover > 0) {
                lidLine(canvas, mask, ox, oy, w, firstRow)
                lidLine(canvas, mask, ox, oy, w, lastRow)
            }

            val loY = firstRow + 1
            val hiY = lastRow - 1
            if (loY > hiY) return
            val cx = w / 2 + offX
            val cy = (h / 2 + offY).coerceIn(loY, hiY)
            for (yy in cy - pupilRadius..cy + pupilRadius) {
                for (xx in cx - pupilRadius..cx + pupilRadius) {
                    if (yy < loY || yy > hiY || xx !in 0 until w) continue
                    if (mask.get(xx, yy) > 0) continue
                    canvas.set(ox + xx, oy + yy, PUPIL)
                }
            }
        }

        private fun lidLine(canvas: MatrixCanvas, mask: MatrixCanvas, ox: Int, oy: Int, w: Int, row: Int) {
            var lo = -1
            var hi = -1
            for (xx in 0 until w) if (mask.get(xx, row) > 0) {
                if (lo < 0) lo = xx
                hi = xx
            }
            if (lo < 0) return
            for (xx in lo..hi) canvas.light(ox + xx, oy + row, RIM)
        }
    }
}
