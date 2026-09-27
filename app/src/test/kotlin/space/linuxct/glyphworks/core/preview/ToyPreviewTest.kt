package space.linuxct.glyphworks.core.preview

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.core.weather.WeatherCondition
import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.screens.*

class ToyPreviewTest {
    private fun masked(frame: IntArray, size: Int) = IntArray(frame.size) { index ->
        if (PanelMask.contains(index % size, index / size, size)) frame[index] else 0
    }

    @Test fun `every registered toy has a deterministic valid preview on both panels`() {
        for (size in listOf(13, 25)) for (id in ScreenRegistry.create().map { it.id }) {
            ToyPreview(size, FakePrefs()).use { first ->
                ToyPreview(size, FakePrefs()).use { second ->
                    first.select(id)
                    second.select(id)
                    var everLit = false
                    for (delta in listOf(0L, 125L, 500L, 1_200L, 2_500L, 4_500L)) {
                        first.advance(delta)
                        second.advance(delta)
                        val frame = first.frame
                        assertArrayEquals("$size $id must be deterministic", frame, second.frame)
                        assertEquals(size * size, frame.size)
                        frame.forEachIndexed { index, value ->
                            assertTrue("$size $id brightness", value in 0..MAX_BRIGHTNESS)
                            if (!PanelMask.contains(index % size, index / size, size)) assertEquals(0, value)
                        }
                        everLit = everLit || frame.any { it > 0 }
                    }
                    assertTrue("$size $id must show its real artwork", everLit)
                }
            }
        }
    }

