package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two prefs hold the state, so it survives a screen switch and process death:
 *
 *  - idle:    pausedElapsed == 0 && start == 0
 *  - running: pausedElapsed == 0 && start >  0   (start is the run's epoch ms)
 *  - paused:  pausedElapsed >  0                 (start is cleared)
 *  - done:    both cleared, plus the in-memory [done] latch until the next press
 *
 * Always test pausedElapsed first. A crash mid-pause then lands on paused, which never
 * chimes by mistake.
 */
class TimerScreen : GlyphScreen {
    override val id = "timer"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var donePhase = 0
    private var pausePhase = 0
    private var done = false

    private fun startMillis(c: ScreenContext) = c.prefs.getLong(PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
    private fun pausedElapsed(c: ScreenContext) =
        c.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, PrefKeys.TIMER_PAUSED_ELAPSED_DEF)

    private fun durationSec(c: ScreenContext) =
        c.prefs.getInt(PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF).coerceAtLeast(MIN_DURATION_SEC)

    private fun durationMs(c: ScreenContext) = durationSec(c) * MILLIS_PER_SECOND

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        done = false
        val paused = pausedElapsed(ctx)
        if (paused > 0) {
            startPauseBlink()
            return
        }
        val start = startMillis(ctx)
        if (start > 0) {
            val elapsedSec = (ctx.ports.clock.nowMillis() - start) / MILLIS_PER_SECOND
            val deadlinePassedWhileAway = elapsedSec >= durationSec(ctx)
            if (deadlinePassedWhileAway) {
                ctx.prefs.putLong(PrefKeys.TIMER_START, 0L)
                ctx.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
                ctx.ports.timer.cancelAlarm()
                done = true
                ctx.pushFrame(renderDone(ctx.size))
            } else {
                startTicker()
            }
        } else {
            ctx.pushFrame(renderIdle(ctx.size))
        }
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        when {
            done -> dismissDone(c)
            pausedElapsed(c) > 0 -> resume(c)
            startMillis(c) > 0 -> pause(c)
            else -> start(c)
        }
    }

    private fun start(c: ScreenContext) {
        val now = c.ports.clock.nowMillis()
        c.prefs.putLong(PrefKeys.TIMER_START, now)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.ports.timer.scheduleAlarm(now + durationMs(c))
        startTicker()
    }

    // Bank the elapsed time before clearing the start, so a crash in between reads
    // back as paused.
    private fun pause(c: ScreenContext) {
        val elapsed = (c.ports.clock.nowMillis() - startMillis(c)).coerceIn(1L, durationMs(c))
        c.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, elapsed)
        c.prefs.putLong(PrefKeys.TIMER_START, 0L)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.ports.timer.cancelAlarm()
        startPauseBlink()
    }

    // Mirror of [pause]: write the rewound start before clearing the bank, so a crash
    // in between stays paused.
    private fun resume(c: ScreenContext) {
        val start = c.ports.clock.nowMillis() - pausedElapsed(c)
        c.prefs.putLong(PrefKeys.TIMER_START, start)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 0L)
        c.ports.timer.scheduleAlarm(start + durationMs(c))
        startTicker()
    }

    private fun dismissDone(c: ScreenContext) {
        done = false
        c.scheduler.clearTicker()
        c.pushFrame(renderIdle(c.size))
    }

    private fun startTicker() {
        ctx?.scheduler?.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        val start = startMillis(c)
        if (start <= 0) {
            c.scheduler.clearTicker()
            c.pushFrame(renderIdle(c.size))
            return
        }
        val durationMs = durationMs(c)
        val elapsedMs = c.ports.clock.nowMillis() - start
        if (elapsedMs >= durationMs) {
            c.prefs.putLong(PrefKeys.TIMER_START, 0L)
            c.ports.timer.cancelAlarm()
            val backstopAlreadyChimed =
                c.prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF) == start
            if (!backstopAlreadyChimed) c.ports.timer.chime()
            c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
            startDonePulse()
            return
        }
        c.pushFrame(
            renderRunning(
                c.size,
                elapsedMs.toFloat() / durationMs,
                (elapsedMs / TICK_MS).toInt(),
            )
        )
    }

    private fun startDonePulse() {
        donePhase = 0
        done = true
        ctx?.scheduler?.setTicker(TICK_MS) { pulseTick() }
    }

    private fun pulseTick() {
        val c = ctx ?: return
        val phase = donePhase++
        if (phase >= PULSE_FRAMES) {
            c.scheduler.clearTicker()
            c.pushFrame(renderDone(c.size))
            return
        }
        c.pushFrame(renderDonePulse(c.size, phase))
    }

    private fun startPauseBlink() {
        pausePhase = 0
        ctx?.scheduler?.setTicker(BLINK_TICK_MS) { pauseTick() }
    }

    private fun pauseTick() {
        val c = ctx ?: return
        val paused = pausedElapsed(c)
        if (paused <= 0) {
            c.scheduler.clearTicker()
            return
        }
        c.pushFrame(renderPaused(c.size, paused.toFloat() / durationMs(c), pausePhase++))
    }

    companion object {
        const val TICK_MS = 125L
        const val PULSE_FRAMES = 24
        private const val PULSE_PERIOD = 4

        const val BLINK_TICK_MS = 150L
        private const val BLINK_PERIOD = 5
        private const val BLINK_ON_FRAMES = 3

        private const val MILLIS_PER_SECOND = 1000L
        private const val MIN_DURATION_SEC = 5

        // Peak half-amplitude of the sand mound, as a fraction of the matrix height.
        // Keep it under 1/4 or a column stops rising with time and the sand drains.
        private const val MOUND = 0.22f

        private const val GRAIN_MIN_V = 1100
        private const val GRAIN_SPAN_V = 900
        private const val GRAIN_SPACING = 3
        private const val RIM_V = 600

        private const val HASH_GOLDEN_RATIO = -1640531527
        private const val HASH_MIX = 0x27d4eb2d
        private const val HASH_SHIFT_A = 15
        private const val HASH_SHIFT_B = 13
        private const val GRAIN_LANE_SEED_STRIDE = 31
        private const val GRAIN_VALUE_SEED_DIVISOR = 7

        private fun sandHeight(size: Int, fraction: Float, x: Int): Float {
            val elapsed = fraction.coerceIn(0f, 1f)
            val centre = (size - 1) / 2f
            val distanceFromCentre = if (centre <= 0f) 0f else abs(x - centre) / centre
            val amplitude = MOUND * size * 4f * elapsed * (1f - elapsed)
            return (elapsed * size + amplitude * (1f - 2f * distanceFromCentre))
                .coerceIn(0f, size.toFloat())
        }

        private fun drawSand(canvas: MatrixCanvas, size: Int, fraction: Float) {
            for (x in 0 until size) {
                val height = sandHeight(size, fraction, x)
                for (y in 0 until size) {
                    val rowBottom = (size - 1 - y).toFloat()
                    val cover = (height - rowBottom).coerceIn(0f, 1f)
                    if (cover > 0f) canvas.light(x, y, (MAX_BRIGHTNESS * cover).roundToInt())
                }
            }
        }

        private fun hash(n: Int): Int {
            var h = n * HASH_GOLDEN_RATIO
            h = h xor (h ushr HASH_SHIFT_A)
            h *= HASH_MIX
            h = h xor (h ushr HASH_SHIFT_B)
            return h and Int.MAX_VALUE
        }

        private fun drawGrains(canvas: MatrixCanvas, size: Int, fraction: Float, subframe: Int) {
            val centre = (size - 1) / 2
            val freeRows = (size - sandHeight(size, fraction, centre)).toInt()
            if (freeRows <= 0) return
            val lateral = if (size >= 25) 2 else 1
            val maxGrains = if (size >= 25) 6 else 3
            val grains = (freeRows * lateral / GRAIN_SPACING).coerceIn(1, maxGrains)
            for (i in 0 until grains) {
                val pos = subframe + i * GRAIN_SPACING
                val y = Math.floorMod(pos, freeRows)
                val seed = hash(Math.floorDiv(pos, freeRows) * GRAIN_LANE_SEED_STRIDE + i)
                val x = centre + (seed % (2 * lateral + 1)) - lateral
                canvas.light(x, y, GRAIN_MIN_V + (seed / GRAIN_VALUE_SEED_DIVISOR) % GRAIN_SPAN_V)
            }
        }

        private fun drawRim(canvas: MatrixCanvas, size: Int) {
            val centre = (size - 1) / 2f
            canvas.ring(centre, centre, size / 2f - 1f, size / 2f - 0.2f, RIM_V)
        }

        fun renderIdle(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            drawRim(canvas, size)
            return canvas.copyOut()
        }

        fun renderRunning(size: Int, fraction: Float, subframe: Int): IntArray {
            val canvas = MatrixCanvas(size)
            drawSand(canvas, size, fraction)
            drawGrains(canvas, size, fraction, subframe)
            return canvas.copyOut()
        }

        fun renderPaused(size: Int, fraction: Float, phase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (Math.floorMod(phase, BLINK_PERIOD) < BLINK_ON_FRAMES) {
                drawRim(canvas, size)
                drawSand(canvas, size, fraction)
            }
            return canvas.copyOut()
        }

        fun renderDone(size: Int): IntArray = renderDonePulse(size, 0)

        fun renderDonePulse(size: Int, phase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (Math.floorMod(phase, PULSE_PERIOD) < PULSE_PERIOD / 2) canvas.fill(MAX_BRIGHTNESS)
            return canvas.copyOut()
        }
    }
}
