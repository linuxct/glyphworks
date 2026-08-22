package space.linuxct.glyphworks.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename

class CustomScreenTest {

    @Test
    fun `a static design pushes its one frame and nothing else`() {
        val h = TestHarness(13)
        h.design.design = design(DesignKind.STATIC, frames = listOf(frame(13, lit = 40)))
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertEquals(1, h.frames.size)
        assertArrayEquals(decoded(13, lit = 40), h.frames[0])
        h.scheduler.advanceTime(60_000)
        assertEquals(1, h.frames.size)
    }

    @Test
    fun `each frame is held for exactly its own authored duration`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            frames = listOf(
                frame(13, lit = 0, durationMs = 100),
                frame(13, lit = 1, durationMs = 250),
                frame(13, lit = 2, durationMs = 40),
            ),
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())

        h.scheduler.advanceTime(99)
        assertEquals("frame 0 must survive its full 100 ms", 1, h.frames.size)
        h.scheduler.advanceTime(1)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        h.scheduler.advanceTime(249)
        assertEquals("frame 1 is authored at 250 ms, not at frame 0's 100", 2, h.frames.size)
        h.scheduler.advanceTime(1)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())

        h.scheduler.advanceTime(10_000)
        assertEquals(3, h.frames.size)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
    }

    @Test
    fun `playOnce rests on frame 0, plays through on a press, and returns`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_ONCE,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        h.scheduler.advanceTime(5_000)
        assertEquals(1, h.frames.size)

        screen.onEvent(Events.CHANGE)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())

        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        val settled = h.frames.size
        h.scheduler.advanceTime(5_000)
        assertEquals(settled, h.frames.size)
    }

    @Test
    fun `playPause toggles on every press`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(4) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        screen.onEvent(Events.CHANGE)
        val paused = h.frames.size
        h.scheduler.advanceTime(5_000)
        assertEquals("a paused design must not advance", paused, h.frames.size)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        screen.onEvent(Events.CHANGE)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
    }

    @Test
    fun `loop on restarts at frame 0 after the last frame`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(100)
        h.scheduler.advanceTime(100)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())
    }

    @Test
    fun `no design selected renders the placeholder`() {
        val h = TestHarness(13)
        h.design.design = null
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertEquals(1, h.frames.size)
        assertArrayEquals(CustomScreen.renderPlaceholder(13), h.frames[0])
    }

    @Test
    fun `onDeactivate cancels the chain so no frame arrives afterwards`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(50)
        val atDeactivation = h.frames.size

        screen.onDeactivate()
        h.scheduler.advanceTime(60_000)

        assertEquals(
            "a pending frame after deactivate would land on the NEXT screen",
            atDeactivation,
            h.frames.size,
        )
    }

    private companion object {
        fun frame(size: Int, lit: Int, durationMs: Int = 120): DesignFrame {
            val cells = StringBuilder("0".repeat(size * size))
            cells.setCharAt(lit, '2')
            return DesignFrame(durationMs, cells.toString())
        }

        fun decoded(size: Int, lit: Int): IntArray =
            DesignFrames.decode(frame(size, lit).cells, DEFAULT_LEVELS, size)!!

        fun design(
            kind: DesignKind,
            keyMode: KeyMode = KeyMode.PLAY_PAUSE,
            loop: Boolean = false,
            codename: PokemonCodename = PokemonCodename.BELLSPROUT,
            frames: List<DesignFrame>,
        ) = Design(
            id = "testdesign",
            name = "Test",
            kind = kind,
            keyMode = keyMode,
            loop = loop,
            levels = DEFAULT_LEVELS,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
    }
}
