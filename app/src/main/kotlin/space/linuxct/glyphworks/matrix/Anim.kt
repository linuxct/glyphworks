package space.linuxct.glyphworks.matrix

object Anim {
    fun clamp01(t: Float): Float = t.coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)

    fun easeInOut(t: Float): Float {
        val c = clamp01(t)
        return c * c * (3f - 2f * c)
    }

    fun pingPong(step: Int, steps: Int): Int {
        if (steps <= 1) return 0
        val period = 2 * (steps - 1)
        val m = ((step % period) + period) % period
        return if (m < steps) m else period - m
    }

    fun triangle(t: Float): Float {
        val c = clamp01(t)
        return if (c < 0.5f) c * 2f else (1f - c) * 2f
    }
}
