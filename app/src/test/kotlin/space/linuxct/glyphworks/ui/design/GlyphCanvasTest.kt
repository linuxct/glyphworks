package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.ui.ILLUSTRATION_HEIGHT
import space.linuxct.glyphworks.ui.tutorialCamera
import kotlin.math.hypot

private const val TUTORIAL_DIALOG_WIDTH_DP = 320f
private const val TUTORIAL_CARD_PADDING_DP = 20f
private const val BODY_MIN_WIDTH_FRACTION = 0.60f

class GlyphCanvasTest {
    private fun centerOf(cell: IntOffset, disc: Offset, radius: Float, size: Int): Offset {
        val pitch = matrixCellPitch(radius, size)
        val g0x = disc.x - pitch * (size - 1) / 2f
        val g0y = disc.y - pitch * (size - 1) / 2f
        return Offset(g0x + cell.x * pitch, g0y + cell.y * pitch)
    }

    private fun isDrawn(cell: IntOffset, disc: Offset, radius: Float, size: Int): Boolean {
        val c = centerOf(cell, disc, radius, size)
        return hypot(c.x - disc.x, c.y - disc.y) <= radius * PanelMask.GRID_EXTENT
    }

    private val disc = Offset(311.5f, 402.25f)
    private val radius = 268.75f

    private val ledsPerPanel = mapOf(13 to 137, 25 to 489)

    @Test
    fun everyDrawnCellRoundTripsThroughItsOwnCentre() {
        for (size in intArrayOf(13, 25)) {
            var drawn = 0
            for (row in 0 until size) {
                for (column in 0 until size) {
                    val cell = IntOffset(column, row)
                    val hit = matrixCellAt(centerOf(cell, disc, radius, size), disc, radius, size)
                    if (isDrawn(cell, disc, radius, size)) {
                        assertEquals("cell $cell at size $size", cell, hit)
                        drawn++
                    } else {
                        assertNull("masked cell $cell at size $size", hit)
                    }
                }
            }
            assertEquals("drawn cells at $size", ledsPerPanel.getValue(size), drawn)
        }
    }

    // The 13x13 rows below were counted off a photograph of the panel. 489 is
    // Nothing's published LED count for the Phone (3).
    @Test
    fun theMaskIsThePanelsOwnLedLayout() {
        assertEquals(137, PanelMask.count(13))
        assertEquals(489, PanelMask.count(25))
        assertEquals(
            listOf(5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5),
            (0 until 13).map { row -> (0 until 13).count { PanelMask.contains(it, row, 13) } },
        )
        for (size in intArrayOf(13, 25)) {
            for (row in 0 until size) {
                for (column in 0 until size) {
                    assertEquals(
                        "cell ($column, $row) at $size",
                        isDrawn(IntOffset(column, row), disc, radius, size),
                        PanelMask.contains(column, row, size),
                    )
                }
            }
        }
    }

    @Test
    fun touchesResolveToTheNearestCell() {
        val size = 13
        val pitch = matrixCellPitch(radius, size)
        val cell = IntOffset(6, 6)
        val centre = centerOf(cell, disc, radius, size)
        for (dx in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
            for (dy in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
                val touch = Offset(centre.x + dx * pitch, centre.y + dy * pitch)
                assertEquals(cell, matrixCellAt(touch, disc, radius, size))
            }
        }
        assertEquals(
            IntOffset(7, 6),
            matrixCellAt(Offset(centre.x + 0.51f * pitch, centre.y), disc, radius, size),
        )
        assertEquals(
            IntOffset(6, 7),
            matrixCellAt(Offset(centre.x, centre.y + 0.51f * pitch), disc, radius, size),
        )
    }

    @Test
    fun touchesOutsideTheGridAreRejected() {
        val size = 13
        assertNull(matrixCellAt(Offset(disc.x + radius * 4f, disc.y), disc, radius, size))
        assertNull(matrixCellAt(Offset(-500f, -500f), disc, radius, size))
        assertNull(matrixCellAt(disc, disc, 0f, size))
        assertNull(matrixCellAt(disc, disc, radius, 0))
        assertNotNull(matrixCellAt(disc, disc, radius, 25))
    }

