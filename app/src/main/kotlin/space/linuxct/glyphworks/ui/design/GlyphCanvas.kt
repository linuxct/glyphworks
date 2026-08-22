package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.matrix.PanelMask
import kotlin.math.roundToInt

data class MatrixDisc(val center: Offset, val radius: Float)

fun MatrixDisc.transformedBy(scale: Float, offset: Offset): MatrixDisc = MatrixDisc(
    center = Offset(center.x * scale + offset.x, center.y * scale + offset.y),
    radius = radius * scale,
)

val MATRIX_DISC_COLOR = Color(0xFF0E0E0E)

val RECORDING_DOT_COLOR = Color(0xFFE0392C)

internal enum class Tone {
    BODY,
    PLATE,
    PLATE_RIM,
    LENS,
    LENS_GLASS,
    KEY,
    GLASS,
    RECORDING,
}

internal sealed interface DeviceShape {
    val tone: Tone

    data class Round(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val corner: Float,
        override val tone: Tone,
        val strokeWidth: Float? = null,
    ) : DeviceShape

    data class Dot(
        val center: Offset,
        val radius: Float,
        override val tone: Tone,
    ) : DeviceShape
}

/**
 * The device in its own coordinates: 1.0 is the body's width, (0, 0) is the body's top-left
 * corner. Features on the camera plate are placed in the plate's own box through [island].
 *
 * Every number comes off a straight-on press photograph. The plate's flat top face starts at
 * (578, 165) and is 849 px wide there, so an island coordinate is (px - 578) / 849 across and
 * (px - 165) / 849 down. The plate measures 216 px on a 234 px body, and 849 x 638 px on its
 * flat face — not the outer bevel, which is about 5 % larger. Re-measure before changing one.
 */
internal object DeviceBack {

    const val BODY_LENGTH = 2.11f

    const val BODY_CORNER = 0.17f

    const val ISLAND_MARGIN = 0.045f

    const val ISLAND_WIDTH = 0.91f

    const val ISLAND_ASPECT = 1.331f

    const val ISLAND_HEIGHT = ISLAND_WIDTH / ISLAND_ASPECT

    const val ISLAND_CORNER = 0.16f

    const val ISLAND_LEFT = ISLAND_MARGIN
    const val ISLAND_TOP = ISLAND_MARGIN
    const val ISLAND_RIGHT = ISLAND_LEFT + ISLAND_WIDTH
    const val ISLAND_BOTTOM = ISLAND_TOP + ISLAND_HEIGHT

    private const val ISLAND_RIM_STROKE = 0.006f

    fun island(x: Float, y: Float): Offset =
        Offset(ISLAND_LEFT + ISLAND_WIDTH * x, ISLAND_TOP + ISLAND_WIDTH * y)

    fun islandLength(v: Float): Float = ISLAND_WIDTH * v

    val matrix = MatrixDisc(center = island(0.7332f, 0.2833f), radius = islandLength(0.2173f))

    const val KEY_WIDTH = 0.038f
    const val KEY_HEIGHT = 0.15f
    const val KEY_TOP = ISLAND_BOTTOM + 0.046f
    const val KEY_LEFT = 1f - KEY_WIDTH * 0.45f

    const val KEY_TRAVEL = 0.013f

    val plate: List<DeviceShape> = listOf(
        cameraPlate(),
        cameraPlateRim(),
        mainCameraBarrel(),
        mainCameraGlass(),
        twoCameraModule(),
        upperSensorDot(),
        lowerSensorDot(),
        pillBelowMatrix(),
        recordingIndicator(),
        matrixGlass(),
    )

    private fun cameraPlate() =
        islandRound(0f, 0f, 1f, ISLAND_HEIGHT / ISLAND_WIDTH, ISLAND_CORNER, Tone.PLATE)

    private fun cameraPlateRim() = islandRound(
        0f, 0f, 1f, ISLAND_HEIGHT / ISLAND_WIDTH, ISLAND_CORNER, Tone.PLATE_RIM,
        stroke = ISLAND_RIM_STROKE,
    )

    private fun mainCameraBarrel() = islandDot(0.1790f, 0.2002f, 0.1237f, Tone.LENS)

    private fun mainCameraGlass() = islandDot(0.1790f, 0.2002f, 0.0843f, Tone.LENS_GLASS)

    private fun twoCameraModule() = islandRound(0.0554f, 0.4240f, 0.5171f, 0.6678f, 0.112f, Tone.LENS)

    private fun upperSensorDot() = islandDot(0.4122f, 0.1366f, 0.0400f, Tone.LENS)

    private fun lowerSensorDot() = islandDot(0.4193f, 0.2615f, 0.0436f, Tone.LENS)

