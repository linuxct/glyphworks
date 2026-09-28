package space.linuxct.glyphworks.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.ui.design.DeviceBack

class ToyStageTest {
    @Test
    fun cameraSideIsCroppedButTopOutlineAndEntireMatrixStayVisible() {
        for (panel in listOf(13, 25)) {
            for (width in listOf(320f, 420f, 480f, 840f)) {
                for (height in listOf(190f, 260f, 310f)) {
                    val disc = toyStageMatrix(Size(width, height), panel)
                    // Recover the actual body bounds from the model used by the drawing code.
                    val bodyWidth = disc.radius / if (panel == 25) 0.215f else DeviceBack.matrix.radius
                    val left = disc.center.x - bodyWidth * if (panel == 25) 0.74f else DeviceBack.matrix.center.x
                    val top = disc.center.y - bodyWidth * if (panel == 25) 0.28f else DeviceBack.matrix.center.y
                    val case = "$panel, ${width}x$height"
                    assertTrue("left border still visible: $case", left < 0f)
                    assertTrue("top outline cropped: $case", top >= height * 0.04f)
                    assertTrue("right margin too small: $case", left + bodyWidth <= width * 0.88f)
                    assertTrue("disc still too far right: $case", disc.center.x <= width * 0.58f)
                    assertTrue("disc clipped at left: $case", disc.center.x - disc.radius > 0f)
                    assertTrue("disc clipped at right: $case", disc.center.x + disc.radius < width)
                    assertTrue("disc clipped at top: $case", disc.center.y - disc.radius > top)
                    assertTrue("disc overlaps metadata: $case", disc.center.y + disc.radius <= height * 0.83f)
                    if (panel == 13) {
                        val cameraCenter = left + DeviceBack.island(0.1790f, 0.2002f).x * bodyWidth
                        val cameraRadius = DeviceBack.islandLength(0.1237f) * bodyWidth
                        assertTrue("camera not cropped: $case", cameraCenter - cameraRadius < 0f)
                        assertTrue("camera completely hidden: $case", cameraCenter + cameraRadius > 0f)
                    }
                }
            }
        }
    }

    @Test
    fun portraitPreviewsHaveLargerMatricesThanThePreviousFullWidthPhone() {
        for (panel in listOf(13, 25)) {
            for (viewport in listOf(Size(420f, 280f), Size(480f, 310f))) {
                val originalRadius = viewport.width * 0.94f * if (panel == 25) 0.215f else DeviceBack.matrix.radius
                assertTrue(toyStageMatrix(viewport, panel).radius > originalRadius * 1.05f)
            }
        }
    }
}
