package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.*
import space.linuxct.pipeline.Parameter
import space.linuxct.pipeline.Value
import space.linuxct.pipeline.ValueType
import space.linuxct.pipeline.text

/** One discoverable contract for palette descriptions, property sheets and portable settings. */
object NativeCatalog {
    data class Behavior(
        val id: String,
        val title: String,
        val parameters: List<Parameter> = emptyList(),
        val commands: List<String> = emptyList(),
        val outputs: Map<String, ValueType> = emptyMap(),
        val slots: List<String> = listOf("frame"),
        val capabilities: Set<String> = emptySet(),
    )
    private fun number(id: String, title: String, default: Number, min: Double, max: Double) = Parameter(id, title, ValueType.NUMBER, Value.Number(default.toDouble()), minimum = min, maximum = max, quickSetting = true)
    private fun bool(id: String, title: String, default: Boolean) = Parameter(id, title, ValueType.BOOLEAN, Value.Bool(default), quickSetting = true)
    private fun choice(id: String, title: String, default: String, values: List<String>) = Parameter(id, title, ValueType.TEXT, Value.Text(default), choices = values.map { Value.Text(it) }, quickSetting = true)
    private val numeric = ValueType.NUMBER
    private val textual = ValueType.TEXT
    private val logical = ValueType.BOOLEAN
    val behaviors: List<Behavior> = listOf(
        Behavior("ambient", "Ambient", parameters = space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.ambient().entry()!!.parameters + Parameter("backgrounds", "Background order", ValueType.LIST, Value.Items(listOf(text("text_clock")))), commands = listOf("action", "next"), outputs = mapOf("index" to numeric, "cycleElapsed" to ValueType.DURATION), capabilities = setOf("audio", "battery")),
        Behavior("clock", "Clock", listOf(number("theme", "Clock style", 0, 0.0, 3.0), bool("twelveHour", "12-hour clock", false)), outputs = mapOf("hour" to numeric, "minute" to numeric)),
        Behavior("eyes", "Eyes", parameters = listOf(number("wanderMinimum", "Minimum gaze hold (ms)", 1500, 50.0, 60000.0), number("wanderSpread", "Random gaze hold (ms)", 2000, 1.0, 60000.0), number("blinkMinimum", "Minimum blink interval (ms)", 2500, 50.0, 60000.0), number("blinkSpread", "Random blink interval (ms)", 3000, 1.0, 60000.0), number("gazeEase", "Gaze movement easing", 0.25, 0.01, 1.0)), commands = listOf("gaze", "blink"), outputs = mapOf("x" to numeric, "y" to numeric, "targetX" to numeric, "targetY" to numeric, "blinkPhase" to numeric), slots = listOf("frame", "gaze", "blink")),
        Behavior("speed", "Download speed", parameters = listOf(number("valueOffsetY", "Number vertical offset", 0, -25.0, 25.0), number("footerOffsetY", "Arrow and unit vertical offset", 0, -25.0, 25.0)), outputs = mapOf("bytesPerSecond" to numeric, "formatted" to textual), slots = listOf("frame", "arrow", "unit", "value"), capabilities = setOf("speed")),
        Behavior("battery", "Battery", listOf(bool("showWatts", "Show charging power", false)), outputs = mapOf("percent" to numeric, "charging" to logical, "watts" to numeric), capabilities = setOf("battery")),
        Behavior("notifications", "Notifications", listOf(choice("style", "Style", NotificationPrefs.DEFAULT_STYLE, NotificationPrefs.styles)), outputs = mapOf("count" to numeric), slots = listOf("frame", "icon", "count", "unavailable"), capabilities = setOf("notifications")),
        Behavior("weather", "Weather", listOf(choice("unit", "Temperature unit", WeatherPrefs.CELSIUS, listOf(WeatherPrefs.CELSIUS, WeatherPrefs.FAHRENHEIT)), choice("iconStyle", "Icon style", WeatherPrefs.ORIGINAL, listOf(WeatherPrefs.ORIGINAL, WeatherPrefs.NOTHING_INSPIRED))), outputs = mapOf("status" to textual, "temperature" to numeric, "condition" to textual), slots = listOf("frame", "condition", "temperature", "loading", "unavailable") + space.linuxct.glyphworks.core.weather.WeatherCondition.entries.map { "condition.${it.name.lowercase()}" }, capabilities = setOf("weather")),
        Behavior("solar", "Solar path", parameters = listOf(number("horizonOffset", "Horizon vertical offset", 0, -20.0, 20.0), number("arcScale", "Sun path scale", 1, 0.1, 2.0)), outputs = mapOf("sunrise" to numeric, "sunset" to numeric, "kind" to textual, "dayProgress" to numeric), capabilities = setOf("location")),
        Behavior("moon", "Moon phase", parameters = listOf(number("earthshine", "Unlit surface brightness", 0.09, 0.0, 1.0), number("softness", "Terminator softness", 0.14, 0.001, 1.0)), outputs = mapOf("fraction" to numeric)),
        Behavior("dice", "Dice", listOf(choice("sides", "Die", "D6", listOf("D4", "D6", "D8", "D10", "D12", "D20"))), listOf("roll"), mapOf("result" to numeric, "sides" to numeric), listOf("frame", "rolling", "result") + (1..6).map { "face.$it" }),
        Behavior("coin", "Coin flip", listOf(number("design", "Design", 0, 0.0, 1.0)), listOf("flip"), mapOf("heads" to logical, "result" to textual), listOf("frame", "flipping", "heads", "tails")),
        Behavior("dino", "Dino", listOf(number("jumpVelocity", "Jump velocity", 0.66, 0.01, 3.0), number("gravity", "Gravity", 0.045, 0.001, 1.0), number("startSpeed", "Starting speed", 0.30, 0.01, 3.0), number("maxSpeed", "Maximum speed", 0.50, 0.01, 3.0), number("speedRamp", "Speed per point", 0.013, 0.0, 1.0), number("minimumGap", "Minimum obstacle gap", 20, 1.0, 1000.0), number("gapSpread", "Extra random gap", 10, 0.0, 1000.0)), listOf("start", "jump", "reset", "restart"), mapOf("score" to numeric, "height" to numeric, "velocity" to numeric, "speed" to numeric, "airborne" to logical, "obstacles" to ValueType.LIST), listOf("idle", "run", "jump", "obstacle", "ground", "game_over", "frame")),
        Behavior("bottle", "Spin the bottle", commands = listOf("spin"), outputs = mapOf("angle" to numeric, "result" to numeric, "burstFrame" to numeric), slots = listOf("frame", "idle", "spinning", "burst", "pointer")),
        Behavior("counter", "Counter", commands = listOf("increment", "reset", "set"), outputs = mapOf("value" to numeric)),
        Behavior("breathing", "Breathing", listOf(choice("pace", "Pace", "4", (1..20).map(Int::toString))), listOf("start", "stop", "toggle"), mapOf("running" to logical, "step" to numeric), listOf("frame", "idle", "inhale", "exhale")),
        Behavior("timer", "Timer", listOf(number("durationSeconds", "Duration (seconds)", 60, 5.0, 86400.0)), listOf("start", "pause", "resume", "reset", "complete"), mapOf("remaining" to numeric, "elapsed" to numeric, "deadline" to numeric), listOf("frame", "idle", "running", "paused", "done"), setOf("timer")),
        Behavior("compass", "Compass", parameters = listOf(number("ringScale", "Compass ring scale", 1, 0.0, 2.0), number("needleScale", "North needle length", 1, 0.0, 2.0), number("tailScale", "South needle length", 1, 0.0, 2.0)), outputs = mapOf("heading" to numeric), capabilities = setOf("compass")),
        Behavior("level", "Level", parameters = listOf(number("ballScale", "Ball scale", 1, 0.1, 2.0), number("targetScale", "Target scale", 1, 0.0, 2.0), number("maximumTilt", "Maximum inclination (degrees)", 30, 1.0, 90.0), number("tolerance", "Level tolerance (degrees)", 4, 0.0, 45.0)), outputs = mapOf("pitch" to numeric, "roll" to numeric), capabilities = setOf("orientation")),
        Behavior("visualizer", "Music visualizer", listOf(number("theme", "Style", 0, 0.0, 2.0), number("tuning", "Sensitivity", PrefKeys.VISUALIZER_TUNING_DEF, 1.0, 6.0)), listOf("aod"), mapOf("spectrum" to ValueType.LIST), listOf("frame", "silent", "unavailable"), setOf("audio")),
        Behavior("custom", "Custom design", listOf(Parameter("asset", "Design", ValueType.TEXT, Value.Text(""))), listOf("play", "pause", "restart", "frame"), mapOf("playing" to logical, "frame" to numeric, "frames" to numeric), slots = listOf("design")),
    ).map { behavior ->
        val marquee = if (behavior.id in setOf("weather", "notifications")) listOf(
            number("holdDuration", "Hold each panel (ms)", 3000, 50.0, 60000.0),
            number("slideDuration", "Slide duration (ms)", 1000, 50.0, 60000.0),
        ) else emptyList()
        val layout = listOf(number("offsetX", "Horizontal offset", 0, -25.0, 25.0), number("offsetY", "Vertical offset", 0, -25.0, 25.0), number("renderScale", "Drawing scale", 1, 0.1, 4.0))
        behavior.copy(parameters = behavior.parameters + marquee + (if (behavior.id in setOf("dino", "ambient")) emptyList() else layout), outputs = behavior.outputs + ("phase" to ValueType.TEXT))
    }
    fun get(id: String): Behavior? {
        val normalized = id.removePrefix("glyphworks.")
        if (normalized.startsWith("background.")) {
            val background = normalized.removePrefix("background.")
            val base = when (background) {
                "text_clock", "analog_clock", "pixel_clock" -> "clock"
                "battery_text", "battery_gauge" -> "battery"
                "solar_path" -> "solar"
                "moon_phase" -> "moon"
                else -> background
            }
            return behaviors.firstOrNull { it.id == base }?.copy(id = normalized, title = background.replace('_', ' ').replaceFirstChar(Char::uppercase))
                ?: when (background) {
                    "connection" -> Behavior(normalized, "Connection", outputs = mapOf("connection" to textual), capabilities = setOf("connection"))
                    "tilt_ball" -> Behavior(normalized, "Tilt ball", capabilities = setOf("orientation"))
                    else -> null
                }
        }
        return behaviors.firstOrNull { it.id == normalized }
    }
    private val aliases = mapOf(
        "clock" to mapOf("theme" to PrefKeys.CLOCK_THEME, "twelveHour" to PrefKeys.USE_12H),
        "dice" to mapOf("sides" to PrefKeys.SELECTED_DICE), "coin" to mapOf("design" to PrefKeys.COIN_DESIGN),
        "battery" to mapOf("showWatts" to PrefKeys.BATTERY_SHOW_WATTS),
        "breathing" to mapOf("pace" to PrefKeys.BREATHING_PACE), "timer" to mapOf("durationSeconds" to PrefKeys.TIMER_DURATION),
        "notifications" to mapOf("style" to NotificationPrefs.STYLE),
        "weather" to mapOf("unit" to WeatherPrefs.UNIT, "iconStyle" to WeatherPrefs.ICON_STYLE),
        "visualizer" to mapOf("theme" to PrefKeys.VISUALIZER_THEME, "tuning" to PrefKeys.VISUALIZER_TUNING),
    )
    fun preference(type: String, parameter: String): String {
        val plain = type.removePrefix("glyphworks.").removePrefix("background.")
        val base = when (plain) { "text_clock", "pixel_clock", "analog_clock" -> "clock"; "battery_text", "battery_gauge" -> "battery"; else -> plain }
        return aliases[base]?.get(parameter) ?: parameter
    }
    fun settings(type: String, parameters: Map<String, Value>) = parameters.mapKeys { preference(type, it.key) }

