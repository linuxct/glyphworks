package space.linuxct.glyphworks.ui.design

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename

class DesignDemoTest {
    private val home = PokemonCodename.BELLSPROUT

    private fun replayTo(step: Int): DemoSandbox = runBlocking {
        val sandbox = DemoSandbox(home)
        val actor = DemoActor(DemoGhost(), DemoTargets(), instant = true)
        for (i in 0 until step) DEMO_STEPS[i].act(actor, sandbox)
        sandbox
    }

    private fun EditorState.lit(index: Int): List<Int> =
        frames[index].frame.copyOfCells().withIndex().filter { it.value > 0 }.map { it.index }

    @Test
    fun theDemoDesignIsAThrowawayThatCouldNotBeSavedIfItTried() {
        val design = demoDesign(home, "Slow Ember")
        assertEquals("", design.id)
        assertEquals("", design.author)
        assertEquals(DesignKind.DYNAMIC, design.kind)
        assertNotNull(design.variantFor(home))
        assertNull(design.variantFor(PokemonCodename.ARBOK))
        assertEquals(1, DemoSandbox(home).state.variantsPresent.size)
    }

    @Test
    fun everyStepSaysSomethingAndPointsAtSomething() {
        assertTrue(DEMO_STEPS.size > 1)
        DEMO_STEPS.forEachIndexed { i, step ->
            assertTrue("step $i has no caption", step.caption != 0)
            if (i == DEMO_STEPS.lastIndex) {
                assertNull("the last step should point at nothing", step.target)
            } else {
                assertNotNull("step $i points at nothing", step.target)
            }
            if (step.targetIndex != null) assertNotNull("step $i", step.target)
        }
        assertEquals(
            "two steps share a caption",
            DEMO_STEPS.size,
            DEMO_STEPS.map { it.caption }.toSet().size,
        )
        assertEquals(DemoStage.CREATE, DEMO_STEPS.first().stage)
        assertEquals(DemoStage.EDITOR, DEMO_STEPS.last().stage)
    }

    @Test
    fun theWholeTourEndsWithTheAnimationItNarrated() {
        val state = replayTo(DEMO_STEPS.size).state

        assertEquals("frames", 3, state.frames.size)
        assertEquals(0, state.selectedIndex)
        assertTrue("the moved frame should be the blank one", state.lit(0).isEmpty())

        val drawn = state.lit(1)
        assertTrue("nothing was painted", drawn.isNotEmpty())
        val nudged = state.lit(2)
        assertTrue("the duplicate lost the drawing", nudged.containsAll(drawn))
        assertTrue("the duplicate was never nudged", nudged.size > drawn.size)

        assertEquals(state.design.levels.last(), state.frames[1].frame.copyOfCells()[drawn.first()])

        val durations = state.frames.map { it.durationMs }
        assertTrue("duration never changed", durations[0] > durations[1])
        assertEquals("only the selected frame's duration should move", durations[1], durations[2])

        assertEquals(KeyMode.PLAY_PAUSE, state.design.keyMode)
        assertTrue(state.design.loop)

        assertEquals("", state.design.id)
        assertEquals(1, state.variantsPresent.size)
        assertNull(state.design.variantFor(PokemonCodename.ARBOK))
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
    }
}
