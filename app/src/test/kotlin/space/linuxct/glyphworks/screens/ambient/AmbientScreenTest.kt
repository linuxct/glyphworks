package space.linuxct.glyphworks.screens.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.ConnectionState
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.screens.BatteryScreen
import space.linuxct.glyphworks.screens.VisualizerScreen

class NightWindowTest {
    @Test
    fun `night is 2300 to 0559`() {
        assertTrue(NightWindow.isNight(23))
        assertTrue(NightWindow.isNight(0))
        assertTrue(NightWindow.isNight(5))
        assertFalse(NightWindow.isNight(6))
        assertFalse(NightWindow.isNight(12))
        assertFalse(NightWindow.isNight(22))
    }
}

class AmbientScreenTest {
    private fun harness(size: Int = 13): Pair<AmbientScreen, TestHarness> {
        val h = TestHarness(size)
        h.clock.hour = 12
        h.clock.min = 34
        h.spectrum.values = null
        return AmbientScreen() to h
    }

    @Test
    fun `default background is the digital clock`() {
        val (screen, h) = harness()
        GoldenAscii.check("ambient_13_bg_textclock", screen.composite(h.context), 13)
    }

    @Test
    fun `background gallery goldens`() {
        val (screen, h) = harness()
        h.clock.hour = 10
        h.clock.min = 8
        h.clock.sec = 0
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(1))
        GoldenAscii.check("ambient_13_bg_analog_1008", screen.composite(h.context), 13)

        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(2))
        h.connectivity.value = ConnectionState.WIFI
        GoldenAscii.check("ambient_13_bg_wifi", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.CELLULAR
        GoldenAscii.check("ambient_13_bg_cellular", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.AIRPLANE
        GoldenAscii.check("ambient_13_bg_airplane", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.NONE
        GoldenAscii.check("ambient_13_bg_noconn", screen.composite(h.context), 13)

        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(3))
        h.battery.level = 85
        GoldenAscii.check("ambient_13_bg_battery85", screen.composite(h.context), 13)

        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(5))
        GoldenAscii.check("ambient_13_bg_tiltball", screen.composite(h.context), 13)
    }

    @Test
    fun `shake activation shows background for 30s after a shake`() {
        val (screen, h) = harness()
        h.prefs.putBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, true)
        h.shake.millisSince = Long.MAX_VALUE
        assertTrue(screen.composite(h.context).all { it == 0 })
        h.shake.millisSince = 5_000
        assertFalse(screen.composite(h.context).all { it == 0 })
        h.shake.millisSince = 31_000
        assertTrue(screen.composite(h.context).all { it == 0 })
    }

    @Test
    fun `charging layer replaces background and stops at 100`() {
        val (screen, h) = harness()
        val background = screen.composite(h.context)
        h.battery.charging = true
        h.battery.level = 65
        val frame = screen.composite(h.context)
        assertTrue(
            frame.contentEquals(BatteryScreen.renderFrame(13, 65, true, h.clock.now)),
        )
        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
        h.battery.watts = 45f
        assertTrue(screen.composite(h.context).contentEquals(BatteryScreen.renderWattage(13, 45f)))
        h.battery.level = 100
        assertTrue(screen.composite(h.context).contentEquals(background))
    }

    @Test
    fun `audio layer wins over charging and reverts on silence`() {
        val (screen, h) = harness()
        h.battery.charging = true
        h.battery.level = 65
        val ramp = FloatArray(32) { it / 31f }
        h.spectrum.values = ramp
        val frame = screen.composite(h.context)
        val expectedBands = h.spectrum.bands(13)!!
        assertTrue(frame.contentEquals(VisualizerScreen.renderFrame(13, expectedBands, 0)))

        h.spectrum.values = FloatArray(32) { 0.01f }
        val next = screen.composite(h.context)
        assertTrue(next.contentEquals(BatteryScreen.renderFrame(13, 65, true, h.clock.now)))
    }

    @Test
    fun `25x25 composite`() {
        val (screen, h) = harness(25)
        GoldenAscii.check("ambient_25_bg_textclock", screen.composite(h.context), 25)
        assertEquals(25 * 25, screen.composite(h.context).size)
    }

    @Test
    fun `single press cycles in catalog order and wraps on both panels`() {
        for (size in listOf(13, 25)) {
            val (screen, h) = harness(size)
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "connection,text_clock")
            screen.onActivate(h.context)
            val clock = h.lastFrame()
            screen.onEvent(Events.CHANGE)
            val connection = h.lastFrame()
            assertFalse(connection.contentEquals(clock))
            assertTrue(connection.contentEquals(BackgroundRenderers.create("connection").render(h.context, h.clock.now)))
            screen.onEvent(Events.CHANGE)
            assertTrue(h.lastFrame().contentEquals(clock))
            screen.onEvent(Events.ACTION_DOWN)
            screen.onEvent(Events.ACTION_UP)
            assertTrue(h.lastFrame().contentEquals(clock))
        }
    }

    @Test
    fun `automatic deadline uses elapsed time independent of wall clock jumps`() {
        val (screen, h) = harness()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
        h.prefs.putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
        screen.onActivate(h.context)
        val clock = h.lastFrame()
        h.clock.now += 86_400_000L
        assertTrue(screen.composite(h.context).contentEquals(clock))
        h.clock.elapsed += 14_999L
        assertTrue(screen.composite(h.context).contentEquals(clock))
        h.clock.now -= 172_800_000L
        h.clock.elapsed += 1L
        assertTrue(screen.composite(h.context).contentEquals(BackgroundRenderers.create("connection").render(h.context, h.clock.now)))
    }

    @Test
    fun `hidden backgrounds pause auto cycling and get fresh interval after return`() {
        for (gate in listOf("charging", "audio", "night", "shake", "disabled")) {
            val (screen, h) = harness()
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
            h.prefs.putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
            screen.onActivate(h.context)
            val clock = h.lastFrame()
            h.clock.advance(14_000)
            when (gate) {
                "charging" -> h.battery.charging = true
                "audio" -> h.spectrum.values = FloatArray(32) { 0.8f }
                "night" -> {
                    h.prefs.putBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, false)
                    h.clock.hour = 23
                }
                "shake" -> h.prefs.putBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, true)
                "disabled" -> h.prefs.putBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, false)
            }
            screen.composite(h.context)
            h.clock.advance(900_000)
            screen.composite(h.context)
            h.battery.charging = false
            h.spectrum.values = null
            h.clock.hour = 12
            h.prefs.putBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, false)
            h.prefs.putBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, true)
            assertTrue(gate, screen.composite(h.context).contentEquals(clock))
            h.clock.advance(14_999)
            assertTrue(gate, screen.composite(h.context).contentEquals(clock))
            h.clock.advance(1)
            assertFalse(gate, screen.composite(h.context).contentEquals(clock))
        }
    }

    @Test
    fun `manual changes under an overlay select what appears after it ends`() {
        val (screen, h) = harness()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
        h.battery.charging = true
        h.spectrum.values = FloatArray(32) { 0.8f }
        screen.onActivate(h.context)
        val audio = h.lastFrame()
        screen.onEvent(Events.CHANGE)
        assertTrue(h.lastFrame().contentEquals(audio))
        h.spectrum.values = null
        assertTrue(screen.composite(h.context).contentEquals(BatteryScreen.renderFrame(13, 80, true, h.clock.now)))
        h.battery.charging = false
        assertTrue(screen.composite(h.context).contentEquals(BackgroundRenderers.create("connection").render(h.context, h.clock.now)))
    }

    @Test
    fun `empty background remains blank but music and charging still operate`() {
        val (screen, h) = harness()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "")
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        assertTrue(h.lastFrame().all { it == 0 })
        h.battery.charging = true
        assertTrue(screen.composite(h.context).contentEquals(BatteryScreen.renderFrame(13, 80, true, h.clock.now)))
        h.spectrum.values = FloatArray(32) { 0.8f }
        assertTrue(screen.composite(h.context).contentEquals(VisualizerScreen.renderFrame(13, h.spectrum.bands(13)!!, 0)))
    }

    @Test
    fun `in process switches retain selected background but new instance starts at first`() {
        val (screen, h) = harness()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
        h.prefs.putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
        screen.onActivate(h.context)
        val clock = h.lastFrame()
        screen.onEvent(Events.CHANGE)
        val connection = h.lastFrame()
        h.scheduler.clearTicker()
        screen.onDeactivate()
        h.clock.advance(900_000)
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(connection))
        h.clock.advance(14_999)
        assertTrue(screen.composite(h.context).contentEquals(connection))
        h.clock.advance(1)
        assertTrue(screen.composite(h.context).contentEquals(clock))
        screen.onEvent(Events.CHANGE)
        h.scheduler.clearTicker()
        screen.onDeactivate()
        val restarted = AmbientScreen()
        restarted.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(clock))
    }

    @Test
    fun `selection edits apply live independently of main toy switches`() {
        val (screen, h) = harness()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection,battery_text")
        h.prefs.putBoolean(PrefKeys.screenEnabled("clock"), false)
        screen.onActivate(h.context)
        val clock = h.lastFrame()
        screen.onEvent(Events.CHANGE)
        val connection = h.lastFrame()
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
        assertTrue(screen.composite(h.context).contentEquals(connection))
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,battery_text")
        assertTrue(screen.composite(h.context).contentEquals(clock))
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "")
        assertTrue(screen.composite(h.context).all { it == 0 })
    }
}
