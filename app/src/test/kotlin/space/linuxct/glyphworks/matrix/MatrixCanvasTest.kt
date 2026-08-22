package space.linuxct.glyphworks.matrix

import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii

class MatrixCanvasTest {
    @Test
    fun `out of bounds drawing is ignored`() {
        val canvas = MatrixCanvas(13)
        canvas.set(-1, 0, 4095)
        canvas.set(0, -1, 4095)
        canvas.set(13, 0, 4095)
        canvas.set(0, 13, 4095)
        canvas.line(-5, -5, 20, 20, 4095)
        assertEquals(4095, canvas.get(0, 0))
        assertEquals(4095, canvas.get(12, 12))
    }

    @Test
    fun `values clamp to matrix range`() {
        val canvas = MatrixCanvas(13)
        canvas.set(1, 1, 99999)
        canvas.set(2, 1, -7)
        assertEquals(MAX_BRIGHTNESS, canvas.get(1, 1))
        assertEquals(0, canvas.get(2, 1))
    }

    @Test
    fun `light never darkens`() {
        val canvas = MatrixCanvas(13)
        canvas.light(3, 3, 3000)
        canvas.light(3, 3, 100)
        assertEquals(3000, canvas.get(3, 3))
    }

    @Test
    fun `shapes sampler 13`() {
        val canvas = MatrixCanvas(13)
        canvas.circle(6, 6, 6, 1200)
        canvas.ray(6, 6, 0f, 5f, 4095)
        canvas.ray(6, 6, 90f, 4f, 2400)
        GoldenAscii.check("canvas_13_shapes", canvas.copyOut(), 13)
    }

    @Test
    fun `progress ring quarters 13`() {
        for ((name, pct) in listOf("25" to 0.25f, "50" to 0.5f, "75" to 0.75f)) {
            val canvas = MatrixCanvas(13)
            canvas.arcRing(6f, 6f, 5f, 6.2f, 0f, 360f * pct, 4095)
            GoldenAscii.check("canvas_13_ring_$name", canvas.copyOut(), 13)
        }
    }
}
