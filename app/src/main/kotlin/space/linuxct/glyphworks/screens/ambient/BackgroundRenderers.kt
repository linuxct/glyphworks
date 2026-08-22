package space.linuxct.glyphworks.screens.ambient

import space.linuxct.glyphworks.core.ConnectionState
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.screens.BatteryScreen
import space.linuxct.glyphworks.screens.ClockScreen
import space.linuxct.glyphworks.screens.MoonMath
import space.linuxct.glyphworks.screens.MoonScreen
import space.linuxct.glyphworks.screens.SolarMath
import space.linuxct.glyphworks.screens.SolarScreen
import space.linuxct.glyphworks.screens.SpeedScreen

interface AmbientBackground {
    fun render(c: ScreenContext, nowMs: Long): IntArray
}

object BackgroundRenderers {
    const val TEXT_CLOCK = 0
    const val ANALOG_CLOCK = 1
    const val CONNECTION = 2
    const val BATTERY_TEXT = 3
    const val SPEED = 4
    const val TILT_BALL = 5
    const val PIXEL_CLOCK = 6
    const val BATTERY_GAUGE = 7
    const val SOLAR_PATH = 8
    const val MOON_PHASE = 9

    const val COUNT = 10

    fun create(index: Int): AmbientBackground = when (index) {
        ANALOG_CLOCK -> AnalogClockBackground()
        CONNECTION -> ConnectionBackground()
        BATTERY_TEXT -> BatteryTextBackground()
        SPEED -> SpeedBackground()
        TILT_BALL -> TiltBallBackground()
        PIXEL_CLOCK -> PixelClockBackground()
        BATTERY_GAUGE -> BatteryGaugeBackground()
        SOLAR_PATH -> SolarPathBackground()
        MOON_PHASE -> MoonPhaseBackground()
        else -> TextClockBackground()
    }
}

private const val PERCENT_FULL = 100
private const val HOURS_ON_DIAL = 12
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val MILLIS_PER_SECOND = 1000L

private const val PM_DOT = 1100

// The emitter's centre falls between cells at both sizes. This is the smallest radius
// that lights the two nearest cells fully, which the frame needs so the whole glyph is
// not held below full brightness.
private const val WIFI_EMITTER_RADIUS = 1.1f

private const val TILT_ACCEL = 0.06f
private const val TILT_DRAG = 0.92f
private const val TILT_BOUNCE = 0.6f
private const val TILT_WALL = 300

private const val SUN_TIMES_CACHE_MS = 60_000

private class TextClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val use12h = c.prefs.getBoolean(PrefKeys.USE_12H, false)
        val hour24 = c.ports.clock.hourOfDay()
        val hour = if (use12h) {
            (hour24 % HOURS_ON_DIAL).let { if (it == 0) HOURS_ON_DIAL else it }
        } else {
            hour24
        }
        val hh = hour.toString().padStart(2, '0')
        val mm = c.ports.clock.minute().toString().padStart(2, '0')
        if (c.size >= 25) {
            Font3x5.drawStringCentered(canvas, "$hh:$mm", 10, MAX_BRIGHTNESS)
        } else {
            Font3x5.drawString(canvas, hh, 3, 1, MAX_BRIGHTNESS)
            Font3x5.drawString(canvas, mm, 3, 7, MAX_BRIGHTNESS)
        }
        if (use12h && hour24 >= HOURS_ON_DIAL) canvas.set(c.size - 1, 0, PM_DOT)
        return canvas.copyOut()
    }
}

private class AnalogClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray = ClockScreen.renderAnalog(c)
}

private class ConnectionBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val s = c.size
        when (c.ports.connectivity.state()) {
            ConnectionState.WIFI -> {
                val cx = s / 2f
                val cy = s * 3f / 4f
                canvas.discSoft(cx, cy, WIFI_EMITTER_RADIUS, MAX_BRIGHTNESS)
                canvas.arcRing(cx, cy, 2.4f, 3.2f, 315f, 90f, 2600)
                canvas.arcRing(cx, cy, 4.4f, 5.2f, 315f, 90f, 1500)
                if (s >= 25) canvas.arcRing(cx, cy, 6.4f, 7.2f, 315f, 90f, 900)
            }
            ConnectionState.CELLULAR -> {
                val base = s - 3
                val xs = if (s >= 25) listOf(6, 10, 14, 18) else listOf(3, 5, 7, 9)
                xs.forEachIndexed { i, x ->
                    val h = (i + 1) * (if (s >= 25) 4 else 2)
                    for (y in base - h + 1..base) canvas.light(x, y, 1200 + i * 700)
                }
            }
            ConnectionState.AIRPLANE -> {
                val cx = s / 2
                canvas.line(cx, 2, cx, s - 3, MAX_BRIGHTNESS) // fuselage
                canvas.line(2, s / 2 - 1, s - 3, s / 2 - 1, 2600) // wings
                canvas.line(cx - 2, s - 4, cx + 2, s - 4, 1800) // tail
            }
            ConnectionState.NONE -> {
                val center = (s - 1) / 2f
                canvas.circle(s / 2, s / 2, s / 2 - 2, 1500)
                canvas.line(s - 4, 3, 3, s - 4, 3000)
                canvas.discSoft(center, center, 0.7f, 800)
            }
        }
        return canvas.copyOut()
    }
}

