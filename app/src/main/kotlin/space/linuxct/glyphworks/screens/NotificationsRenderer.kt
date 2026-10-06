package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/** One presentation state per visible toy, shared by standalone and Ambient adapters. */
class NotificationsPresentation {
    private var startedAt = 0L
    private var style: String? = null

    fun reset() { style = null }
    fun elapsed(c: ScreenContext) = (c.ports.clock.elapsedMillis() - startedAt).coerceAtLeast(0)

    fun render(c: ScreenContext): IntArray {
        val selected = NotificationPrefs.style(c.prefs)
        val elapsed = c.ports.clock.elapsedMillis()
        if (style != selected || elapsed < startedAt) {
            style = selected
            startedAt = elapsed
        }
        return NotificationsRenderer.renderFrame(c.size, c.ports.notifications.count(), selected, elapsed - startedAt)
    }
}

object NotificationsRenderer {
    const val HOLD_MS = 3_000L
    const val SLIDE_MS = 1_000L
    const val LOOP_MS = 2 * (HOLD_MS + SLIDE_MS)

    private val LABEL_GLYPHS = listOf(
        listOf(" # ", "###", " # ", " ##"),
        listOf("   ", "# #", " # ", "# #"),
        listOf(" # ", "###", " # ", " ##"),
    )

    fun renderFrame(size: Int, count: Int?, style: String = NotificationPrefs.DEFAULT_STYLE, elapsedMs: Long = 0,
                    iconFrame: IntArray? = null, countFrame: IntArray? = null): IntArray {
        val c = MatrixCanvas(size)
        val text = when { count == null -> "?"; count > 9 -> "9+"; else -> count.coerceAtLeast(0).toString() }
        fun replacement(frame: IntArray?, shift: Int = 0): Boolean {
            if (frame == null || frame.size != size * size) return false
            for (y in 0 until size) for (x in 0 until size) if (frame[y * size + x] > 0) c.set(x + shift, y, frame[y * size + x])
            return true
        }
        fun drawBell(shift: Int) { if (!replacement(iconFrame, shift)) bell(c, shift) }
        fun drawLargeCount(shift: Int) { if (!replacement(countFrame, shift)) largeCount(c, text, shift) }
        when (NotificationPrefs.normalize(style)) {
            NotificationPrefs.ENVELOPE -> {
                if (!replacement(iconFrame)) envelope(c)
                if (!replacement(countFrame)) smallCount(c, text, envelopeStyle = true)
            }
            NotificationPrefs.DOT -> {
                drawLargeCount(0)
                if (!replacement(iconFrame)) if (size >= 25) c.blit(listOf(" # ", "###", " # "), 19, 4, MAX_BRIGHTNESS)
                else c.set(10, 2, MAX_BRIGHTNESS)
            }
            NotificationPrefs.BELL -> {
                val phase = elapsedMs.coerceAtLeast(0) % LOOP_MS
                when {
                    phase < HOLD_MS -> drawBell(0)
                    phase < HOLD_MS + SLIDE_MS -> {
                        val shift = ((phase - HOLD_MS) * size / SLIDE_MS).toInt()
                        drawBell(-shift)
                        drawLargeCount(size - shift)
                    }
                    phase < 2 * HOLD_MS + SLIDE_MS -> drawLargeCount(0)
                    else -> {
                        val shift = ((phase - 2 * HOLD_MS - SLIDE_MS) * size / SLIDE_MS).toInt()
                        drawLargeCount(-shift)
                        drawBell(size - shift)
                    }
                }
            }
            else -> {
                if (!replacement(iconFrame)) label(c)
                if (!replacement(countFrame)) smallCount(c, text)
            }
        }
        return InformationDrawing.mask(c)
    }

    private fun smallCount(c: MatrixCanvas, text: String, envelopeStyle: Boolean = false) {
        val large = c.size >= 25
        val lowered = envelopeStyle && !large
        // At the bottom of the 13×13 circle, shift 9+ right to keep the 9's last row intact.
        InformationDrawing.text(c, text, if (large) 13 else if (lowered) 8 else 7,
            if (large) 2 else 1, shift = if (lowered && text.length > 1) 1 else 0)
    }

    private fun largeCount(c: MatrixCanvas, text: String, shift: Int) {
        val scale = if (c.size >= 25) { if (text.length == 1) 4 else 3 } else 2
        // The compact 9+ keeps the same height on 13×13 without clipping its sides.
        val horizontal = if (c.size < 25 && text.length > 1) 1 else scale
        InformationDrawing.text(c, text, (c.size - 5 * scale) / 2, horizontal, shift, scale)
    }

    private fun envelope(c: MatrixCanvas) {
        val large = c.size >= 25
        val top = if (large) 3 else 1
        val depth = if (large) 7 else 3
        // Extend only the 13×13 outline downward; preserve the dimmer flap's pixels.
        c.rect(if (large) 5 else 2, top, if (large) 15 else 9, if (large) 9 else 6, MAX_BRIGHTNESS)
        for (step in 1..depth) {
            val offset = depth - step
            c.set(c.size / 2 - offset, top + step, 1800)
            c.set(c.size / 2 + offset, top + step, 1800)
        }
    }

    private fun label(c: MatrixCanvas) {
        val scale = if (c.size >= 25) 2 else 1
        val top = if (c.size >= 25) 3 else 2
        val left = (c.size - (9 * scale + 2)) / 2
        LABEL_GLYPHS.forEachIndexed { index, rows ->
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, pixel ->
                    if (pixel == '#') c.fillRect(
                        left + index * (3 * scale + 1) + x * scale,
                        top + y * scale, scale, scale, MAX_BRIGHTNESS,
                    )
                }
            }
        }
    }

    private fun bell(c: MatrixCanvas, shift: Int) {
        if (c.size >= 25) {
            c.fillRect(11 + shift, 2, 3, 2, MAX_BRIGHTNESS)
            c.fillRect(9 + shift, 4, 7, 2, MAX_BRIGHTNESS)
            c.fillRect(7 + shift, 6, 2, 2, MAX_BRIGHTNESS)
            c.fillRect(16 + shift, 6, 2, 2, MAX_BRIGHTNESS)
            c.fillRect(6 + shift, 8, 2, 8, MAX_BRIGHTNESS)
            c.fillRect(17 + shift, 8, 2, 8, MAX_BRIGHTNESS)
            c.fillRect(4 + shift, 16, 3, 2, MAX_BRIGHTNESS)
            c.fillRect(18 + shift, 16, 3, 2, MAX_BRIGHTNESS)
            c.fillRect(3 + shift, 18, 19, 2, MAX_BRIGHTNESS)
            c.fillRect(10 + shift, 21, 5, 2, MAX_BRIGHTNESS)
        } else {
            c.blit(listOf(
                "    ###    ",
                "   #####   ",
                "  ##   ##  ",
                "  #     #  ",
                "  #     #  ",
                "  #     #  ",
                "  #     #  ",
                " ##     ## ",
                "###########",
                "           ",
                "    ###    ",
            ), 1 + shift, 1, MAX_BRIGHTNESS)
        }
    }
}
