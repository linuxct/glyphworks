package space.linuxct.glyphworks.screens.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.NotificationPort
import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.Ports
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.WeatherPort
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.weather.WeatherCondition
import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus
import space.linuxct.glyphworks.screens.NotificationsScreen
import space.linuxct.glyphworks.screens.NotificationsRenderer
import space.linuxct.glyphworks.screens.WeatherRenderer

class InformationAmbientBackgroundsTest {
    private class WeatherProbe : WeatherPort {
        var value = WeatherSnapshot(WeatherStatus.READY, 21.0, WeatherCondition.CLEAR)
        val demand = mutableListOf<Boolean>()
        override fun snapshot() = value
        override fun setActive(active: Boolean) { demand += active }
    }

    private fun context(h: TestHarness, weather: WeatherProbe, count: () -> Int? = { null }): ScreenContext {
        val p = h.ports
        val ports = Ports(
            p.clock, p.random, p.battery, p.speed, p.spectrum, p.azimuth, p.shake,
            p.tilt, p.incline, p.light, p.connectivity, p.location, p.timer, p.design,
            NotificationPort(count), weather,
        )
        return ScreenContext(h.size, h.prefs, ports, h.scheduler) { h.frames += it }
    }

    @Test
    fun `notification background shares renderer and live count without reading while hidden`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            var count: Int? = null
            var reads = 0
            val c = context(h, WeatherProbe()) { reads++; count }
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications")
            val screen = AmbientScreen()
            for (value in listOf(null, 0, 8, 32, 999, 1234)) {
                count = value
                assertTrue(screen.composite(c).contentEquals(NotificationsScreen.renderFrame(size, value)))
            }
            val before = reads
            h.battery.charging = true
            screen.composite(c)
            assertEquals(before, reads)
        }
    }

    @Test
    fun `every notification style shares the toy setting and animation with Ambient`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            h.notifications.value = 9
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications")
            val standalone = NotificationsScreen()
            val ambient = AmbientScreen()
            standalone.onActivate(h.context)
            for (style in NotificationPrefs.styles) {
                h.prefs.putString(NotificationPrefs.STYLE, style)
                h.scheduler.tick()
                assertEquals(if (style == NotificationPrefs.BELL) 50L else 500L, h.scheduler.tickerInterval)
                val started = h.clock.elapsed
                assertArrayEquals(h.lastFrame(), ambient.composite(h.context))
                for (delta in listOf(2950L, 500L, 500L, 3000L, 500L, 500L)) {
                    h.clock.advance(delta)
                    h.scheduler.tick()
                    val expected = NotificationsRenderer.renderFrame(size, 9, style, h.clock.elapsed - started)
                    assertArrayEquals(expected, h.lastFrame())
                    assertArrayEquals(expected, ambient.composite(h.context))
                }
            }
        }
    }

    @Test
    fun `notification marquee reflects live counts without restarting and resets when style or visibility changes`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            h.notifications.value = 9
            h.prefs.putString(NotificationPrefs.STYLE, NotificationPrefs.BELL)
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "notifications")
            val standalone = NotificationsScreen()
            val ambient = AmbientScreen()
            standalone.onActivate(h.context)
            val bell = h.lastFrame()
            assertArrayEquals(bell, ambient.composite(h.context))
            h.scheduler.tick(80)
            h.notifications.value = 10
            h.scheduler.tick()
            val fullCount = NotificationsRenderer.renderFrame(size, 10, NotificationPrefs.BELL, 4050)
            assertArrayEquals(fullCount, h.lastFrame())
            assertArrayEquals(fullCount, ambient.composite(h.context))
            h.clock.now -= 100_000 // Wall-clock corrections must not rewind the animation.
            h.scheduler.tick()
            assertArrayEquals(fullCount, h.lastFrame())

            h.battery.charging = true
            ambient.composite(h.context)
            h.clock.advance(20_000)
            h.battery.charging = false
            assertArrayEquals(bell, ambient.composite(h.context))

            h.prefs.putString(NotificationPrefs.STYLE, NotificationPrefs.DOT)
            h.scheduler.tick()
            assertArrayEquals(h.lastFrame(), ambient.composite(h.context))
            h.prefs.putString(NotificationPrefs.STYLE, NotificationPrefs.BELL)
            h.scheduler.tick()
            assertArrayEquals(bell, h.lastFrame())
            assertArrayEquals(bell, ambient.composite(h.context))
            h.scheduler.tick(80)
            standalone.onDeactivate()
            standalone.onActivate(h.context)
            assertArrayEquals(bell, h.lastFrame())
        }
    }

    @Test
    fun `weather holds icon on reveal and on first usable data at both sizes`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            val weather = WeatherProbe()
            val c = context(h, weather)
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "weather")
            val screen = AmbientScreen()
            weather.value = WeatherSnapshot(WeatherStatus.LOADING)
            screen.onActivate(c)
            h.clock.advance(5_000)
            weather.value = WeatherSnapshot(WeatherStatus.READY, -12.0, WeatherCondition.SNOW)
            val icon = WeatherRenderer.renderFrame(size, weather.value, 0)
            assertTrue(screen.composite(c).contentEquals(icon))
            h.clock.advance(4_000)
            assertTrue(screen.composite(c).contentEquals(WeatherRenderer.renderFrame(size, weather.value, 4_000)))
            h.prefs.putString(WeatherPrefs.UNIT, WeatherPrefs.FAHRENHEIT)
            assertTrue(screen.composite(c).contentEquals(WeatherRenderer.renderFrame(size, weather.value, 4_000, true)))
            h.spectrum.values = FloatArray(32) { 0.8f }
            screen.composite(c)
            h.clock.advance(10_000)
            h.spectrum.values = null
            assertTrue(screen.composite(c).contentEquals(icon))
            h.clock.advance(4_000)
            weather.value = WeatherSnapshot(WeatherStatus.UNAVAILABLE)
            screen.composite(c)
            weather.value = WeatherSnapshot(WeatherStatus.STALE, -12.0, WeatherCondition.SNOW)
            assertTrue(screen.composite(c).contentEquals(WeatherRenderer.renderFrame(size, weather.value, 0, true)))
        }
    }

    @Test
    fun `weather stays active across backgrounds and overlays but releases demand when no longer configured`() {
        val h = TestHarness()
        val weather = WeatherProbe()
        val c = context(h, weather)
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,weather")
        val screen = AmbientScreen()
        screen.onActivate(c)
        assertEquals(listOf(true), weather.demand)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick(10)
        screen.onEvent(Events.CHANGE)
        h.battery.charging = true
        h.scheduler.tick(10)
        assertEquals(listOf(true), weather.demand)
        h.prefs.putBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, false)
        h.scheduler.tick()
        assertEquals(listOf(true, false), weather.demand)
        h.prefs.putBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, true)
        h.scheduler.tick()
        assertEquals(listOf(true, false, true), weather.demand)
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock")
        h.scheduler.tick()
        assertEquals(listOf(true, false, true, false), weather.demand)
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "weather")
        h.scheduler.tick()
        screen.onDeactivate()
        assertEquals(listOf(true, false, true, false, true, false), weather.demand)
    }
}
