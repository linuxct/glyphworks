package space.linuxct.glyphworks.pipeline.templates

import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.pipeline.native.NativeCatalog
import space.linuxct.glyphworks.pipeline.native.NativeLibrary
import space.linuxct.pipeline.*

/** Versioned, immutable source documents. Editing always operates on a remapped user copy. */
object BuiltinPipelines {
    const val VERSION = 1
    const val AMBIENT_OPTIONS = "ambient_background_options"
    fun all(): List<PipelineDocument> = NativeLibrary.toyIds.map(::toy)
    fun toy(id: String): PipelineDocument {
        require(id in NativeLibrary.toyIds)
        if (id == "ambient") return ambient()
        val behavior = checkNotNull(NativeCatalog.get(id))
        val programId = "builtin_$id"
        val command = when (id) { "dice" -> "roll"; "coin" -> "flip"; "bottle" -> "spin"; "breathing" -> "toggle"; "counter" -> "increment"; else -> "action" }
        val interactive = id in setOf("dice", "coin", "dino", "bottle", "counter", "breathing", "timer", "custom")
        val scripts = buildList {
            add(Script(id = "${programId}_start", blocks = listOf(show(id)), trigger = Trigger("start")))
            if (interactive) add(Script(id = "${programId}_action", name = "When the action key is pressed", trigger = Trigger("key.action"), blocks = listOf(command(command), Block(op = "input.consume"))))
            if (id in setOf("dice", "coin", "bottle", "counter", "custom")) add(Script(id = "${programId}_shake", name = "When shaken", trigger = Trigger("shake"), blocks = listOf(command(if (id == "counter") "reset" else if (id == "custom") "action" else command), Block(op = "input.consume"))))
        }
        val program = Program(id = programId, name = behavior.title, scripts = scripts, template = "glyphworks.$id", templateVersion = VERSION, immediateAction = id == "dino", description = "Copy this toy to customize its controls, settings and artwork.")
        return document(program).copy(requires = behavior.capabilities.map { Requirement(it) } + Requirement("native.$id"))
    }

    /** Migration reads canonical legacy ordering without changing preferences or enabling new behavior. */
    fun ambient(prefs: Prefs? = null, backgroundOrder: List<String>? = null): PipelineDocument {
        fun flag(key: String, default: Boolean) = prefs?.getBoolean(key, default) ?: default
        val backgrounds = backgroundOrder?.filter { it in AmbientBackgrounds.orderedIds }?.distinct()
            ?: prefs?.let(AmbientBackgrounds::readSelection) ?: listOf(AmbientBackgrounds.TEXT_CLOCK)
        val parameters = listOf(
            Parameter("autoCycle", "Cycle automatically", ValueType.BOOLEAN, boolean(flag(PrefKeys.AMBIENT_AUTO_CYCLE, false)), quickSetting = true),
            Parameter("interval", "Cycle interval", ValueType.DURATION, duration(15_000), minimum = 1000.0, maximum = 3_600_000.0, quickSetting = true),
            Parameter("showBackground", "Show backgrounds", ValueType.BOOLEAN, boolean(flag(PrefKeys.AMBIENT_USE_BACKGROUND, true)), quickSetting = true),
            Parameter("showCharging", "Show charging", ValueType.BOOLEAN, boolean(flag(PrefKeys.AMBIENT_USE_CHARGING, true)), quickSetting = true),
            Parameter("showAtNight", "Show backgrounds at night", ValueType.BOOLEAN, boolean(flag(PrefKeys.AMBIENT_NIGHT_VISIBLE, true)), quickSetting = true),
            Parameter("shakeToShow", "Show backgrounds after a shake", ValueType.BOOLEAN, boolean(flag(PrefKeys.AMBIENT_SHAKE_ACTIVATE, false)), quickSetting = true),
            Parameter("overrideDuration", "Pause music after a key press", ValueType.DURATION, duration(0), minimum = 0.0, maximum = 3_600_000.0, quickSetting = true),
        )
        val count = Expression("block.count", key = AMBIENT_OPTIONS)
        val music = and(op("greater", input("music.energy"), num(0.1)), op("not", Expression("timer.running", key = "musicOverride")))
        val charging = and(param("showCharging"), input("battery.charging"), op("less", input("battery.level"), num(100)))
        val night = op("or", op("greaterEqual", input("time.hour"), num(23)), op("less", input("time.hour"), num(6)))
        val visible = and(param("showBackground"), op("greater", count, num(0)), op("or", param("showAtNight"), op("not", night)), op("or", op("not", param("shakeToShow")), Expression("timer.running", key = "shakeWindow")))
        val backgroundVisible = and(input("presentation.visible"), visible, op("not", music), op("not", charging))
        val advance = listOf(set("index", op("modulo", op("add", variable("index"), num(1)), op("max", count, num(1)))), set("cycleElapsed", Expression.ms(0)))
        val choices = Block(id = AMBIENT_OPTIONS, op = "flow.select",
            arguments = mapOf("index" to variable("index"), "wrap" to Expression.bool(true)),
            body = backgrounds.map { show("background.$it", "background") })
        val backgroundRoutine = Routine(id = "ambient_background", name = "Show my background", blocks = listOf(
            branch(visible, listOf(choices), listOf(off("background"))),
        ))
        val refresh = listOf(
            call(backgroundRoutine.id),
            branch(charging, listOf(show("battery", "charging", 10)), listOf(release("charging"))),
            branch(music, listOf(show("visualizer", "music", 20)), listOf(release("music"))),
        )
        val program = Program(id = "builtin_ambient", name = "Ambient", kind = ProgramKind.AMBIENT,
            parameters = parameters,
            variables = listOf(Variable("index", "Selected background"), Variable("cycleElapsed", "Visible cycle time", ValueType.DURATION, duration(0))),
            scripts = listOf(
                Script(id = "ambient_start", blocks = listOf(Block(op = "flow.forever", body = refresh + listOf(
                    wait(50),
                    branch(and(param("autoCycle"), backgroundVisible), listOf(Block(op = "variable.change", arguments = mapOf("variable" to str("cycleElapsed"), "by" to Expression.ms(50))), branch(op("greaterEqual", variable("cycleElapsed"), param("interval")), advance))),
                )))),
                Script(id = "ambient_key", name = "Cycle and temporarily return to backgrounds", trigger = Trigger("key.action"), blocks = advance + listOf(
                    branch(op("greater", param("overrideDuration"), Expression.ms(0)), listOf(Block(op = "timer.start", arguments = mapOf("name" to str("musicOverride"), "duration" to param("overrideDuration"))))),
                    Block(op = "input.consume"),
                )),
                Script(id = "ambient_shake", name = "Show backgrounds for 30 seconds", trigger = Trigger("shake"), blocks = listOf(timer("shakeWindow", 30_000))),
            ), template = "glyphworks.ambient", templateVersion = VERSION,
            description = "Your background order, with editable music and charging rules.",
        )
        return document(program).copy(routines = listOf(backgroundRoutine), requires = listOf(Requirement("audio"), Requirement("battery")))
    }