    fun commandParameters(type: String, command: String): List<Parameter> = when (type.removePrefix("glyphworks.")) {
        "eyes" -> if (command == "gaze") listOf(number("x", "Horizontal gaze", 0, -1.0, 1.0), number("y", "Vertical gaze", 0, -1.0, 1.0), Parameter("duration", "Hold gaze", ValueType.DURATION, space.linuxct.pipeline.duration(1500))) else emptyList()
        "counter" -> when (command) {
            "increment" -> listOf(number("amount", "Add", 1, -999.0, 999.0), number("wrap", "Wrap after", 1000, 1.0, 1000.0))
            "set" -> listOf(number("value", "Value", 0, 0.0, 999.0))
            else -> emptyList()
        }
        "custom" -> if (command == "frame") listOf(number("index", "Frame (from 0)", 0, 0.0, 239.0)) else emptyList()
        else -> emptyList()
    }

    fun capabilities(type: String, parameters: Map<String, Value> = emptyMap(), prefs: Prefs? = null): Set<String> {
        val base = get(type)?.capabilities.orEmpty()
        if (type.removePrefix("glyphworks.") != "ambient") return base
        val selected = (parameters["backgrounds"] as? Value.Items)?.values?.map { it.text() }
            ?: prefs?.let(space.linuxct.glyphworks.core.ambient.AmbientBackgrounds::readSelection)
            ?: listOf(space.linuxct.glyphworks.core.ambient.AmbientBackgrounds.TEXT_CLOCK)
        return base + selected.flatMap { get("background.$it")?.capabilities.orEmpty() }
    }

