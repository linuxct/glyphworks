package space.linuxct.glyphworks.screens.ambient

import space.linuxct.glyphworks.screens.BatteryScreen
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The charging layer, drawn every tick while the device charges below 100%. All the
 * animation comes from the clock, so it is deterministic in tests.
 */
object ChargingRenderer {

    const val STYLE_FILL_GRID = 0
    const val STYLE_PARTICLES = 1
    const val STYLE_BATTERY_GLYPH = 2
    const val STYLE_NUMERIC = 3
    const val STYLE_WATTS = 4

    /** A null watt reading falls back to [numeric], since a percentage beats a blank. */
    fun render(
        size: Int,
        style: Int,
        levelPercent: Int,
        nowMs: Long,
        chargeWatts: Float? = null,
    ): IntArray = when (style) {
        STYLE_PARTICLES -> particles(size, nowMs)
        STYLE_BATTERY_GLYPH -> batteryGlyph(size, levelPercent, nowMs)
        STYLE_NUMERIC -> numeric(size, levelPercent, nowMs)
        STYLE_WATTS -> chargeWatts
            ?.let { BatteryScreen.renderWattage(size, it) }
            ?: numeric(size, levelPercent, nowMs)
        else -> fillGrid(size, levelPercent, nowMs)
    }

    // Panel brightness multiplies the finished frame, so anything that pulses or moves
    // must not be the brightest element, or it drags the whole frame with it. Each style
    // keeps one still element at MAX_BRIGHTNESS instead.
    private const val FILL_EDGE = MAX_BRIGHTNESS
    private const val FILL_WAVE = 3300
    private const val FILL_BODY = 2234
    private const val PARTICLE_HI = MAX_BRIGHTNESS
    private const val PARTICLE_LO = 1500
    private const val BASELINE = 900
    private const val GLYPH = MAX_BRIGHTNESS
    private const val GLYPH_FILL = 2730

    private const val PERCENT_FULL = 100

    private const val WAVE_MS_PER_ROW = 120

    private const val PULSE_MID = 2200
    private const val PULSE_SWING = 1800
    private const val PULSE_MIN = 600
    private const val PULSE_MS_PER_RADIAN = 200.0

    /** Scatters the streams across the columns without repeating a column. */
    private const val STREAM_COLUMN_STRIDE = 7
    private const val STREAM_COLUMN_OFFSET = 3

    private const val STREAM_MS_PER_ROW_MIN = 90
    private const val STREAM_MS_PER_ROW_STRIDE = 37
    private const val STREAM_MS_PER_ROW_SPREAD = 70
    private const val STREAM_PHASE_STRIDE = 5
    private const val STREAM_TAIL_DIVISOR = 3

    private fun boltPulse(nowMs: Long): Int =
        (PULSE_MID + PULSE_SWING * sin(nowMs / PULSE_MS_PER_RADIAN))
            .roundToInt()
            .coerceIn(PULSE_MIN, MAX_BRIGHTNESS)

    private fun fillGrid(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val fillRows = (size * level / PERCENT_FULL).coerceIn(0, size)
        val waveRow = (nowMs / WAVE_MS_PER_ROW % size).toInt()
        for (y in size - fillRows until size) {
            val rowFromBottom = size - 1 - y
            val v = if (rowFromBottom == waveRow) FILL_WAVE else FILL_BODY
            for (x in 0 until size) canvas.light(x, y, v)
        }
        // The edge marker keeps the level readable while the wave passes.
        if (fillRows in 1 until size) {
            val y = size - fillRows
            for (x in 0 until size) canvas.light(x, y, FILL_EDGE)
        }
        return canvas.copyOut()
    }

    // Brightness ramps across the streams rather than per stream, so the top of the
    // range does not depend on how many streams the panel fits.
    private fun particles(size: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        for (x in 0 until size) canvas.light(x, size - 1, BASELINE)
        val streams = if (size >= 25) 10 else 6
        for (i in 0 until streams) {
            val x = (i * STREAM_COLUMN_STRIDE + STREAM_COLUMN_OFFSET) % size
            val msPerRow = STREAM_MS_PER_ROW_MIN +
                (i * STREAM_MS_PER_ROW_STRIDE) % STREAM_MS_PER_ROW_SPREAD
            val travel = (nowMs / msPerRow + i * STREAM_PHASE_STRIDE) % (size - 1)
            val y = size - 2 - travel.toInt()
            val v = PARTICLE_LO + (PARTICLE_HI - PARTICLE_LO) * i / (streams - 1)
            canvas.light(x, y, v)
            canvas.light(x, y + 1, v / STREAM_TAIL_DIVISOR)
        }
        return canvas.copyOut()
    }

    private val BOLT = listOf(
        "..#",
        ".#.",
        "###",
        ".#.",
        "#..",
    )

    private fun batteryGlyph(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val pulse = boltPulse(nowMs)
        if (size >= 25) {
            canvas.rect(3, 8, 17, 9, GLYPH)
            canvas.fillRect(20, 11, 2, 3, GLYPH) // cap
            val fill = 15 * level / PERCENT_FULL
            canvas.fillRect(4, 9, fill, 7, GLYPH_FILL)
            canvas.blit(BOLT, 10, 10, pulse)
        } else {
            canvas.rect(1, 4, 10, 5, GLYPH)
            canvas.fillRect(11, 5, 1, 3, GLYPH) // cap
            val fill = 8 * level / PERCENT_FULL
            canvas.fillRect(2, 5, fill, 3, GLYPH_FILL)
            canvas.blit(BOLT, 5, 4, pulse)
        }
        return canvas.copyOut()
    }

    private fun numeric(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val text = if (level >= PERCENT_FULL) "$PERCENT_FULL" else "$level%"
        val textY = if (size >= 25) 7 else 2
        Font3x5.drawStringCentered(canvas, text, textY, MAX_BRIGHTNESS)
        val boltY = if (size >= 25) 15 else 8
        canvas.blit(BOLT, size / 2 - 1, boltY, boltPulse(nowMs))
        return canvas.copyOut()
    }
}
