package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

/** A design frame as a text grid, so the model can see the art it just wrote. */
object GlyphAsciiPreview {

    const val OFF_PANEL: Char = ' '

    const val RAMP: String = ".:-=+*#@"

    const val LEGEND: String =
        "legend: a space means the panel has no LED at that cell (it is outside the disc), " +
            "'.' means an LED that is off, and ':' '-' '=' '+' '*' '#' '@' are increasing brightness"

    /** Any brightness above 0 gets at least RAMP[1], or dim palette entries read as off. */
    fun charFor(brightness: Int): Char {
        if (brightness <= 0) return RAMP[0]
        val lit = brightness.coerceAtMost(DesignFrames.MAX_BRIGHTNESS)
        val steps = RAMP.length - 2
        val index = 1 + (lit.toLong() * steps / DesignFrames.MAX_BRIGHTNESS).toInt()
        return RAMP[index.coerceIn(1, RAMP.length - 1)]
    }

    /** A wrong-length frame still renders, with the uncovered cells blank. Never throws. */
    fun render(frame: IntArray, size: Int): String {
        if (size <= 0) return ""
        val sb = StringBuilder((size + 1) * size)
        for (y in 0 until size) {
            if (y > 0) sb.append('\n')
            for (x in 0 until size) {
                if (!PanelMask.contains(x, y, size)) {
                    sb.append(OFF_PANEL)
                    continue
                }
                val cell = y * size + x
                sb.append(if (cell < frame.size) charFor(frame[cell]) else OFF_PANEL)
            }
        }
        return sb.toString()
    }

    /** Null, not a best-effort drawing: a picture of a rejected frame would mislead. */
    fun renderCells(cells: String, levels: List<Int>, size: Int): String? {
        val decoded = DesignFrames.decode(cells, levels, size) ?: return null
        return render(decoded, size)
    }

    fun renderCells(cells: String, levels: List<Int>, codename: PokemonCodename): String? =
        renderCells(cells, levels, codename.size)

    /** `#` where the panel has an LED, blank where it does not. Goes in the prompt. */
    fun panelMap(size: Int): String {
        if (size <= 0) return ""
        val sb = StringBuilder((size + 1) * size)
        for (y in 0 until size) {
            if (y > 0) sb.append('\n')
            for (x in 0 until size) {
                sb.append(if (PanelMask.contains(x, y, size)) '#' else OFF_PANEL)
            }
        }
        return sb.toString()
    }

    /** The disc is convex, so a row's live cells are one unbroken first-to-last run. */
    fun liveSpans(size: Int): List<IntRange?> = (0 until size).map { y ->
        val first = (0 until size).firstOrNull { PanelMask.contains(it, y, size) }
        val last = (size - 1 downTo 0).firstOrNull { PanelMask.contains(it, y, size) }
        if (first == null || last == null) null else first..last
    }

    fun liveSpanTable(size: Int): String = liveSpans(size).withIndex().joinToString("\n") { (y, span) ->
        if (span == null) "  row $y: no cells"
        else "  row $y: columns ${span.first}-${span.last} (${span.last - span.first + 1} cells)"
    }
}
