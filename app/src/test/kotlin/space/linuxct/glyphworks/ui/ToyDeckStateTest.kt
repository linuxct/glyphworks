package space.linuxct.glyphworks.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToyDeckStateTest {
    private val toys = listOf("clock", "dino", "weather", "battery")

    @Test fun `only a settled centered card can begin a reorder`() {
        val state = ToyDeckState(toys, "dino")
        assertFalse(state.beginHold("clock"))
        state.swipe(0.3f)
        assertFalse(state.beginHold("dino"))
        state.stop()
        assertTrue(state.beginHold("dino"))
        assertFalse(state.beginHold("dino"))
        state.stop()
    }

    @Test fun `leaving the screen during edge reorder cancels the transaction`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val state = ToyDeckState(toys, "dino")
            assertTrue(state.beginHold("dino"))
            state.dragHeld(70f, 100f, scope) { }
            assertEquals(listOf("clock", "weather", "dino", "battery"), state.order)
            assertEquals("dino", state.selectedId)
            state.stop()
            assertEquals(toys, state.order)
            assertEquals(1f, state.position, 0f)
            assertNull(state.heldId)
            assertNull(state.finishHold(true))
            assertTrue(state.beginHold("dino"))
            state.stop()
        } finally { scope.cancel() }
    }

    @Test fun `drop persists stable identity but cancellation restores the original order`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val state = ToyDeckState(toys, "dino")
            state.beginHold("dino")
            state.dragHeld(-70f, 100f, scope) { }
            assertEquals(listOf("dino", "clock", "weather", "battery"), state.finishHold(true))
            assertEquals("dino", state.selectedId)
            assertEquals(0, state.selectedIndex)
            state.beginHold("dino")
            state.dragHeld(70f, 100f, scope) { }
            assertNull(state.finishHold(false))
            assertEquals(listOf("dino", "clock", "weather", "battery"), state.order)
            assertEquals(0, state.selectedIndex)
        } finally { scope.cancel() }
    }

    @Test fun `holding a short drag moves only one position in either direction`() = runTest {
        for (direction in listOf(-1, 1)) {
            val state = ToyDeckState(toys, "dino")
            var steps = 0
            state.beginHold("dino")
            state.dragHeld(70f * direction, 100f, this) { steps++ }
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1 + direction, state.selectedIndex)
            assertEquals(1, steps)
            assertEquals("dino", state.selectedId)
            state.finishHold(true)
        }
    }

    @Test fun `small pointer corrections cannot cause extra swaps`() = runTest {
        val state = ToyDeckState(toys, "dino")
        state.beginHold("dino")
        state.dragHeld(70f, 100f, this) { }
        repeat(5) {
            state.dragHeld(-10f, 100f, this) { }
            state.dragHeld(10f, 100f, this) { }
        }
        assertEquals(listOf("clock", "weather", "dino", "battery"), state.order)
        // Returning toward the center allows another deliberate single-slot move.
        state.dragHeld(-40f, 100f, this) { }
        state.dragHeld(40f, 100f, this) { }
        assertEquals(listOf("clock", "weather", "battery", "dino"), state.finishHold(true))
    }

    @Test fun `outer edge waits before repeating slowly one slot at a time`() = runTest {
        val roster = (0..7).map { "toy$it" }
        for (direction in listOf(-1, 1)) {
            val state = ToyDeckState(roster, "toy3")
            var steps = 0
            state.beginHold("toy3")
            state.dragHeld(130f * direction, 100f, this) { steps++ }
            advanceTimeBy(999)
            runCurrent()
            assertEquals(3 + direction, state.selectedIndex)
            assertEquals(1, steps)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3 + 2 * direction, state.selectedIndex)
            advanceTimeBy(799)
            runCurrent()
            assertEquals(2, steps)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3 + 3 * direction, state.selectedIndex)
            assertEquals(3, steps)
            state.finishHold(true)
            val droppedOrder = state.order
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(droppedOrder, state.order)
        }
    }

    @Test fun `leaving outer edge stops repeat and returning requires a fresh pause`() = runTest {
        val state = ToyDeckState(toys, "dino")
        state.beginHold("dino")
        state.dragHeld(130f, 100f, this) { }
        advanceTimeBy(900)
        state.dragHeld(-20f, 100f, this) { }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, state.selectedIndex)
        state.dragHeld(20f, 100f, this) { }
        advanceTimeBy(999)
        runCurrent()
        assertEquals(2, state.selectedIndex)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, state.selectedIndex)
        state.finishHold(false)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(toys, state.order)
        assertNull(state.heldId)
    }

    @Test fun `edge repeat stops at boundary and reversing moves back one slot`() = runTest {
        val state = ToyDeckState(toys, "weather")
        var steps = 0
        state.beginHold("weather")
        state.dragHeld(130f, 100f, this) { steps++ }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, state.selectedIndex)
        assertEquals(1, steps)
        state.dragHeld(-200f, 100f, this) { steps++ }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, state.selectedIndex)
        assertEquals(2, steps)
        assertEquals(toys, state.finishHold(true))
    }
}