    private fun pillBelowMatrix() = islandRound(0.6148f, 0.6231f, 0.8575f, 0.6855f, 0.031f, Tone.LENS)

    private fun recordingIndicator() =
        islandRound(0.9011f, 0.4900f, 0.9588f, 0.5465f, 0.013f, Tone.RECORDING)

    private fun matrixGlass() = DeviceShape.Dot(matrix.center, matrix.radius, Tone.GLASS)

    private fun islandRound(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        corner: Float,
        tone: Tone,
        stroke: Float? = null,
    ): DeviceShape.Round {
        val topLeft = island(left, top)
        val bottomRight = island(right, bottom)
        return DeviceShape.Round(
            topLeft.x, topLeft.y, bottomRight.x, bottomRight.y,
            islandLength(corner), tone, stroke?.let(::islandLength),
        )
    }

    private fun islandDot(x: Float, y: Float, radius: Float, tone: Tone): DeviceShape.Dot =
        DeviceShape.Dot(island(x, y), islandLength(radius), tone)
}

/** How magnified the device is, and which device point sits at the centre of the frame. */
data class Camera(val zoom: Float, val focus: Offset) {

    fun map(p: Offset, canvas: Size): Offset = Offset(
        (p.x - focus.x) * zoom + canvas.width / 2f,
        (p.y - focus.y) * zoom + canvas.height / 2f,
    )

    fun map(length: Float): Float = length * zoom

    fun matrixDisc(canvas: Size): MatrixDisc =
        MatrixDisc(map(DeviceBack.matrix.center, canvas), DeviceBack.matrix.radius * zoom)

    fun transformedBy(scale: Float, offset: Offset, canvas: Size): Camera {
        if (scale <= 0f || zoom <= 0f) return this
        val canvasCenterX = canvas.width / 2f
        val canvasCenterY = canvas.height / 2f
        return Camera(
            zoom = zoom * scale,
            focus = Offset(
                focus.x + (canvasCenterX * (1f - scale) - offset.x) / (zoom * scale),
                focus.y + (canvasCenterY * (1f - scale) - offset.y) / (zoom * scale),
            ),
        )
    }
}

internal fun cropBelow(canvas: Size, camera: Camera): Float {
    if (camera.zoom <= 0f) return DeviceBack.BODY_LENGTH
    return camera.focus.y + (canvas.height / 2f) / camera.zoom + DeviceBack.BODY_CORNER
}

private const val UNLIT_ALPHA = 0.10f

private const val PIXEL_FRACTION = 0.80f

private const val PIXEL_CORNER_FRACTION = 0.16f

private fun Tone.resolve(base: Color, keyPressed: Boolean): Color = when (this) {
    Tone.BODY -> base.copy(alpha = 0.26f)
    Tone.PLATE -> base.copy(alpha = 0.13f)
    Tone.PLATE_RIM -> base.copy(alpha = 0.22f)
    Tone.LENS -> base.copy(alpha = 0.34f)
    Tone.LENS_GLASS -> base.copy(alpha = 0.5f)
    Tone.KEY -> base.copy(alpha = if (keyPressed) 0.85f else 0.45f)
    Tone.GLASS -> MATRIX_DISC_COLOR
    Tone.RECORDING -> RECORDING_DOT_COLOR
}

fun DrawScope.drawDeviceBack(base: Color, camera: Camera, keyPressed: Boolean = false): MatrixDisc {
    if (camera.zoom <= 0f) return camera.matrixDisc(size)
    val bodyBottom = maxOf(DeviceBack.BODY_LENGTH, cropBelow(size, camera))
    withTransform({
        translate(
            size.width / 2f - camera.focus.x * camera.zoom,
            size.height / 2f - camera.focus.y * camera.zoom,
        )
        scale(camera.zoom, camera.zoom, pivot = Offset.Zero)
    }) {
        drawBody(base, keyPressed, bodyBottom)
        drawEssentialKey(base, keyPressed)
        drawPlateShapes(base, keyPressed)
    }
    return camera.matrixDisc(size)
}

private fun DrawScope.drawBody(base: Color, keyPressed: Boolean, bodyBottom: Float) {
    drawRoundRect(
        Tone.BODY.resolve(base, keyPressed),
        topLeft = Offset.Zero,
        size = Size(1f, bodyBottom),
        cornerRadius = CornerRadius(DeviceBack.BODY_CORNER),
    )
}

