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
        assertEquals("weather", deck.selectedId)
        assertEquals(3, deck.selectedIndex)
    }

    @Test
    fun `missing initial selection falls back to the first toy`() {
        val deck = ToyDeckOrder(roster, "removed")
        assertEquals("ambient", deck.selectedId)
        assertEquals(0, deck.selectedIndex)
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
        assertEquals("notifications", deck.selectedId)
        assertEquals(2, deck.selectedIndex)
        assertFalse(deck.reordering)
    }

    @Test
    fun `selection and accessibility actions cannot steal the held toy`() {
        val deck = ToyDeckOrder(roster, "clock")
        assertTrue(deck.beginReorder("clock"))
        deck.select(3)
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
        input.clear()
        assertEquals(roster, deck.order)
        deck.moveSelected(1)
        assertEquals(roster, snapshot)
        val result = deck.finishReorder(commit = true)
        deck.moveSelected(1)
        assertEquals(listOf("ambient", "notifications", "clock", "weather"), result)
    }

    @Test
    fun `empty and single item decks safely reject moves`() {
        val empty = ToyDeckOrder(emptyList(), "removed")
        empty.select(7)
        assertEquals("", empty.selectedId)
        assertEquals(-1, empty.selectedIndex)
        assertFalse(empty.beginReorder(""))
        assertFalse(empty.moveSelected(1))
        assertEquals(emptyList<String>(), empty.finishReorder(commit = false))

        val single = ToyDeckOrder(listOf("ambient"), "ambient")
        assertTrue(single.beginReorder("ambient"))
        assertFalse(single.moveHeld(1))
        assertFalse(single.moveHeld(-1))
        assertFalse(single.moveHeld(0))
        assertEquals(listOf("ambient"), single.finishReorder(commit = true))
    }

    @Test
    fun `slow drags snap to the nearest page`() {
        assertEquals(0, toyDeckSnapTarget(0.3f, 0f, 8))
        assertEquals(1, toyDeckSnapTarget(0.7f, 0.5f, 8))
        assertEquals(3, toyDeckSnapTarget(2.5f, -1.19f, 8))
        assertEquals(3, toyDeckSnapTarget(3.2f, 1.19f, 8))
    }

    @Test
    fun `fast flick advances at least one page in its direction`() {
        assertEquals(4, toyDeckSnapTarget(3.1f, 1.2f, 8))
        assertEquals(2, toyDeckSnapTarget(3.1f, -1.2f, 8))
        assertEquals(1, toyDeckSnapTarget(0f, 2f, 8))
        assertEquals(6, toyDeckSnapTarget(7f, -2f, 8))
    }

    @Test
    fun `strong flick travels at most two pages from the nearest anchor`() {
        assertEquals(5, toyDeckSnapTarget(3.1f, 4f, 8))
        assertEquals(1, toyDeckSnapTarget(3.1f, -4f, 8))
        assertEquals(5, toyDeckSnapTarget(3.1f, 1000f, 8))
        assertEquals(1, toyDeckSnapTarget(3.1f, -1000f, 8))
    }

    @Test
    fun `snap targets stay inside boundaries for overscroll and small decks`() {
        assertEquals(0, toyDeckSnapTarget(-3f, -10f, 8))
        assertEquals(7, toyDeckSnapTarget(20f, 10f, 8))
        assertEquals(0, toyDeckSnapTarget(0f, -2f, 2))
        assertEquals(1, toyDeckSnapTarget(0f, 50f, 2))
        assertEquals(0, toyDeckSnapTarget(20f, 50f, 1))
        assertEquals(0, toyDeckSnapTarget(20f, 50f, 0))
    }

    @Test
    fun `invalid motion samples have deterministic safe targets`() {
        assertEquals(0, toyDeckSnapTarget(Float.NaN, 0f, 8))
        assertEquals(7, toyDeckSnapTarget(Float.POSITIVE_INFINITY, 0f, 8))
        assertEquals(0, toyDeckSnapTarget(Float.NEGATIVE_INFINITY, 0f, 8))
        assertEquals(3, toyDeckSnapTarget(3.2f, Float.NaN, 8))
        assertEquals(3, toyDeckSnapTarget(3.2f, Float.POSITIVE_INFINITY, 8))
    }
}