    /** Freeze inherited presentation settings into a portable document; device permissions stay local. */
    fun exportSettings(document: space.linuxct.pipeline.PipelineDocument, prefs: Prefs): space.linuxct.pipeline.PipelineDocument {
        fun values(type: String): Map<String, Value> {
            if (type.removePrefix("glyphworks.") == "ambient") return space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.ambient(prefs).entry()!!.parameters.associate { it.id to it.default } + ("backgrounds" to Value.Items(space.linuxct.glyphworks.core.ambient.AmbientBackgrounds.readSelection(prefs).map(::text)))
            return get(type)?.parameters.orEmpty().associate { parameter ->
            val key = preference(type, parameter.id)
            val default = parameter.default
            parameter.id to if (!prefs.contains(key)) default else when (default) {
                is Value.Bool -> Value.Bool(prefs.getBoolean(key, default.value))
                is Value.Text -> Value.Text(prefs.getString(key, default.value))
                is Value.Number -> Value.Number(runCatching { prefs.getInt(key, default.value.toInt()).toDouble() }.getOrElse { runCatching { prefs.getFloat(key, default.value.toFloat()).toDouble() }.getOrDefault(default.value) }, default.unit)
                else -> default
            }
        }
        }
        fun convert(block: space.linuxct.pipeline.Block): space.linuxct.pipeline.Block {
            val nested = block.copy(body = block.body.map(::convert), otherwise = block.otherwise.map(::convert))
            if (block.op != "display.toy") return nested
            val toy = block.arguments["toy"] ?: return nested
            fun explicit(source: space.linuxct.pipeline.Block, type: String): space.linuxct.pipeline.Block {
                val existing = source.arguments["parameters"]?.value as? Value.Record
                // Dynamic parameter records retain their authored expression, never evaluate on import.
                if (source.arguments["parameters"]?.op?.let { it != "literal" } == true) return source
                return source.copy(arguments = source.arguments + ("parameters" to space.linuxct.pipeline.Expression.literal(Value.Record(values(type) + existing?.fields.orEmpty()))))
            }
            val literal = (toy.value as? Value.Text)?.value?.takeIf { toy.op == "literal" }
            if (literal != null) return explicit(nested, literal)
            // Ambient and editable menus select behaviors from a list. Preserve that selection while
            // attaching explicit settings to each possible native branch instead of relying on receiver prefs.
            return nested.copy(op = "flow.sequence", arguments = emptyMap(), body = NativeLibrary.ids.map { type ->
                space.linuxct.pipeline.Block(op = "flow.if", arguments = mapOf("condition" to space.linuxct.pipeline.Expression.operation("equal", toy, space.linuxct.pipeline.Expression.str(type))), body = listOf(explicit(nested.copy(id = space.linuxct.pipeline.pipelineId(), arguments = nested.arguments + ("toy" to space.linuxct.pipeline.Expression.str(type))), type)))
            }, otherwise = emptyList())
        }
        return document.copy(programs = document.programs.map { p -> p.copy(scripts = p.scripts.map { it.copy(blocks = it.blocks.map(::convert)) }) }, routines = document.routines.map { it.copy(blocks = it.blocks.map(::convert)) })
    }
}
