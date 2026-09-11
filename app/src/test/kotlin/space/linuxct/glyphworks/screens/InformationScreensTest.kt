package space.linuxct.glyphworks.screens

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii
import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.weather.*
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask
import java.io.File

class InformationScreensTest {
    private fun weather(condition: WeatherCondition = WeatherCondition.CLEAR, temp: Double = 24.0, day: Boolean = true) =
        WeatherSnapshot(WeatherStatus.READY, temp, condition, day)

    private fun frames(size: Int): List<Pair<String, IntArray>> = buildList {
        for (count in listOf<Int?>(null) + (0..10)) {
            add("notifications_${size}_${count ?: "unavailable"}" to NotificationsScreen.renderFrame(size, count))
            for (style in NotificationPrefs.styles.filter { it != NotificationPrefs.TEXT }) {
                add("notifications_${size}_${style}_${count ?: "unavailable"}" to
                    NotificationsRenderer.renderFrame(size, count, style, 4000))
            }
        }
        for (time in listOf(0L, 3000L, 3500L, 3950L, 4000L, 7000L, 7500L, 7950L, 8000L)) {
            add("notifications_${size}_bell_phase_$time" to
                NotificationsRenderer.renderFrame(size, 9, NotificationPrefs.BELL, time))
        }
        WeatherCondition.entries.forEach { condition ->
            add("weather_${size}_${condition.name.lowercase()}" to WeatherRenderer.renderFrame(size, weather(condition), 0))
        }
        add("weather_${size}_night" to WeatherRenderer.renderFrame(size, weather(day = false), 0))
        add("weather_${size}_partly_cloudy_night" to
            WeatherRenderer.renderFrame(size, weather(WeatherCondition.PARTLY_CLOUDY, day = false), 0))
        for (temp in listOf(-89.0, -12.0, -1.0, 0.0, 24.0, 40.0)) {
            for (fahrenheit in listOf(false, true)) {
                add("weather_${size}_temp_${temp.toInt()}_${if (fahrenheit) "f" else "c"}" to
                    WeatherRenderer.renderFrame(size, weather(temp = temp), 4000, fahrenheit))
            }
        }
        for (time in listOf(3000L, 3500L, 3950L, 4000L, 7000L, 7500L, 7950L, 8000L)) {
            add("weather_${size}_phase_$time" to WeatherRenderer.renderFrame(size, weather(), time))
        }
        add("weather_${size}_loading" to WeatherRenderer.renderFrame(size, WeatherSnapshot(WeatherStatus.LOADING), 0))
        add("weather_${size}_unavailable" to WeatherRenderer.renderFrame(size, WeatherSnapshot(WeatherStatus.UNAVAILABLE), 0))
        add("weather_${size}_stale" to WeatherRenderer.renderFrame(size, weather().copy(status = WeatherStatus.STALE), 4000))
    }

    @Test fun `Phone 4a Pro designs and animation goldens`() = checkAndExport(13)
    @Test fun `Phone 3 designs and animation goldens`() = checkAndExport(25)

    private fun checkAndExport(size: Int) {
        val cases = frames(size)
        cases.forEach { (name, frame) ->
            GoldenAscii.check(name, frame, size)
            frame.forEachIndexed { i, v ->
                if (!PanelMask.contains(i % size, i / size, size)) assertEquals("$name cell $i", 0, v)
            }
        }
        val dir = File("build/reports/information-toys").apply { mkdirs() }
        writePreviews(dir, size, cases)
    }

    /** SVG review artifacts use the tested cells/mask and need no desktop or Android graphics. */
    private fun panelSvg(size: Int, frame: IntArray, side: Int): String = buildString {
        val pitch = (side - 20).toDouble() / size
        val dot = pitch * 0.74
        for (y in 0 until size) for (x in 0 until size) {
            if (!PanelMask.contains(x, y, size)) continue
            val value = frame[y * size + x]
            val shade = if (value == 0) 24 else (255 * value / 4095).coerceAtLeast(45)
            val left = 10 + (x + 0.5) * pitch - dot / 2
            val top = 10 + (y + 0.5) * pitch - dot / 2
            append("""<rect x="$left" y="$top" width="$dot" height="$dot" rx="${dot * 0.15}" fill="rgb($shade,$shade,$shade)"/>""")
        }
    }

