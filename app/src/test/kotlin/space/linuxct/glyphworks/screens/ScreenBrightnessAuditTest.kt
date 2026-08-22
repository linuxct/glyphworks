package space.linuxct.glyphworks.screens

import org.junit.Assert.fail
import org.junit.Test
import space.linuxct.glyphworks.FakeClock
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.screens.ambient.BackgroundRenderers
import space.linuxct.glyphworks.screens.ambient.ChargingRenderer

class ScreenBrightnessAuditTest {

    private val fullyDimStates = mapOf(
        "timer [idle]" to "an idle vessel is a dim outline and nothing else",
    )

    private val dimByDesign = mapOf(
        "eyes" to "closed blink frames are all rim, by definition",
        "breathing" to "the idle resting disc is dimmer than the breath it rests at",
        "timer" to "the first seconds of a run are a sub-row sand surface",
    )

    @Test
    fun `every screen peaks at full scale on 13 columns`() = auditAll(13)

    @Test
    fun `every screen peaks at full scale on 25 columns`() = auditAll(25)

    private fun auditAll(size: Int) {
        val failures = mutableListOf<String>()
        for (id in ScreenRegistry.create().map { it.id }) {
            for (state in statesOf(id)) {
                val screen = ScreenRegistry.create().first { it.id == id }
                failures += audit(size, screen, state)
            }
        }
        if (failures.isNotEmpty()) {
            fail("brightness audit failed on ${size}x$size:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    private fun audit(size: Int, screen: GlyphScreen, state: State): List<String> {
        val where = "${screen.id} [${state.label}]"
        val h = harness(size)
        state.setUp(h)
        h.prefs.putString(PrefKeys.SCREEN_ORDER, screen.id)
        h.prefs.putString(PrefKeys.CURRENT_SCREEN, screen.id)

        val manager = h.manager(listOf(screen))
        manager.startSession()

        var elapsed = 0L
        var nextPress = PRESS_EVERY_MS
        var step = 0
        while (elapsed < SWEEP_MS) {
            state.onStep(h, step)
            val before = h.clock.now
            val interval = h.scheduler.tickerInterval
            if (interval != null && interval > 0) h.scheduler.tick() else h.scheduler.advanceTime(IDLE_STEP_MS)
            elapsed += h.clock.now - before
            if (state.press && elapsed >= nextPress) {
                nextPress += PRESS_EVERY_MS
                manager.dispatchGlyphEvent(Events.CHANGE)
            }
            step++
        }
        manager.stopSession()

        val lit = h.output.filter { frame -> frame.any { it > 0 } }
        if (lit.isEmpty()) return listOf("$where: produced no lit frame (${h.output.size} frames) — the audit rig is wrong, not the art")

        val peaks = lit.map { frame -> frame.max() }
        if (where in fullyDimStates) return emptyList()
        if (screen.id in dimByDesign) {
            if (peaks.none { it == MAX_BRIGHTNESS }) {
                return listOf(
                    "$where: listed as dim by design (${dimByDesign[screen.id]}) but NOT ONE of its " +
                        "${lit.size} frames peaks at $MAX_BRIGHTNESS (brightest was ${peaks.max()}) — " +
                        "the exemption is for dim states, not dim art",
                )
            }
            return emptyList()
        }
        val dim = peaks.filter { it != MAX_BRIGHTNESS }
        if (dim.isEmpty()) return emptyList()
        return listOf(
            "$where: ${dim.size} of ${lit.size} frames peak below $MAX_BRIGHTNESS " +
                "(peaks ${dim.min()}..${dim.max()}) — raise the brightest element to $MAX_BRIGHTNESS and " +
                "express the rest as ratios of it, or justify it in dimByDesign",
        )
    }

    private fun harness(size: Int) = TestHarness(size, FakeClock(hour = 10, min = 8, sec = 20)).apply {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        battery.level = 55
        battery.charging = false
        battery.watts = 45f
        speed.total = 4_000_000L
        spectrum.values = FloatArray(32)
        azimuth.value = 200f
        shake.millisSince = 0L
        tilt.x = 0.3f
        tilt.y = -0.2f
        incline.pitch = 9f
        incline.roll = -14f
        light.lux = 300f
        location.value = 41.4 to 2.2
    }

    private class State(
        val label: String,
        val press: Boolean = true,
        val onStep: (TestHarness, Int) -> Unit = { _, _ -> },
        val setUp: (TestHarness) -> Unit = {},
    )

    private fun statesOf(id: String): List<State> = when (id) {
        "ambient" -> buildList {
            for (bg in 0 until BackgroundRenderers.COUNT) {
                add(
                    State("background $bg") {
                        it.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, bg)
                    },
                )
            }
            for (style in 0..ChargingRenderer.STYLE_WATTS) {
                add(
                    State("charging style $style") {
                        it.battery.charging = true
                        it.prefs.putInt(PrefKeys.AMBIENT_CHARGING_STYLE, style)
                    },
                )
            }
            add(State("audio override") { it.spectrum.values = loudBands() })
        }
        "clock" -> (0..2).map { theme ->
            State("theme $theme") { it.prefs.putInt(PrefKeys.CLOCK_THEME, theme) }
        }
        "battery" -> listOf(
            State("discharging"),
            State("charging") { it.battery.charging = true },
            State("charging, watts readout") {
                it.battery.charging = true
                it.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
            },
        )
        "speed" -> listOf(
            State("idle link"),
            State("downloading", onStep = { h, step -> h.speed.total = 4_000_000L + step * 45_000L }),
        )
        "dice" -> listOf("D4", "D6", "D8", "D12", "D20").map { type ->
            State(type) { it.prefs.putString(PrefKeys.SELECTED_DICE, type) }
        }
        "coin" -> (0..1).map { design ->
            State("design $design") { it.prefs.putInt(PrefKeys.COIN_DESIGN, design) }
        }
        "breathing" -> listOf("2", "4", "8").map { pace ->
            State("pace $pace") { it.prefs.putString(PrefKeys.BREATHING_PACE, pace) }
        }
        "timer" -> listOf(
            State("idle", press = false),
            State("running", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_START, it.clock.now)
            },
            State("paused", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 20_000L)
            },
            State("done", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_START, it.clock.now - 120_000L)
            },
        )
        "compass" -> listOf(
            State("bearing"),
            State("no sensor") { it.azimuth.value = null },
        )
        "level" -> listOf(
            State("tilted"),
            State("flat") {
                it.incline.pitch = 0.5f
                it.incline.roll = -0.5f
            },
            State("no sensor") {
                it.incline.pitch = null
                it.incline.roll = null
            },
        )
        "visualizer" -> buildList {
            for (theme in 0..2) {
                add(
                    State("theme $theme") {
                        it.prefs.putInt(PrefKeys.VISUALIZER_THEME, theme)
                        it.spectrum.values = loudBands()
                    },
                )
            }
            add(State("silence"))
            add(State("no microphone") { it.spectrum.values = null })
        }
        "custom" -> listOf(
            State("no design") { it.design.design = null },
            State("static design") { it.design.design = auditDesign(DesignKind.STATIC, frames = 1) },
            State("dynamic design") { it.design.design = auditDesign(DesignKind.DYNAMIC, frames = 6) },
        )
        else -> listOf(State("default"))
    }

    private companion object {
        const val SWEEP_MS = 30_000L

        const val IDLE_STEP_MS = 50L

        const val PRESS_EVERY_MS = 2_500L

        fun loudBands() = FloatArray(32) { i -> (0.9f - i * 0.02f).coerceAtLeast(0.15f) }

        fun auditDesign(kind: DesignKind, frames: Int): Design = Design(
            id = "auditdesign",
            kind = kind,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            levels = DEFAULT_LEVELS,
            variants = PokemonCodename.entries.associate { codename ->
                codename.codename to DesignVariant(
                    List(frames) { i ->
                        val cells = StringBuilder("1".repeat(codename.cellCount))
                        cells.setCharAt(i % codename.cellCount, '2')
                        DesignFrame(durationMs = 120, cells = cells.toString())
                    },
                )
            },
        )
    }
}
