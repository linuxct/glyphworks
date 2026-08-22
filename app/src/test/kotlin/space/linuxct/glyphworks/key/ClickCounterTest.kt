package space.linuxct.glyphworks.key

import org.junit.Assert.assertEquals
import org.junit.Test

private const val TEST_WINDOW_MS = 400L

class ClickCounterTest {

    @Test
    fun `presses inside the window accumulate`() {
        val counter = ClickCounter(TEST_WINDOW_MS)
        assertEquals(1, counter.onPress(1000))
        assertEquals(2, counter.onPress(1300))
        assertEquals(3, counter.onPress(1650))
        assertEquals(3, counter.finish())
    }

    @Test
    fun `a gap larger than the window starts a new burst`() {
        val counter = ClickCounter(TEST_WINDOW_MS)
        counter.onPress(1000)
        assertEquals(1, counter.onPress(1500))
    }

    @Test
    fun `finish resets the burst`() {
        val counter = ClickCounter(TEST_WINDOW_MS)
        counter.onPress(1000)
        counter.onPress(1200)
        assertEquals(2, counter.finish())
        assertEquals(0, counter.finish())
        assertEquals(1, counter.onPress(1300))
    }

    @Test
    fun `four or more clicks are reported as counted`() {
        val counter = ClickCounter(TEST_WINDOW_MS)
        repeat(5) { counter.onPress(1000L + it * 100) }
        assertEquals(5, counter.finish())
    }
}