    @Test fun `all demonstrations leave caller preferences and listeners untouched`() {
        val backing = FakePrefs().apply {
            putInt(PrefKeys.COUNTER, 512)
            putLong(PrefKeys.TIMER_START, 12_345L)
            putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 400L)
            putLong(PrefKeys.TIMER_CHIMED_FOR, 12_345L)
            putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications,weather")
            putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
        }
        val before = backing.map.toMap()
        var changes = 0
        backing.addChangeListener { changes++ }
        val readOnly = object : Prefs by backing {
            override fun putBoolean(key: String, v: Boolean): Unit = error("Preview wrote $key")
            override fun putInt(key: String, v: Int): Unit = error("Preview wrote $key")
            override fun putLong(key: String, v: Long): Unit = error("Preview wrote $key")
            override fun putFloat(key: String, v: Float): Unit = error("Preview wrote $key")
            override fun putString(key: String, v: String): Unit = error("Preview wrote $key")
            override fun remove(key: String): Unit = error("Preview removed $key")
            override fun addChangeListener(listener: (String) -> Unit): Unit = error("Preview retained a real listener")
            override fun removeChangeListener(listener: (String) -> Unit): Unit = error("Preview removed a real listener")
        }
        for (size in listOf(13, 25)) ToyPreview(size, readOnly).use { preview ->
            for (id in ScreenRegistry.create().map { it.id }) {
                ToyPreview.thumbnail(id, size, readOnly)
                preview.select(id)
                repeat(5) { preview.interact(); preview.advance(1_000) }
            }
        }
        assertEquals(before, backing.map)
        assertEquals(0, changes)
    }

    @Test fun `card thumbnails show the requested stable poses on both panels`() {
        for (size in listOf(13, 25)) {
            val prefs = FakePrefs().apply { putString(PrefKeys.SELECTED_DICE, "D20") }
            val expected = mapOf(
                "bottle" to BottleScreen.renderIdle(size),
                "dice" to DiceScreen.renderFace(size, face = 6, sides = 6),
                "eyes" to EyesScreen.renderFrame(size, pupilX = -1f, pupilY = -1f, blinkPhase = -1),
            )
            for ((id, frame) in expected) {
                assertArrayEquals("$size $id", masked(frame, size), ToyPreview.thumbnail(id, size, prefs))
            }
            for (design in listOf(CoinScreen.DESIGN_LETTERS, CoinScreen.DESIGN_ART)) {
                prefs.putInt(PrefKeys.COIN_DESIGN, design)
                assertArrayEquals("$size heads design $design",
                    masked(CoinScreen.renderResult(size, heads = true, design), size),
                    ToyPreview.thumbnail("coin", size, prefs))
            }
            assertFalse("$size pupils must look away from centre",
                masked(EyesScreen.renderFrame(size, 0f, 0f, -1), size)
                    .contentEquals(ToyPreview.thumbnail("eyes", size, prefs)))
        }
    }

    @Test fun `every card thumbnail is valid deterministic and independently owned`() {
        for (size in listOf(13, 25)) for (id in ScreenRegistry.create().map { it.id }) {
            val prefs = FakePrefs()
            val original = ToyPreview.thumbnail(id, size, prefs)
            assertEquals(size * size, original.size)
            assertTrue(original.any { it > 0 })
            original.forEachIndexed { index, value ->
                assertTrue("$size $id brightness", value in 0..MAX_BRIGHTNESS)
                if (!PanelMask.contains(index % size, index / size, size)) assertEquals(0, value)
            }
            val other = ToyPreview.thumbnail(id, size, prefs)
            assertArrayEquals(original, other)
            other.fill(0)
            assertArrayEquals(original, ToyPreview.thumbnail(id, size, prefs))
        }
    }

    @Test fun `Bottle live preview settles perfectly right on every repeat and after an interrupted spin`() {
        for (size in listOf(13, 25)) ToyPreview(size, FakePrefs()).use { preview ->
            val right = masked(BottleScreen.renderPointer(size, 90f), size)
            preview.select("bottle")
            preview.advance(1_000)
            assertFalse("$size Bottle must visibly spin", right.contentEquals(preview.frame))
            preview.interact()
            preview.advance(5_400)
            assertArrayEquals("$size retriggered spin must point right", right, preview.frame)
            repeat(3) {
                preview.advance(BottleScreen.SPIN_MS + 3_500)
                assertArrayEquals("$size repeated spin must point right", right, preview.frame)
            }
        }
    }

    @Test fun `virtual time is independent of caller frame cadence`() {
        for (id in listOf("dice", "coin", "bottle", "eyes", "dino", "timer", "breathing", "ambient")) {
            val prefs = FakePrefs().apply { putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications,weather") }
            ToyPreview(13, prefs).use { slow ->
                ToyPreview(13, prefs).use { fast ->
                    slow.select(id)
                    fast.select(id)
                    slow.advance(9_999)
                    repeat(303) { fast.advance(33) }
                    assertArrayEquals(id, slow.frame, fast.frame)
                }
            }
        }
    }

    @Test fun `Dino runs and jumps within the first seconds on both panel sizes`() {
        for (size in listOf(13, 25)) ToyPreview(size, FakePrefs()).use { preview ->
            preview.select("dino")
            assertFalse(masked(DinoScreen.renderIdle(size), size).contentEquals(preview.frame))
            val poses = mutableSetOf<List<Int>>()
            val topRows = mutableSetOf<Int>()
            repeat(40) {
                preview.advance(100)
                val frame = preview.frame
                poses += frame.toList()
                topRows += (0 until size).first { y ->
                    (DinoScreen.charX(size) until DinoScreen.charX(size) + DinoScreen.charW(size)).any { x ->
                        frame[y * size + x] == MAX_BRIGHTNESS
                    }
                }
            }
            assertTrue("$size dino strides and ground must move", poses.size > 10)
            assertTrue("$size dino must visibly leave the ground", topRows.max() - topRows.min() >= 3)
        }
    }

    @Test fun `Ambient cycles only the configured backgrounds and preserves a single or empty selection`() {
        for (size in listOf(13, 25)) {
            val prefs = FakePrefs().apply { putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications,weather") }
            ToyPreview(size, prefs).use { preview ->
                preview.select("ambient")
                assertArrayEquals(NotificationsRenderer.renderFrame(size, 7), preview.frame)
                preview.advance(ToyPreview.AMBIENT_CYCLE_MS)
                val weather = WeatherSnapshot(WeatherStatus.READY, 24.0, WeatherCondition.PARTLY_CLOUDY)
                assertArrayEquals(WeatherRenderer.renderFrame(size, weather, 0), preview.frame)
                preview.advance(ToyPreview.AMBIENT_CYCLE_MS)
                assertArrayEquals(NotificationsRenderer.renderFrame(size, 10), preview.frame)

                prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "weather")
                preview.select("ambient")
                preview.advance(ToyPreview.AMBIENT_CYCLE_MS)
                assertArrayEquals(WeatherRenderer.renderFrame(size, weather, 4_000), preview.frame)

                prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "")
                preview.select("ambient")
                preview.advance(12_000)
                assertTrue(preview.frame.all { it == 0 })
            }
        }
    }

    @Test fun `real presentation preferences remain readable and update without writes`() {
        for (size in listOf(13, 25)) {
            val prefs = FakePrefs()
            ToyPreview(size, prefs).use { preview ->
                preview.select("notifications")
                prefs.putString(NotificationPrefs.STYLE, NotificationPrefs.ENVELOPE)
                preview.advance(500)
                assertArrayEquals(NotificationsRenderer.renderFrame(size, 7, NotificationPrefs.ENVELOPE), preview.frame)

                preview.select("weather")
                prefs.putString(WeatherPrefs.ICON_STYLE, WeatherPrefs.NOTHING_INSPIRED)
                prefs.putString(WeatherPrefs.UNIT, WeatherPrefs.FAHRENHEIT)
                preview.advance(4_000)
                val weather = WeatherSnapshot(WeatherStatus.READY, 24.0, WeatherCondition.PARTLY_CLOUDY)
                assertArrayEquals(WeatherRenderer.renderFrame(size, weather, 4_000, true, WeatherPrefs.NOTHING_INSPIRED), preview.frame)
            }
        }
    }

    @Test fun `custom animation respects frame durations and snapshots mutable input collections`() {
        val frames = mutableListOf(
            DesignFrame(100, "1".repeat(169)),
            DesignFrame(300, "2".repeat(169)),
            DesignFrame(200, "0".repeat(169)),
        )
        val levels = mutableListOf(0, 1_000, MAX_BRIGHTNESS)
        val variants = mutableMapOf("bellsprout" to DesignVariant(frames))
        val design = Design(kind = DesignKind.DYNAMIC, loop = true, levels = levels, variants = variants)
        ToyPreview(13, FakePrefs(), design).use { preview ->
            levels[1] = 0
            frames.clear()
            variants.clear()
            preview.select("custom")
            assertEquals(1_000, preview.frame[84])
            preview.advance(99)
            assertEquals(1_000, preview.frame[84])
            preview.advance(1)
            assertEquals(MAX_BRIGHTNESS, preview.frame[84])
            preview.advance(299)
            assertEquals(MAX_BRIGHTNESS, preview.frame[84])
            preview.advance(1)
            assertEquals(0, preview.frame[84])
            preview.advance(200)
            assertEquals(1_000, preview.frame[84])
        }
    }

    @Test fun `switch and close cancel prior callbacks and returned frames cannot mutate the preview`() {
        ToyPreview(13, FakePrefs()).use { preview ->
            preview.select("dice")
            preview.advance(550)
            preview.select("custom")
            val expected = masked(CustomScreen.renderPlaceholder(13), 13)
            preview.advance(10_000)
            assertArrayEquals(expected, preview.frame)
            preview.frame.fill(0)
            assertArrayEquals(expected, preview.frame)
            preview.close()
            preview.advance(10_000)
            preview.interact()
            assertNull(preview.selectedId)
            assertTrue(preview.frame.all { it == 0 })
        }
    }
}
