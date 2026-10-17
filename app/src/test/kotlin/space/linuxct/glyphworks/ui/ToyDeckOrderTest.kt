package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyDeckOrderTest {
    private val roster = listOf("ambient", "clock", "notifications", "weather")

    @Test
    fun `saved order trims IDs drops removed toys and duplicates and appends new toys`() {
        assertEquals(
            listOf("weather", "clock", "ambient", "notifications"),
            normalizeToyOrder(" weather ,missing,clock,weather,, clock ", roster),
        )
    }

    @Test
    fun `empty or obsolete saved order follows the known roster`() {
        assertEquals(roster, normalizeToyOrder("", roster))
        assertEquals(roster, normalizeToyOrder("missing, old", roster))
        assertEquals(emptyList<String>(), normalizeToyOrder("clock", emptyList()))
    }

    @Test
    fun `normalization stays stable and tolerates duplicate roster entries`() {
        val normalized = normalizeToyOrder("weather,ambient", roster + "clock")
        assertEquals(listOf("weather", "ambient", "clock", "notifications"), normalized)
        assertEquals(normalized, normalizeToyOrder(normalized.joinToString(","), roster))
    }

    @Test
    fun `selection is identified by ID and indices clamp to deck edges`() {
        val deck = ToyDeckOrder(roster, "notifications")
        assertEquals("notifications", deck.selectedId)
        assertEquals(2, deck.selectedIndex)
        deck.select(Int.MIN_VALUE)
        assertEquals("ambient", deck.selectedId)
        deck.select(Int.MAX_VALUE)
        assertEquals(ToyRequest.ID, deck.selectedId)
        assertEquals(4, deck.selectedIndex)
        deck.select(3)
        assertEquals("weather", deck.selectedId)
    }

    @Test
    fun `missing initial selection falls back to the first toy`() {
        val deck = ToyDeckOrder(roster, "removed")
        assertEquals("ambient", deck.selectedId)
        assertEquals(0, deck.selectedIndex)
    }

    @Test
    fun `request card is appended exactly once and never enters persisted order`() {
        val deck = ToyDeckOrder(
            listOf(ToyRequest.ID, "weather", "clock", ToyRequest.ID, "weather", "ambient"),
            "clock",
        )
        val realOrder = listOf("weather", "clock", "ambient")
        assertEquals(realOrder, deck.order)
        assertEquals(realOrder + ToyRequest.ID, deck.cards)
        assertEquals(realOrder, deck.finishReorder(commit = true))
        assertEquals(realOrder, deck.finishReorder(commit = false))
        assertEquals(realOrder, normalizeToyOrder("${ToyRequest.ID},weather,clock,ambient", realOrder))
    }

    @Test
    fun `request card can retain focus but cannot be dragged or moved by accessibility`() {
        val deck = ToyDeckOrder(roster, ToyRequest.ID)
        assertEquals(ToyRequest.ID, deck.selectedId)
        assertEquals(roster.size, deck.selectedIndex)
        assertFalse(deck.beginReorder(ToyRequest.ID))
        assertFalse(deck.reordering)
        assertFalse(deck.moveHeld(-1))
        assertFalse(deck.moveSelected(-1))
        assertFalse(deck.moveSelected(1))
        assertEquals(roster, deck.order)
        assertEquals(roster + ToyRequest.ID, deck.cards)
        deck.select(0)
        assertEquals("ambient", deck.selectedId)
    }

    @Test
    fun `last real toy cannot move into or past the request slot`() {
        val deck = ToyDeckOrder(roster, "weather")
        assertFalse(deck.moveSelected(1))
        assertTrue(deck.beginReorder("weather"))
        assertFalse(deck.moveHeld(1))
        assertTrue(deck.moveHeld(-1))
        assertEquals(listOf("ambient", "clock", "weather", "notifications", ToyRequest.ID), deck.cards)
        assertTrue(deck.moveHeld(1))
        assertFalse(deck.moveHeld(Int.MAX_VALUE))
        assertEquals(roster, deck.finishReorder(commit = true))
        assertEquals(roster + ToyRequest.ID, deck.cards)
    }

    @Test
    fun `only the selected card can start a drag and another drag cannot replace it`() {
        val deck = ToyDeckOrder(roster, "clock")
        assertFalse(deck.beginReorder("weather"))
        assertFalse(deck.beginReorder("missing"))
        assertFalse(deck.reordering)
        assertTrue(deck.beginReorder("clock"))
        assertTrue(deck.reordering)
        assertFalse(deck.beginReorder("clock"))
        assertFalse(deck.beginReorder("weather"))
    }

    @Test
    fun `held moves are single slots and preserve selected identity through commit`() {
        val deck = ToyDeckOrder(roster, "clock")
        assertTrue(deck.beginReorder("clock"))
        assertTrue(deck.moveHeld(Int.MAX_VALUE))
        assertEquals(listOf("ambient", "notifications", "clock", "weather"), deck.order)
        assertEquals("clock", deck.selectedId)
        assertEquals(2, deck.selectedIndex)
        assertTrue(deck.moveHeld(1))
        assertFalse(deck.moveHeld(1))
        assertEquals(3, deck.selectedIndex)
        val result = deck.finishReorder(commit = true)
        assertEquals(listOf("ambient", "notifications", "weather", "clock"), result)
        assertEquals(result, deck.order)
        assertEquals(result + ToyRequest.ID, deck.cards)
        assertEquals("clock", deck.selectedId)
        assertFalse(deck.reordering)
    }

    @Test
    fun `cancel restores the whole original order after several moves`() {
        val deck = ToyDeckOrder(roster, "notifications")
        assertTrue(deck.beginReorder("notifications"))
        assertTrue(deck.moveHeld(-1))
        assertTrue(deck.moveHeld(-1))
        assertFalse(deck.moveHeld(-1))
        assertTrue(deck.moveHeld(1))
        assertEquals(roster, deck.finishReorder(commit = false))
        assertEquals(roster + ToyRequest.ID, deck.cards)
        assertEquals("notifications", deck.selectedId)
        assertEquals(2, deck.selectedIndex)
        assertFalse(deck.reordering)
    }

    @Test
    fun `selection and accessibility actions cannot steal the held toy`() {
        val deck = ToyDeckOrder(roster, "clock")
        assertTrue(deck.beginReorder("clock"))
        deck.select(roster.size)
        assertEquals("clock", deck.selectedId)
        assertFalse(deck.moveSelected(1))
        assertTrue(deck.moveHeld(1))
        deck.finishReorder(commit = false)
        assertEquals("clock", deck.selectedId)
        assertEquals(roster, deck.order)
    }

    @Test
    fun `accessibility moves outside drag follow the selected toy and reject boundary noops`() {
        val deck = ToyDeckOrder(roster, "ambient")
        assertFalse(deck.moveSelected(-1))
        assertFalse(deck.moveSelected(0))
        assertFalse(deck.moveHeld(1))
        assertTrue(deck.moveSelected(1))
        assertEquals(listOf("clock", "ambient", "notifications", "weather"), deck.order)
        assertEquals("ambient", deck.selectedId)
        assertEquals(1, deck.selectedIndex)
        assertTrue(deck.moveSelected(Int.MIN_VALUE))
        assertEquals(roster, deck.order)
        assertFalse(deck.reordering)
    }

    @Test
    fun `cancel uses the order at drag start and a repeated finish is harmless`() {
        val deck = ToyDeckOrder(roster, "clock")
        assertTrue(deck.moveSelected(1))
        val baseline = deck.order
        deck.beginReorder("clock")
        deck.moveHeld(1)
        assertEquals(baseline, deck.finishReorder(commit = false))
        assertEquals(baseline, deck.finishReorder(commit = false))
        assertEquals(baseline, deck.finishReorder(commit = true))
    }

    @Test
    fun `order snapshots and constructor input never alias internal state`() {
        val input = roster.toMutableList()
        val deck = ToyDeckOrder(input, "clock")
        val snapshot = deck.order
        val cardSnapshot = deck.cards
        input.clear()
        assertEquals(roster, deck.order)
        deck.moveSelected(1)
        assertEquals(roster, snapshot)
        assertEquals(roster + ToyRequest.ID, cardSnapshot)
        val result = deck.finishReorder(commit = true)
        deck.moveSelected(1)
        assertEquals(listOf("ambient", "notifications", "clock", "weather"), result)
    }

    @Test
    fun `empty and single item decks safely reject moves`() {
        val empty = ToyDeckOrder(emptyList(), "removed")
        empty.select(7)
        assertEquals(ToyRequest.ID, empty.selectedId)
        assertEquals(0, empty.selectedIndex)
        assertEquals(listOf(ToyRequest.ID), empty.cards)
        assertFalse(empty.beginReorder(ToyRequest.ID))
        assertFalse(empty.moveSelected(1))
        assertFalse(empty.moveSelected(-1))
        empty.select(Int.MIN_VALUE)
        assertEquals(ToyRequest.ID, empty.selectedId)
        assertEquals(emptyList<String>(), empty.finishReorder(commit = false))

        val requestOnly = ToyDeckOrder(listOf(ToyRequest.ID, ToyRequest.ID), "removed")
        assertEquals(emptyList<String>(), requestOnly.order)
        assertEquals(listOf(ToyRequest.ID), requestOnly.cards)
        assertEquals(ToyRequest.ID, requestOnly.selectedId)

        val single = ToyDeckOrder(listOf("ambient"), "ambient")
        assertTrue(single.beginReorder("ambient"))
        assertFalse(single.moveHeld(1))
        assertFalse(single.moveHeld(-1))
        assertFalse(single.moveHeld(0))
        assertEquals(listOf("ambient"), single.finishReorder(commit = true))
        single.select(1)
        assertEquals(ToyRequest.ID, single.selectedId)
        assertFalse(single.beginReorder(ToyRequest.ID))
    }

    @Test
    fun `slow drags snap to the nearest page`() {
        assertEquals(0, toyDeckSnapTarget(0.3f, 0f, 8, swipeStartPosition = 0.3f))
        assertEquals(1, toyDeckSnapTarget(0.7f, 0.5f, 8, swipeStartPosition = 0.7f))
        assertEquals(3, toyDeckSnapTarget(2.5f, -1.19f, 8, swipeStartPosition = 2.5f))
        assertEquals(3, toyDeckSnapTarget(3.2f, 1.19f, 8, swipeStartPosition = 3.2f))
    }

    @Test
    fun `fast flick finishes the partial slot without adding a page at an exact anchor`() {
        assertEquals(4, toyDeckSnapTarget(3.1f, 1.2f, 8, swipeStartPosition = 3f))
        assertEquals(2, toyDeckSnapTarget(2.9f, -1.2f, 8, swipeStartPosition = 3f))
        assertEquals(0, toyDeckSnapTarget(0f, 2f, 8, swipeStartPosition = 0f))
        assertEquals(7, toyDeckSnapTarget(7f, -2f, 8, swipeStartPosition = 7f))
        assertEquals(4, toyDeckSnapTarget(4f, 1000f, 8, swipeStartPosition = 3f))
        assertEquals(2, toyDeckSnapTarget(2f, -1000f, 8, swipeStartPosition = 3f))
    }

    @Test
    fun `short flicks move only one card regardless of speed including interrupted snaps`() {
        for (speed in listOf(1.2f, 4f, 1000f)) {
            assertEquals(4, toyDeckSnapTarget(3.1f, speed, 8, swipeStartPosition = 3f))
            assertEquals(2, toyDeckSnapTarget(2.9f, -speed, 8, swipeStartPosition = 3f))
            // Crossing a rounding boundary during a short swipe must not skip a neighbor.
            assertEquals(4, toyDeckSnapTarget(4.2f, speed, 8, swipeStartPosition = 3.4f))
            assertEquals(4, toyDeckSnapTarget(3.8f, -speed, 8, swipeStartPosition = 4.6f))
        }
    }

    @Test
    fun `release speed alone cannot move a stationary deck`() {
        for (speed in listOf(-1000f, -4f, -1.2f, 1.2f, 4f, 1000f)) {
            assertEquals(3, toyDeckSnapTarget(3f, speed, 8, swipeStartPosition = 3f))
            assertEquals(3, toyDeckSnapTarget(3.1f, speed, 8, swipeStartPosition = 3.1f))
        }
    }

    @Test
    fun `longer flicks finish traveled slots within the three card budget`() {
        assertEquals(3, toyDeckSnapTarget(2.6f, 1000f, 20, swipeStartPosition = 0f))
        assertEquals(3, toyDeckSnapTarget(2.6f, 2f, 20, swipeStartPosition = 0f))
        assertEquals(14, toyDeckSnapTarget(14.4f, -1000f, 20, swipeStartPosition = 17f))
        assertEquals(14, toyDeckSnapTarget(14.4f, -2f, 20, swipeStartPosition = 17f))
        assertEquals(6, toyDeckSnapTarget(6.2f, 1000f, 20, swipeStartPosition = 4.4f))
        assertEquals(14, toyDeckSnapTarget(13.8f, -1000f, 20, swipeStartPosition = 15.6f))
    }

    @Test
    fun `long direct drags settle in place without extra travel or snapping backward`() {
        for (speed in listOf(0f, 2f, 1000f)) {
            assertEquals(6, toyDeckSnapTarget(6.2f, speed, 20, swipeStartPosition = 0f))
            assertEquals(12, toyDeckSnapTarget(12.2f, -speed, 20, swipeStartPosition = 18f))
        }
    }

    @Test
    fun `reversing direction at release finishes the current slot without extra momentum`() {
        assertEquals(8, toyDeckSnapTarget(8.2f, -10f, 20, swipeStartPosition = 7f))
        assertEquals(8, toyDeckSnapTarget(7.2f, 10f, 20, swipeStartPosition = 8f))
    }

    @Test
    fun `release speed ignores a final movement spike after a slow drag`() {
        val velocity = ToyDeckVelocity()
        velocity.reset(0, 0f)
        for (time in 16L..160L step 16) velocity.add(time, time * 0.1f)
        velocity.add(170, 100f)
        assertEquals(100f, velocity.atRelease(176), 0.001f)
        // With a 300 px stride this remains a slow drag, despite the lift-off jump.
        assertEquals(5, toyDeckSnapTarget(5.3f, velocity.atRelease(176) / 300f, 20, 5f))
    }

    @Test
    fun `sustained fast motion and short deliberate flicks retain their speed`() {
        for (direction in listOf(-1, 1)) {
            val velocity = ToyDeckVelocity()
            velocity.reset(0, 500f)
            for (time in 16L..160L step 16) velocity.add(time, 500f + time * 2f * direction)
            assertEquals(2000f * direction, velocity.atRelease(176), 0.001f)
            velocity.reset(0, 0f)
            velocity.add(16, 100f * direction)
            velocity.add(32, 200f * direction)
            velocity.add(48, 300f * direction)
            assertEquals(6250f * direction, velocity.atRelease(50), 0.001f)
            // A brief gesture may contain just one MOVE sample after DOWN.
            velocity.reset(0, 0f)
            velocity.add(16, 100f * direction)
            assertEquals(6250f * direction, velocity.atRelease(20), 0.001f)
        }
    }

    @Test
    fun `pause or insufficient movement before release produces no fling`() {
        val velocity = ToyDeckVelocity()
        assertEquals(0f, velocity.atRelease(100), 0f)
        velocity.reset(0, 0f)
        assertEquals(0f, velocity.atRelease(20), 0f)
        velocity.add(4, 100f)
        assertEquals(0f, velocity.atRelease(6), 0f)
        velocity.reset(0, 0f)
        velocity.add(16, 100f)
        velocity.add(32, 200f)
        velocity.add(48, 300f)
        assertEquals(0f, velocity.atRelease(150), 0f)
    }

    @Test
    fun `new gesture discards old velocity and duplicate samples cannot distort it`() {
        val velocity = ToyDeckVelocity()
        velocity.reset(0, 0f)
        velocity.add(16, 100f)
        velocity.reset(1000, 500f)
        for (time in 1016L..1128L step 16) {
            velocity.add(time, 500f)
            velocity.add(time, 9000f)
            velocity.add(time - 1, -9000f)
        }
        velocity.add(1130, Float.NaN)
        assertEquals(0f, velocity.atRelease(1144), 0f)
    }

    @Test
    fun `snap targets stay inside boundaries for overscroll and small decks`() {
        assertEquals(0, toyDeckSnapTarget(-3f, -10f, 8, swipeStartPosition = -3f))
        assertEquals(7, toyDeckSnapTarget(20f, 10f, 8, swipeStartPosition = 20f))
        assertEquals(0, toyDeckSnapTarget(0f, -2f, 2, swipeStartPosition = 0f))
        assertEquals(0, toyDeckSnapTarget(0f, 50f, 2, swipeStartPosition = 0f))
        assertEquals(1, toyDeckSnapTarget(0.2f, 50f, 2, swipeStartPosition = 0f))
        assertEquals(0, toyDeckSnapTarget(0.8f, -50f, 2, swipeStartPosition = 1f))
        assertEquals(0, toyDeckSnapTarget(20f, 50f, 1, swipeStartPosition = 20f))
        assertEquals(0, toyDeckSnapTarget(20f, 50f, 0, swipeStartPosition = 20f))
    }

    @Test
    fun `invalid motion samples have deterministic safe targets`() {
        assertEquals(0, toyDeckSnapTarget(Float.NaN, 0f, 8, swipeStartPosition = Float.NaN))
        assertEquals(7, toyDeckSnapTarget(Float.POSITIVE_INFINITY, 0f, 8, swipeStartPosition = Float.POSITIVE_INFINITY))
        assertEquals(0, toyDeckSnapTarget(Float.NEGATIVE_INFINITY, 0f, 8, swipeStartPosition = Float.NEGATIVE_INFINITY))
        assertEquals(3, toyDeckSnapTarget(3.2f, Float.NaN, 8, swipeStartPosition = 3.2f))
        assertEquals(3, toyDeckSnapTarget(3.2f, Float.POSITIVE_INFINITY, 8, swipeStartPosition = 3.2f))
    }
}
