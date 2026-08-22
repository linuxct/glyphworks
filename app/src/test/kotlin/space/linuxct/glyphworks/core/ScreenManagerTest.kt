package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakeClock
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.FakeRandom
import space.linuxct.glyphworks.FakeAzimuth
import space.linuxct.glyphworks.FakeBattery
import space.linuxct.glyphworks.FakeConnectivity
import space.linuxct.glyphworks.FakeDesignPort
import space.linuxct.glyphworks.FakeIncline
import space.linuxct.glyphworks.FakeLight
import space.linuxct.glyphworks.FakeLocation
import space.linuxct.glyphworks.FakeScheduler
import space.linuxct.glyphworks.FakeShake
import space.linuxct.glyphworks.FakeSpectrum
import space.linuxct.glyphworks.FakeSpeed
import space.linuxct.glyphworks.FakeTimer
import space.linuxct.glyphworks.FakeTilt
import space.linuxct.glyphworks.screens.CustomScreen

private class ProbeScreen(
    override val id: String,
    private val pixel: Int,
) : GlyphScreen {
    override val interactive = true
    var activations = 0
    var deactivations = 0
    val events = mutableListOf<String>()
    private var ctx: ScreenContext? = null

    private var retained: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        this.retained = ctx
        activations++
        push()
    }

    override fun onDeactivate() {
        deactivations++
        ctx = null
    }

    override fun onEvent(event: String) {
        events += event
    }

    fun push() = pushVia(ctx)

    fun pushAfterDeactivate() = pushVia(retained)

    fun contextForTest(): ScreenContext = checkNotNull(retained)

    private fun pushVia(c: ScreenContext?) {
        if (c == null) return
        val frame = IntArray(c.size * c.size)
        frame[0] = pixel
        c.pushFrame(frame)
    }
}

class ScreenManagerTest {
    private val clock = FakeClock()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler(clock)
    private val ports = Ports(
        clock, FakeRandom(), FakeBattery(), FakeSpeed(), FakeSpectrum(),
        FakeAzimuth(), FakeShake(), FakeTilt(), FakeIncline(), FakeLight(), FakeConnectivity(),
        FakeLocation(), FakeTimer(), FakeDesignPort(),
    )
    private val output = mutableListOf<IntArray>()

    private val a = ProbeScreen("ambient", 1000)
    private val b = ProbeScreen("clock", 2000)
    private val c = ProbeScreen("dice", 3000)

    private val custom = ProbeScreen(CustomScreen.ID, 4000)

    private fun manager(vararg screens: GlyphScreen) = ScreenManager(
        screens.toList(), prefs, ports, scheduler, 13,
    ) { output += it.copyOf() }.also {
        prefs.putString(PrefKeys.SCREEN_ORDER, "ambient,clock,dice")
    }

