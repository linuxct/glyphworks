package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Battery gauge. With [PrefKeys.BATTERY_SHOW_WATTS] on and a good power reading, a
 * charging device shows the watts instead; anything else falls back to the gauge.
 */
class BatteryScreen : GlyphScreen {
    override val id = "battery"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val charging = c.ports.battery.isCharging()
        val showWatts = c.prefs.getBoolean(PrefKeys.BATTERY_SHOW_WATTS, PrefKeys.BATTERY_SHOW_WATTS_DEF)
        c.pushFrame(
            renderFrame(
                c.size,
                c.ports.battery.levelPercent(),
                charging,
                c.ports.clock.nowMillis(),
                if (charging && showWatts) c.ports.battery.chargeWatts() else null,
            ),
        )
    }

    companion object {
        const val TICK_MS = 1000L

        private const val PERCENT_FULL = 100
        private const val MIN_WATTS = 1
        private const val MAX_WATTS = 999

        private const val LINE_GAP = 1
        private const val TWO_LINE_HEIGHT = 2 * Font3x5.HEIGHT + LINE_GAP

        private const val WAVE_MS_PER_ROW = 150
        private const val PULSE_MID = 2400
        private const val PULSE_SWING = 1600
        private const val PULSE_MIN = 800
        private const val PULSE_MS_PER_RADIAN = 200.0

        /** The "W" glyph, dimmer than the digits so the number reads first. */
        private const val UNIT = 2600

        // Panel brightness multiplies the whole frame, so the brightest cell sets how
        // bright it looks. Pinning the edge marker at the peak keeps that still and lets
        // only the bolt pulse.
        private const val EDGE = MAX_BRIGHTNESS
        private const val WAVE = 3300
        private const val FILL = 2000

        private val BOLT = listOf(
            "..#",
            ".#.",
            "###",
            ".#.",
            "#..",
        )

        /** Clamped so the font can always place it: "45W" fits, "1200W" would not. */
        fun formatWatts(watts: Float): String =
            "${watts.roundToInt().coerceIn(MIN_WATTS, MAX_WATTS)}W"

        /** "120W" is 15 cells wide, so on 13 columns the digits stack above the "W". */
        fun renderWattage(size: Int, watts: Float): IntArray {
            val canvas = MatrixCanvas(size)
            val text = formatWatts(watts)
            val digits = text.dropLast(1)
            if (Font3x5.stringWidth(text) <= size) {
                val y = (size - Font3x5.HEIGHT) / 2
                var x = (size - Font3x5.stringWidth(text)) / 2
                digits.forEach { x += Font3x5.draw(canvas, it, x, y, MAX_BRIGHTNESS) }
                Font3x5.draw(canvas, 'W', x, y, UNIT)
            } else {
                val digitsTop = size / 2 - TWO_LINE_HEIGHT / 2
                val unitTop = digitsTop + Font3x5.HEIGHT + LINE_GAP
                Font3x5.drawStringCentered(canvas, digits, digitsTop, MAX_BRIGHTNESS)
                Font3x5.drawStringCentered(canvas, "W", unitTop, UNIT)
            }
            return canvas.copyOut()
        }

        /** [chargeWatts] defaults to null so the ambient background gets the gauge. */
        fun renderFrame(
            size: Int,
            levelPercent: Int,
            charging: Boolean,
            nowMs: Long,
            chargeWatts: Float? = null,
        ): IntArray {
            if (charging && chargeWatts != null) return renderWattage(size, chargeWatts)
            val canvas = MatrixCanvas(size)
            val level = levelPercent.coerceIn(0, PERCENT_FULL)
            val fillRows = (size * level / PERCENT_FULL).coerceIn(0, size)

            for (y in size - fillRows until size) {
                val rowFromBottom = size - 1 - y
                val waveRow = (nowMs / WAVE_MS_PER_ROW % size).toInt()
                val wave = charging && rowFromBottom == waveRow
                val v = if (wave) WAVE else FILL
                for (x in 0 until size) canvas.light(x, y, v)
            }
            if (fillRows in 1..size) {
                val y = (size - fillRows).coerceAtLeast(0)
                for (x in 0 until size) canvas.light(x, y, EDGE)
            }
            if (charging) {
                val pulse = (PULSE_MID + PULSE_SWING * sin(nowMs / PULSE_MS_PER_RADIAN))
                    .roundToInt()
                    .coerceIn(PULSE_MIN, MAX_BRIGHTNESS)
                val boltY = if (size >= 25) size / 2 - 3 else size / 2 - 2
                val boltX = size / 2 - BOLT[0].length / 2
                // Overwrite instead of max-blending, so the bolt still shows inside the
                // fill when the pulse is low.
                BOLT.forEachIndexed { by, row ->
                    row.forEachIndexed { bx, ch ->
                        if (ch == '#') canvas.set(boltX + bx, boltY + by, pulse)
                    }
                }
            }
            return canvas.copyOut()
        }
    }
}
