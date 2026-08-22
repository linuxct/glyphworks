package space.linuxct.glyphworks.core

import kotlin.math.abs
import kotlin.math.log10

/** Every field below lives on the scheduler thread, and the public methods hop there. */
class AutoBrightness(
    private val prefs: Prefs,
    private val light: LightPort,
    private val scheduler: RenderScheduler,
    private val onBrightnessChanged: () -> Unit,
) {
    private var sessionActive = false
    private var screenOn = true
    private var polling = false
    private var poll: Cancelable? = null
    private var warmup: Cancelable? = null

    fun start() = scheduler.run {
        sessionActive = true
        sync()
    }

    fun stop() = scheduler.run {
        sessionActive = false
        sync()
    }

    fun onEnabledChanged() = scheduler.run { sync() }

    fun setScreenOn(on: Boolean) = scheduler.run {
        if (screenOn == on) return@run
        screenOn = on
        DebugLog.d(C, "screen ${if (on) "ON" else "OFF"}")
        if (polling) {
            if (on) sample()
            schedule()
        }
    }

    private fun enabled() = prefs.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF)

    private fun sync() {
        val should = sessionActive && enabled()
        if (should == polling) return
        polling = should
        DebugLog.i(C, "auto-brightness ${if (should) "START" else "STOP"}")
        if (should) {
            sample()
            schedule()
        } else {
            poll?.cancel(); poll = null
            warmup?.cancel(); warmup = null
        }
    }

    private fun schedule() {
        poll?.cancel()
        val interval = if (screenOn) POLL_SCREEN_ON_MS else POLL_SCREEN_OFF_MS
        poll = scheduler.postDelayed(interval) {
            if (!polling) return@postDelayed
            sample()
            schedule()
        }
    }

    // TYPE_LIGHT registers lazily and reports only on change, so the first read is always
    // null. Touch the sensor, then read it again after a warm-up.
    private fun sample() {
        light.lux()
        warmup?.cancel()
        warmup = scheduler.postDelayed(WARMUP_MS) {
            if (!polling) return@postDelayed
            apply(light.lux())
        }
    }

    private fun apply(lux: Float?) {
        if (lux == null) return
        val target = luxToBrightness(lux)
        val current = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)
        val moveIsTooSmall = abs(target - current) < HYSTERESIS
        if (moveIsTooSmall) return
        val next = target.coerceIn(FLOOR, 1f)
        DebugLog.i(C, "lux=$lux target=$target: $current -> $next")
        prefs.putFloat(PrefKeys.BRIGHTNESS, next)
        onBrightnessChanged()
    }

    companion object {
        private const val C = "AutoBright"

        const val POLL_SCREEN_ON_MS = 60_000L

        const val POLL_SCREEN_OFF_MS = 15 * 60_000L

        const val WARMUP_MS = 1_500L

        const val FLOOR = 0.15f

        const val SATURATION_LUX = 10_000f

        const val HYSTERESIS = 0.04f

        private val LOG_SPAN = log10(1f + SATURATION_LUX)

        fun luxToBrightness(lux: Float): Float {
            val safe = if (lux.isNaN()) 0f else lux.coerceAtLeast(0f)
            val t = (log10(1f + safe) / LOG_SPAN).coerceIn(0f, 1f)
            return (FLOOR + (1f - FLOOR) * t).coerceIn(FLOOR, 1f)
        }
    }
}
