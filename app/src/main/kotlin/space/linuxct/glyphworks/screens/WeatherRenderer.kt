package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.weather.WeatherCondition
import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt

/** The two panels move left along one repeating strip; the incoming panel starts on the right. */
object WeatherRenderer {
    const val HOLD_MS = 3_000L
    const val SLIDE_MS = 1_000L
    const val LOOP_MS = 2 * (HOLD_MS + SLIDE_MS)

    fun renderFrame(
        size: Int,
        snapshot: WeatherSnapshot,
        elapsedMs: Long,
        fahrenheit: Boolean = false,
        iconStyle: String = WeatherPrefs.ORIGINAL,
    ): IntArray {
        val c = MatrixCanvas(size)
        val usable = snapshot.status == WeatherStatus.READY || snapshot.status == WeatherStatus.STALE
        if (!usable || snapshot.condition == null || snapshot.temperatureC?.isFinite() != true) {
            if (iconStyle == WeatherPrefs.NOTHING_INSPIRED) WeatherGlyphs.drawStatusCloud(c)
            else cloud(c, 0)
            val mark = if (snapshot.status == WeatherStatus.LOADING) "..." else "?"
            InformationDrawing.text(c, mark, if (size >= 25) 14 else 8, if (size >= 25) 2 else 1)
            return InformationDrawing.mask(c)
        }
        val phase = elapsedMs.coerceAtLeast(0) % LOOP_MS
        when {
            phase < HOLD_MS -> icon(c, snapshot, 0, iconStyle)
            phase < HOLD_MS + SLIDE_MS -> {
                val shift = ((phase - HOLD_MS) * size / SLIDE_MS).toInt()
                icon(c, snapshot, -shift, iconStyle)
                temperature(c, snapshot.temperatureC, fahrenheit, size - shift)
            }
            phase < 2 * HOLD_MS + SLIDE_MS -> temperature(c, snapshot.temperatureC, fahrenheit, 0)
            else -> {
                val shift = ((phase - 2 * HOLD_MS - SLIDE_MS) * size / SLIDE_MS).toInt()
                temperature(c, snapshot.temperatureC, fahrenheit, -shift)
                icon(c, snapshot, size - shift, iconStyle)
            }
        }
        if (snapshot.status == WeatherStatus.STALE) c.set(size / 2, size - 1, 1800)
        return InformationDrawing.mask(c)
    }

    private fun sprite(c: MatrixCanvas, rows: List<String>, x: Int, y: Int, shift: Int = 0, shiftY: Int = 0) {
        rows.forEachIndexed { yy, row -> row.forEachIndexed { xx, cell ->
            val v = when (cell) { '#' -> MAX_BRIGHTNESS; '+' -> 2200; '.' -> 700; else -> 0 }
            if (v > 0) dot(c, x + xx, y + yy, shift, v, shiftY)
        } }
    }

    private fun dot(c: MatrixCanvas, x: Int, y: Int, shift: Int, value: Int = MAX_BRIGHTNESS, shiftY: Int = 0) {
        val scale = if (c.size >= 25) 2 else 1
        val offset = if (scale == 2) -1 else 0
        // The foreground cloud must cover the sun/moon, including their brighter pixels.
        val left = x * scale + offset + shift
        val top = y * scale + offset + shiftY
        for (dy in 0 until scale) for (dx in 0 until scale) c.set(left + dx, top + dy, value)
    }

    private fun cloud(c: MatrixCanvas, shift: Int, top: Int = 2) = sprite(c, listOf(
        "   ###   ",
        " ##...#  ",
        "#......##",
        "#.......#",
        " ####### ",
    ), 2, top, shift)

    private fun sun(c: MatrixCanvas, shift: Int) = sprite(c, listOf(
        "    #    ",
        " #     # ",
        "   ###   ",
        "  #####  ",
        "# ##### #",
        "  #####  ",
        "   ###   ",
        " #     # ",
        "    #    ",
    ), 2, 2, shift)