    @Test
    fun `session starts on persisted screen and cycles with wraparound`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, a.activations)

        m.next()
        assertEquals(1, a.deactivations)
        assertEquals(1, b.activations)
        assertEquals("clock", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))

        m.next()
        m.next()
        assertEquals(2, a.activations)
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
    }

    @Test
    fun `home jumps to first enabled screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.next()
        m.next()
        m.home()
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
        assertEquals(2, a.activations)
        m.home()
        assertEquals(2, a.activations)
    }

    @Test
    fun `disabled screens are skipped`() {
        prefs.putBoolean(PrefKeys.screenEnabled("clock"), false)
        val m = manager(a, b, c)
        m.startSession()
        m.next()
        assertEquals(0, b.activations)
        assertEquals(1, c.activations)
    }

    @Test
    fun `events reach only the active screen of a live session`() {
        val m = manager(a, b, c)
        m.dispatchGlyphEvent(Events.CHANGE)
        assertTrue(a.events.isEmpty())
        m.startSession()
        m.dispatchGlyphEvent(Events.CHANGE)
        assertEquals(listOf(Events.CHANGE), a.events)
        assertTrue(b.events.isEmpty())
    }

    @Test
    fun `stopSession deactivates and blocks cycling`() {
        val m = manager(a, b, c)
        m.startSession()
        m.stopSession()
        assertEquals(1, a.deactivations)
        assertFalse(m.sessionLive)
        m.next()
        assertEquals(0, b.activations)
        assertEquals(0, c.activations)
    }

    @Test
    fun `brightness scaling and frame dedup are applied to output`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, output.size)
        assertEquals(500, output[0][0])
        a.push()
        assertEquals(1, output.size)
    }

    @Test
    fun `reapplyBrightness re-pushes at the new level without a redraw`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1000, output[0][0])

        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        m.reapplyBrightness()
        assertEquals(2, output.size)
        assertEquals(500, output[1][0])

        repeat(20) { m.reapplyBrightness() }
        assertEquals(500, output.last()[0])

        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        m.reapplyBrightness()
        assertEquals(1000, output.last()[0])
    }

    @Test
    fun `transient preview does not persist current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.showTransient("dice")
        assertEquals(1, c.activations)
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        m.clearTransient()
        assertEquals(2, a.activations)
    }

    @Test
    fun `selectScreen persists and switches immediately`() {
        val m = manager(a, b, c)
        m.startSession()
        m.selectScreen("dice")
        assertEquals(1, c.activations)
        assertEquals("dice", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        m.stopSession()
        m.startSession()
        assertEquals(2, c.activations)
        assertEquals(1, a.activations)
        assertEquals(0, b.activations)
    }

    @Test
    fun `live preview drops a screen's frame and passes only its own`() {
        val m = manager(a, b, c)
        m.startSession()
        val before = output.size

        m.beginLivePreview()
        assertTrue(m.livePreviewActive)
        assertEquals(1, a.deactivations)

        a.pushAfterDeactivate()
        assertEquals("a deactivated screen must not reach the panel", before, output.size)

        val drawing = IntArray(13 * 13).also { it[7] = 4095 }
        m.pushLivePreview(drawing)
        assertEquals(before + 1, output.size)
        assertEquals(4095, output.last()[7])

        a.pushAfterDeactivate()
        assertEquals(before + 1, output.size)
        assertEquals(4095, output.last()[7])
    }

    @Test
    fun `ending the preview hands the matrix back to the current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        m.pushLivePreview(IntArray(13 * 13).also { it[7] = 4095 })

        m.endLivePreview()
        assertFalse(m.livePreviewActive)
        assertEquals("the current screen is re-rendered, not just re-enabled", 2, a.activations)
        assertEquals(1000, output.last()[0])
        assertEquals(0, output.last()[7])

        val n = output.size
        b.onActivate(a.contextForTest())
        assertEquals(n + 1, output.size)
        assertEquals(2000, output.last()[0])
    }

    @Test
    fun `the preview goes through the same brightness scaling as every toy`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()

        m.pushLivePreview(IntArray(13 * 13).also { it[3] = 2048 })
        assertEquals(1024, output.last()[3])

        val n = output.size
        m.pushLivePreview(IntArray(13 * 13).also { it[3] = 2048 })
        assertEquals(n, output.size)

        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        m.reapplyBrightness()
        assertEquals(2048, output.last()[3])
    }

    @Test
    fun `selecting a toy during the preview persists it without activating it`() {
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        val activations = b.activations

        m.selectScreen("clock")

        assertEquals("clock", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        assertEquals("nothing may activate behind the preview gate", activations, b.activations)

        m.endLivePreview()
        assertEquals(activations + 1, b.activations)
        assertEquals(2000, output.last()[0])
    }

    @Test
    fun `beginLivePreview closes the Essential-Key menu`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.beginLivePreview()
        assertFalse(m.inMenu)
        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    @Test
    fun `refreshing re-runs onActivate on the current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, a.activations)

        m.refreshCurrentScreen()
        assertEquals("the screen must be re-activated, not merely left alone", 2, a.activations)
        assertEquals("and torn down first, so its ticker and one-shots go", 1, a.deactivations)
        assertEquals(0, b.activations)
        assertEquals(0, c.activations)

        m.selectScreen("dice")
        m.refreshCurrentScreen()
        assertEquals(2, c.activations)
        assertEquals(2, a.activations)
    }

    @Test
    fun `refreshing is skipped while the live preview owns the matrix`() {
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        m.refreshCurrentScreen()
        assertEquals("a refresh must not re-arm a screen behind the gate", 1, a.activations)
        m.endLivePreview()
        assertEquals(2, a.activations)
        m.refreshCurrentScreen()
        assertEquals(3, a.activations)
    }

    @Test
    fun `choosing a different design re-activates the design toy`() {
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        assertEquals(1, custom.activations)

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals("the design toy must re-read its design", 2, custom.activations)
        assertEquals("and be torn down first, so its frame chain is cancelled", 1, custom.deactivations)
    }

    @Test
    fun `choosing a different design is skipped while the live preview owns the matrix`() {
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        m.beginLivePreview()

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals("a design change must not re-arm a screen behind the gate", 1, custom.activations)

        m.endLivePreview()
        assertEquals(2, custom.activations)
    }

    @Test
    fun `re-selecting the design already playing does not restart it`() {
        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-a")
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        assertEquals(1, custom.activations)

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-a")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals(1, custom.activations)

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals(2, custom.activations)
    }

    @Test
    fun `enterMenu blinks the previewed toy between content and blank`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, output.size)
        m.enterMenu()
        assertTrue(m.inMenu)

        scheduler.advanceTime(450)
        assertTrue("blink-off frame should be blank", output.last().all { it == 0 })

        scheduler.advanceTime(300)
        assertTrue("blink-on frame should have content", output.last().any { it != 0 })
    }

    @Test
    fun `menuNext previews the next toy without persisting current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.menuNext()
        assertEquals(1, b.activations)
        assertEquals("ambient", persistedScreen())
        m.menuNext()
        assertEquals(1, c.activations)
        assertEquals("ambient", persistedScreen())
        m.menuNext()
        assertEquals(2, a.activations)
        assertEquals("ambient", persistedScreen())
    }

    @Test
    fun `menu auto-commits the preview after the timeout and stops blinking`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.menuNext()
        assertEquals("ambient", persistedScreen())

        scheduler.advanceTime(5000)
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())

        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    @Test
    fun `a press before the timeout re-arms auto-commit`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        scheduler.advanceTime(4000)
        m.menuNext()
        assertTrue(m.inMenu)
        scheduler.advanceTime(4000)
        assertTrue(m.inMenu)
        assertEquals("ambient", persistedScreen())
        scheduler.advanceTime(1000)
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())
    }

    @Test
    fun `commitMenu sets the previewed toy immediately and shows it steady`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.menuNext()
        m.commitMenu()
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())
        assertTrue("committed toy shows steady content", output.last().any { it != 0 })

        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    @Test
    fun `home from within the menu exits and jumps to ambient`() {
        val m = manager(a, b, c)
        m.startSession()
        m.next()
        m.enterMenu()
        m.menuNext()
        m.home()
        assertFalse(m.inMenu)
        assertEquals("ambient", persistedScreen())

        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    @Test
    fun `stopSession cancels the menu`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        assertTrue(m.inMenu)
        m.stopSession()
        assertFalse(m.inMenu)
        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    private fun persistedScreen() =
        prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
}