private class BatteryTextBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val level = c.ports.battery.levelPercent().coerceIn(0, PERCENT_FULL)
        val text = if (level >= PERCENT_FULL) "$PERCENT_FULL" else "$level%"
        val textTop = c.size / 2 - Font3x5.HEIGHT / 2
        Font3x5.drawStringCentered(canvas, text, textTop, MAX_BRIGHTNESS)
        return canvas.copyOut()
    }
}

private class SpeedBackground : AmbientBackground {
    private var lastTotal = -1L
    private var lastSampleAt = 0L
    private var bytesPerSec = 0L

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        if (nowMs - lastSampleAt >= MILLIS_PER_SECOND) {
            val total = c.ports.speed.totalRxBytes()
            if (lastTotal >= 0 && nowMs > lastSampleAt) {
                val elapsed = nowMs - lastSampleAt
                bytesPerSec = ((total - lastTotal) * MILLIS_PER_SECOND / elapsed).coerceAtLeast(0)
            }
            lastTotal = total
            lastSampleAt = nowMs
        }
        return SpeedScreen.renderFrame(c.size, bytesPerSec)
    }
}

private class TiltBallBackground : AmbientBackground {
    private var px = -1f
    private var py = -1f
    private var vx = 0f
    private var vy = 0f

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val s = c.size
        if (px < 0) {
            px = (s - 1) / 2f
            py = (s - 1) / 2f
        }
        // Screen x grows right, but sensor +x means tilted left, so invert it.
        vx += -c.ports.tilt.tiltX() * TILT_ACCEL
        vy += c.ports.tilt.tiltY() * TILT_ACCEL
        vx *= TILT_DRAG
        vy *= TILT_DRAG
        px += vx
        py += vy
        val min = 1f
        val max = s - 2f
        if (px < min) { px = min; vx = -vx * TILT_BOUNCE }
        if (px > max) { px = max; vx = -vx * TILT_BOUNCE }
        if (py < min) { py = min; vy = -vy * TILT_BOUNCE }
        if (py > max) { py = max; vy = -vy * TILT_BOUNCE }

        val canvas = MatrixCanvas(s)
        canvas.rect(0, 0, s, s, TILT_WALL)
        canvas.discSoft(px, py, if (s >= 25) 2.2f else 1.3f, MAX_BRIGHTNESS)
        return canvas.copyOut()
    }
}

private class PixelClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray = ClockScreen.renderFrame(c)
}

private class BatteryGaugeBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray = BatteryScreen.renderFrame(
        c.size,
        c.ports.battery.levelPercent(),
        c.ports.battery.isCharging(),
        nowMs,
    )
}

private class SolarPathBackground : AmbientBackground {
    private var cachedTimes: SolarMath.SunTimes? = null
    private var cachedAt = 0L

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        var times = cachedTimes
        if (times == null || nowMs - cachedAt >= SUN_TIMES_CACHE_MS) {
            cachedAt = nowMs
            val loc = c.ports.location.latLon()
            times = if (loc == null) {
                SolarMath.SunTimes(SolarScreen.FALLBACK_RISE, SolarScreen.FALLBACK_SET, SolarMath.Kind.NORMAL)
            } else {
                SolarMath.sunTimes(
                    c.ports.clock.dayOfYear(),
                    loc.first,
                    loc.second,
                    c.ports.clock.utcOffsetMinutes(),
                )
            }
            cachedTimes = times
        }
        val minutes = c.ports.clock.hourOfDay() * MINUTES_PER_HOUR + c.ports.clock.minute()
        val (rise, set) = when (times.kind) {
            SolarMath.Kind.POLAR_DAY -> 0 to MINUTES_PER_DAY
            SolarMath.Kind.POLAR_NIGHT -> Int.MAX_VALUE to Int.MAX_VALUE
            SolarMath.Kind.NORMAL -> times.riseMin to times.setMin
        }
        return SolarScreen.renderFrame(c.size, minutes, rise, set)
    }
}

private class MoonPhaseBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray =
        MoonScreen.renderFrame(c.size, MoonMath.phaseFraction(nowMs))
}
