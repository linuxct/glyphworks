package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/** A tally counter. Glyph Touch adds one, shake resets it, and the count lives in prefs. */
class CounterScreen : GlyphScreen {
    override val id = "counter"
    override val interactive = true

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        push()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        val c = ctx ?: return
        when (event) {
            Events.CHANGE -> {
                val v = (c.prefs.getInt(PrefKeys.COUNTER, PrefKeys.COUNTER_DEF) + 1) % WRAP_AT
                c.prefs.putInt(PrefKeys.COUNTER, v)
                push()
            }
            Events.SHAKE -> {
                c.prefs.putInt(PrefKeys.COUNTER, 0)
                blinkConfirm()
            }
        }
    }

    private fun blinkConfirm() {
        val c = ctx ?: return
        push()
        c.scheduler.postDelayed(BLINK_DARK_MS) { ctx?.pushFrame(IntArray(c.size * c.size)) }
        c.scheduler.postDelayed(BLINK_END_MS) { push() }
    }

    private fun push() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, c.prefs.getInt(PrefKeys.COUNTER, PrefKeys.COUNTER_DEF)))
    }

    companion object {
        const val MAX_COUNT = 999
        private const val WRAP_AT = MAX_COUNT + 1

        private const val BLINK_DARK_MS = 150L
        private const val BLINK_END_MS = 300L

        fun renderFrame(size: Int, value: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val text = value.coerceIn(0, MAX_COUNT).toString()
            if (size >= 25) {
                val columns = listOf(4, 11, 18)
                val y = 10
                drawAtColumns(canvas, text, columns, y)
            } else {
                val columns = listOf(0, 5, 10)
                val y = 4
                drawAtColumns(canvas, text, columns, y)
            }
            return canvas.copyOut()
        }

        /** Right-aligns [text] on the fixed digit columns, units last. */
        private fun drawAtColumns(canvas: MatrixCanvas, text: String, columns: List<Int>, y: Int) {
            val start = columns.size - text.length
            text.forEachIndexed { i, ch ->
                Font3x5.draw(canvas, ch, columns[start + i], y, MAX_BRIGHTNESS)
            }
        }
    }
}
