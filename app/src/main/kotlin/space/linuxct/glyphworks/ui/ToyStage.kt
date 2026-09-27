package space.linuxct.glyphworks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import space.linuxct.glyphworks.ui.theme.glyphCorner

/**
 * A close crop keeps the matrix readable at normal selector heights. The preview engine
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
    val backdrop = colors.surfaceContainerLow
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
            .clip(glyphCorner(28.dp, 32.dp))
            .background(backdrop),
    ) {
        // Only the artwork handles preview actions. The informational badge is a separate,
        // non-clickable surface above it. Preview actions never show an indication.
        Canvas(Modifier.fillMaxSize().then(interaction).semantics { contentDescription = description }) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            // At 220–290 dp high, the lit grid is approximately 134–176 dp across.
            // Keep it face-on: perspective is a poor trade for the small LED glyphs.
            val radius = minOf(size.height * 0.315f, size.width * 0.28f)
            val center = Offset(size.width * 0.64f, size.height * 0.53f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.13f), Color.Transparent),
                    center = center,
                    radius = radius * 1.65f,
                ),
                radius = radius * 1.65f,
                center = center,
            )

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

            drawCircle(Color.Black.copy(alpha = 0.55f), disc.radius + 2.dp.toPx(), disc.center)
            drawCircle(MATRIX_DISC_COLOR, disc.radius, disc.center)
            drawCircle(
                Color.White.copy(alpha = 0.18f),
                disc.radius + 1.dp.toPx(),
                disc.center,
                style = Stroke(1.dp.toPx()),
            )
            drawMatrix(disc.center, disc.radius * 0.965f, panelSize, frame.value, unlitAlpha = 0.055f)

            // The phone continues beyond the crop; the caption sits on a quiet surface.
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, backdrop),
                    startY = size.height * 0.84f,
                    endY = size.height,
                ),
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).widthIn(max = 210.dp),
            shape = CircleShape,
            color = colors.surface.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(colors.primary, CircleShape))
                Text(
                    stringResource(R.string.toy_stage_sample_preview),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/** A stylized glass-back crop, deliberately separate from the 4a Pro camera-island model. */
private fun DrawScope.drawPhoneThreeDetail(center: Offset, radius: Float, ink: Color): MatrixDisc {
    val width = radius / 0.215f
    val left = center.x - width * 0.74f
    val top = center.y - width * 0.28f
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
