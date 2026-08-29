package space.linuxct.glyphworks.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * Compose has no backdrop filter, so frosting an overlay means keeping a copy of the page and
 * redrawing it blurred underneath. [recordBackdrop] takes the copy, [backdropBlur] spends it.
 */
@Stable
internal class Backdrop internal constructor(
    internal val content: GraphicsLayer,
    internal val blurred: GraphicsLayer,
) {
    // Written and read inside the draw phase, so plain vars: state here would loop recomposition.
    internal var captured = IntSize.Zero
}

@Composable
internal fun rememberBackdrop(): Backdrop {
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(content, blurred) { Backdrop(content, blurred) }
}

internal fun Modifier.recordBackdrop(backdrop: Backdrop): Modifier = drawWithContent {
    backdrop.content.record { this@drawWithContent.drawContent() }
    backdrop.captured = IntSize(size.width.toInt(), size.height.toInt())
    drawLayer(backdrop.content)
}

/**
 * Clip before this so the frost keeps the caller's shape. [version] is read during the draw to
 * subscribe it to the page moving; without that the blur would freeze on the first frame.
 */
@Composable
internal fun Modifier.backdropBlur(
    backdrop: Backdrop,
    radius: Dp,
    version: () -> Int,
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val px = with(LocalDensity.current) { radius.toPx() }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            version()
            val captured = backdrop.captured
            if (captured.width == 0 || captured.height == 0) return@drawBehind
            backdrop.blurred.renderEffect = BlurEffect(px, px, TileMode.Clamp)
            backdrop.blurred.record(captured) { drawLayer(backdrop.content) }
            translate(-origin.x, -origin.y) { drawLayer(backdrop.blurred) }
        }
}
