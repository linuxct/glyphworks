package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds

class PrefsMigrationTest {
    private fun legacyStore(): FakePrefs = FakePrefs().apply {
        putInt(PrefKeys.PREFS_VERSION, 1)
        putLong("teaStartMillis", 5_000L)
        putInt("teaDurationSec", 120)
        putLong("teaChimedFor", 4_000L)
        putBoolean("screen_enabled_tea", false)
        putString(PrefKeys.SCREEN_ORDER, "ambient,clock,tea,compass")
        putString(PrefKeys.CURRENT_SCREEN, "tea")
    }

    @Test
    fun `legacy store is fully translated`() {
        val prefs = legacyStore()
        assertTrue(PrefsMigration.run(prefs))

        assertEquals(5_000L, prefs.getLong(PrefKeys.TIMER_START, -1L))
        assertEquals(4_000L, prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, -1L))
        assertFalse(prefs.getBoolean(PrefKeys.screenEnabled("timer"), true))
        assertEquals("ambient,clock,timer,compass", prefs.getString(PrefKeys.SCREEN_ORDER, ""))
        assertEquals("timer", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
        assertEquals(180, prefs.getInt(PrefKeys.TIMER_DURATION, -1))

        assertTrue(prefs.map.keys.none { it.contains("tea") })
        assertEquals(PrefKeys.PREFS_VERSION_CURRENT, prefs.getInt(PrefKeys.PREFS_VERSION, -1))
    }

    @Test
    fun `fresh install writes nothing but the version`() {
        val prefs = FakePrefs()
        assertTrue(PrefsMigration.run(prefs))
        assertEquals(setOf(PrefKeys.PREFS_VERSION), prefs.map.keys)
        assertEquals(PrefKeys.PREFS_VERSION_CURRENT, prefs.getInt(PrefKeys.PREFS_VERSION, -1))
    }

    @Test
    fun `a partial legacy store neither crashes nor clobbers new values`() {
        val prefs = FakePrefs().apply {
            putInt(PrefKeys.PREFS_VERSION, 1)
            putLong("teaStartMillis", 7_000L)
            putInt(PrefKeys.TIMER_DURATION, 600)
        }
        assertTrue(PrefsMigration.run(prefs))

        assertEquals(7_000L, prefs.getLong(PrefKeys.TIMER_START, -1L))
        assertEquals(600, prefs.getInt(PrefKeys.TIMER_DURATION, -1))
        assertFalse(prefs.contains(PrefKeys.SCREEN_ORDER))
        assertFalse(prefs.contains(PrefKeys.CURRENT_SCREEN))
        assertFalse(prefs.contains(PrefKeys.screenEnabled("timer")))
        assertTrue(prefs.map.keys.none { it.contains("tea") })
    }

