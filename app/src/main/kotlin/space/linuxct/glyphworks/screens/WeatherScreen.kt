package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.weather.WeatherStatus

class WeatherScreen : GlyphScreen {
    override val id = "weather"
    override val interactive = false
    private var context: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        context = ctx
        ctx.ports.weather.setActive(true)
        var startedAt = ctx.ports.clock.elapsedMillis()
        var wasReady = false
        ctx.scheduler.setTicker(50L) {
            val snapshot = ctx.ports.weather.snapshot()
            val ready = snapshot.status in setOf(WeatherStatus.READY, WeatherStatus.STALE) &&
                snapshot.temperatureC?.isFinite() == true && snapshot.condition != null
            if (ready && !wasReady) startedAt = ctx.ports.clock.elapsedMillis()
            wasReady = ready
            ctx.pushFrame(WeatherRenderer.renderFrame(ctx.size, snapshot,
                ctx.ports.clock.elapsedMillis() - startedAt, WeatherPrefs.fahrenheit(ctx.prefs)))
        }
    }

    override fun onDeactivate() {
        context?.ports?.weather?.setActive(false)
        context = null
    }
}