    private fun moon(c: MatrixCanvas, shift: Int) {
        // Four dots form the same star as Nothing's clear-night artwork, inside the crescent.
        sprite(c, listOf(" # ", "# #", " # "), 8, 2, shift)
        if (c.size < 25) {
            // Hand-tuned horns and a rounded bowl keep the broad crescent legible on 13×13.
            sprite(c, listOf(
                "   ##      ",
                "  ###      ",
                " ###       ",
                " ##        ",
                "###        ",
                "###        ",
                "####      #",
                " #####   ##",
                " ######### ",
                "  #######  ",
                "    ###    ",
            ), 1, 1, shift)
            return
        }
        // Draw the broader diagonal crescent at native resolution on the larger panel.
        val center = (c.size - 1) / 2f
        val radius = (c.size - 4) / 2f
        val radiusSquared = radius * radius
        val cutoutX = center + radius * 0.5f
        val cutoutY = center - radius * 0.5f
        for (y in 0 until c.size) for (x in 0 until c.size) {
            val dx = x - center
            val dy = y - center
            val cutDx = x - cutoutX
            val cutDy = y - cutoutY
            if (dx * dx + dy * dy <= radiusSquared && cutDx * cutDx + cutDy * cutDy > radiusSquared) {
                c.set(x + shift, y, MAX_BRIGHTNESS)
            }
        }
    }

    private fun icon(c: MatrixCanvas, snapshot: WeatherSnapshot, shift: Int, iconStyle: String) {
        if (iconStyle == WeatherPrefs.NOTHING_INSPIRED) {
            snapshot.condition?.let { WeatherGlyphs.draw(c, it, snapshot.isDay, shift) }
            return
        }
        when (snapshot.condition) {
            WeatherCondition.CLEAR -> if (snapshot.isDay) sun(c, shift) else moon(c, shift)
            WeatherCondition.CLOUDY -> cloud(c, shift, top = 4)
            WeatherCondition.PARTLY_CLOUDY -> {
                if (snapshot.isDay) {
                    sprite(c, listOf(
                        "   #   ",
                        " #   # ",
                        "  ###  ",
                        "# ### #",
                        "  ###  ",
                        " #   # ",
                        "   #   ",
                    ), 5, 2, shift)
                } else {
                    sprite(c, listOf("  ## ", " ##  ", " ##  ", "  ###"), 6, 3, shift)
                }
                cloud(c, shift, top = 5)
            }
            else -> {
                cloud(c, shift)
                when (snapshot.condition) {
                    WeatherCondition.FOG -> {
                        for (x in 2..8) dot(c, x, 8, shift, 2200)
                        for (x in 4..10) dot(c, x, 10, shift)
                    }
                    WeatherCondition.DRIZZLE -> for (x in listOf(5, 8)) {
                        dot(c, x, 8, shift, 2200)
                        dot(c, x - 1, 9, shift, 2200)
                    }
                    WeatherCondition.RAIN -> sprite(c, listOf(
                        "  + # + #",
                        " + # + # ",
                        "+ # + #  ",
                    ), 2, 8, shift)
                    WeatherCondition.SNOW -> sprite(c, listOf("# # #", " ### ", "# # #"), 4, 8, shift)
                    // One physical row separates the cloud and bolt on either panel.
                    WeatherCondition.THUNDERSTORM -> sprite(c,
                        listOf("  ## ", " ##  ", "#### ", "  #  ", " #   "), 4, 8, shift,
                        shiftY = if (c.size >= 25) -1 else 0)
                    else -> Unit
                }
            }
        }
    }

    private fun temperature(c: MatrixCanvas, celsius: Double, fahrenheit: Boolean, shift: Int) {
        val value = (if (fahrenheit) celsius * 9.0 / 5.0 + 32 else celsius).roundToInt()
        val text = value.toString()
        // Keep the number above the units, with both rows inside the circular panel.
        sprite(c, if (fahrenheit) listOf("## ###", "## ## ", "   #  ")
            else listOf("## ###", "## #  ", "   ###"), 4, 9, shift)
        if (c.size >= 25) {
            val scale = if (text.length <= 3) 2 else 1
            InformationDrawing.text(c, text, if (scale == 2) 5 else 7, scale, shift)
        } else if (Font3x5.stringWidth(text) <= c.size) {
            InformationDrawing.text(c, text, 2, 1, shift)
        } else {
            // Extreme negative Fahrenheit values keep their sign above the digits.
            c.line(5 + shift, 0, 7 + shift, 0, MAX_BRIGHTNESS)
            val magnitude = value.toLong().let { kotlin.math.abs(it).coerceAtMost(999) }.toString()
            Font3x5.drawString(c, magnitude, (c.size - Font3x5.stringWidth(magnitude)) / 2 + shift, 2, MAX_BRIGHTNESS)
        }
    }
}
