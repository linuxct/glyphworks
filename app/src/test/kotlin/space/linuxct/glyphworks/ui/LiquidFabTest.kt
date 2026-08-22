package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class LiquidFabTest {
    private val twoPi = (2.0 * PI).toFloat()

    @Test
    fun `the phase stays inside one turn`() {
        var t = 0L
        while (t < 24L * 60 * 60 * 1000) {
            val phase = liquidPhase(t)
            assertTrue("t=$t gave $phase", phase >= 0f && phase < twoPi)
            t += 137
        }
    }

    @Test
    fun `the loop closes exactly`() {
        for (offset in listOf(0L, 1L, 1234L, LIQUID_PERIOD_MS - 1)) {
            assertEquals(liquidPhase(offset), liquidPhase(offset + LIQUID_PERIOD_MS), 1e-4f)
            assertEquals(liquidPhase(offset), liquidPhase(offset + 100 * LIQUID_PERIOD_MS), 1e-3f)
        }
    }

    @Test
    fun coverage() {
        for (k in 0 until LIQUID_SLOTS) {
            val spread = spreadOverDisc(liquidFrame(slotPhase(k, 0f)), fine = true)
            assertTrue(
                "slot $k is not flat at its boundary: mix ranged ${spread.lo}..${spread.hi}",
                spread.pure,
            )
            assertEquals("slot $k deviates from pure", 0f, spread.deviation, 0f)
        }
    }

    @Test
    fun headings() {
        val maxTurn = PI.toFloat() - LIQUID_MIN_TURN
        for (k in 0 until LIQUID_SLOTS) {
            val turn = liquidSeparation(liquidArrivalAngle(k), liquidArrivalAngle(k + 1))
            assertTrue(
                "slots $k -> ${k + 1} turned ${deg(turn)}°, outside [${deg(LIQUID_MIN_TURN)}, ${deg(maxTurn)}]",
                turn in LIQUID_MIN_TURN..maxTurn,
            )
        }
    }

    @Test
    fun `red and blue alternate, and each arrives from its own heading`() {
        for (k in 0 until LIQUID_SLOTS) {
            val heading = liquidArrivalAngle(k)
            val frame = liquidFrame(slotPhase(k, 0.5f))
            val arriving = liquidMixAt(0.85f * cos(heading), 0.85f * sin(heading), frame)
            val leaving = liquidMixAt(-0.85f * cos(heading), -0.85f * sin(heading), frame)
            if (k % 2 == 0) {
                assertTrue("slot $k should be red arriving: $arriving vs $leaving", arriving > leaving)
            } else {
                assertTrue("slot $k should be blue arriving: $arriving vs $leaving", arriving < leaving)
            }
        }
    }

    @Test
    fun curvature() {
        val deviations = mutableListOf<Float>()
        for (k in 0 until LIQUID_SLOTS) {
            for (step in 0..8) {
                val u = 0.30f + step / 8f * 0.40f
                boundaryDeviation(liquidFrame(slotPhase(k, u)))?.let { deviations += it }
            }
        }
        assertTrue("only ${deviations.size} usable instants", deviations.size > 100)
        val mean = deviations.average().toFloat()
        val peak = deviations.max()
        assertTrue("mean departure from a chord is only ${pct(mean)} of the diameter", mean > 0.045f)
        assertTrue("peak departure is only ${pct(peak)} of the diameter", peak > 0.12f)
    }

    @Test
    fun `the fallback brush covers the disc whenever the shader does`() {
        assertEquals(LIQUID_FRONT_GRADIENT + LIQUID_EDGE, LIQUID_FALLBACK_CLAMP, 1e-6f)
        assertTrue(LIQUID_FALLBACK_CLAMP < LIQUID_CLAMP_BOUND)
        assertTrue(LIQUID_TIDE_AMPLITUDE > LIQUID_FALLBACK_CLAMP)
    }

    @Test
    fun `every constant interpolated into the shader is a legal AGSL literal`() {
        // The shader's numbers come from the Kotlin constants, but `Float.toString`
        // answers "1.0E-5" for a small enough value and AGSL will not parse that.
        // The only symptom is a FAB stuck on the fallback brush.
        val exponent = Regex("""\d[eE][-+]?\d""").find(LIQUID_AGSL)
        assertTrue("scientific notation in the shader: ${exponent?.value}", exponent == null)
        assertTrue(!Regex("""\d,\d""").containsMatchIn(LIQUID_AGSL))
        assertTrue(LIQUID_AGSL.contains("$LIQUID_TIDE_AMPLITUDE") || LIQUID_AGSL.contains("$LIQUID_SWELL_AMOUNT"))
        assertTrue(!LIQUID_AGSL.contains("$"))
        for (uniform in listOf("uSize", "uDir", "uPhase", "uOrigin", "uTide", "uLow", "uHigh")) {
            assertTrue("$uniform is not declared", LIQUID_AGSL.contains("uniform") && LIQUID_AGSL.contains(uniform))
        }
        assertTrue("the shader branches", !LIQUID_AGSL.contains("if ("))
    }

    private fun slotPhase(k: Int, u: Float): Float =
        ((k + u) / LIQUID_SLOTS * twoPi).mod(twoPi)

    private fun deg(radians: Float): String = "%.1f".format(radians * 180f / PI.toFloat())

    private fun pct(fraction: Float): String = "%.2f%%".format(fraction * 100f)

    private class Spread(val lo: Float, val hi: Float) {
        val pure: Boolean get() = lo >= 1f || hi <= 0f

        val deviation: Float
            get() = when {
                lo >= 1f -> 1f - lo
                hi <= 0f -> hi
                else -> min(1f - lo, hi)
            }
    }

    private fun spreadOverDisc(frame: LiquidFrame, fine: Boolean): Spread {
        val n = if (fine) 90 else 34
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (iy in -n..n) {
            val y = iy / n.toFloat()
            for (ix in -n..n) {
                val x = ix / n.toFloat()
                if (x * x + y * y > 1f) continue
                val mix = liquidMixAt(x, y, frame)
                if (mix < lo) lo = mix
                if (mix > hi) hi = mix
            }
        }
        val rimPoints = if (fine) 720 else 180
        for (i in 0 until rimPoints) {
            val a = i / rimPoints.toFloat() * twoPi
            for (r in listOf(1f, 0.997f)) {
                val mix = liquidMixAt(r * cos(a), r * sin(a), frame)
                if (mix < lo) lo = mix
                if (mix > hi) hi = mix
            }
        }
        return Spread(lo, hi)
    }

    private fun boundaryDeviation(frame: LiquidFrame): Float? =
        boundaryProfile(frame)?.let { residual -> residual.maxOf { abs(it) } / 2f }

    private fun boundaryProfile(frame: LiquidFrame): FloatArray? {
        val nx = -frame.dirY
        val ny = frame.dirX
        val n = 40
        val offsets = FloatArray(2 * n + 1) { Float.NaN }
        for (i in -n..n) {
            val t = i / (n + 1f)
            val half = sqrt(1f - t * t)
            var previous = liquidField(nx * t - frame.dirX * half, ny * t - frame.dirY * half, frame)
            for (step in 1..200) {
                val u = (step / 100f - 1f) * half
                val f = liquidField(nx * t + frame.dirX * u, ny * t + frame.dirY * u, frame)
                if (previous * f <= 0f && previous != f) {
                    val w = previous / (previous - f)
                    val uPrev = ((step - 1) / 100f - 1f) * half
                    offsets[i + n] = uPrev + w * (u - uPrev)
                    break
                }
                previous = f
            }
        }
        val first = offsets.indexOfFirst { !it.isNaN() }
        val last = offsets.indexOfLast { !it.isNaN() }
        if (first < 0 || last - first < 0.7f * offsets.size) return null
        for (i in first..last) if (offsets[i].isNaN()) return null
        val residual = FloatArray(offsets.size)
        for (i in first..last) {
            val along = (i - first).toFloat() / (last - first)
            residual[i] = offsets[i] - (offsets[first] + (offsets[last] - offsets[first]) * along)
        }
        return residual
    }
}
