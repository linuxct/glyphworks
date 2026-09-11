package space.linuxct.glyphworks.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.screens.ambient.AmbientScreen
import kotlin.math.abs

class SolarMathTest {
    @Test
    fun `equator equinox is roughly 6 to 18`() {
        val t = SolarMath.sunTimes(dayOfYear = 80, latDeg = 0.0, lonDeg = 0.0, utcOffsetMin = 0)
        assertEquals(SolarMath.Kind.NORMAL, t.kind)
        assertTrue("rise ${t.riseMin}", abs(t.riseMin - 360) <= 15)
        assertTrue("set ${t.setMin}", abs(t.setMin - 1080) <= 15)
    }
}

class MoonMathTest {
    private val newMoonEpoch = 947_182_440_000L

    @Test
    fun `anchor new moon is phase zero`() {
        assertTrue(MoonMath.phaseFraction(newMoonEpoch) < 0.001)
    }
}

class BatteryScreenTest {
    @Test
    fun `gauge goldens`() {
        GoldenAscii.check("battery_13_60", BatteryScreen.renderFrame(13, 60, false, 1_000_000), 13)
        GoldenAscii.check("battery_13_60_charging", BatteryScreen.renderFrame(13, 60, true, 1_000_000), 13)
        GoldenAscii.check("battery_25_60_charging", BatteryScreen.renderFrame(25, 60, true, 1_000_000), 25)
    }

    @Test
    fun `the toy only shows wattage when the pref is on`() {
        val h = TestHarness(13)
        h.battery.level = 60
        h.battery.charging = true
        h.battery.watts = 45f
        val screen = BatteryScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderFrame(13, 60, true, h.clock.now)))

        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderWattage(13, 45f)))

        h.battery.charging = false
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderFrame(13, 60, false, h.clock.now)))
    }

    @Test
    fun `Battery and both Ambient render paths share preferences and wattage fallback on both panels`() {
        for (size in listOf(13, 25)) {
            val h = TestHarness(size)
            h.battery.level = 60
            val battery = BatteryScreen()
            val ambient = AmbientScreen()
            battery.onActivate(h.context)
            val states = listOf(
                Triple(false, false, 45f),
                Triple(false, true, 45f),
                Triple(true, false, 45f),
                Triple(true, true, 45f),
                Triple(true, true, null),
                Triple(true, true, 0f),
                Triple(true, true, -1f),
                Triple(true, true, Float.NaN),
                Triple(true, true, Float.POSITIVE_INFINITY),
            )
            for ((charging, showWatts, watts) in states) {
                h.battery.charging = charging
                h.battery.watts = watts
                h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, showWatts)
                h.scheduler.tick()
                val standalone = h.lastFrame()
                val expected = if (charging && showWatts && watts != null && watts.isFinite() && watts > 0) {
                    BatteryScreen.renderWattage(size, watts)
                } else {
                    BatteryScreen.renderFrame(size, 60, charging, h.clock.now)
                }
                val state = "$size, charging=$charging, watts enabled=$showWatts, reading=$watts"
                assertTrue("standalone: $state", standalone.contentEquals(expected))

                h.prefs.putBoolean(PrefKeys.AMBIENT_USE_CHARGING, false)
                h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.BATTERY_GAUGE)
                assertTrue("background: $state", ambient.composite(h.context).contentEquals(standalone))
                if (charging) {
                    h.prefs.putBoolean(PrefKeys.AMBIENT_USE_CHARGING, true)
                    h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.TEXT_CLOCK)
                    assertTrue("overlay: $state", ambient.composite(h.context).contentEquals(standalone))
                }
            }
        }
    }
}

class SolarScreenTest {
    @Test
    fun `arc positions render`() {
        GoldenAscii.check("solar_13_morning", SolarScreen.renderFrame(13, 9 * 60, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_13_noon", SolarScreen.renderFrame(13, 12 * 60, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_13_night", SolarScreen.renderFrame(13, 0, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_25_noon", SolarScreen.renderFrame(25, 12 * 60, 6 * 60, 18 * 60), 25)
    }
}

class MoonScreenTest {
    @Test
    fun `phase goldens`() {
        GoldenAscii.check("moon_13_new", MoonScreen.renderFrame(13, 0.0), 13)
        GoldenAscii.check("moon_13_firstquarter", MoonScreen.renderFrame(13, 0.25), 13)
        GoldenAscii.check("moon_13_full", MoonScreen.renderFrame(13, 0.5), 13)
        GoldenAscii.check("moon_13_waning75", MoonScreen.renderFrame(13, 0.75), 13)
        GoldenAscii.check("moon_25_full", MoonScreen.renderFrame(25, 0.5), 25)
    }
}

class SkyAmbientBackgroundsTest {
    @Test
    fun `ambient backgrounds 7-9 delegate to the screen renderers`() {
        val h = TestHarness(13)
        h.spectrum.values = null
        h.battery.level = 60
        h.battery.charging = true
        h.prefs.putBoolean(PrefKeys.AMBIENT_USE_CHARGING, false)
        val screen = AmbientScreen()

        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(7))
        assertTrue(
            screen.composite(h.context)
                .contentEquals(BatteryScreen.renderFrame(h.context)),
        )
        h.battery.watts = 45f
        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(BatteryScreen.renderWattage(13, 45f)),
        )
        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, false)
        h.battery.watts = null

        h.battery.charging = false
        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(9))
        assertTrue(
            screen.composite(h.context)
                .contentEquals(MoonScreen.renderFrame(13, MoonMath.phaseFraction(h.clock.now))),
        )

        h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(8))
        h.clock.hour = 12
        h.clock.min = 0
        val times = SolarMath.sunTimes(h.clock.doy, 0.0, 0.0, 0)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(SolarScreen.renderFrame(13, 12 * 60, times.riseMin, times.setMin)),
        )
    }
}
