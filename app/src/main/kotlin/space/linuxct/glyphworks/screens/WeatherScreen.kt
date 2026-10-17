package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.weather.WeatherStatus

class WeatherScreen : GlyphScreen {
    override val id = "weather"
    override val interactive = false
    private var context: ScreenContext? = null
    private var startedAt = 0L
    private var wasReady = false
    override fun behaviorState(): Map<String, Any> {
        val c = context ?: return emptyMap()
        val elapsed = c.ports.clock.elapsedMillis() - startedAt
        val phase = elapsed.coerceAtLeast(0) % WeatherRenderer.LOOP_MS
        val status = c.ports.weather.snapshot().status
        return mapOf("elapsed" to elapsed, "phase" to when {
            !wasReady -> if (status == WeatherStatus.LOADING) "loading" else "unavailable"
            phase < WeatherRenderer.HOLD_MS -> "condition"
            phase < WeatherRenderer.HOLD_MS + WeatherRenderer.SLIDE_MS -> "slide_to_temperature"
            phase < 2 * WeatherRenderer.HOLD_MS + WeatherRenderer.SLIDE_MS -> "temperature"
            else -> "slide_to_condition"
        })
    }

    override fun onActivate(ctx: ScreenContext) {
        context = ctx
        ctx.ports.weather.setActive(true)
        startedAt = ctx.ports.clock.elapsedMillis()
        wasReady = false
        ctx.scheduler.setTicker(50L) {
            val snapshot = ctx.ports.weather.snapshot()
            val ready = snapshot.status in setOf(WeatherStatus.READY, WeatherStatus.STALE) &&
                snapshot.temperatureC?.isFinite() == true && snapshot.condition != null
            if (ready && !wasReady) startedAt = ctx.ports.clock.elapsedMillis()
            wasReady = ready
            ctx.pushFrame(WeatherRenderer.renderFrame(ctx.size, snapshot,
                ctx.ports.clock.elapsedMillis() - startedAt, WeatherPrefs.fahrenheit(ctx.prefs),
                WeatherPrefs.iconStyle(ctx.prefs)))
        }
    }

    override fun onDeactivate() {
        context?.ports?.weather?.setActive(false)
        context = null
    }
}