    fun examples(): List<PipelineDocument> = listOf(faceDownClock(), shakeWeather(), notificationDisplay(), chargingBattery(), musicDisplay(), dailySequence(), ControllerTemplate.create(), AuthoredRunner.create())
    fun faceDownClock(): PipelineDocument {
        val condition = input("orientation.faceDown")
        return document(Program(id = "face_down_clock", name = "Rear-facing clock", scripts = listOf(Script(trigger = Trigger("tick", condition, TriggerEdge.CHANGE, initially = true), blocks = listOf(branch(condition, listOf(show("clock")), listOf(off())))))))
    }
    fun shakeWeather(): PipelineDocument = document(Program(id = "shake_weather", name = "Shake for weather", scripts = listOf(Script(trigger = Trigger("shake"), blocks = listOf(show("weather", "weather", 10, 30_000))))))
    fun notificationDisplay(): PipelineDocument = document(Program(id = "notification_display", name = "New notification", scripts = listOf(Script(trigger = Trigger("notification.posted"), blocks = listOf(show("notifications", "notification", 10, 10_000))))))
    fun chargingBattery(): PipelineDocument = document(Program(id = "charging_battery", name = "Charging display", scripts = listOf(Script(trigger = Trigger("tick", input("battery.charging"), TriggerEdge.CHANGE, true), blocks = listOf(branch(input("battery.charging"), listOf(show("battery")), listOf(release())))))))
    fun musicDisplay(): PipelineDocument = document(Program(id = "music_display", name = "Music display", scripts = listOf(Script(trigger = Trigger("tick", input("music.playing"), TriggerEdge.CHANGE, true), blocks = listOf(branch(input("music.playing"), listOf(show("visualizer")), listOf(release())))))))
    fun dailySequence(): PipelineDocument = document(Program(id = "daily_sequence", name = "Morning weather", scripts = listOf(Script(trigger = Trigger("calendar.daily", calendar = CalendarTrigger(hour = 8, minute = 0)), blocks = listOf(show("weather", "morning", 10, 30_000), wait(30_000), show("battery", "morning", 10, 10_000))))))

    internal fun document(program: Program): PipelineDocument = PipelineDocument(id = program.id, name = program.name, entryPoint = program.id, programs = listOf(program.copy(scripts = program.scripts.map { it.copy(blocks = it.blocks.map(::fresh)) })), createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z", author = "GlyphWorks", createdWith = "GlyphWorks pipeline library")
    private fun fresh(block: Block): Block = block.copy(id = pipelineId(), body = block.body.map(::fresh), otherwise = block.otherwise.map(::fresh))
    internal fun num(value: Number) = Expression.num(value)
    internal fun str(value: String) = Expression.str(value)
    internal fun input(key: String) = Expression.input(key)
    internal fun param(key: String) = Expression.parameter(key)
    internal fun variable(key: String) = Expression.variable(key)
    internal fun op(name: String, vararg args: Expression) = Expression.operation(name, *args)
    internal fun and(vararg args: Expression): Expression = args.reduce { acc, next -> op("and", acc, next) }
    internal fun set(id: String, value: Expression) = Block(op = "variable.set", arguments = mapOf("variable" to str(id), "value" to value))
    internal fun wait(ms: Long) = Block(op = "flow.wait", arguments = mapOf("duration" to Expression.ms(ms)))
    internal fun timer(name: String, ms: Long) = Block(op = "timer.start", arguments = mapOf("name" to str(name), "duration" to Expression.ms(ms)))
    internal fun show(toy: String, slot: String = "main", priority: Int = 0, durationMs: Long = 0) = Block(op = "display.toy", arguments = mapOf("toy" to str(toy), "slot" to str(slot), "priority" to num(priority), "duration" to Expression.ms(durationMs)))
    internal fun off(slot: String = "main") = Block(op = "display.off", arguments = mapOf("slot" to str(slot)))
    internal fun release(slot: String = "main") = Block(op = "display.release", arguments = mapOf("slot" to str(slot)))
    internal fun command(command: String, slot: String = "main") = Block(op = "native.command", arguments = mapOf("slot" to str(slot), "command" to str(command)))
    internal fun branch(condition: Expression, yes: List<Block>, no: List<Block> = emptyList()) = Block(op = "flow.if", arguments = mapOf("condition" to condition), body = yes, otherwise = no)
    internal fun call(id: String) = Block(op = "routine.call", arguments = mapOf("routine" to str(id)))
}
