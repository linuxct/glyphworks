package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.screens.*
import space.linuxct.glyphworks.screens.ambient.AmbientScreen
import space.linuxct.glyphworks.screens.ambient.BackgroundRenderers
import space.linuxct.pipeline.*
import java.time.Instant
import java.time.ZoneId

/** GlyphWorks' platform-free behavior library. Host-owned ports are read-only dependencies. */
object NativeLibrary {
    val toyIds = listOf("ambient", "clock", "eyes", "speed", "battery", "notifications", "weather", "solar", "moon", "dice", "coin", "dino", "bottle", "counter", "breathing", "timer", "compass", "level", "visualizer", "custom")
    val ids: Set<String> = (toyIds + AmbientBackgrounds.orderedIds.map { "background.$it" }).toSet()
    fun create(type: String, context: NativeContext, ports: Ports, prefs: Prefs): NativeBehavior? {
        val id = type.removePrefix("glyphworks.")
        if (id !in ids) return null
        if (id == "dino") return DinoBehavior(context)
        if (id == "ambient") return AmbientBehavior(context, ports, prefs)
        if (id == "custom" && context.bindings["design"]?.routineId != null) return DrawingBehavior(context)
        return ScreenBehavior(id, context, ports, prefs)
    }
    fun prepare(document: PipelineDocument, size: Int, assets: PipelineAssets? = null) = NativeArtwork.prewarm(document.designs, size, assets)
    internal fun screen(id: String): GlyphScreen = when (id) {
        "ambient" -> AmbientScreen()
        "clock" -> ClockScreen()
        "eyes" -> EyesScreen()
        "speed", "background.speed" -> SpeedScreen()
        "battery" -> BatteryScreen()
        "notifications", "background.notifications" -> NotificationsScreen()
        "weather", "background.weather" -> WeatherScreen()
        "solar", "background.solar_path" -> SolarScreen()
        "moon", "background.moon_phase" -> MoonScreen()
        "dice" -> DiceScreen()
        "coin" -> CoinScreen()
        "bottle" -> BottleScreen()
        "counter" -> CounterScreen()
        "breathing" -> BreathingScreen()
        "timer" -> TimerScreen()
        "compass" -> CompassScreen()
        "level" -> LevelScreen()
        "visualizer" -> VisualizerScreen()
        "custom" -> CustomScreen()
        else -> BackgroundBehaviorScreen(id.removePrefix("background."))
    }
}

private class BackgroundBehaviorScreen(private val background: String) : GlyphScreen {
    override val id = "background.$background"
    override val interactive = false
    private val renderer = BackgroundRenderers.create(background)
    private var context: ScreenContext? = null
    override fun onActivate(ctx: ScreenContext) {
        context = ctx
        renderer.onShow(ctx, ctx.ports.clock.elapsedMillis())
        ctx.scheduler.setTicker(50) { ctx.pushFrame(renderer.render(ctx, ctx.ports.clock.nowMillis())) }
    }
    override fun onDeactivate() { context?.let { renderer.onHide(it) }; context = null }
}

