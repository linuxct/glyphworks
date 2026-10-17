package space.linuxct.glyphworks.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakeAzimuth
import space.linuxct.glyphworks.FakeBattery
import space.linuxct.glyphworks.FakeClock
import space.linuxct.glyphworks.FakeConnectivity
import space.linuxct.glyphworks.FakeDesignPort
import space.linuxct.glyphworks.FakeIncline
import space.linuxct.glyphworks.FakeLight
import space.linuxct.glyphworks.FakeLocation
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.FakeRandom
import space.linuxct.glyphworks.FakeScheduler
import space.linuxct.glyphworks.FakeShake
import space.linuxct.glyphworks.FakeSpectrum
import space.linuxct.glyphworks.FakeSpeed
import space.linuxct.glyphworks.FakeTimer
import space.linuxct.glyphworks.FakeTilt
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.Ports
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionControl
import space.linuxct.glyphworks.screens.ambient.AmbientScreen
import space.linuxct.glyphworks.screens.ambient.BackgroundRenderers

private class RouterProbe(override val id: String) : GlyphScreen {
    override val interactive = true
    var activations = 0
    val events = mutableListOf<String>()
    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        activations++
        val f = IntArray(ctx.size * ctx.size)
        f[0] = 1000
        ctx.pushFrame(f)
    }

    override fun onDeactivate() { ctx = null }
    override fun onEvent(event: String) { events += event }
}

private class FakeSessionControl(var shouldRun: Boolean = true) : SessionControl {
    var reviveCount = 0
    override val sessionShouldRun get() = shouldRun
    override fun revive() { reviveCount++ }
}

private const val SINGLE_PRESS = 1
private const val DOUBLE_PRESS = 2
private const val TRIPLE_PRESS = 3

class KeyActionRouterTest {
    private val clock = FakeClock()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler(clock)
    private val ports = Ports(
        clock, FakeRandom(), FakeBattery(), FakeSpeed(), FakeSpectrum(),
        FakeAzimuth(), FakeShake(), FakeTilt(), FakeIncline(), FakeLight(), FakeConnectivity(),
        FakeLocation(), FakeTimer(), FakeDesignPort(),
    )
    private val output = mutableListOf<IntArray>()

    private val ambient = RouterProbe("ambient")
    private val clockScreen = RouterProbe("clock")
    private val dice = RouterProbe("dice")

    private val screenManager = ScreenManager(
        listOf(ambient, clockScreen, dice), prefs, ports, scheduler, 13,
    ) { output += it.copyOf() }

    private val arbiter = FakeSessionControl()

