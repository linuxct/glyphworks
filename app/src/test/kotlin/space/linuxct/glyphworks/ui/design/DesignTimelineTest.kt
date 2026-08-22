package space.linuxct.glyphworks.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignCodec
import kotlin.math.abs

class DesignTimelineTest {
    private class Sim(count: Int, val width: Int = 60) {
        val ids = MutableList(count) { it }
        var index = 0
        var offset = 0f

        fun startAt(i: Int) {
            index = i
            offset = 0f
        }

        fun drag(px: Float, sign: Int = 1) {
            offset += px * sign
            settle()
        }

        fun autoScroll(consumed: Float) {
            offset += consumed
            settle()
        }

        private fun settle() {
            val shift = reorderShift(offset, width, index, ids.lastIndex)
            if (shift == 0) return
            val step = if (shift > 0) 1 else -1
            repeat(abs(shift)) {
                moveItem(ids, index, index + step)
                index += step
                offset -= step * width
            }
        }
    }

    @Test
    fun crossingTheThresholdMovesOnePlace() {
        assertEquals(1, reorderShift(37f, 60, 3, 9))
        assertEquals(-1, reorderShift(-37f, 60, 3, 9))
    }

    @Test
    fun theEndsOfTheListAreHard() {
        assertEquals(0, reorderShift(10_000f, 60, 9, 9))
        assertEquals(0, reorderShift(-10_000f, 60, 0, 9))
        assertEquals(0, reorderShift(10_000f, 60, 0, 0))
        assertEquals(0, reorderShift(10_000f, 0, 0, 9))
    }

    @Test
    fun aFrameCanBeDraggedToTheEndOfASixtyFrameTimeline() {
        val sim = Sim(count = 60)
        sim.startAt(1)
        val dragged = sim.ids[1]

        sim.drag(60f)
        repeat(400) { sim.autoScroll(10f) }

        assertEquals("did not reach the end", 59, sim.index)
        assertEquals("wrong frame at the end", dragged, sim.ids[59])
        assertEquals(59, sim.ids.indexOf(dragged))
        assertEquals((0 until 60).filter { it != dragged }, sim.ids.dropLast(1))
    }

    @Test
    fun aHorizontalDragIsMirroredInAnRtlLocale() {
        assertEquals(1, dragSign(rtl = false))
        assertEquals(-1, dragSign(rtl = true))

        val ltr = Sim(count = 6).apply { startAt(3); drag(-120f, dragSign(rtl = false)) }
        val rtl = Sim(count = 6).apply { startAt(3); drag(-120f, dragSign(rtl = true)) }

        assertEquals("LTR: leftward is towards the start", 1, ltr.index)
        assertEquals("RTL: leftward is towards the end", 5, rtl.index)
        assertEquals(listOf(0, 3, 1, 2, 4, 5), ltr.ids)
        assertEquals(listOf(0, 1, 2, 4, 5, 3), rtl.ids)

        val logical = 40f
        assertEquals(40f, logical * dragSign(rtl = false), 0f)
        assertEquals(-40f, logical * dragSign(rtl = true), 0f)
    }

    @Test
    fun selectionFollowsTheFrameThroughAMove() {
        assertEquals(7, selectionAfterMove(selected = 2, from = 2, to = 7))
        assertEquals(3, selectionAfterMove(selected = 4, from = 1, to = 6))
        assertEquals(5, selectionAfterMove(selected = 4, from = 8, to = 2))
        assertEquals(4, selectionAfterMove(selected = 4, from = 6, to = 8))
        assertEquals(4, selectionAfterMove(selected = 4, from = 1, to = 0))
    }

    @Test
    fun everyLadderRungIsAValueTheCodecAccepts() {
        assertEquals(DesignCodec.MIN_DURATION_MS, DURATION_STEPS.first())
        assertEquals(DesignCodec.MAX_DURATION_MS, DURATION_STEPS.last())
        for (step in DURATION_STEPS) {
            assertTrue("$step below the floor", step >= DesignCodec.MIN_DURATION_MS)
            assertTrue("$step above the ceiling", step <= DesignCodec.MAX_DURATION_MS)
        }
        for (i in 1 until DURATION_STEPS.size) {
            assertTrue("not ascending at $i", DURATION_STEPS[i] > DURATION_STEPS[i - 1])
        }
    }

    @Test
    fun durationsAreClampedToTheCodecsRange() {
        assertEquals(DesignCodec.MIN_DURATION_MS, clampDuration(0))
        assertEquals(DesignCodec.MIN_DURATION_MS, clampDuration(-5_000))
        assertEquals(DesignCodec.MAX_DURATION_MS, clampDuration(Int.MAX_VALUE))
        assertEquals(120, clampDuration(120))
    }

    @Test
    fun steppingWalksTheLadderAndSaturates() {
        assertEquals(150, stepDuration(120, up = true))
        assertEquals(100, stepDuration(120, up = false))
        assertEquals(120, stepDuration(111, up = true))
        assertEquals(100, stepDuration(111, up = false))
        assertEquals(DesignCodec.MAX_DURATION_MS, stepDuration(DesignCodec.MAX_DURATION_MS, up = true))
        assertEquals(DesignCodec.MIN_DURATION_MS, stepDuration(DesignCodec.MIN_DURATION_MS, up = false))
        assertEquals(DesignCodec.MIN_DURATION_MS, stepDuration(-1, up = false))
        assertEquals(30, stepDuration(-1, up = true))
    }
}
