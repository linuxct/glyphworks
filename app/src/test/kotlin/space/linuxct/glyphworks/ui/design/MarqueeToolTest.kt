package space.linuxct.glyphworks.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.MarqueePlan
import space.linuxct.glyphworks.core.design.PokemonCodename

class MarqueeToolTest {

    private val home = PokemonCodename.BELLSPROUT

    private fun drawn(): Design = Design(
        id = "0123456789abcdef0123456789abcdef",
        name = "Test",
        author = "someone",
        createdAt = "2026-01-01T00:00:00Z",
        modifiedAt = "2026-01-01T00:00:00Z",
        kind = DesignKind.STATIC,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            home.codename to DesignVariant(
                frames = listOf(
                    DesignFrame(
                        durationMs = 120,
                        cells = DesignFrames.encode(
                            IntArray(home.cellCount) { DEFAULT_LEVELS[2] },
                            DEFAULT_LEVELS,
                            home.size,
                        )!!,
                    ),
                ),
            ),
            PokemonCodename.ARBOK.codename to DesignVariant(),
        ),
    )

    @Test
    fun aGeneratedMarqueeReplacesTheOpenPanelAndIsAnOrdinaryDesign() {
        val state = EditorState(drawn(), home)
        val before = state.design
        assertEquals(1, drawnFrameCount(state))

        val plan = marqueePlanFor(state, "Hey") as MarqueePlan.Ready
        assertTrue(applyMarquee(state, plan.frames))

        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertTrue(state.design.loop)
        assertEquals(plan.frames.size, state.frames.size)
        assertEquals(plan.frames, state.design.variantFor(home)!!.frames)
        assertNotEquals(before.variantFor(home), state.design.variantFor(home))
        assertEquals(before.variantFor(PokemonCodename.ARBOK), state.design.variantFor(PokemonCodename.ARBOK))
        assertEquals(before.id, state.design.id)
        assertEquals(before.author, state.design.author)
        assertTrue(DesignCodec.validate(state.composed()) is DesignCodec.Result.Ok)
    }

    @Test
    fun theEditorsOwnUndoTakesAMarqueeBackAndRedoPutsItOn() {
        val state = EditorState(drawn(), home)
        val before = state.composed()
        assertFalse(state.canUndo)

        val plan = marqueePlanFor(state, "Hey") as MarqueePlan.Ready
        assertTrue(applyMarquee(state, plan.frames))
        assertTrue("the marquee left nothing to undo", state.canUndo)

        assertTrue(state.undo())
        assertEquals(before.copy(modifiedAt = ""), state.composed().copy(modifiedAt = ""))
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(1, state.frames.size)

        assertTrue("a marquee that cannot be redone is not on the stack", state.canRedo)
        assertTrue(state.redo())
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertEquals(plan.frames, state.design.variantFor(home)!!.frames)
    }

    @Test
    fun aPhraseTooLongToFitComesBackWithTwoWaysOutThatBothWork() {
        val state = EditorState(drawn(), home)
        val long = "the quick brown fox jumps over the lazy dog and keeps on going"

        val plan = marqueePlanFor(state, long) as MarqueePlan.TooLong
        assertTrue(plan.framesNeeded > plan.maxFrames)
        assertEquals(DesignCodec.MAX_FRAMES, plan.maxFrames)

        assertTrue(plan.prefix.isNotEmpty() && long.startsWith(plan.prefix))
        assertTrue(marqueePlanFor(state, plan.prefix) is MarqueePlan.Ready)

        val faster = requireNotNull(plan.stepThatFits)
        assertTrue(marqueePlanFor(state, long, faster) is MarqueePlan.Ready)
    }
}
