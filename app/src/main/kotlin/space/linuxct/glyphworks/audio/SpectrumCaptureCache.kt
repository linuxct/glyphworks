package space.linuxct.glyphworks.audio

/** Serialized by the audio owner. One FFT read per interval, one conversion per size/tuning. */
internal class SpectrumCaptureCache(private val intervalMs: Long = 25) {
    private var sampledAt = -1L
    private var captured: ByteArray? = null
    private val converted = mutableMapOf<Pair<Int, Int>, FloatArray>()
    fun sample(now: Long, bands: Int, tuning: Int, read: () -> ByteArray?, convert: (ByteArray, Int, Int) -> FloatArray): FloatArray? {
        if (sampledAt < 0 || now < sampledAt || now - sampledAt >= intervalMs) {
            captured = read()?.copyOf()
            sampledAt = now
            converted.clear()
        }
        val frame = captured ?: return null
        return converted.getOrPut(bands to tuning) { convert(frame, bands, tuning) }.copyOf()
    }
    fun clear() { sampledAt = -1; captured = null; converted.clear() }
}