private fun DrawScope.drawEssentialKey(base: Color, keyPressed: Boolean) {
    val travel = if (keyPressed) DeviceBack.KEY_TRAVEL else 0f
    drawRoundRect(
        Tone.KEY.resolve(base, keyPressed),
        topLeft = Offset(DeviceBack.KEY_LEFT - travel, DeviceBack.KEY_TOP),
        size = Size(DeviceBack.KEY_WIDTH, DeviceBack.KEY_HEIGHT),
        cornerRadius = CornerRadius(DeviceBack.KEY_WIDTH / 2f),
    )
}

private fun DrawScope.drawPlateShapes(base: Color, keyPressed: Boolean) {
    for (shape in DeviceBack.plate) {
        val color = shape.tone.resolve(base, keyPressed)
        when (shape) {
            is DeviceShape.Dot -> drawCircle(color, radius = shape.radius, center = shape.center)
            is DeviceShape.Round -> drawRoundRect(
                color,
                topLeft = Offset(shape.left, shape.top),
                size = Size(shape.right - shape.left, shape.bottom - shape.top),
                cornerRadius = CornerRadius(shape.corner),
                style = shape.strokeWidth?.let { Stroke(width = it) } ?: Fill,
            )
        }
    }
}

/** [frame] is a `size` x `size` grid read row-major (`y * size + x`), values 0..4095. */
fun DrawScope.drawMatrix(
    center: Offset,
    radius: Float,
    size: Int,
    frame: IntArray,
    unlitAlpha: Float = UNLIT_ALPHA,
) {
    if (size <= 0 || radius <= 0f) return
    val cell = matrixCellPitch(radius, size)
    val origin = gridOrigin(center, cell, size)
    val pixelSide = cell * PIXEL_FRACTION
    val pixelSize = Size(pixelSide, pixelSide)
    val pixelCorner = CornerRadius(pixelSide * PIXEL_CORNER_FRACTION)
    for (row in 0 until size) {
        for (column in 0 until size) {
            if (!PanelMask.contains(column, row, size)) continue
            val index = row * size + column
            val value = if (index < frame.size) frame[index] else 0
            val alpha = if (value > 0) {
                (value / DesignFrames.MAX_BRIGHTNESS.toFloat()).coerceIn(0f, 1f)
            } else {
                unlitAlpha
            }
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(
                    origin.x + column * cell - pixelSide / 2f,
                    origin.y + row * cell - pixelSide / 2f,
                ),
                size = pixelSize,
                cornerRadius = pixelCorner,
            )
        }
    }
}

private const val GHOST_FRACTION = 0.34f

private const val GHOST_ALPHA = 0.32f

/** Call this after [drawMatrix], or the unlit-cell wash covers the ghost. */
fun DrawScope.drawMatrixGhost(
    center: Offset,
    radius: Float,
    size: Int,
    ghost: IntArray,
    current: IntArray,
) {
    if (size <= 0 || radius <= 0f) return
    val cell = matrixCellPitch(radius, size)
    val origin = gridOrigin(center, cell, size)
    val ghostRadius = cell * GHOST_FRACTION / 2f
    val tint = Color.White.copy(alpha = GHOST_ALPHA)
    for (row in 0 until size) {
        for (column in 0 until size) {
            val index = row * size + column
            val ghostIsLit = index < ghost.size && ghost[index] > 0
            val alreadyPainted = index < current.size && current[index] > 0
            if (!ghostIsLit || alreadyPainted) continue
            if (!PanelMask.contains(column, row, size)) continue
            drawCircle(
                tint,
                radius = ghostRadius,
                center = Offset(origin.x + column * cell, origin.y + row * cell),
            )
        }
    }
}

/** Returns `IntOffset(x = column, y = row)`, so the caller indexes `frame[y * size + x]`. */
fun matrixCellAt(offset: Offset, center: Offset, radius: Float, size: Int): IntOffset? {
    if (size <= 0 || radius <= 0f) return null
    val cell = matrixCellPitch(radius, size)
    if (cell <= 0f) return null
    val origin = gridOrigin(center, cell, size)
    val column = ((offset.x - origin.x) / cell).roundToInt()
    val row = ((offset.y - origin.y) / cell).roundToInt()
    if (!PanelMask.contains(column, row, size)) return null
    return IntOffset(column, row)
}

fun matrixCellPitch(radius: Float, size: Int): Float =
    if (size <= 0) 0f else radius * 2f * PanelMask.GRID_EXTENT / size

private fun gridOrigin(center: Offset, cell: Float, size: Int): Offset =
    Offset(center.x - cell * (size - 1) / 2f, center.y - cell * (size - 1) / 2f)

fun matrixCellCenter(center: Offset, radius: Float, size: Int, x: Int, y: Int): Offset {
    val cell = matrixCellPitch(radius, size)
    val origin = gridOrigin(center, cell, size)
    return Offset(origin.x + x * cell, origin.y + y * cell)
}
