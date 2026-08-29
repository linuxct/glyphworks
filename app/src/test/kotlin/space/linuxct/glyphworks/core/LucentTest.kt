package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs

// Read off a 4a Pro. Other builds wrap the same stamp in a codename and a region.
private const val OS_5 = "C5.0-260819-1642"
private const val OS_4 = "B4.1-260723-1820"
private const val OS_4_NAMED = "Metroid-B4.1-260814-1733-IND"

class LucentTest {
    @Test
    fun `the build number decides the Nothing OS major`() {
        assertEquals(5, nothingOsMajor(OS_5))
        assertEquals(4, nothingOsMajor(OS_4))
        assertEquals(4, nothingOsMajor(OS_4_NAMED))
        assertTrue(runsLucentOs(OS_5))
        assertFalse(runsLucentOs(OS_4))
    }

    @Test
    fun `a build number from another phone reads as the old look`() {
        assertNull(nothingOsMajor("TQ3A.230901.001"))
        assertNull(nothingOsMajor(""))
        assertFalse(runsLucentOs("TQ3A.230901.001"))
    }

    @Test
    fun `a first install picks the look the phone is running`() {
        assertTrue(resolveLucent(FakePrefs(), onLucentOs = true))
        assertFalse(resolveLucent(FakePrefs(), onLucentOs = false))
    }

    @Test
    fun `upgrading to Nothing OS 5 turns the new look on once`() {
        val prefs = FakePrefs()
        assertFalse(resolveLucent(prefs, onLucentOs = false))

        assertTrue("the upgrade did not adopt the new look", resolveLucent(prefs, onLucentOs = true))

        prefs.putBoolean(PrefKeys.LUCENT_ENABLED, false)
        assertFalse("the upgrade fired twice", resolveLucent(prefs, onLucentOs = true))
    }

    @Test
    fun `downgrading and upgrading again never overrules the user`() {
        val prefs = FakePrefs()
        resolveLucent(prefs, onLucentOs = true)
        prefs.putBoolean(PrefKeys.LUCENT_ENABLED, false)

        assertFalse(resolveLucent(prefs, onLucentOs = false))
        assertFalse("the second upgrade overruled the user", resolveLucent(prefs, onLucentOs = true))
    }

    @Test
    fun `turning it on by hand on Nothing OS 4 sticks`() {
        val prefs = FakePrefs()
        prefs.putBoolean(PrefKeys.LUCENT_ENABLED, true)
        assertTrue(resolveLucent(prefs, onLucentOs = false))
    }
}
