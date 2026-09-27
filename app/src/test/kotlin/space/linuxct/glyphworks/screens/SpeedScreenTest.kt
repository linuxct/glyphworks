package space.linuxct.glyphworks.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.screens.ambient.BackgroundRenderers
import java.io.File

class SpeedScreenTest {
    private val examples = linkedMapOf(
        "0k" to 0L, "7k" to 7_000L, "45k" to 45_000L, "99k" to 99_999L,
        "0_1m" to 100_000L, "2_3m" to 2_340_000L, "9_9m" to 9_999_999L,
        "15m" to 15_000_000L, "99m" to 250_000_000L,
    )

    @Test fun `format keeps decimal SI units and handles boundaries`() {
        val expected = listOf("0K", "7K", "45K", "99K", "0.1M", "2.3M", "9.9M", "15M", "99M")
        assertEquals(expected, examples.values.map(SpeedScreen::formatSpeed))
        assertEquals("0K", SpeedScreen.formatSpeed(-1_000))
        assertEquals("0K", SpeedScreen.formatSpeed(Long.MIN_VALUE))
        assertEquals("0.9M", SpeedScreen.formatSpeed(999_999))
        assertEquals("1.0M", SpeedScreen.formatSpeed(1_000_000))
        assertEquals("10M", SpeedScreen.formatSpeed(10_000_000))
        assertEquals("99M", SpeedScreen.formatSpeed(Long.MAX_VALUE))
    }

    @Test fun `render goldens for both panels and export a local review sheet`() {
        for (size in listOf(13, 25)) for ((name, speed) in examples) {
            GoldenAscii.check("speed_${size}_$name", SpeedScreen.renderFrame(size, speed), size)
        }
        writeReviewSheet()
    }

    @Test fun `every possible formatted value preserves complete digits unit and arrow inside the circle`() {
        val speeds = (0L..99L).map { it * 1_000 } +
            (1L..99L).map { it * 100_000 } + (10L..99L).map { it * 1_000_000 }
        for (size in listOf(13, 25)) for (speed in speeds) {
            val text = SpeedScreen.formatSpeed(speed)
            val frame = SpeedScreen.renderFrame(size, speed)
            GoldenAscii.assertFrameValid(frame, size)
            // Check the unmasked drawing: applying a mask must never remove any artwork.
            frame.forEachIndexed { i, value ->
                if (value > 0) assertTrue("$size $text clips at (${i % size}, ${i / size})",
                    PanelMask.contains(i % size, i / size, size))
            }
            val scale = if (size == 25) 2 else 1
            val numberPixels = text.dropLast(1).sumOf(::glyphPixels) * scale * scale
            assertEquals("$size $text loses number pixels", numberPixels, frame.count { it == MAX_BRIGHTNESS })
            assertEquals("$size $text loses unit pixels", glyphPixels(text.last()), frame.count { it == 2600 })
            assertEquals("$size $text needs an eleven-pixel arrow", 11, frame.count { it == 2200 })
        }
    }

    @Test fun `down arrow has a filled head with two stem pixels`() {
        for (size in listOf(13, 25)) {
            val frame = SpeedScreen.renderFrame(size, 45_000)
            val pixels = frame.indices.filter { frame[it] == 2200 }.map { it % size to it / size }
            val left = pixels.minOf { it.first }
            val top = pixels.minOf { it.second }
            assertEquals(setOf(2 to 0, 2 to 1, 0 to 2, 1 to 2, 2 to 2, 3 to 2, 4 to 2,
                1 to 3, 2 to 3, 3 to 3, 2 to 4),
                pixels.map { (x, y) -> x - left to y - top }.toSet())
        }
    }

    @Test fun `standalone and Ambient use the same layout as speed crosses units`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            val screen = SpeedScreen()
            val background = BackgroundRenderers.create(AmbientBackgrounds.SPEED)
            h.speed.total = 10_000_000L
            screen.onActivate(h.context)
            background.onShow(h.context, h.clock.elapsed)
            assertArrayEquals(SpeedScreen.renderFrame(size, 0), h.lastFrame())
            for (speed in listOf(45_000L, 2_340_000L, 9_999_999L, 15_000_000L, 0L)) {
                h.speed.total += speed
                h.scheduler.tick()
                assertArrayEquals(SpeedScreen.renderFrame(size, speed), h.lastFrame())
                assertArrayEquals(h.lastFrame(), background.render(h.context, h.clock.now))
            }
            // A reset RX counter is an idle sample, rather than a negative number.
            h.speed.total = 0
            h.scheduler.tick()
            assertArrayEquals(SpeedScreen.renderFrame(size, 0), h.lastFrame())
            assertArrayEquals(h.lastFrame(), background.render(h.context, h.clock.now))
            screen.onDeactivate()
        }
    }

    private fun glyphPixels(char: Char): Int {
        val glyph = MatrixCanvas(5)
        Font3x5.draw(glyph, char, 0, 0, MAX_BRIGHTNESS)
        return glyph.copyOut().count { it > 0 }
    }

    /** Review artifacts use the production renderer and measured panel mask, just like the goldens. */
    private fun writeReviewSheet() {
        val dir = File("build/reports/speed-toy").apply { mkdirs() }
        for (size in listOf(13, 25)) {
            val cell = 280
            val row = 290
            val svg = buildString {
                append("""<svg xmlns="http://www.w3.org/2000/svg" width="840" height="910"><rect width="100%" height="100%" fill="#101010"/>""")
                val device = if (size == 13) "FroggerPro · 13 × 13" else "Metroid · 25 × 25"
                append("""<text x="15" y="27" font-family="sans-serif" font-size="20" fill="white">$device</text>""")
                examples.values.forEachIndexed { index, speed ->
                    val frame = SpeedScreen.renderFrame(size, speed)
                    val left = index % 3 * cell + 15
                    val top = index / 3 * row + 40
                    val pitch = 230.0 / size
                    val dot = pitch * 0.74
                    append("""<g transform="translate($left,$top)"><rect width="250" height="250" fill="black"/>""")
                    for (y in 0 until size) for (x in 0 until size) {
                        if (!PanelMask.contains(x, y, size)) continue
                        val v = frame[y * size + x]
                        val shade = if (v == 0) 24 else (255 * v / MAX_BRIGHTNESS).coerceAtLeast(45)
                        val px = 10 + (x + 0.5) * pitch - dot / 2
                        val py = 10 + (y + 0.5) * pitch - dot / 2
                        append("""<rect x="$px" y="$py" width="$dot" height="$dot" rx="${dot * 0.15}" fill="rgb($shade,$shade,$shade)"/>""")
                    }
                    val text = SpeedScreen.formatSpeed(speed)
                    val label = "${text.dropLast(1)} ${if (text.last() == 'K') "kB/s" else "MB/s"}"
                    append("""</g><text x="$left" y="${top + 275}" font-family="sans-serif" font-size="18" fill="white">$label</text>""")
                }
                append("</svg>")
            }
            File(dir, "download-speed-$size.svg").writeText(svg)
        }
    }
}
