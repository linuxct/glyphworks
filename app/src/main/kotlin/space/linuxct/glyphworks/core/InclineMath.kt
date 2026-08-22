package space.linuxct.glyphworks.core

import kotlin.math.abs
import kotlin.math.atan2

/** Pitch and roll from the gravity vector, for a matrix on the back of the phone. */
object InclineMath {

    fun pitchDegrees(gy: Float, gz: Float): Float = degreesFromHorizontal(gy, gz)

    fun rollDegrees(gx: Float, gz: Float): Float {
        val angle = degreesFromHorizontal(gx, gz)
        val viewedFaceDown = gz < 0f
        return if (viewedFaceDown) -angle else angle
    }

    private fun degreesFromHorizontal(g: Float, gz: Float): Float =
        Math.toDegrees(atan2(g.toDouble(), abs(gz).toDouble())).toFloat()
}
