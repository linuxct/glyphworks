package space.linuxct.glyphworks.core.design

import kotlin.math.abs
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS as PANEL_MAX_BRIGHTNESS

/**
 * The `cells` string of a design frame: one character per cell, holding the palette index in
 * base36 (`0`-`9` then `a`-`z`). Cells run row-major, so the character at `y * size + x` is
 * the cell at (x, y). Bad input comes back as null.
 */
object DesignFrames {

    const val MAX_PALETTE = 36

    const val MAX_BRIGHTNESS = PANEL_MAX_BRIGHTNESS

    fun decode(cells: String, levels: List<Int>, size: Int): IntArray? {
        if (size <= 0) return null
        val count = size * size
        if (cells.length != count) return null
        if (levels.isEmpty()) return null
        val out = IntArray(count)
        for (i in 0 until count) {
            val index = indexOfChar(cells[i])
            if (index < 0 || index >= levels.size) return null
            out[i] = levels[index].coerceIn(0, MAX_BRIGHTNESS)
        }
        return out
    }

    fun encode(frame: IntArray, levels: List<Int>, size: Int): String? {
        if (size <= 0) return null
        if (frame.size != size * size) return null
        if (levels.isEmpty() || levels.size > MAX_PALETTE) return null
        val sb = StringBuilder(frame.size)
        for (value in frame) {
            sb.append(charOfIndex(nearestLevel(value, levels)))
        }
        return sb.toString()
    }

    fun blank(codename: PokemonCodename): String = "0".repeat(codename.cellCount)

    fun nearestLevel(value: Int, levels: List<Int>): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in levels.indices) {
            val distance = abs(levels[i].coerceIn(0, MAX_BRIGHTNESS) - value)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    // Not `Character.digit`, which also accepts non-ASCII digits.
    private fun indexOfChar(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }

    private fun charOfIndex(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))
}
