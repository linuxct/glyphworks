package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.matrix.PanelMask
import kotlin.math.roundToInt

/** [luminance] is row-major, `width * height` entries, 0 (black) to 255 (white). */
class SourceImage(
    val width: Int,
    val height: Int,
    val luminance: IntArray,
) {
    val isUsable: Boolean
        get() = width > 0 && height > 0 && luminance.size == width * height
}

/**
 * A photograph as a frame this app would store: fitted to the panel, masked to the disc,
 * quantised to a palette. Deterministic, so the model can read a threshold back and reuse
 * it. Nothing here throws and nothing here is Android.
 */
object ImageQuantiser {

    const val SOURCE_EDGE = 192

    // A cell no source pixel landed on. Takes no part in the range or the threshold.
    const val NO_SAMPLE = -1

    // Below this spread the picture is a flat field, and stretching it is amplified noise.
    const val MIN_RANGE = 8

    const val MIN_CONTRAST = 0.25
    const val MAX_CONTRAST = 4.0
    const val DEFAULT_CONTRAST = 1.0

    // Above this every cell falls below the cut and the frame comes out blank.
    const val MAX_THRESHOLD = 0.95

    sealed interface Result {
        data class Ok(
            val cells: String,
            val threshold: Double,
            val automatic: Boolean,
            val lit: Int,
            val sampled: Int,
            val range: Int,
        ) : Result

        data class Flat(val range: Int) : Result

        data object Unusable : Result
    }

    /** Box-averaged, not point-sampled: one pixel in six thousand would just sample noise. */
    fun sample(image: SourceImage, size: Int): IntArray {
        val out = IntArray(maxOf(size, 0) * maxOf(size, 0)) { NO_SAMPLE }
        if (size <= 0 || !image.isUsable) return out

        // Fit, not fill: the long edge lands on the panel and the short edge is centred.
        val scale = minOf(size.toDouble() / image.width, size.toDouble() / image.height)
        val fittedWidth = (image.width * scale).roundToInt().coerceIn(1, size)
        val fittedHeight = (image.height * scale).roundToInt().coerceIn(1, size)
        val originX = (size - fittedWidth) / 2
        val originY = (size - fittedHeight) / 2

        for (cellY in 0 until fittedHeight) {
            val panelY = originY + cellY
            // Bounds from the cell index, never accumulated, so rounding cannot build up.
            val top = cellY * image.height / fittedHeight
            val bottom = maxOf(top + 1, (cellY + 1) * image.height / fittedHeight)
            for (cellX in 0 until fittedWidth) {
                val panelX = originX + cellX
                if (!PanelMask.contains(panelX, panelY, size)) continue
                val left = cellX * image.width / fittedWidth
                val right = maxOf(left + 1, (cellX + 1) * image.width / fittedWidth)
                var sum = 0L
                var counted = 0L
                for (y in top until minOf(bottom, image.height)) {
                    val row = y * image.width
                    for (x in left until minOf(right, image.width)) {
                        sum += image.luminance[row + x].coerceIn(0, 255)
                        counted++
                    }
                }
                if (counted > 0) out[panelY * size + panelX] = (sum / counted).toInt()
            }
        }
        return out
    }

    /** A null [threshold] asks [otsu] for one. [invert] leaves the letterbox dark. */
    fun quantise(
        image: SourceImage,
        size: Int,
        levelCount: Int,
        threshold: Double? = null,
        contrast: Double = DEFAULT_CONTRAST,
        invert: Boolean = false,
    ): Result {
        if (size <= 0 || levelCount < 2 || !image.isUsable) return Result.Unusable

        val samples = sample(image, size)
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var sampled = 0
        for (v in samples) {
            if (v == NO_SAMPLE) continue
            sampled++
            if (v < low) low = v
            if (v > high) high = v
        }
        if (sampled == 0) return Result.Unusable

        val range = high - low
        if (range < MIN_RANGE) return Result.Flat(range)

        val gain = contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST)
        val normalised = DoubleArray(samples.size)
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            var value = (samples[i] - low).toDouble() / range
            if (invert) value = 1.0 - value
            // Gain about the mid-point: contrast pushes light and dark apart rather than
            // brightening the whole picture.
            normalised[i] = (0.5 + (value - 0.5) * gain).coerceIn(0.0, 1.0)
        }

        val automatic = threshold == null
        // Judged after inversion and gain, so the reported number reproduces this frame.
        val cut = (threshold ?: otsu(samples, normalised)).coerceIn(0.0, MAX_THRESHOLD)

        var lit = 0
        val cells = CharArray(size * size) { '0' }
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            val level = levelFor(normalised[i], cut, levelCount)
            if (level > 0) lit++
            cells[i] = base36(level)
        }
        return Result.Ok(
            cells = String(cells),
            threshold = cut,
            automatic = automatic,
            lit = lit,
            sampled = sampled,
            range = range,
        )
    }

    /** The step at [cut] is the point: at this size a nearly-off cell reads as off. */
    fun levelFor(value: Double, cut: Double, levelCount: Int): Int {
        if (levelCount < 2) return 0
        if (value < cut) return 0
        val span = 1.0 - cut
        if (span <= 0.0) return levelCount - 1
        val step = ((value - cut) / span * (levelCount - 1)).toInt()
        return (1 + step).coerceIn(1, levelCount - 1)
    }

    /** Otsu's method: the cut lands in the valley between subject and background. */
    fun otsu(samples: IntArray, normalised: DoubleArray): Double {
        val histogram = IntArray(BUCKETS)
        var total = 0
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            val bucket = (normalised[i] * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
            histogram[bucket]++
            total++
        }
        if (total == 0) return 0.5

        var sum = 0.0
        for (b in 0 until BUCKETS) sum += b.toDouble() * histogram[b]

        var backgroundWeight = 0
        var backgroundSum = 0.0
        var best = 0.0
        var bestBucket = BUCKETS / 2
        for (b in 0 until BUCKETS - 1) {
            backgroundWeight += histogram[b]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break
            backgroundSum += b.toDouble() * histogram[b]
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val between = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (between > best) {
                best = between
                bestBucket = b
            }
        }
        // bestBucket is still background, so the cut sits just above it.
        return ((bestBucket + 1).toDouble() / (BUCKETS - 1)).coerceIn(0.0, MAX_THRESHOLD)
    }

    private const val BUCKETS = 256

    private fun base36(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))
}