    private fun writePreviews(dir: File, size: Int, cases: List<Pair<String, IntArray>>) {
        fun sheet(filename: String, entries: List<Pair<String, IntArray>>, columns: Int = 6) {
            val height = 230 * ((entries.size + columns - 1) / columns)
            val svg = buildString {
                append("""<svg xmlns="http://www.w3.org/2000/svg" width="${210 * columns}" height="$height"><rect width="100%" height="100%" fill="#101010"/>""")
                entries.forEachIndexed { i, (name, frame) ->
                    val x = (i % columns) * 210
                    val y = (i / columns) * 230
                    val label = name.replace("_${size}_", " ").replace('_', ' ')
                    append("""<g transform="translate(${x + 10},${y + 5})"><rect width="190" height="190" fill="black"/>""")
                    append(panelSvg(size, frame, 190))
                    append("""</g><text x="${x + 8}" y="${y + 211}" font-family="sans-serif" font-size="12" fill="white">$label</text>""")
                }
                append("</svg>")
            }
            File(dir, filename).writeText(svg)
        }
        sheet("goldens_$size.svg", cases)
        sheet("notification_envelopes_$size.svg", cases.filter {
            it.first.startsWith("notifications_${size}_envelope_")
        })
        val weatherExamples = listOf(
            "Clear · day" to "clear", "Clear · night" to "night",
            "Partly cloudy · day" to "partly_cloudy", "Partly cloudy · night" to "partly_cloudy_night",
            "Cloudy" to "cloudy", "Fog" to "fog", "Drizzle" to "drizzle", "Rain" to "rain",
            "Snow" to "snow", "Thunderstorm" to "thunderstorm", "Loading" to "loading",
            "Unavailable / no access" to "unavailable", "Stale data · bottom dot" to "stale",
            "Temperature · −12°C" to "temp_-12_c", "Temperature · 75°F" to "temp_24_f",
        )
        sheet("weather_icons_$size.svg", weatherExamples.map { (label, suffix) ->
            label to cases.first { it.first == "weather_${size}_$suffix" }.second
        }, columns = 5)
        val examples = listOf("envelope_9", "envelope_10", "9", "10", "dot_9", "dot_10",
            "bell_phase_0", "bell_phase_3500", "bell_9", "bell_10", "bell_phase_7500", "bell_phase_8000")
        sheet("notification_styles_$size.svg", examples.map { suffix ->
            cases.first { it.first == "notifications_${size}_$suffix" }
        })
        fun animation(filename: String, render: (Long) -> IntArray) {
            val svg = buildString {
                append("""<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="black"/>
                    <style>@keyframes glyphFrame {0%,0.6249%{opacity:1}0.625%,100%{opacity:0}}.frame{opacity:0;animation:glyphFrame 8s step-end infinite}</style>""")
                for (time in 0L until 8000L step 50) {
                    val delay = if (time == 0L) 0L else time - 8000L
                    append("""<g class="frame" style="animation-delay:${delay}ms">""")
                    append(panelSvg(size, render(time), 400))
                    append("</g>")
                }
                append("</svg>")
            }
            File(dir, filename).writeText(svg)
        }
        animation("weather_$size.svg") { WeatherRenderer.renderFrame(size, weather(), it) }
        animation("notifications_$size.svg") {
            NotificationsRenderer.renderFrame(size, 9, NotificationPrefs.BELL, it)
        }
    }

    @Test fun `both panels hold and travel left at exact boundaries`() {
        for (size in listOf(13, 25)) {
            val state = weather()
            fun frame(t: Long) = WeatherRenderer.renderFrame(size, state, t)
            assertArrayEquals(frame(0), frame(2999))
            assertArrayEquals(frame(0), frame(3000))
            assertArrayEquals(frame(4000), frame(6999))
            assertArrayEquals(frame(4000), frame(7000))
            assertArrayEquals(frame(0), frame(8000))
            assertFalse(frame(0).contentEquals(frame(3500)))
            assertFalse(frame(3500).contentEquals(frame(7500)))
            assertArrayEquals(frame(0), frame(-100))
        }
    }

