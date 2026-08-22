package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import kotlin.math.roundToInt

object BrightnessScale {

    private const val MIN_LIT = 1

    private const val UNITY_EPSILON = 0.5f / MAX_BRIGHTNESS

    fun scale(frame: IntArray, brightness01: Float): IntArray {
        val brightness = brightness01.coerceIn(0f, 1f)
        if (brightness >= 1f - UNITY_EPSILON) return frame
        val out = IntArray(frame.size)
        for (i in frame.indices) {
            val lit = frame[i]
            if (lit <= 0) continue
            val scaled = (lit * brightness).roundToInt()
            out[i] = if (scaled < MIN_LIT) MIN_LIT else scaled
        }
        return out
    }
}