private class ScreenBehavior(
    private val type: String,
    private val context: NativeContext,
    sourcePorts: Ports,
    sourcePrefs: Prefs,
) : NativeBehavior {
    private val baseType = type.removePrefix("background.")
    private val time = NativeTime(context)
    private val scheduler = NativeScheduling(context, time)
    private val artwork = NativeArtwork(context) { values() }
    private val screen = NativeLibrary.screen(type)
    private var closed = false
    private var started = false
    private var suspended = false
    private var startedAt = 0L
    private var phaseAt = 0L
    private var previousPhase = ""
    private var lastFrame = IntArray(context.size * context.size)
    private val stateKeys = setOf(PrefKeys.COUNTER, PrefKeys.TIMER_START, PrefKeys.TIMER_PAUSED_ELAPSED, PrefKeys.TIMER_CHIMED_FOR)
    private val persisted = stateKeys.associateWith { context.readState(it) ?: context.parameters[it] ?: Value.Number(0.0) }
    private val prefs = NativePreferences(sourcePrefs, NativeCatalog.settings(type, context.parameters) + persisted, { key -> if (key in stateKeys) context.readState(key) else null }) { key, value ->
        if (key in stateKeys && value != null) context.writeState(key, value)
        context.emitEvent(PipelineEvent("native.event", mapOf("kind" to Value.Text("state"), "instance" to Value.Text(context.instanceId), "key" to Value.Text(key), "value" to (value ?: Value.Unavailable("Removed"))), context.clock.elapsedMillis()))
    }
    // Artwork/animation clocks freeze during a covered display. Calendar and timer clocks do not.
    private val animationClock = baseType in setOf("eyes", "dice", "coin", "bottle", "breathing", "notifications", "weather", "custom")
    private val ports = Ports(
        clock = object : ClockPort {
            override fun elapsedMillis() = time.elapsed()
            override fun nowMillis() = context.clock.wallMillis() - if (animationClock) context.clock.elapsedMillis() - time.elapsed() else 0
            private fun date() = Instant.ofEpochMilli(context.clock.wallMillis()).atZone(ZoneId.systemDefault())
            override fun hourOfDay() = date().hour
            override fun minute() = date().minute
            override fun second() = date().second
            override fun utcOffsetMinutes() = date().offset.totalSeconds / 60
            override fun dayOfYear() = date().dayOfYear
        },
        random = object : RandomPort {
            override fun nextInt(bound: Int) = context.random.nextInt(bound)
            override fun nextFloat() = context.random.nextDouble().toFloat()
        },
        battery = sourcePorts.battery, speed = sourcePorts.speed,
        spectrum = object : SpectrumPort {
            override fun bands(n: Int) = sourcePorts.spectrum.bands(n, prefs.getInt(PrefKeys.VISUALIZER_TUNING, PrefKeys.VISUALIZER_TUNING_DEF))
        },
        azimuth = sourcePorts.azimuth, shake = sourcePorts.shake, tilt = sourcePorts.tilt,
        incline = sourcePorts.incline, light = sourcePorts.light, connectivity = sourcePorts.connectivity,
        location = sourcePorts.location,
        timer = object : TimerSignalPort {
            override fun scheduleAlarm(atEpochMillis: Long) = signal("schedule", mapOf("deadline" to Value.Number(atEpochMillis.toDouble())))
            override fun cancelAlarm() = signal("cancel")
            override fun chime() = signal("chime")
            private fun signal(action: String, values: Map<String, Value> = emptyMap()) = context.action("native.timer.$action", values + ("instance" to Value.Text(context.instanceId)))
        },
        design = object : DesignPort {
            override fun selected() = when {
                "design" in context.bindings -> artwork.designs[context.bindings.getValue("design").assetId]
                "asset" in context.parameters -> artwork.designs[context.parameters.getValue("asset").text()]
                else -> sourcePorts.design.selected()
            }
        },
        notifications = sourcePorts.notifications,
        weather = object : WeatherPort {
            override fun snapshot() = sourcePorts.weather.snapshot()
            override fun setActive(active: Boolean) = Unit // The host's demand broker owns leases.
        },
    )
    override fun start() {
        if (started || closed) return
        started = true; startedAt = time.elapsed(); phaseAt = startedAt
        screen.onActivate(ScreenContext(context.size, prefs, ports, scheduler) { frame -> output(frame) })
    }
    private fun output(frame: IntArray) {
        if (closed) return
        val state = values()
        val phase = state["phase"]?.text().orEmpty().ifBlank { "frame" }
        if (phase != previousPhase) {
            val previous = previousPhase
            previousPhase = phase; phaseAt = time.elapsed()
            context.emitEvent(PipelineEvent("native.event", state + mapOf("kind" to Value.Text("phase"), "instance" to Value.Text(context.instanceId)), context.clock.elapsedMillis()))
            if (previous.isNotEmpty() && phase in setOf("result", "heads", "tails", "pointer", "done")) context.emitEvent(PipelineEvent("native.event", state + mapOf("kind" to Value.Text("result"), "instance" to Value.Text(context.instanceId)), context.clock.elapsedMillis()))
        }
        val source = if (baseType == "weather" && phase !in setOf("loading", "unavailable")) {
            val snapshot = ports.weather.snapshot()
            val condition = snapshot.condition?.name?.lowercase().orEmpty()
            val elapsed = state["elapsed"]?.number()?.toLong() ?: (time.elapsed() - startedAt)
            WeatherRenderer.renderFrame(context.size, snapshot, elapsed, WeatherPrefs.fahrenheit(prefs), WeatherPrefs.iconStyle(prefs),
                artwork.frame("condition.$condition", elapsed) ?: artwork.frame("condition", elapsed), artwork.frame("temperature", elapsed))
        } else if (baseType == "notifications") {
            val elapsed = state["elapsed"]?.number()?.toLong() ?: (time.elapsed() - startedAt)
            NotificationsRenderer.renderFrame(context.size, ports.notifications.count(), NotificationPrefs.style(prefs), elapsed,
                artwork.frame("icon", elapsed), artwork.frame("count", elapsed))
        } else when (baseType) {
            "speed" -> SpeedScreen.renderFrame(context.size, state["bytesPerSecond"]?.number()?.toLong() ?: 0L, setting("valueOffsetY", 0f).toInt(), setting("footerOffsetY", 0f).toInt(), artwork.frame("arrow", time.elapsed() - startedAt), artwork.frame("unit", time.elapsed() - startedAt), artwork.frame("value", time.elapsed() - startedAt))
            "compass" -> CompassScreen.renderFrame(context.size, ports.azimuth.azimuthDegrees(), setting("ringScale", 1f), setting("needleScale", 1f), setting("tailScale", 1f))
            "level" -> LevelScreen.renderFrame(context.size, ports.incline.pitchDegrees(), ports.incline.rollDegrees(), setting("ballScale", 1f), setting("targetScale", 1f), setting("maximumTilt", 30f), setting("tolerance", 4f))
            "moon", "moon_phase" -> MoonScreen.renderFrame(context.size, MoonMath.phaseFraction(ports.clock.nowMillis()), setting("earthshine", 0.09f), setting("softness", 0.14f))
            "solar", "solar_path" -> {
                val kind = state["kind"]?.text()
                val rise = if (kind == "polar_night") Int.MAX_VALUE else if (kind == "polar_day") 0 else state["sunrise"]?.number()?.toInt() ?: SolarScreen.FALLBACK_RISE
                val set = if (kind == "polar_night") Int.MAX_VALUE else if (kind == "polar_day") 1440 else state["sunset"]?.number()?.toInt() ?: SolarScreen.FALLBACK_SET
                SolarScreen.renderFrame(context.size, ports.clock.hourOfDay() * 60 + ports.clock.minute(), rise, set, setting("horizonOffset", 0f).toInt(), setting("arcScale", 1f))
            }
            else -> frame
        }
        val replacementPhase = if (type == "dice" && phase == "result") artwork.frame("face.${state["result"]?.number()?.toInt()}", time.elapsed() - phaseAt) else null
        val phaseArt = if ((baseType == "weather" && phase !in setOf("loading", "unavailable")) || (baseType == "notifications" && phase != "unavailable")) null else artwork.frame(phase, time.elapsed() - phaseAt)
        lastFrame = layout(replacementPhase ?: phaseArt ?: artwork.frame("frame", time.elapsed() - startedAt) ?: source)
        if (!suspended) context.emitFrame(lastFrame)
    }
    private fun setting(key: String, default: Float) = prefs.getFloat(key, default)
    private fun layout(source: IntArray): IntArray {
        val xOffset = setting("offsetX", 0f).toInt(); val yOffset = setting("offsetY", 0f).toInt()
        val scale = setting("renderScale", 1f).coerceIn(0.1f, 4f)
        if (xOffset == 0 && yOffset == 0 && scale == 1f) return source.copyOf()
        val size = context.size; val center = (size - 1) / 2f
        return IntArray(size * size) { index ->
            val x = kotlin.math.round((index % size - center - xOffset) / scale + center).toInt()
            val y = kotlin.math.round((index / size - center - yOffset) / scale + center).toInt()
            if (x in 0 until size && y in 0 until size) source[y * size + x] else 0
        }
    }
    private fun marqueeElapsed(elapsed: Long): Long {
        val hold = setting("holdDuration", 3000f).toLong().coerceIn(50, 60000)
        val slide = setting("slideDuration", 1000f).toLong().coerceIn(50, 60000)
        val phase = elapsed.coerceAtLeast(0) % (2 * (hold + slide))
        return when {
            phase < hold -> phase * 3000 / hold
            phase < hold + slide -> 3000 + (phase - hold) * 1000 / slide
            phase < 2 * hold + slide -> 4000 + (phase - hold - slide) * 3000 / hold
            else -> 7000 + (phase - 2 * hold - slide) * 1000 / slide
        }
    }
    override fun event(event: PipelineEvent) {
        if (closed || suspended) return
        val name = when (event.name) {
            "key.action", "key.press", "glyph.change" -> Events.CHANGE
            "motion.shake", "shake" -> Events.SHAKE
            "key.down" -> Events.ACTION_DOWN
            "key.up" -> Events.ACTION_UP
            "session.aod" -> Events.AOD
            else -> event.name
        }
        screen.onEvent(name)
    }
    override fun command(name: String, arguments: Map<String, Value>) {
        if (closed) return
        if (name == "configure") { arguments.forEach { (key, value) -> prefs.set(NativeCatalog.preference(type, key), value) }; return }
        if (screen.behaviorCommand(name, arguments.mapValues { primitive(it.value) })) return
        when (name) {
            "action", "toggle" -> screen.onEvent(Events.CHANGE)
            "shake" -> screen.onEvent(Events.SHAKE)
            "aod" -> screen.onEvent(Events.AOD)
        }
    }
    override fun values(): Map<String, Value> {
        val state = screen.behaviorState().mapValues { value(it.value) }.toMutableMap()
        state.putIfAbsent("phase", Value.Text("frame"))
        if (baseType in setOf("weather", "notifications") && state["phase"]?.text() !in setOf("loading", "unavailable")) {
            val elapsed = marqueeElapsed(state["elapsed"]?.number()?.toLong() ?: (time.elapsed() - startedAt))
            state["elapsed"] = Value.Number(elapsed.toDouble())
            if (baseType == "weather" || NotificationPrefs.style(prefs) == NotificationPrefs.BELL) state["phase"] = Value.Text(when {
                elapsed < 3000 -> if (baseType == "weather") "condition" else "icon"
                elapsed < 4000 -> if (baseType == "weather") "slide_to_temperature" else "slide_to_count"
                elapsed < 7000 -> if (baseType == "weather") "temperature" else "count"
                else -> if (baseType == "weather") "slide_to_condition" else "slide_to_icon"
            })
        }
        when (type.removePrefix("background.")) {
            "clock", "text_clock", "pixel_clock", "analog_clock" -> { state["hour"] = Value.Number(ports.clock.hourOfDay().toDouble()); state["minute"] = Value.Number(ports.clock.minute().toDouble()) }
            "battery", "battery_text", "battery_gauge" -> { state["percent"] = Value.Number(ports.battery.levelPercent().toDouble()); state["charging"] = Value.Bool(ports.battery.isCharging()); state["watts"] = ports.battery.chargeWatts()?.let { Value.Number(it.toDouble()) } ?: Value.Unavailable() }
            "notifications" -> state["count"] = ports.notifications.count()?.let { Value.Number(it.toDouble()) } ?: Value.Unavailable()
            "weather" -> { val weather = ports.weather.snapshot(); state["status"] = Value.Text(weather.status.name.lowercase()); state["temperature"] = weather.temperatureC?.let { Value.Number(it) } ?: Value.Unavailable(); state["condition"] = weather.condition?.let { Value.Text(it.name.lowercase()) } ?: Value.Unavailable() }
            "level" -> { state["pitch"] = ports.incline.pitchDegrees()?.let { Value.Number(it.toDouble()) } ?: Value.Unavailable(); state["roll"] = ports.incline.rollDegrees()?.let { Value.Number(it.toDouble()) } ?: Value.Unavailable() }
            "compass" -> state["heading"] = ports.azimuth.azimuthDegrees()?.let { Value.Number(it.toDouble()) } ?: Value.Unavailable()
            "solar", "solar_path" -> {
                val rise = state["sunrise"]?.number() ?: SolarScreen.FALLBACK_RISE.toDouble()
                val set = state["sunset"]?.number() ?: SolarScreen.FALLBACK_SET.toDouble()
                state["dayProgress"] = Value.Number(((ports.clock.hourOfDay() * 60 + ports.clock.minute() - rise) / (set - rise).coerceAtLeast(1.0)).coerceIn(0.0, 1.0))
            }
            "moon", "moon_phase" -> state["fraction"] = Value.Number(MoonMath.phaseFraction(ports.clock.nowMillis()).toDouble())
            "visualizer" -> state["spectrum"] = ports.spectrum.bands(context.size)?.let { Value.Items(it.map { band -> Value.Number(band.toDouble()) }) } ?: Value.Unavailable()
            "connection" -> state["connection"] = Value.Text(ports.connectivity.state().name.lowercase())
        }
        return state
    }
    override fun suspend() { if (!closed && !suspended) { suspended = true; scheduler.suspend() } }
    override fun resume() { if (!closed && suspended) { suspended = false; if (baseType == "speed") screen.behaviorCommand("resample", emptyMap()); scheduler.resume(); context.emitFrame(lastFrame.copyOf()) } }
    override fun close() { if (closed) return; closed = true; scheduler.close(); if (started) screen.onDeactivate() }
    private fun value(raw: Any): Value = when (raw) { is Boolean -> Value.Bool(raw); is Number -> Value.Number(raw.toDouble()); is String -> Value.Text(raw); else -> Value.Unavailable() }
    private fun primitive(value: Value): Any = when (value) { is Value.Number -> value.value; is Value.Bool -> value.value; is Value.Text -> value.value; else -> value.display() }
}


