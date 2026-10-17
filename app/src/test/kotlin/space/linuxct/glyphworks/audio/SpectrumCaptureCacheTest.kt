package space.linuxct.glyphworks.audio

import org.junit.Assert.*
import org.junit.Test

class SpectrumCaptureCacheTest {
    @Test fun manyConsumersShareOneCaptureButKeepTheirSensitivityAndPanelSamples() {
        val cache = SpectrumCaptureCache()
        var captures = 0; var conversions = 0
        fun sample(at: Long, size: Int = 13, tuning: Int = 1) = cache.sample(at, size, tuning,
            read = { captures++; byteArrayOf(captures.toByte()) },
            convert = { fft, n, gain -> conversions++; FloatArray(n) { fft[0].toFloat() * gain } })!!
        val first = sample(1000)
        first.fill(999f) // Callers cannot change another subscriber's cached spectrum.
        repeat(50) { assertEquals(1f, sample(1010).first(), 0f) }
        assertEquals(6f, sample(1010, tuning = 6).first(), 0f)
        assertEquals(25, sample(1024, size = 25).size)
        assertEquals(1, captures); assertEquals(3, conversions)
        assertEquals(2f, sample(1025).first(), 0f)
        assertEquals(2, captures)
        cache.clear()
        assertEquals(3f, sample(1026).first(), 0f)
    }

    @Test fun unavailableCaptureIsSharedAndRecoversWithoutInventingSilence() {
        val cache = SpectrumCaptureCache(); var reads = 0
        repeat(20) { assertNull(cache.sample(1000, 13, 1, { reads++; null }, { _, n, _ -> FloatArray(n) })) }
        assertEquals(1, reads)
        assertArrayEquals(floatArrayOf(0.5f), cache.sample(1025, 1, 1, { byteArrayOf(1) }, { _, _, _ -> floatArrayOf(0.5f) }), 0f)
    }
}
