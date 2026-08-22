package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class InclineMathTest {
    private val G = 9.81f

    private fun pitchVector(deg: Double, faceUp: Boolean): Triple<Float, Float, Float> {
        val r = Math.toRadians(deg)
        val z = (if (faceUp) 1 else -1) * G * cos(r).toFloat()
        return Triple(0f, G * sin(r).toFloat(), z)
    }

    private fun rollVector(deg: Double, faceUp: Boolean): Triple<Float, Float, Float> {
        val r = Math.toRadians(deg)
        val z = (if (faceUp) 1 else -1) * G * cos(r).toFloat()
        return Triple(G * sin(r).toFloat(), 0f, z)
    }

    private fun pitch(v: Triple<Float, Float, Float>) = InclineMath.pitchDegrees(v.second, v.third)

    private fun roll(v: Triple<Float, Float, Float>) = InclineMath.rollDegrees(v.first, v.third)

    @Test
    fun `face up flat reads dead level`() {
        assertEquals(0f, InclineMath.pitchDegrees(0f, G), 1e-4f)
        assertEquals(0f, InclineMath.rollDegrees(0f, G), 1e-4f)
    }

    @Test
    fun `face down tilt keeps the pitch sign and magnitude`() {
        assertEquals(5f, pitch(pitchVector(5.0, faceUp = false)), 1e-3f)
        assertEquals(20f, pitch(pitchVector(20.0, faceUp = false)), 1e-3f)
        assertEquals(-20f, pitch(pitchVector(-20.0, faceUp = false)), 1e-3f)
        assertEquals(pitch(pitchVector(20.0, faceUp = true)), pitch(pitchVector(20.0, faceUp = false)), 1e-3f)
    }

    @Test
    fun `roll magnitude survives the flip and the sign mirrors`() {
        val up = roll(rollVector(20.0, faceUp = true))
        val down = roll(rollVector(20.0, faceUp = false))
        assertEquals(20f, up, 1e-3f)
        assertEquals(-up, down, 1e-3f)
    }

    @Test
    fun `every orientation stays in the documented minus 90 to 90 range`() {
        for (deg in -180..180 step 5) {
            for (faceUp in listOf(true, false)) {
                val p = pitch(pitchVector(deg.toDouble(), faceUp))
                val r = roll(rollVector(deg.toDouble(), faceUp))
                assertTrue("pitch $p at $deg", p.isFinite() && p >= -90f && p <= 90f)
                assertTrue("roll $r at $deg", r.isFinite() && r >= -90f && r <= 90f)
            }
        }
    }
}
