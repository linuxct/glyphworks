package space.linuxct.glyphworks.screens.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.core.ambient.AmbientCarousel

class AmbientCarouselTest {
    private val enabled = listOf("text_clock", "connection", "weather")

    @Test
    fun `manual changes wrap with automatic cycling off by default`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = false, nowMs = 0)
        assertEquals("text_clock", carousel.update(true, 0))
        assertEquals("text_clock", carousel.update(true, 1_000_000))
        carousel.advance(1_000_001)
        assertEquals("connection", carousel.currentId)
        carousel.advance(1_000_002)
        assertEquals("weather", carousel.currentId)
        carousel.advance(1_000_003)
        assertEquals("text_clock", carousel.currentId)
    }

    @Test
    fun `automatic changes occur at fifteen seconds and never catch up in a burst`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 0)
        carousel.update(true, 0)
        assertEquals("text_clock", carousel.update(true, 14_999))
        assertEquals("connection", carousel.update(true, 15_000))
        assertEquals("weather", carousel.update(true, 900_000))
        assertEquals("weather", carousel.update(true, 900_001))
        assertEquals("text_clock", carousel.update(true, 915_000))
    }

    @Test
    fun `manual change resets automatic deadline`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 0)
        carousel.update(true, 0)
        carousel.advance(14_000)
        assertEquals("connection", carousel.update(true, 15_000))
        assertEquals("connection", carousel.update(true, 28_999))
        assertEquals("weather", carousel.update(true, 29_000))
    }

    @Test
    fun `hidden time does not count and manual changes still choose the next background`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 0)
        carousel.update(true, 0)
        carousel.update(false, 14_000)
        assertEquals("text_clock", carousel.update(false, 900_000))
        carousel.advance(900_001)
        assertEquals("connection", carousel.update(false, 950_000))
        assertEquals("connection", carousel.update(true, 1_000_000))
        assertEquals("connection", carousel.update(true, 1_014_999))
        assertEquals("weather", carousel.update(true, 1_015_000))
    }

    @Test
    fun `pause preserves selection and starts a fresh interval on resume`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 0)
        carousel.update(true, 0)
        carousel.advance(5_000)
        carousel.pause()
        carousel.configure(enabled, automatic = true, nowMs = 900_000)
        assertEquals("connection", carousel.update(true, 900_000))
        assertEquals("connection", carousel.update(true, 914_999))
        assertEquals("weather", carousel.update(true, 915_000))
    }

    @Test
    fun `empty and single selections stay fixed`() {
        val carousel = AmbientCarousel()
        carousel.configure(emptyList(), automatic = true, nowMs = 0)
        carousel.advance(10_000)
        assertNull(carousel.update(true, 900_000))
        carousel.configure(listOf("weather"), automatic = true, nowMs = 1_000_000)
        carousel.update(true, 1_000_000)
        carousel.advance(1_000_001)
        assertEquals("weather", carousel.update(true, 9_000_000))
    }

    @Test
    fun `live selections retain current entry or recover to first and reset deadline`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 0)
        carousel.update(true, 0)
        carousel.advance(100)
        carousel.configure(listOf("connection", "weather"), automatic = true, nowMs = 10_000)
        assertEquals("connection", carousel.update(true, 24_999))
        assertEquals("weather", carousel.update(true, 25_000))
        carousel.configure(listOf("text_clock", "connection"), automatic = true, nowMs = 25_001)
        assertEquals("text_clock", carousel.update(true, 25_001))
        carousel.configure(emptyList(), automatic = true, nowMs = 25_002)
        assertNull(carousel.update(true, 25_002))
    }

    @Test
    fun `enabling automatic cycling starts a fresh interval`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = false, nowMs = 0)
        carousel.update(true, 0)
        carousel.configure(enabled, automatic = true, nowMs = 90_000)
        assertEquals("text_clock", carousel.update(true, 104_999))
        assertEquals("connection", carousel.update(true, 105_000))
        carousel.configure(enabled, automatic = false, nowMs = 105_001)
        assertEquals("connection", carousel.update(true, 1_000_000))
    }

    @Test
    fun `invalid elapsed time moving backwards restarts without advancing`() {
        val carousel = AmbientCarousel()
        carousel.configure(enabled, automatic = true, nowMs = 100_000)
        carousel.update(true, 100_000)
        assertEquals("text_clock", carousel.update(true, 50_000))
        assertEquals("text_clock", carousel.update(true, 64_999))
        assertEquals("connection", carousel.update(true, 65_000))
    }

    @Test
    fun `selection codec preserves intentional empty values and uses catalog order`() {
        val prefs = FakePrefs()
        assertEquals(listOf("text_clock"), AmbientBackgrounds.readSelection(prefs))
        prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "")
        assertEquals(emptyList<String>(), AmbientBackgrounds.readSelection(prefs))
        assertEquals(
            listOf("text_clock", "weather"),
            AmbientBackgrounds.decode("weather,unknown, text_clock ,weather"),
        )
        assertEquals("text_clock,weather", AmbientBackgrounds.encode(listOf("weather", "text_clock", "weather")))
        assertEquals(emptyList<String>(), AmbientBackgrounds.decode("unknown"))
    }
}