    private fun routerFor(menuMode: Boolean, live: Boolean = true): KeyActionRouter {
        prefs.putString(PrefKeys.SCREEN_ORDER, "ambient,clock,dice")
        prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, menuMode)
        if (live) screenManager.startSession()
        return KeyActionRouter(arbiter, screenManager, scheduler, prefs)
    }

    private fun persisted() = prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)

    @Test
    fun `classic single press dispatches glyph change to the active toy`() {
        val router = routerFor(menuMode = false)
        router.execute(SINGLE_PRESS)
        assertEquals(listOf(Events.CHANGE), ambient.events)
        assertFalse(screenManager.inMenu)
    }

    @Test
    fun `classic double press cycles to the next toy`() {
        val router = routerFor(menuMode = false)
        router.execute(DOUBLE_PRESS)
        assertEquals("clock", persisted())
        assertFalse(screenManager.inMenu)
    }

    @Test
    fun `classic triple press jumps home`() {
        val router = routerFor(menuMode = false)
        router.execute(DOUBLE_PRESS)
        router.execute(TRIPLE_PRESS)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `menu mode double press opens the blinking selector instead of cycling`() {
        val router = routerFor(menuMode = true)
        router.execute(DOUBLE_PRESS)
        assertTrue(screenManager.inMenu)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `menu mode single press inside the menu cycles the preview without persisting`() {
        val router = routerFor(menuMode = true)
        router.execute(DOUBLE_PRESS)
        router.execute(SINGLE_PRESS)
        assertTrue(screenManager.inMenu)
        assertEquals(1, clockScreen.activations)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `menu mode double press inside the menu commits the preview`() {
        val router = routerFor(menuMode = true)
        router.execute(DOUBLE_PRESS)
        router.execute(SINGLE_PRESS)
        router.execute(DOUBLE_PRESS)
        assertFalse(screenManager.inMenu)
        assertEquals("clock", persisted())
    }

    @Test
    fun `menu mode triple press inside the menu exits to ambient`() {
        val router = routerFor(menuMode = true)
        router.execute(DOUBLE_PRESS)
        router.execute(SINGLE_PRESS)
        router.execute(TRIPLE_PRESS)
        assertFalse(screenManager.inMenu)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `glyph button cycles the preview in the menu and dispatches change outside it`() {
        val router = routerFor(menuMode = true)
        router.glyphButtonChange()
        assertEquals(listOf(Events.CHANGE), ambient.events)
        router.execute(DOUBLE_PRESS)
        router.glyphButtonChange()
        assertTrue(screenManager.inMenu)
        assertEquals(1, clockScreen.activations)
    }

    @Test
    fun `no session owner revives and swallows the action`() {
        val router = routerFor(menuMode = false)
        arbiter.shouldRun = false
        ambient.events.clear()
        router.execute(SINGLE_PRESS)
        assertEquals(1, arbiter.reviveCount)
        assertTrue(ambient.events.isEmpty())
    }

    @Test
    fun `real Ambient cycles through both key paths without consuming multi press navigation`() {
        for (menuMode in listOf(false, true)) {
            val h = TestHarness()
            h.prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, "text_clock,connection")
            h.prefs.putString(PrefKeys.SCREEN_ORDER, "ambient,clock")
            h.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, menuMode)
            val ambientScreen = AmbientScreen()
            val otherScreen = RouterProbe("clock")
            val manager = h.manager(listOf(ambientScreen, otherScreen))
            manager.startSession()
            val router = KeyActionRouter(FakeSessionControl(), manager, h.scheduler, h.prefs)
            val initial = h.output.last()

            router.firstPress()
            assertTrue(h.output.last().contentEquals(initial))
            router.execute(SINGLE_PRESS)
            val connection = h.output.last()
            assertTrue(connection.contentEquals(BackgroundRenderers.create("connection").render(h.context, h.clock.now)))
            router.glyphButtonChange()
            assertTrue(h.output.last().contentEquals(initial))

            router.firstPress()
            router.execute(DOUBLE_PRESS)
            if (menuMode) {
                assertTrue(manager.inMenu)
                assertEquals("ambient", manager.currentScreen().id)
                router.glyphButtonChange()
                assertEquals("clock", manager.currentScreen().id)
                router.execute(DOUBLE_PRESS)
                assertFalse(manager.inMenu)
            }
            assertEquals("clock", manager.currentScreen().id)
            router.execute(TRIPLE_PRESS)
            assertEquals("ambient", manager.currentScreen().id)
            assertTrue(h.output.last().contentEquals(initial))
        }
    }

    @Test fun `consuming raw down suppresses instant and resolved action for the same press`() {
        screenManager.startSession()
        var resolved = 0
        val router = KeyActionRouter(arbiter, screenManager, scheduler, prefs,
            rawKey = { down -> down }, resolvedSingle = { resolved++; false })
        router.keyDown(); router.firstPress(); router.keyUp(); router.execute(1)
        assertEquals(0, resolved)
        assertTrue(ambient.events.isEmpty())
        router.execute(1)
        assertEquals(1, resolved)
        assertEquals(listOf(Events.CHANGE), ambient.events)
    }

    @Test fun `hold is emitted once only while an observed physical key remains down`() {
        screenManager.startSession(); var holds = 0
        val router = KeyActionRouter(arbiter, screenManager, scheduler, prefs, heldKey = { duration -> assertEquals(600L, duration); holds++; false })
        router.keyDown(); scheduler.advanceTime(599); assertEquals(0, holds)
        router.keyUp(); scheduler.advanceTime(1000); assertEquals(0, holds)
        router.keyDown(); scheduler.advanceTime(600); scheduler.advanceTime(2000); assertEquals(1, holds)
        router.keyUp(); router.glyphButtonChange(); scheduler.advanceTime(1000); assertEquals(1, holds)
    }

}
