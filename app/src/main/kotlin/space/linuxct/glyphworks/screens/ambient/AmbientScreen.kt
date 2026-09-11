package space.linuxct.glyphworks.screens.ambient

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.core.ambient.AmbientCarousel
import space.linuxct.glyphworks.screens.BatteryScreen
import space.linuxct.glyphworks.screens.VisualizerScreen

/**
 * The ambient home screen. Audio wins over charging, then the selected background.
 * Covered backgrounds are suspended so their animation and automatic cycle can resume cleanly.
 */
class AmbientScreen : GlyphScreen {
    override val id = "ambient"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private val backgrounds = HashMap<String, AmbientBackground>()
    private val carousel = AmbientCarousel()
    private var visibleId: String? = null
    private var visibleContext: ScreenContext? = null
    private var weatherActive = false

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        hideBackground()
        carousel.pause()
        if (weatherActive) ctx?.ports?.weather?.setActive(false)
        weatherActive = false
        ctx = null
        backgrounds.clear()
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        configureCarousel(c)
        carousel.advance(c.ports.clock.elapsedMillis())
        tick()
    }

    private fun tick() {
        val c = ctx ?: return
        updateWeatherActivity(c)
        c.pushFrame(composite(c))
    }

    fun composite(c: ScreenContext): IntArray {
        val nowMs = c.ports.clock.nowMillis()
        val elapsedMs = c.ports.clock.elapsedMillis()
        configureCarousel(c)
        val bands = c.ports.spectrum.bands(c.size)
        val audioVisible = bands != null && (bands.maxOrNull() ?: 0f) > VisualizerScreen.SILENCE_THRESHOLD
        val chargingVisible = c.prefs.getBoolean(PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_USE_CHARGING_DEF) &&
            c.ports.battery.isCharging() &&
            c.ports.battery.levelPercent() != PERCENT_FULL
        val showBackground = !audioVisible && !chargingVisible &&
            c.prefs.getBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_BACKGROUND_DEF) &&
            backgroundVisible(c)
        val selected = carousel.update(showBackground, elapsedMs)
        if (!showBackground || selected == null) hideBackground()

        if (audioVisible) {
            return VisualizerScreen.renderFrame(
                c.size,
                checkNotNull(bands),
                c.prefs.getInt(PrefKeys.VISUALIZER_THEME, PrefKeys.VISUALIZER_THEME_DEF),
            )
        }

        if (chargingVisible) {
            return BatteryScreen.renderFrame(c, nowMs)
        }

        if (showBackground && selected != null) {
            val renderer = backgrounds.getOrPut(selected) { BackgroundRenderers.create(selected) }
            if (visibleId != selected || visibleContext !== c) {
                hideBackground()
                visibleId = selected
                visibleContext = c
                renderer.onShow(c, elapsedMs)
            }
            return renderer.render(c, nowMs)
        }

        return IntArray(c.size * c.size)
    }

    private fun configureCarousel(c: ScreenContext) {
        carousel.configure(
            AmbientBackgrounds.readSelection(c.prefs),
            c.prefs.getBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, PrefKeys.AMBIENT_AUTO_CYCLE_DEF),
            c.ports.clock.elapsedMillis(),
        )
    }

    private fun hideBackground() {
        val c = visibleContext
        val renderer = visibleId?.let { backgrounds[it] }
        if (c != null) renderer?.onHide(c)
        visibleId = null
        visibleContext = null
    }

    private fun updateWeatherActivity(c: ScreenContext) {
        // A cycle entry needs a ready snapshot when it returns. Keep one weather lease
        // for the Ambient lifetime instead of starting location/network every 15 seconds.
        val needed = c.prefs.getBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_BACKGROUND_DEF) &&
            AmbientBackgrounds.WEATHER in AmbientBackgrounds.readSelection(c.prefs)
        if (needed == weatherActive) return
        weatherActive = needed
        c.ports.weather.setActive(needed)
    }

    private fun backgroundVisible(c: ScreenContext): Boolean {
        if (NightWindow.isNight(c.ports.clock.hourOfDay()) &&
            !c.prefs.getBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, PrefKeys.AMBIENT_NIGHT_VISIBLE_DEF)
        ) {
            return false
        }
        if (c.prefs.getBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, PrefKeys.AMBIENT_SHAKE_ACTIVATE_DEF) &&
            c.ports.shake.millisSinceLastShake() > SHAKE_WINDOW_MS
        ) {
            return false
        }
        return true
    }

    companion object {
        const val TICK_MS = 50L
        const val SHAKE_WINDOW_MS = 30_000L

        private const val PERCENT_FULL = 100
    }
}
