package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS

internal object InformationDrawing {
    fun text(c: MatrixCanvas, text: String, y: Int, scale: Int, shift: Int = 0, verticalScale: Int = scale) {
        val width = text.sumOf { Font3x5.width(it) * scale } + (text.length - 1)
        var x = (c.size - width) / 2 + shift
        for (char in text) {
            val glyph = MatrixCanvas(5)
            Font3x5.draw(glyph, char, 0, 0, MAX_BRIGHTNESS)
            for (gy in 0 until 5) for (gx in 0 until Font3x5.width(char)) {
                if (glyph.get(gx, gy) > 0) c.fillRect(x + gx * scale, y + gy * verticalScale, scale, verticalScale, MAX_BRIGHTNESS)
            }
            x += Font3x5.width(char) * scale + 1
        }
    }

    fun mask(c: MatrixCanvas): IntArray = c.copyOut().also { frame ->
        frame.indices.forEach { i ->
            if (!PanelMask.contains(i % c.size, i / c.size, c.size)) frame[i] = 0
        }
    }
}
