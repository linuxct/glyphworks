package space.linuxct.glyphworks.matrix

object PanelMask {

    const val GRID_EXTENT = 0.84f

    /**
     * Measured from a photo of the lit panel. At 13x13 the rows hold 5, 9, 11, 11, 13, 13, 13,
     * 13, 13, 11, 11, 9, 5 LEDs, and at 25x25 the total is 489, the count Nothing publishes for
     * the Phone (3). GlyphCanvasTest asserts both.
     */
    fun contains(x: Int, y: Int, size: Int): Boolean {
        if (size <= 0 || x < 0 || y < 0 || x >= size || y >= size) return false
        val center = (size - 1) / 2f
        val dx = x - center
        val dy = y - center
        val radius = size / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    fun isEdge(x: Int, y: Int, size: Int): Boolean = contains(x, y, size) && (
        !contains(x - 1, y, size) || !contains(x + 1, y, size) ||
            !contains(x, y - 1, size) || !contains(x, y + 1, size)
        )

    fun count(size: Int): Int {
        var n = 0
        for (y in 0 until size) {
            for (x in 0 until size) if (contains(x, y, size)) n++
        }
        return n
    }
}
