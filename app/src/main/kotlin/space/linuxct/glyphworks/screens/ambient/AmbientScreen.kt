package space.linuxct.glyphworks.screens.ambient

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.screens.VisualizerScreen

/**
 * The ambient home screen. Layers run background, then charging, then audio, and each
 * active one replaces the whole buffer, so the last one wins.
 */
class AmbientScreen : GlyphScreen {
    override val id = "ambient"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private val backgrounds = HashMap<Int, AmbientBackground>()

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
        backgrounds.clear()
    }

    override fun onEvent(event: String) = Unit

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(composite(c))
    }

    fun composite(c: ScreenContext): IntArray {
        val nowMs = c.ports.clock.nowMillis()
        var frame: IntArray? = null

        if (c.prefs.getBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_BACKGROUND_DEF) &&
            backgroundVisible(c)
        ) {
            val idx = c.prefs.getInt(PrefKeys.AMBIENT_BACKGROUND, PrefKeys.AMBIENT_BACKGROUND_DEF)
                .coerceIn(0, BackgroundRenderers.COUNT - 1)
            val renderer = backgrounds.getOrPut(idx) { BackgroundRenderers.create(idx) }
            frame = renderer.render(c, nowMs)
        }

        if (c.prefs.getBoolean(PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_USE_CHARGING_DEF) &&
            c.ports.battery.isCharging() &&
            c.ports.battery.levelPercent() != PERCENT_FULL
        ) {
            val style = c.prefs.getInt(
                PrefKeys.AMBIENT_CHARGING_STYLE,
                PrefKeys.AMBIENT_CHARGING_STYLE_DEF,
            )
            frame = ChargingRenderer.render(
                c.size,
                style,
                c.ports.battery.levelPercent(),
                nowMs,
                // Only for the style that draws it: the port hits the battery service
                // and this runs every tick.
                if (style == ChargingRenderer.STYLE_WATTS) c.ports.battery.chargeWatts() else null,
            )
        }

        val bands = c.ports.spectrum.bands(c.size)
        if (bands != null && (bands.maxOrNull() ?: 0f) > VisualizerScreen.SILENCE_THRESHOLD) {
            frame = VisualizerScreen.renderFrame(
                c.size,
                bands,
                c.prefs.getInt(PrefKeys.VISUALIZER_THEME, PrefKeys.VISUALIZER_THEME_DEF),
            )
        }

        return frame ?: IntArray(c.size * c.size)
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
