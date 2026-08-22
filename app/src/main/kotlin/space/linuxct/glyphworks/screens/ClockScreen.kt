package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask

/** Clock. At 13x13 the digits stack; at 25x25 it is one HH:MM line. */
class ClockScreen : GlyphScreen {
    override val id = "clock"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { render() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun render() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c))
    }

    companion object {
        const val TICK_MS = 50L

        const val THEME_PLAIN = 0
        const val THEME_BATTERY_BAR = 1
        const val THEME_BATTERY_RING = 2
        const val THEME_ANALOG = 3

        private const val PERCENT_FULL = 100
        private const val HOURS_ON_DIAL = 12
        private const val MINUTES_PER_HOUR = 60f
        private const val SECONDS_PER_MINUTE = 60f
        private const val DEGREES_PER_HOUR = 30f
        private const val DEGREES_PER_MINUTE = 6f
        private const val DEGREES_PER_TURN = 360f

        private const val DIGIT_BRIGHT = MAX_BRIGHTNESS
        private const val EXTRA_BRIGHT = 1100

        private const val HOUR_BRIGHT = MAX_BRIGHTNESS

        /** Minute hand at 0.6x, so a glance tells it from the hour hand. */
        private const val MINUTE_BRIGHT = 2457

        private const val BORDER_BRIGHT = EXTRA_BRIGHT

        /** Pure renderer, also used by the ambient pixel-clock background. */
        fun renderFrame(c: ScreenContext): IntArray {
            val canvas = MatrixCanvas(c.size)
            val use12h = c.prefs.getBoolean(PrefKeys.USE_12H, false)
            val theme = c.prefs.getInt(PrefKeys.CLOCK_THEME, PrefKeys.CLOCK_THEME_DEF)
            if (theme == THEME_ANALOG) return renderAnalog(c)
            val hour24 = c.ports.clock.hourOfDay()
            val minute = c.ports.clock.minute()
            val pm = hour24 >= HOURS_ON_DIAL
            val hour = if (use12h) {
                val h = hour24 % HOURS_ON_DIAL
                if (h == 0) HOURS_ON_DIAL else h
            } else {
                hour24
            }
            val hh = hour.toString().padStart(2, '0')
            val mm = minute.toString().padStart(2, '0')
            val batteryPercent = c.ports.battery.levelPercent()
            val batteryDegrees = DEGREES_PER_TURN * batteryPercent / PERCENT_FULL

            if (c.size >= 25) {
                Font3x5.drawStringCentered(canvas, "$hh:$mm", 10, DIGIT_BRIGHT)
                when (theme) {
                    THEME_BATTERY_BAR -> {
                        val fill = batteryPercent * c.size / PERCENT_FULL
                        for (x in 0 until fill) canvas.light(x, 18, EXTRA_BRIGHT)
                    }
                    THEME_BATTERY_RING -> {
                        val center = (c.size - 1) / 2f
                        canvas.arcRing(
                            center, center, c.size / 2f - 1f, c.size / 2f,
                            0f, batteryDegrees, EXTRA_BRIGHT,
                        )
                    }
                }
            } else {
                Font3x5.drawString(canvas, hh, 3, 1, DIGIT_BRIGHT)
                Font3x5.drawString(canvas, mm, 3, 7, DIGIT_BRIGHT)
                when (theme) {
                    THEME_BATTERY_BAR -> {
                        val fill = batteryPercent * c.size / PERCENT_FULL
                        for (x in 0 until fill) canvas.light(x, 6, EXTRA_BRIGHT)
                    }
                    THEME_BATTERY_RING -> {
                        canvas.arcRing(6f, 6f, 6f, 6.4f, 0f, batteryDegrees, EXTRA_BRIGHT)
                    }
                }
            }
            // A corner dot marks PM in 12 h mode.
            if (use12h && pm) canvas.set(c.size - 1, 0, EXTRA_BRIGHT)
            return canvas.copyOut()
        }

        /**
         * No second hand, but the minute angle carries the seconds so it steps every
         * second. The border follows [PanelMask.isEdge] because the panel is a disc and
         * a rectangle would be clipped at the corners.
         */
        fun renderAnalog(c: ScreenContext): IntArray {
            val canvas = MatrixCanvas(c.size)
            val center = c.size / 2

            // Hands stop one cell short of the border: 5 and 6 inside a radius-6
            // panel at 13, 7 and 9 inside 12 at 25.
            val hourLen = if (c.size >= 25) 7f else 5f
            val minLen = if (c.size >= 25) 9f else 6f

            val hour = c.ports.clock.hourOfDay() % HOURS_ON_DIAL
            val minute = c.ports.clock.minute()
            val second = c.ports.clock.second()
            val hourAngle = (hour + minute / MINUTES_PER_HOUR) * DEGREES_PER_HOUR
            val minAngle = (minute + second / SECONDS_PER_MINUTE) * DEGREES_PER_MINUTE

            for (y in 0 until c.size) {
                for (x in 0 until c.size) {
                    if (PanelMask.isEdge(x, y, c.size)) canvas.set(x, y, BORDER_BRIGHT)
                }
            }
            canvas.ray(center, center, minAngle, minLen, MINUTE_BRIGHT)
            canvas.ray(center, center, hourAngle, hourLen, HOUR_BRIGHT)
            return canvas.copyOut()
        }
    }
}
