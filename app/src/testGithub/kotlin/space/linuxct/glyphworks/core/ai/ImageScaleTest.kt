package space.linuxct.glyphworks.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageScaleTest {

    @Test
    fun `a photo from a phone camera comes down to the cap on its long edge`() {
        val (w, h) = ImageScale.targetSize(4032, 3024)
        assertEquals(ImageScale.MAX_EDGE, w)
        assertEquals(768, h)
    }

    @Test
    fun `nothing exceeds the cap, collapses to zero, or changes shape`() {
        val sizes = listOf(
            4032 to 3024, 3024 to 4032, 8000 to 6000, 1024 to 1024, 1025 to 1,
            1 to 1025, 12000 to 9, 9 to 12000, 2000 to 2000, 1080 to 1920,
        )
        for ((width, height) in sizes) {
            val (w, h) = ImageScale.targetSize(width, height)
            val what = "${width}x$height -> ${w}x$h"
            assertTrue(what, maxOf(w, h) <= ImageScale.MAX_EDGE)
            assertTrue(what, w >= 1 && h >= 1)
            if (minOf(w, h) > 1) {
                val before = width.toDouble() / height
                val after = w.toDouble() / h
                assertTrue("$what distorted", abs(before - after) / before < 0.02)
            }
        }
    }

    @Test
    fun `an image already within the cap is left exactly as it is`() {
        assertEquals(800 to 600, ImageScale.targetSize(800, 600))
        assertEquals(1024 to 1024, ImageScale.targetSize(1024, 1024))
        assertFalse(ImageScale.needsScaling(1024, 1024))
        assertTrue(ImageScale.needsScaling(1025, 10))
    }

    @Test
    fun `an image at or below the cap is decoded whole`() {
        assertEquals(1, ImageScale.sampleSize(1024, 768))
        assertEquals(1, ImageScale.sampleSize(640, 480))
        assertEquals(1, ImageScale.sampleSize(0, 0))
    }

    @Test
    fun `a large photo is subsampled rather than decoded at full size`() {
        assertEquals(2, ImageScale.sampleSize(4032, 3024))
        assertEquals(4, ImageScale.sampleSize(8000, 6000))
    }

    @Test
    fun `the data url is well formed`() {
        val url = ChatWire.imageDataUrl("QUJD")
        assertEquals("data:image/jpeg;base64,QUJD", url)
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals("QUJD", url.substringAfter("base64,"))
        assertEquals(
            "data:image/png;base64,QUJD",
            ChatWire.imageDataUrl("QUJD", "image/png"),
        )
    }
}
