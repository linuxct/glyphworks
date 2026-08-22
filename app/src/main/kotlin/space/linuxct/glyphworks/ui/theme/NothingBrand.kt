package space.linuxct.glyphworks.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal val NothingRed = Color(0xFFD71921)

internal val NothingBlue = Color(0xFF110E56)

internal const val LIQUID_RED_LSTAR = 36f

internal val NothingLiquidRed: Color = NothingRed.scaledToLuminance(luminanceOfLstar(LIQUID_RED_LSTAR))

internal val NothingLiquidBlue = NothingBlue

internal fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

internal fun linearToSrgb(linear: Float): Float {
    val c = linear.coerceIn(0f, 1f)
    return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
}

internal fun luminanceOfLstar(lStar: Float): Float = ((lStar + 16f) / 116f).pow(3)

internal fun Color.scaledToLuminance(target: Float): Color {
    val current = luminance()
    if (current <= 0f) return this
    val factor = target / current
    return Color(
        red = linearToSrgb(srgbToLinear(red) * factor),
        green = linearToSrgb(srgbToLinear(green) * factor),
        blue = linearToSrgb(srgbToLinear(blue) * factor),
        alpha = alpha,
    )
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

// Mixes encoded values, matching AGSL's mix() in ui/LiquidFab.kt. Compose's lerp(Color, Color,
// Float) works in a different space and would draw a different ramp from the GPU's.
internal fun liquidMix(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = NothingLiquidBlue.red + (NothingLiquidRed.red - NothingLiquidBlue.red) * f,
        green = NothingLiquidBlue.green + (NothingLiquidRed.green - NothingLiquidBlue.green) * f,
        blue = NothingLiquidBlue.blue + (NothingLiquidRed.blue - NothingLiquidBlue.blue) * f,
    )
}
