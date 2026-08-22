package space.linuxct.glyphworks.core.design

import space.linuxct.glyphworks.matrix.PanelMask

object MarqueeText {

    const val DEFAULT_DURATION_MS: Int = 80

    fun scaleFor(size: Int): Int = maxOf(1, size / MarqueeFont.HEIGHT)

    fun defaultStep(size: Int): Int = scaleFor(size)

    fun topRow(size: Int, scale: Int): Int = (size - MarqueeFont.HEIGHT * scale) / 2

    fun frameCount(size: Int, stripWidth: Int, scale: Int, step: Int): Int {
        if (size <= 0 || stripWidth <= 0 || scale < 1 || step < 1) return 0
        val travel = size + stripWidth * scale - 2
        return if (travel < 0) 1 else travel / step + 1
    }

    fun frameCount(text: String, size: Int, scale: Int, step: Int): Int =
        frameCount(size, MarqueeFont.stripWidth(text), scale, step)

    fun maxPrefixLength(
        text: String,
        size: Int,
        scale: Int,
        step: Int,
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): Int {
        if (size <= 0 || scale < 1 || step < 1 || maxFrames < 1) return 0
        var columns = 0
        var fits = 0
        for ((i, c) in text.withIndex()) {
            val width = MarqueeFont.width(c)
            if (width <= 0) return fits
            val next = if (i == 0) width else columns + MarqueeFont.GAP + width
            if (frameCount(size, next, scale, step) > maxFrames) return fits
            columns = next
            fits = i + 1
        }
        return fits
    }

    fun frames(
        text: String,
        size: Int,
        paletteIndex: Int = 1,
        durationMs: Int = DEFAULT_DURATION_MS,
        scale: Int = scaleFor(size),
        step: Int = scaleFor(size),
    ): List<DesignFrame> {
        if (size <= 0 || scale < 1 || step < 1) return emptyList()
        if (paletteIndex < 1 || paletteIndex >= DesignFrames.MAX_PALETTE) return emptyList()
        val strip = MarqueeFont.strip(text)
        if (strip.isEmpty()) return emptyList()
        val glyphHeight = MarqueeFont.HEIGHT * scale
        if (glyphHeight > size) return emptyList()

        val top = topRow(size, scale)
        val count = frameCount(size, strip.size, scale, step)
        if (count < 1 || count > DesignCodec.MAX_FRAMES) return emptyList()
        val lit = charOfIndex(paletteIndex)

        val out = ArrayList<DesignFrame>(count)
        for (f in 0 until count) {
            val offset = (size - 1) - f * step
            val cells = CharArray(size * size) { '0' }
            for (x in 0 until size) {
                val messageColumn = Math.floorDiv(x - offset, scale)
                if (messageColumn < 0 || messageColumn >= strip.size) continue
                val mask = strip[messageColumn]
                if (mask == 0) continue
                for (r in 0 until MarqueeFont.HEIGHT) {
                    if (mask and (1 shl r) == 0) continue
                    for (k in 0 until scale) {
                        val y = top + r * scale + k
                        // Clip here, not at render time, so the file holds only cells the disc lights.
                        if (y < 0 || y >= size) continue
                        if (!PanelMask.contains(x, y, size)) continue
                        cells[y * size + x] = lit
                    }
                }
            }
            out.add(DesignFrame(durationMs, String(cells)))
        }

        val first = out.indexOfFirst { !it.isAllDark() }
        if (first < 0) return emptyList()
        val last = out.indexOfLast { !it.isAllDark() }
        return out.subList(first, last + 1).toList()
    }

    fun stepThatFits(
        text: String,
        size: Int,
        scale: Int = scaleFor(size),
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): Int? {
        if (size <= 0 || scale < 1 || maxFrames < 2) return null
        val stripWidth = MarqueeFont.stripWidth(text)
        if (stripWidth <= 0) return null
        val travel = size + stripWidth * scale - 2
        val needed = if (travel <= 0) 1 else (travel + maxFrames - 2) / (maxFrames - 1)
        return needed.takeIf { it in 1..size }
    }

    fun plan(
        text: String,
        size: Int,
        paletteIndex: Int = 1,
        durationMs: Int = DEFAULT_DURATION_MS,
        scale: Int = scaleFor(size),
        step: Int = scaleFor(size),
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): MarqueePlan {
        if (text.isEmpty()) return MarqueePlan.Blank
        val missing = MarqueeFont.unsupported(text)
        if (missing.isNotEmpty()) return MarqueePlan.Unsupported(missing)
        val stripWidth = MarqueeFont.stripWidth(text)
        val needed = frameCount(size, stripWidth, scale, step)
        if (needed > maxFrames) {
            return MarqueePlan.TooLong(
                framesNeeded = needed,
                maxFrames = maxFrames,
                prefix = text.take(maxPrefixLength(text, size, scale, step, maxFrames)),
                stepThatFits = stepThatFits(text, size, scale, maxFrames)?.takeIf { it > step },
            )
        }
        val frames = frames(
            text = text,
            size = size,
            paletteIndex = paletteIndex,
            durationMs = durationMs,
            scale = scale,
            step = step,
        )
        return if (frames.isEmpty()) MarqueePlan.Blank else MarqueePlan.Ready(frames)
    }

    private fun charOfIndex(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))

    private fun DesignFrame.isAllDark(): Boolean = cells.all { it == '0' }
}

sealed interface MarqueePlan {

    data class Ready(val frames: List<DesignFrame>) : MarqueePlan

    data class Unsupported(val characters: List<Char>) : MarqueePlan

    data class TooLong(
        val framesNeeded: Int,
        val maxFrames: Int,
        val prefix: String,
        val stepThatFits: Int?,
    ) : MarqueePlan

    data object Blank : MarqueePlan
}
