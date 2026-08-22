package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.KeyMode

class DesignPlaybackTest {

    @Test
    fun `playback walks the frames in order`() {
        assertEquals(1, nextPlaybackFrame(0, count = 3, loop = false))
        assertEquals(2, nextPlaybackFrame(1, count = 3, loop = false))
    }

    @Test
    fun `a looping design starts again`() {
        assertEquals(0, nextPlaybackFrame(2, count = 3, loop = true))
    }

    @Test
    fun `a non-looping design holds its last frame`() {
        assertNull(nextPlaybackFrame(2, count = 3, loop = false))
    }

    @Test
    fun `repeat is the design's own field when the key plays and pauses`() {
        assertTrue(designRepeats(loop = true, keyMode = KeyMode.PLAY_PAUSE))
        assertFalse(designRepeats(loop = false, keyMode = KeyMode.PLAY_PAUSE))
    }

    @Test
    fun `a frame is held for exactly as long as the design says`() {
        assertEquals(120L, playbackHoldMs(120))
        assertEquals(60_000L, playbackHoldMs(60_000))
    }

    private fun run(durationsMs: List<Int>, loop: Boolean, limit: Int = 12): List<Pair<Int, Long>> {
        val pushes = mutableListOf<Pair<Int, Long>>()
        var index = 0
        while (pushes.size < limit) {
            pushes += index to playbackHoldMs(durationsMs[index])
            index = nextPlaybackFrame(index, durationsMs.size, loop) ?: break
        }
        return pushes
    }

    @Test
    fun `a design that does not repeat plays through once`() {
        val pushes = run(listOf(100, 250, 20), loop = false)
        assertEquals(listOf(0, 1, 2), pushes.map { it.first })
        assertEquals(listOf(100L, 250L, PREVIEW_INTERVAL_MS), pushes.map { it.second })
    }

    @Test
    fun `a repeating design cycles for as long as it is left running`() {
        val pushes = run(listOf(100, 250, 20), loop = true, limit = 7)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), pushes.map { it.first })
    }

    @Test
    fun `the preview holds each frame for its own duration`() {
        val durations = listOf(100, 200, 300)
        val playback = PreviewPlayback()
        playback.tick(0, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
        playback.tick(99, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
        playback.tick(100, durations.size, loop = true) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(299, durations.size, loop = true) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(300, durations.size, loop = true) { durations[it] }
        assertEquals(2, playback.frameIndex)
        playback.tick(600, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
    }

    @Test
    fun `the preview rests on the last frame of a design that does not repeat`() {
        val durations = listOf(100, 200)
        val playback = PreviewPlayback()
        playback.tick(0, durations.size, loop = false) { durations[it] }
        playback.tick(100, durations.size, loop = false) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(100 + 200 + PREVIEW_REST_MS - 1, durations.size, loop = false) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(100 + 200 + PREVIEW_REST_MS, durations.size, loop = false) { durations[it] }
        assertEquals(0, playback.frameIndex)
    }

    private val phone = DpSize(411.dp, 919.dp)

    @Test
    fun `the resting preview is a fixed corner disc on a phone`() {
        assertEquals(72.dp, floatingPreviewDiameter(phone, expanded = false))
    }

    @Test
    fun `the expanded preview is near-fullscreen but not fullscreen`() {
        val large = floatingPreviewDiameter(phone, expanded = true)
        assertTrue("$large should be most of the width", large > 340.dp)
        assertTrue("$large should not fill the width", large < 411.dp)
    }

    @Test
    fun `a frame keeps its own cache`() {
        val caches = FramePreviewCaches()
        assertSame(caches.of(7L), caches.of(7L))
        assertNotSame(caches.of(7L), caches.of(8L))
    }

    private companion object {
        const val SAMPLES = 400
        const val TOLERANCE = 1e-4f
    }
}