    @Test fun `every envelope count retains all its glyph pixels inside the circular panel`() {
        val counts = (0..9).map { it to it.toString() } + listOf(10 to "9+", null to "?")
        for (size in listOf(13, 25)) {
            val scale = if (size == 25) 2 else 1
            for ((count, text) in counts) {
                // Measure complete glyphs independently of their placement or the panel mask.
                val expectedPixels = text.sumOf { char ->
                    val glyph = MatrixCanvas(5)
                    Font3x5.draw(glyph, char, 0, 0, MAX_BRIGHTNESS)
                    glyph.copyOut().count { it > 0 } * scale * scale
                }
                val frame = NotificationsRenderer.renderFrame(size, count, NotificationPrefs.ENVELOPE)
                val countAreaStart = if (size == 25) 12 else 7
                val actualPixels = frame.drop(countAreaStart * size).count { it > 0 }
                assertEquals("$size envelope count $text must not be clipped", expectedPixels, actualPixels)
            }
        }
    }

    @Test fun `cloud and lightning have exactly one empty pixel row between them`() {
        for (size in listOf(13, 25)) {
            val frame = WeatherRenderer.renderFrame(size, weather(WeatherCondition.THUNDERSTORM), 0)
            val occupiedRows = frame.toList().chunked(size).map { row -> row.any { it > 0 } }
            val cloudBottom = (occupiedRows.indexOfFirst { it } until size).first { !occupiedRows[it] } - 1
            val lightningTop = (cloudBottom + 1 until size).first { occupiedRows[it] }
            assertEquals("$size cloud/lightning gap", 1, lightningTop - cloudBottom - 1)
        }
    }

    @Test fun `partly cloudy foreground covers the brighter sun and moon pixels`() {
        for (size in listOf(13, 25)) {
            val cloud = WeatherRenderer.renderFrame(size, weather(WeatherCondition.CLOUDY), 0)
            for (day in listOf(true, false)) {
                val partlyCloudy = WeatherRenderer.renderFrame(size, weather(WeatherCondition.PARTLY_CLOUDY, day = day), 0)
                val lowerRows = partlyCloudy.indexOfLast { it > 0 } / size - cloud.indexOfLast { it > 0 } / size
                cloud.forEachIndexed { index, value ->
                    if (value > 0) assertEquals("$size cloud cell $index (day=$day)",
                        value, partlyCloudy[index + lowerRows * size])
                }
            }
        }
    }

    @Test fun `unavailable is different from zero and overflow is visible`() {
        for (size in listOf(13, 25)) {
            for (style in NotificationPrefs.styles) {
                fun frame(count: Int?) = NotificationsRenderer.renderFrame(size, count, style, 4000)
                assertFalse(frame(null).contentEquals(frame(0)))
                assertFalse(frame(9).contentEquals(frame(10)))
                assertArrayEquals(frame(10), frame(999))
                assertArrayEquals(frame(0), frame(-1))
            }
            assertArrayEquals(NotificationsScreen.renderFrame(size, 4),
                NotificationsRenderer.renderFrame(size, 4, "unknown"))
        }
    }

    @Test fun `notification bell holds then moves left and fits both circular panels throughout`() {
        for (size in listOf(13, 25)) {
            fun frame(time: Long) = NotificationsRenderer.renderFrame(size, 9, NotificationPrefs.BELL, time)
            assertArrayEquals(frame(0), frame(2999))
            assertArrayEquals(frame(0), frame(3000))
            assertArrayEquals(frame(4000), frame(6999))
            assertArrayEquals(frame(4000), frame(7000))
            assertArrayEquals(frame(0), frame(8000))
            assertArrayEquals(frame(0), frame(-100))
            assertFalse(frame(0).contentEquals(frame(3500)))
            assertFalse(frame(3500).contentEquals(frame(7500)))
            for (time in 0L until 8000L step 50) {
                val current = frame(time)
                GoldenAscii.assertFrameValid(current, size)
                current.forEachIndexed { i, value ->
                    if (!PanelMask.contains(i % size, i / size, size)) assertEquals(0, value)
                }
            }
        }
    }
}
