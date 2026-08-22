package space.linuxct.glyphworks.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS

class BrightnessScaleTest {
    @Test
    fun `grey stays grey at full brightness`() {
        val frame = IntArray(9) { 2048 }
        val out = BrightnessScale.scale(frame, 1f)
        assertTrue("no cell may be brightened: ${out.toList()}", out.all { it == 2048 })
    }

    @Test
    fun `a lit cell never goes dark`() {
        val out = BrightnessScale.scale(intArrayOf(1, 300, MAX_BRIGHTNESS), 0.05f)
        assertTrue("every lit cell must stay lit: ${out.toList()}", out.all { it > 0 })
    }

    @Test
    fun `dark cells are never lit by the floor`() {
        assertArrayEquals(IntArray(9), BrightnessScale.scale(IntArray(9), 0.05f))
        val out = BrightnessScale.scale(intArrayOf(0, MAX_BRIGHTNESS, 0), 0.1f)
        assertEquals(0, out[0])
        assertEquals(0, out[2])
    }

    @Test
    fun `brightness is clamped to the valid range`() {
        val frame = intArrayOf(MAX_BRIGHTNESS, 2048)
        assertArrayEquals(frame, BrightnessScale.scale(frame, 5f))
        assertTrue(BrightnessScale.scale(frame, -1f).all { it > 0 })
    }
}
