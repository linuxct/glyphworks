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
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.Ports
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionControl

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
}