    @Test
    fun theForwardMappingIsTheDrawLoopsOwnPlacement() {
        val views = listOf(
            MatrixDisc(disc, radius),
            MatrixDisc(disc, radius).transformedBy(2.5f, Offset(-703f, -47.5f)),
            MatrixDisc(disc, radius).transformedBy(4f, Offset(-1400f, -1900f)),
        )
        for (size in intArrayOf(13, 25)) {
            for (view in views) {
                for (row in 0 until size) {
                    for (column in 0 until size) {
                        val cell = IntOffset(column, row)
                        val expected = centerOf(cell, view.center, view.radius, size)
                        val actual = matrixCellCenter(view.center, view.radius, size, column, row)
                        assertEquals("$cell.x at $size", expected.x, actual.x, 1e-3f)
                        assertEquals("$cell.y at $size", expected.y, actual.y, 1e-3f)
                        if (PanelMask.contains(column, row, size)) {
                            assertEquals(
                                "$cell at $size",
                                cell,
                                matrixCellAt(actual, view.center, view.radius, size),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun drawnCells(view: MatrixDisc, size: Int): Set<IntOffset> = buildSet {
        for (row in 0 until size) {
            for (column in 0 until size) {
                val cell = IntOffset(column, row)
                if (isDrawn(cell, view.center, view.radius, size)) add(cell)
            }
        }
    }

    @Test
    fun everyCellRoundTripsUnderZoomAndPan() {
        val rest = MatrixDisc(disc, radius)
        val zoomsAndPans = listOf(
            1f to Offset.Zero,
            1.37f to Offset(-121.5f, -263.25f),
            2f to Offset(-311.5f, -402.25f),
            2.5f to Offset(-703f, -47.5f),
            3.1416f to Offset(-1010.9f, -1600.4f),
            4f to Offset.Zero,
            4f to Offset(-1400f, -1900f),
        )
        for (size in intArrayOf(13, 25)) {
            val expected = drawnCells(rest, size)
            for ((scale, offset) in zoomsAndPans) {
                val view = rest.transformedBy(scale, offset)
                assertEquals(
                    "the transform did nothing at ${scale}x",
                    scale * matrixCellPitch(rest.radius, size),
                    matrixCellPitch(view.radius, size),
                    1e-3f,
                )
                for (row in 0 until size) {
                    for (column in 0 until size) {
                        val cell = IntOffset(column, row)
                        val centre = centerOf(cell, view.center, view.radius, size)
                        val hit = matrixCellAt(centre, view.center, view.radius, size)
                        if (cell in expected) {
                            assertEquals("cell $cell at $size, ${scale}x $offset", cell, hit)
                        } else {
                            assertNull("culled cell $cell at $size, ${scale}x $offset", hit)
                        }
                    }
                }
                assertEquals(
                    "the cull changed shape at ${scale}x $offset",
                    expected,
                    drawnCells(view, size),
                )
            }
        }
    }

    @Test
    fun panningCanNeverPushTheDiscOffScreen() {
        val canvas = Size(1080f, 1600f)
        val middle = Offset(540f, 800f)
        val transform = CanvasTransform()

        transform.onGesture(middle, Offset(400f, -900f), 1f, canvas)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)

        transform.onGesture(middle, Offset.Zero, 8f, canvas)
        assertEquals(MAX_CANVAS_SCALE, transform.scale, 1e-4f)
        assertEquals(-1620f, transform.offsetX, 1e-2f)
        assertEquals(-2400f, transform.offsetY, 1e-2f)

        transform.onGesture(Offset.Zero, Offset(9_000f, 9_000f), 1f, canvas)
        assertEquals(0f, transform.offsetX, 1e-3f)
        assertEquals(0f, transform.offsetY, 1e-3f)
        transform.onGesture(Offset.Zero, Offset(-9_000f, -9_000f), 1f, canvas)
        assertEquals(-(MAX_CANVAS_SCALE - 1f) * canvas.width, transform.offsetX, 1e-3f)
        assertEquals(-(MAX_CANVAS_SCALE - 1f) * canvas.height, transform.offsetY, 1e-3f)

        transform.onGesture(middle, Offset.Zero, 0.01f, canvas)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 1e-3f)
        assertEquals(0f, transform.offsetY, 1e-3f)

        transform.onGesture(middle, Offset(-300f, 120f), 3f, canvas)
        transform.reset()
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test
    fun pinchingKeepsTheCellUnderTheFingersInPlace() {
        val canvas = Size(1080f, 1600f)
        val size = 25
        val transform = CanvasTransform()
        val base = MatrixDisc(disc, radius)
        val offCentrePinchPoint = centerOf(IntOffset(17, 8), base.center, base.radius, size)

        val before = matrixCellAt(offCentrePinchPoint, base.center, base.radius, size)
        assertEquals(IntOffset(17, 8), before)
        for (step in listOf(1.5f, 1.5f, 1.2f)) {
            transform.onGesture(offCentrePinchPoint, Offset.Zero, step, canvas)
            val view = base.transformedBy(transform.scale, transform.offset)
            assertEquals(
                "the cell under the pinch moved at ${transform.scale}x",
                before,
                matrixCellAt(offCentrePinchPoint, view.center, view.radius, size),
            )
        }
    }

    private val editorCanvases = listOf(
        Size(448f, 750f),
        Size(448f, 600f),
        Size(448f, 360f),
        Size(880f, 300f),
    )

    private val tutorialCanvas = Size(
        TUTORIAL_DIALOG_WIDTH_DP - 2 * TUTORIAL_CARD_PADDING_DP,
        ILLUSTRATION_HEIGHT.value,
    )

    private fun Camera.canvasY(deviceY: Float, canvas: Size) = map(Offset(0f, deviceY), canvas).y

    private fun Camera.canvasX(deviceX: Float, canvas: Size) = map(Offset(deviceX, 0f), canvas).x

    @Test
    fun theEditorCameraIsCentredOnTheMatrixAndCarriesNoBias() {
        for (canvas in editorCanvases) {
            val camera = editorCamera(canvas)
            assertEquals("focus x on $canvas", DeviceBack.matrix.center.x, camera.focus.x, 0f)
            assertEquals("focus y on $canvas", DeviceBack.matrix.center.y, camera.focus.y, 0f)
            val disc = camera.matrixDisc(canvas)
            assertEquals("disc x on $canvas", canvas.width / 2f, disc.center.x, 1e-3f)
            assertEquals("disc y on $canvas", canvas.height / 2f, disc.center.y, 1e-3f)
            assertEquals(
                "radius on $canvas",
                minOf(canvas.width, canvas.height) * 0.35f,
                disc.radius,
                1e-3f,
            )
        }
    }

    @Test
    fun theTutorialFramesTheWholePhoneAndCropsIt() {
        val canvas = tutorialCanvas
        val camera = tutorialCamera(canvas)
        val left = camera.canvasX(0f, canvas)
        val right = camera.canvasX(1f, canvas)
        val top = camera.canvasY(0f, canvas)
        assertTrue(
            "the body is only ${right - left} of $canvas wide",
            right - left >= canvas.width * BODY_MIN_WIDTH_FRACTION,
        )
        assertTrue("a left margin of $left", left in 36f..60f)
        assertTrue("no room for the key's ripple", canvas.width - right in 44f..72f)
        assertTrue("the body's top edge at $top", top in 4f..20f)
        assertTrue(
            "the plate is cropped",
            camera.canvasY(DeviceBack.ISLAND_BOTTOM, canvas) < canvas.height,
        )
        val keyBottom = camera.canvasY(DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT, canvas)
        assertTrue("the Essential Key is cropped ($keyBottom)", keyBottom < canvas.height - 8f)
        assertTrue(
            "the key is not on the body's edge",
            camera.canvasX(DeviceBack.KEY_LEFT, canvas) < right &&
                camera.canvasX(DeviceBack.KEY_LEFT + DeviceBack.KEY_WIDTH, canvas) > right,
        )
        assertTrue("the key is not below the plate", DeviceBack.KEY_TOP > DeviceBack.ISLAND_BOTTOM)
    }
}
