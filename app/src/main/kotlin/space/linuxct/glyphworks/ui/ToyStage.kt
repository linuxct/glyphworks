package space.linuxct.glyphworks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.design.Camera
import space.linuxct.glyphworks.ui.design.DeviceBack
import space.linuxct.glyphworks.ui.design.MATRIX_DISC_COLOR
import space.linuxct.glyphworks.ui.design.MatrixDisc
import space.linuxct.glyphworks.ui.design.drawDeviceBack
import space.linuxct.glyphworks.ui.design.drawMatrix

/**
 * The phone's top and right edge frame the matrix; its lower body fades into the page. The preview engine
 * owns time and data: reading [frame] only inside Canvas invalidates drawing, not composition.
 */
@Composable
internal fun ToyStage(
    frame: State<IntArray>,
    panelSize: Int,
    modifier: Modifier = Modifier,
    onInteract: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val deviceName = stringResource(
        if (panelSize == 25) R.string.device_arbok else R.string.device_bellsprout,
    )
    val description = stringResource(R.string.toy_stage_description, deviceName)
    val actionLabel = stringResource(R.string.toy_stage_interact)
    val interaction = if (onInteract == null) Modifier else Modifier.clickable(
        interactionSource = null,
        indication = null,
        role = Role.Button,
        onClickLabel = actionLabel,
        onClick = onInteract,
    )

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // Only the artwork handles preview actions, without a ripple indication.
        Canvas(Modifier.fillMaxSize().then(interaction).semantics { contentDescription = description }) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            // Crop into the camera side of the phone to enlarge and center the matrix,
            // while preserving the top outline and breathing room beyond the right edge.
            val framing = toyStageMatrix(size, panelSize)
            val radius = framing.radius
            val center = framing.center

            val disc = if (panelSize == 25) {
                drawPhoneThreeDetail(center, radius, colors.onSurface)
            } else {
                val zoom = radius / DeviceBack.matrix.radius
                val camera = Camera(
                    zoom,
                    Offset(
                        DeviceBack.matrix.center.x - (center.x - size.width / 2f) / zoom,
                        DeviceBack.matrix.center.y - (center.y - size.height / 2f) / zoom,
                    ),
                )
                drawDeviceBack(colors.onSurface, camera).also {
                    drawRoundRect(
                        colors.onSurface.copy(alpha = 0.12f),
                        topLeft = camera.map(Offset.Zero, size),
                        size = Size(zoom, DeviceBack.BODY_LENGTH * zoom),
                        cornerRadius = CornerRadius(DeviceBack.BODY_CORNER * zoom),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }

            // Only the phone fades into the activity. Draw the matrix afterwards so the
            // live glyph remains fully legible, including pixels at the bottom of the disc.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.60f to Color.Transparent,
                    0.82f to colors.background.copy(alpha = 0.55f),
                    1f to colors.background,
                    startY = 0f,
                    endY = size.height,
                ),
            )

            drawCircle(Color.Black.copy(alpha = 0.55f), disc.radius + 2.dp.toPx(), disc.center)
            drawCircle(MATRIX_DISC_COLOR, disc.radius, disc.center)
            drawCircle(
                Color.White.copy(alpha = 0.18f),
                disc.radius + 1.dp.toPx(),
                disc.center,
                style = Stroke(1.dp.toPx()),
            )
            drawMatrix(disc.center, disc.radius * 0.965f, panelSize, frame.value, unlitAlpha = 0.055f)
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                deviceName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.toy_stage_grid, panelSize, panelSize),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Zoom into the camera side, keeping the top outline visible rather than zooming around the disc. */
internal fun toyStageMatrix(viewport: Size, panelSize: Int): MatrixDisc {
    val matrix = if (panelSize == 25) {
        MatrixDisc(Offset(PHONE_THREE_MATRIX_X, PHONE_THREE_MATRIX_Y), PHONE_THREE_MATRIX_RADIUS)
    } else {
        DeviceBack.matrix
    }
    val top = viewport.height * 0.045f
    // The leftmost 22% of the body sits offscreen, partially cropping the camera while
    // bringing the enlarged matrix toward the center. Height limits still protect the
    // top outline and bottom metadata in short windows.
    val phoneWidth = minOf(viewport.width * 1.10f, viewport.height * 0.78f / (matrix.center.y + matrix.radius))
    val left = -phoneWidth * 0.22f
    return MatrixDisc(
        center = Offset(left + matrix.center.x * phoneWidth, top + matrix.center.y * phoneWidth),
        radius = matrix.radius * phoneWidth,
    )
}

private const val PHONE_THREE_MATRIX_RADIUS = 0.215f
private const val PHONE_THREE_MATRIX_X = 0.74f
private const val PHONE_THREE_MATRIX_Y = 0.28f

/** A stylized glass-back crop, deliberately separate from the 4a Pro camera-island model. */
private fun DrawScope.drawPhoneThreeDetail(center: Offset, radius: Float, ink: Color): MatrixDisc {
    val width = radius / PHONE_THREE_MATRIX_RADIUS
    val left = center.x - width * PHONE_THREE_MATRIX_X
    val top = center.y - width * PHONE_THREE_MATRIX_Y
    val bodySize = Size(width, width * 2.08f)
    val corner = CornerRadius(width * 0.14f)
    drawRoundRect(ink.copy(alpha = 0.18f), Offset(left, top), bodySize, corner)
    drawRoundRect(ink.copy(alpha = 0.18f), Offset(left, top), bodySize, corner, style = Stroke(1.dp.toPx()))
    val inset = 4.dp.toPx()
    drawRoundRect(
        ink.copy(alpha = 0.09f),
        Offset(left + inset, top + inset),
        Size(width - inset * 2, bodySize.height - inset * 2),
        CornerRadius((corner.x - inset).coerceAtLeast(0f)),
        style = Stroke(1.dp.toPx()),
    )

    // Abstract internal traces and a cropped coil suggest the transparent back without
    // inventing a camera arrangement or borrowing the other device's raised island.
    val coilCenter = Offset(left + width * 0.34f, top + width * 0.86f)
    for (fraction in listOf(0.34f, 0.37f, 0.40f)) {
        drawCircle(ink.copy(alpha = 0.07f), width * fraction, coilCenter, style = Stroke(1.dp.toPx()))
    }
    val trace = Path().apply {
        moveTo(left + width * 0.25f, top + width * 0.10f)
        lineTo(left + width * 0.43f, top + width * 0.10f)
        lineTo(left + width * 0.43f, top + width * 0.48f)
        lineTo(left + width * 0.56f, top + width * 0.61f)
    }
    drawPath(trace, ink.copy(alpha = 0.12f), style = Stroke(1.dp.toPx()))
    return MatrixDisc(center, radius)
}