    @Test
    fun `every legacy duration snaps onto an offered preset`() {
        for (legacy in intArrayOf(30, 60, 120, 180, 240, 5, 9_999)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 1)
                putInt("teaDurationSec", legacy)
            }
            PrefsMigration.run(prefs)
            val snapped = prefs.getInt(PrefKeys.TIMER_DURATION, -1)
            assertTrue("$legacy -> $snapped", snapped in PrefKeys.TIMER_DURATION_OPTIONS)
        }
        for ((legacy, expected) in listOf(30 to 60, 120 to 180, 240 to 300, 9_999 to 780)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 1)
                putInt("teaDurationSec", legacy)
            }
            PrefsMigration.run(prefs)
            assertEquals(expected, prefs.getInt(PrefKeys.TIMER_DURATION, -1))
        }
    }

    @Test
    fun `version two selection migrates each old index including out of range values`() {
        for (index in listOf(-1) + (0..9).toList() + listOf(99)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 2)
                putInt(PrefKeys.AMBIENT_BACKGROUND, index)
            }
            assertTrue(PrefsMigration.run(prefs))
            assertEquals(listOf(AmbientBackgrounds.legacyId(index)), AmbientBackgrounds.readSelection(prefs))
            assertFalse(prefs.contains(PrefKeys.AMBIENT_BACKGROUND))
            assertFalse(prefs.getBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, PrefKeys.AMBIENT_AUTO_CYCLE_DEF))
            val afterMigration = prefs.map.toMap()
            assertFalse(PrefsMigration.run(prefs))
            assertEquals(afterMigration, prefs.map)
        }
    }

    @Test
    fun `ambient migration preserves existing new selection including empty`() {
        for (selection in listOf("", "weather,notifications")) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 2)
                putInt(PrefKeys.AMBIENT_BACKGROUND, 4)
                putString(PrefKeys.AMBIENT_BACKGROUNDS, selection)
                putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
            }
            assertTrue(PrefsMigration.run(prefs))
            assertEquals(selection, prefs.getString(PrefKeys.AMBIENT_BACKGROUNDS, "missing"))
            assertTrue(prefs.getBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, false))
            assertFalse(prefs.contains(PrefKeys.AMBIENT_BACKGROUND))
        }
    }

    @Test
    fun `version one also migrates ambient while version two leaves timer values alone`() {
        val legacy = legacyStore().apply { putInt(PrefKeys.AMBIENT_BACKGROUND, 9) }
        assertTrue(PrefsMigration.run(legacy))
        assertEquals(listOf("moon_phase"), AmbientBackgrounds.readSelection(legacy))
        val currentTimer = FakePrefs().apply {
            putInt(PrefKeys.PREFS_VERSION, 2)
            putInt(PrefKeys.TIMER_DURATION, 120)
        }
        assertTrue(PrefsMigration.run(currentTimer))
        assertEquals(120, currentTimer.getInt(PrefKeys.TIMER_DURATION, -1))
        assertEquals(listOf("text_clock"), AmbientBackgrounds.readSelection(currentTimer))
    }

    @Test
    fun `every older schema drops Ambient charging override without replacing Battery preference`() {
        for (version in 1..3) {
            for (oldStyle in listOf(0, 1, 2, 3, 4, 99)) {
                for (batteryWatts in listOf(null, false, true)) {
                    val prefs = FakePrefs().apply {
                        putInt(PrefKeys.PREFS_VERSION, version)
                        putInt("ambientChargingStyle", oldStyle)
                        if (batteryWatts != null) putBoolean(PrefKeys.BATTERY_SHOW_WATTS, batteryWatts)
                        putString(PrefKeys.AMBIENT_BACKGROUNDS, "weather,battery_gauge")
                        putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, true)
                    }
                    assertTrue(PrefsMigration.run(prefs))
                    assertFalse(prefs.contains("ambientChargingStyle"))
                    assertEquals(batteryWatts != null, prefs.contains(PrefKeys.BATTERY_SHOW_WATTS))
                    assertEquals(batteryWatts ?: PrefKeys.BATTERY_SHOW_WATTS_DEF,
                        prefs.getBoolean(PrefKeys.BATTERY_SHOW_WATTS, PrefKeys.BATTERY_SHOW_WATTS_DEF))
                    assertEquals("weather,battery_gauge", prefs.getString(PrefKeys.AMBIENT_BACKGROUNDS, ""))
                    assertTrue(prefs.getBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, false))
                    assertEquals(4, prefs.getInt(PrefKeys.PREFS_VERSION, -1))
                    val migrated = prefs.map.toMap()
                    assertFalse(PrefsMigration.run(prefs))
                    assertEquals(migrated, prefs.map)
                }
            }
        }
    }

    @Test
    fun `current and future schema versions are left unchanged`() {
        for (version in listOf(4, 5)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, version)
                putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
                putString(PrefKeys.AMBIENT_BACKGROUNDS, "")
            }
            val before = prefs.map.toMap()
            assertFalse(PrefsMigration.run(prefs))
            assertEquals(before, prefs.map)
        }
    }
}
