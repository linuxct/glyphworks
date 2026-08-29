package space.linuxct.glyphworks.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Measured off Nothing OS 5: a 3 px stroke at 1.75 px/dp, so a hair under two.
private const val THIN = 1.7f

private const val DOT = 2.4f

private fun thin(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private fun ImageVector.Builder.stroke(width: Float = THIN, block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/** A round cap on a zero-length line is the cheapest true dot. */
private fun ImageVector.Builder.dot(x: Float, y: Float) = stroke(DOT) {
    moveTo(x, y)
    lineTo(x, y)
}

internal val ThinDice: ImageVector = thin("ThinDice") {
    stroke {
        moveTo(9f, 4f)
        lineTo(15f, 4f)
        quadTo(20f, 4f, 20f, 9f)
        lineTo(20f, 15f)
        quadTo(20f, 20f, 15f, 20f)
        lineTo(9f, 20f)
        quadTo(4f, 20f, 4f, 15f)
        lineTo(4f, 9f)
        quadTo(4f, 4f, 9f, 4f)
        close()
    }
    dot(9.3f, 9.3f)
    dot(14.7f, 14.7f)
}

internal val ThinBrush: ImageVector = thin("ThinBrush") {
    stroke {
        moveTo(4f, 20f)
        lineTo(4.9f, 16.1f)
        lineTo(15.6f, 5.4f)
        lineTo(18.6f, 8.4f)
        lineTo(7.9f, 19.1f)
        close()
    }
    stroke {
        moveTo(4.9f, 16.1f)
        lineTo(7.9f, 19.1f)
    }
}

/**
 * One closed outline that rises to a tooth and drops to a valley eight times, plus a hub.
 * Radial spokes off a circle read as a sun; a real gear needs the rim itself to go in and out.
 */
internal val ThinCog: ImageVector = thin("ThinCog") {
    val teeth = 8
    val step = 360f / teeth
    val toothHalf = 13f
    val outer = 9f
    val inner = 6.4f
    stroke {
        for (i in 0 until teeth) {
            val a = i * step
            point(inner, a - step / 2f, first = i == 0)
            point(outer, a - toothHalf)
            point(outer, a + toothHalf)
            point(inner, a + step / 2f)
        }
        close()
    }
    stroke {
        moveTo(14.6f, 12f)
        arcTo(2.6f, 2.6f, 0f, true, true, 9.4f, 12f)
        arcTo(2.6f, 2.6f, 0f, true, true, 14.6f, 12f)
        close()
    }
}

private fun PathBuilder.point(radius: Float, degrees: Float, first: Boolean = false) {
    val a = Math.toRadians(degrees.toDouble())
    val x = 12f + cos(a).toFloat() * radius
    val y = 12f + sin(a).toFloat() * radius
    if (first) moveTo(x, y) else lineTo(x, y)
}

internal val ThinCap: ImageVector = thin("ThinCap") {
    stroke {
        moveTo(2.6f, 9.4f)
        lineTo(12f, 5f)
        lineTo(21.4f, 9.4f)
        lineTo(12f, 13.8f)
        close()
    }
    stroke {
        moveTo(6.6f, 11.3f)
        lineTo(6.6f, 16.4f)
        quadTo(12f, 19.6f, 17.4f, 16.4f)
        lineTo(17.4f, 11.3f)
    }
}
