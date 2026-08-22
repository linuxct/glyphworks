package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Solar Path: the sun along its daily arc, sunrise on the left, sunset on the right. */
class SolarScreen : GlyphScreen {
    override val id = "solar"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private var cachedTimes: SolarMath.SunTimes? = null
    private var cachedAt = 0L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        cachedTimes = null
        cachedAt = 0L
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun sunTimes(c: ScreenContext): SolarMath.SunTimes {
        val now = c.ports.clock.nowMillis()
        val cached = cachedTimes
        if (cached != null && now - cachedAt < SUN_TIMES_CACHE_MS) return cached
        cachedAt = now
        val loc = c.ports.location.latLon()
        val times = if (loc == null) {
            SolarMath.SunTimes(FALLBACK_RISE, FALLBACK_SET, SolarMath.Kind.NORMAL)
        } else {
            SolarMath.sunTimes(
                c.ports.clock.dayOfYear(),
                loc.first,
                loc.second,
                c.ports.clock.utcOffsetMinutes(),
            )
        }
        cachedTimes = times
        return times
    }

    private fun tick() {
        val c = ctx ?: return
        val minutes = c.ports.clock.hourOfDay() * MINUTES_PER_HOUR + c.ports.clock.minute()
        val times = sunTimes(c)
        val (rise, set) = when (times.kind) {
            SolarMath.Kind.POLAR_DAY -> 0 to MINUTES_PER_DAY
            SolarMath.Kind.POLAR_NIGHT -> Int.MAX_VALUE to Int.MAX_VALUE // sun never above horizon
            SolarMath.Kind.NORMAL -> times.riseMin to times.setMin
        }
        c.pushFrame(renderFrame(c.size, minutes, rise, set))
    }

    companion object {
        const val TICK_MS = 1000L

        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        private const val SUN_TIMES_CACHE_MS = 60_000

        const val FALLBACK_RISE = 6 * MINUTES_PER_HOUR
        const val FALLBACK_SET = 18 * MINUTES_PER_HOUR

        private const val SUN = MAX_BRIGHTNESS
        private const val HORIZON = 1600

        /** The night sun sits above [GROUND] so it shows through the max blend. */
        private const val NIGHT_SUN = 1600
        private const val GROUND = 700
        private const val STAR = 900

        private const val NIGHT_SUN_RADIUS = 1.0f

        fun renderFrame(size: Int, minutesLocal: Int, riseMin: Int, setMin: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val horizonY = if (size >= 25) 16 else 8
            val cx = size / 2
            val r = min(horizonY - 2, size / 2 - 1).toFloat()

            canvas.fillRect(0, horizonY + 1, size, size - horizonY - 1, GROUND)
            for (x in 0 until size) canvas.light(x, horizonY, HORIZON)

            val day = riseMin != Int.MAX_VALUE && minutesLocal in riseMin until setMin
            if (day) {
                val f = (minutesLocal - riseMin).toFloat() / (setMin - riseMin).coerceAtLeast(1)
                val px = cx - r * cos(Math.PI * f).toFloat()
                val py = horizonY - r * sin(Math.PI * f).toFloat()
                canvas.discSoft(px, py, if (size >= 25) 1.8f else 1.2f, SUN)
            } else {
                canvas.light(2, 2, STAR)
                canvas.light(size - 4, 1, STAR)
                canvas.light(size / 2 + 1, 4, STAR)
                if (riseMin != Int.MAX_VALUE) {
                    val nightLen = (MINUTES_PER_DAY - (setMin - riseMin)).coerceAtLeast(1)
                    val sinceSet = ((minutesLocal - setMin) + MINUTES_PER_DAY) % MINUTES_PER_DAY
                    val f = sinceSet.toFloat() / nightLen
                    // Squash the arc into the rows below the horizon so the sun stays
                    // on canvas.
                    val rBelow = (size - 2 - horizonY).toFloat().coerceAtMost(r)
                    val px = cx + r * cos(Math.PI * f).toFloat()
                    val py = horizonY + rBelow * sin(Math.PI * f).toFloat()
                    canvas.discSoft(px, py, NIGHT_SUN_RADIUS, NIGHT_SUN)
                }
            }
            return canvas.copyOut()
        }
    }
}
