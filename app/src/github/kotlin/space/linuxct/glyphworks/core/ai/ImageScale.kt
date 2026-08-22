package space.linuxct.glyphworks.core.ai

object ImageScale {

    // 1024 px is the size the Responses API's `detail: "high"` tiling works in. Anything
    // larger is bytes the far end throws away.
    const val MAX_EDGE = 1024

    const val JPEG_QUALITY = 85

    /** Powers of two only: `BitmapFactory` rounds any other `inSampleSize` down. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** In `Long`: `width * maxEdge` overflows `Int` and would give a negative side. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val w = (width.toLong() * maxEdge / longest).toInt().coerceAtLeast(1)
        val h = (height.toLong() * maxEdge / longest).toInt().coerceAtLeast(1)
        return w to h
    }

    fun needsScaling(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Boolean =
        width > 0 && height > 0 && maxOf(width, height) > maxEdge
}