/** A custom drawing routine is a live design; its playhead follows the same pause/resume controls. */
private class DrawingBehavior(private val context: NativeContext) : NativeBehavior {
    private val time = NativeTime(context)
    private val scheduler = NativeScheduling(context, time)
    private val artwork = NativeArtwork(context) { values() }
    private var start = time.elapsed()
    private var position = 0L
    private var playing = true
    private var suspended = false
    private var closed = false
    private var last = IntArray(context.size * context.size)
    private fun elapsed() = if (playing) position + time.elapsed() - start else position
    override fun start() { scheduler.setTicker(50) { if (playing) draw() }; draw() }
    private fun draw() {
        if (closed) return
        last = artwork.frame("design", elapsed())?.copyOf() ?: IntArray(context.size * context.size)
        if (!suspended) context.emitFrame(last)
    }
    override fun event(event: PipelineEvent) { if (!suspended && event.name in setOf("key.action", "shake")) command("action", emptyMap()) }
    override fun command(name: String, arguments: Map<String, Value>) {
        if (closed) return
        when (name) {
            "pause" -> { position = elapsed(); playing = false }
            "play" -> if (!playing) { start = time.elapsed(); playing = true }
            "restart" -> { position = 0; start = time.elapsed(); playing = true }
            "frame" -> { position = ((arguments["index"]?.number()?.toLong() ?: 0).coerceIn(0, 239)) * 50; playing = false }
            "action" -> { command(if (playing) "pause" else "play", arguments); return }
            else -> return
        }
        draw()
    }
    override fun values() = mapOf("phase" to text(if (playing) "playing" else "paused"), "playing" to boolean(playing), "frame" to number(elapsed() / 50), "frames" to number(0))
    override fun suspend() { suspended = true; scheduler.suspend() }
    override fun resume() { suspended = false; scheduler.resume(); context.emitFrame(last.copyOf()) }
    override fun close() { closed = true; scheduler.close() }
}
