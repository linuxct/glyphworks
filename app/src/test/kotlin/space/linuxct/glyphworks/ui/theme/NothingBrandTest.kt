package space.linuxct.glyphworks.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NothingBrandTest {
    private val inks = listOf(Color(0xFFF2F2FA), Color(0xFFEFF0F7))

    @Test
    fun `the sRGB transfer functions are inverses`() {
        var c = 0f
        while (c <= 1f) {
            assertEquals(c, linearToSrgb(srgbToLinear(c)), 1e-4f)
            c += 1f / 64f
        }
    }

    @Test
    fun `every colour the liquid can produce clears 6 to 1 against the ink`() {
        for (ink in inks) {
            for (step in 0..40) {
                val t = step / 40f
                val ratio = contrastRatio(liquidMix(t), ink)
                assertTrue("t=$t gave $ratio:1", ratio >= 6f)
            }
        }
    }

    @Test
    fun `the mix is monotone and hits both brand colours at its ends`() {
        assertEquals(NothingLiquidBlue, liquidMix(0f))
        assertEquals(NothingLiquidRed, liquidMix(1f))
        var previous = liquidMix(0f).luminance()
        for (step in 1..40) {
            val next = liquidMix(step / 40f).luminance()
            assertTrue(next > previous)
            previous = next
        }
    }
}
