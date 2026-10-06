package space.linuxct.pipeline

/** The editor and validator consume the same vocabulary as the interpreter. */
enum class ReferenceKind { ROUTINE, PROGRAM, ASSET, BINDING, VARIABLE }
data class ArgumentSpec(val name: String, val label: String, val type: ValueType, val default: Expression,
                        val choices: List<String> = emptyList(), val reference: ReferenceKind? = null)
data class BlockSpec(val op: String, val title: String, val category: String,
                     val arguments: List<ArgumentSpec> = emptyList(), val body: Boolean = false,
                     val otherwise: Boolean = false, val description: String = "")
private fun n(key: String, label: String, value: Number = 0) = ArgumentSpec(key, label, ValueType.NUMBER, Expression.num(value))
private fun s(key: String, label: String, value: String = "", choices: List<String> = emptyList()) = ArgumentSpec(key, label, ValueType.TEXT, Expression.str(value), choices)
private fun b(key: String, label: String, value: Boolean = true) = ArgumentSpec(key, label, ValueType.BOOLEAN, Expression.bool(value))
private fun ms(key: String, label: String, value: Long = 1000) = ArgumentSpec(key, label, ValueType.DURATION, Expression.ms(value))
private fun ref(key: String, label: String, kind: ReferenceKind) = s(key, label).copy(reference = kind)
private fun any(key: String, label: String) = ArgumentSpec(key, label, ValueType.ANY, Expression.num(0))
private fun record(key: String, label: String) = ArgumentSpec(key, label, ValueType.RECORD, Expression.literal(Value.Record()))

object BlockCatalog {
    private val output = listOf(s("slot", "Display slot", "main"), n("priority", "Priority"), ms("duration", "For (0 = until replaced)", 0))
    val all: List<BlockSpec> = listOf(
        BlockSpec("flow.sequence", "Do together in order", "Control", body = true),
        BlockSpec("flow.if", "If / otherwise", "Control", listOf(b("condition", "Condition")), true, true),
        BlockSpec("flow.select", "Choose one in order", "Control", listOf(n("index", "Selected option (from 0)"), b("wrap", "Wrap around")), true, description = "Each nested block is an option. Drag options to change their order; a sequence groups several actions into one option."),
        BlockSpec("flow.repeat", "Repeat", "Control", listOf(n("count", "Times", 5)), true),
        BlockSpec("flow.while", "While", "Control", listOf(b("condition", "Condition")), true),
        BlockSpec("flow.until", "Repeat until", "Control", listOf(b("condition", "Condition", false)), true),
        BlockSpec("flow.forever", "Forever", "Control", body = true),
        BlockSpec("flow.wait", "Wait", "Control", listOf(ms("duration", "Duration"))),
        BlockSpec("flow.waitVisible", "Wait while visible", "Control", listOf(s("slot", "Display slot", "main"), ms("duration", "Visible duration"))),
        BlockSpec("flow.waitUntil", "Wait until", "Control", listOf(b("condition", "Condition"), ms("timeout", "Timeout (0 = none)", 0))),
        BlockSpec("flow.waitEvent", "Wait for event", "Control", listOf(s("event", "Event", "key.action"), ms("timeout", "Timeout (0 = none)", 0))),
        BlockSpec("flow.parallel", "Run branches together", "Control", body = true, description = "Each nested block is a separate branch. Use a sequence to group a branch."),
        BlockSpec("flow.break", "Leave loop", "Control"),
        BlockSpec("flow.return", "Return result", "Control", listOf(any("value", "Result"))),
        BlockSpec("flow.stop", "Stop", "Control", listOf(s("scope", "Scope", "script", listOf("script", "program")))),
        BlockSpec("routine.call", "Call routine", "Routines", listOf(ref("routine", "Routine", ReferenceKind.ROUTINE), s("resultVariable", "Store result in variable"))),
        BlockSpec("program.run", "Run program", "Routines", listOf(ref("program", "Program", ReferenceKind.PROGRAM), s("slot", "Instance", "child"))),
        BlockSpec("program.stop", "Stop child program", "Routines", listOf(s("slot", "Instance", "child"))),
        BlockSpec("variable.set", "Set variable", "Data", listOf(ref("variable", "Variable", ReferenceKind.VARIABLE), any("value", "Value"))),
        BlockSpec("variable.change", "Change variable", "Data", listOf(ref("variable", "Variable", ReferenceKind.VARIABLE), n("by", "By", 1))),
        BlockSpec("list.add", "Add item to list", "Data", listOf(ref("variable", "List variable", ReferenceKind.VARIABLE), any("value", "Item"))),
        BlockSpec("list.set", "Set list item", "Data", listOf(ref("variable", "List variable", ReferenceKind.VARIABLE), n("index", "Index (from 0)"), any("value", "Item"))),
        BlockSpec("list.remove", "Remove list item", "Data", listOf(ref("variable", "List variable", ReferenceKind.VARIABLE), n("index", "Index (from 0)"))),
        BlockSpec("timer.start", "Start timer", "Events", listOf(s("name", "Timer", "timeout"), ms("duration", "Duration"), b("persistent", "Survive restart", false))),
        BlockSpec("timer.cancel", "Cancel timer", "Events", listOf(s("name", "Timer", "timeout"))),
        BlockSpec("signal.emit", "Send signal", "Events", listOf(s("name", "Signal", "next"), record("values", "Values"))),
        BlockSpec("input.consume", "Handle this key", "Events", description = "Stops the same input reaching the displayed toy."),
        BlockSpec("input.pass", "Pass key to toy", "Events"),
        BlockSpec("display.toy", "Show built-in behavior", "Display", listOf(s("toy", "Behavior", "clock"), record("parameters", "Settings")) + output),
        BlockSpec("display.frame", "Show design frame", "Display", listOf(ref("asset", "Design", ReferenceKind.ASSET), n("frame", "Frame (from 0)")) + output),
        BlockSpec("display.animation", "Play design", "Display", listOf(ref("asset", "Design", ReferenceKind.ASSET), b("loop", "Loop")) + output),
        BlockSpec("display.control", "Control animation", "Display", listOf(s("slot", "Display slot", "main"), s("command", "Action", "pause", listOf("pause", "resume", "seek")), ms("position", "Position", 0))),
        BlockSpec("display.off", "Turn Matrix off", "Display", output),
        BlockSpec("display.release", "Release display slot", "Display", listOf(s("slot", "Slot", "main"))),
        BlockSpec("display.overlay", "Temporary overlay", "Display", listOf(n("priority", "Priority", 10), ms("duration", "Duration", 30000)), true),
        BlockSpec("native.command", "Control built-in behavior", "Display", listOf(s("slot", "Display slot", "main"), s("command", "Command", "action"), record("arguments", "Arguments"))),
        BlockSpec("scene.create", "Create scene", "Scenes", listOf(s("slot", "Display slot", "scene"), n("priority", "Priority"), n("background", "Background level"))),
        BlockSpec("scene.clear", "Clear scene", "Scenes", listOf(n("level", "Brightness level"))),
        BlockSpec("scene.pixel", "Draw pixel", "Scenes", listOf(n("x", "Column"), n("y", "Row"), n("level", "Brightness level", 4095))),
        BlockSpec("scene.line", "Draw line", "Scenes", listOf(n("x", "From column"), n("y", "From row"), n("x2", "To column", 12), n("y2", "To row", 12), n("level", "Brightness level", 4095))),
        BlockSpec("scene.rect", "Draw rectangle", "Scenes", listOf(n("x", "Column"), n("y", "Row"), n("width", "Width", 3), n("height", "Height", 3), n("level", "Brightness level", 4095), b("filled", "Fill"))),
        BlockSpec("scene.circle", "Draw circle", "Scenes", listOf(n("x", "Center column", 6), n("y", "Center row", 6), n("radius", "Radius", 4), n("level", "Brightness level", 4095), b("filled", "Fill"))),
        BlockSpec("scene.clip", "Set drawing clip", "Scenes", listOf(b("enabled", "Enabled"), n("x", "Column"), n("y", "Row"), n("width", "Width", 13), n("height", "Height", 13))),
        BlockSpec("scene.design", "Draw design into scene", "Scenes", listOf(ref("asset", "Design", ReferenceKind.ASSET), n("frame", "Frame (from 0)"), n("x", "Column"), n("y", "Row"), b("transparent", "Zero is transparent"))),
        BlockSpec("scene.text", "Draw text or number", "Scenes", listOf(any("text", "Text"), n("x", "Column"), n("y", "Row"), n("level", "Brightness level", 4095))),
        BlockSpec("scene.present", "Present scene", "Scenes"),
        BlockSpec("sprite.create", "Create sprite", "Sprites", listOf(s("name", "Name", "player"), ref("asset", "Design", ReferenceKind.ASSET), n("x", "Column"), n("y", "Row"), n("z", "Layer"), b("loop", "Loop"))),
        BlockSpec("sprite.set", "Set sprite property", "Sprites", listOf(s("name", "Sprite", "player"), s("property", "Property", "x", listOf("x", "y", "vx", "vy", "ax", "ay", "z", "visible", "frame", "scale", "rotation", "hitX", "hitY", "hitWidth", "hitHeight")), any("value", "Value"))),
        BlockSpec("sprite.control", "Control sprite animation", "Sprites", listOf(s("name", "Sprite", "player"), s("command", "Action", "pause", listOf("pause", "resume", "seek")), ms("position", "Position", 0))),
        BlockSpec("sprite.move", "Move sprite", "Sprites", listOf(s("name", "Sprite", "player"), n("x", "Columns"), n("y", "Rows"))),
        BlockSpec("sprite.costume", "Change sprite design", "Sprites", listOf(s("name", "Sprite", "player"), ref("asset", "Design", ReferenceKind.ASSET))),
        BlockSpec("sprite.remove", "Remove sprite", "Sprites", listOf(s("name", "Sprite", "player"))),
        BlockSpec("sprite.step", "Advance sprite physics", "Sprites", listOf(ms("duration", "Time step", 50))),
        BlockSpec("host.haptic", "Haptic feedback", "Device", listOf(s("kind", "Kind", "tick", listOf("tick", "confirm", "reject")))),
        BlockSpec("host.chime", "Play timer chime", "Device"),
    )
    private val indexed = all.associateBy { it.op }
    operator fun get(op: String): BlockSpec? = indexed[op]
}

data class ExpressionSpec(val op: String, val title: String, val result: ValueType,
                          val arguments: List<ValueType> = emptyList(), val keyKind: String? = null)
object ExpressionCatalog {
    val all = buildList {
        add(ExpressionSpec("literal", "Value", ValueType.ANY))
        for ((op, title) in listOf("variable" to "Variable", "parameter" to "Parameter", "input" to "Device value", "event" to "Event value"))
            add(ExpressionSpec(op, title, ValueType.ANY, keyKind = op))
        for (op in listOf("add", "subtract", "multiply", "divide", "modulo", "min", "max", "random")) add(ExpressionSpec(op, op.replaceFirstChar(Char::uppercase), ValueType.NUMBER, listOf(ValueType.NUMBER, ValueType.NUMBER)))
        for (op in listOf("abs", "round", "floor", "ceil", "negate", "sin", "cos")) add(ExpressionSpec(op, op.replaceFirstChar(Char::uppercase), ValueType.NUMBER, listOf(ValueType.NUMBER)))
        for (op in listOf("equal", "notEqual", "greater", "greaterEqual", "less", "lessEqual")) add(ExpressionSpec(op, op, ValueType.BOOLEAN, listOf(ValueType.ANY, ValueType.ANY)))
        for (op in listOf("and", "or")) add(ExpressionSpec(op, op, ValueType.BOOLEAN, listOf(ValueType.BOOLEAN, ValueType.BOOLEAN)))
        add(ExpressionSpec("not", "Not", ValueType.BOOLEAN, listOf(ValueType.BOOLEAN)))
        add(ExpressionSpec("available", "Is available", ValueType.BOOLEAN, listOf(ValueType.ANY)))
        add(ExpressionSpec("choose", "Choose if true / false", ValueType.ANY, listOf(ValueType.BOOLEAN, ValueType.ANY, ValueType.ANY)))
        add(ExpressionSpec("clamp", "Limit between", ValueType.NUMBER, listOf(ValueType.NUMBER, ValueType.NUMBER, ValueType.NUMBER)))
        add(ExpressionSpec("between", "Value in range", ValueType.BOOLEAN, listOf(ValueType.NUMBER, ValueType.NUMBER, ValueType.NUMBER)))
        add(ExpressionSpec("time.range", "Time in range (minutes, supports overnight)", ValueType.BOOLEAN, listOf(ValueType.NUMBER, ValueType.NUMBER, ValueType.NUMBER)))
        add(ExpressionSpec("random.choice", "Choose a random list item", ValueType.ANY, listOf(ValueType.LIST)))
        add(ExpressionSpec("join", "Join text", ValueType.TEXT, listOf(ValueType.ANY, ValueType.ANY)))
        add(ExpressionSpec("format", "Number to text (decimal places)", ValueType.TEXT, listOf(ValueType.NUMBER, ValueType.NUMBER)))
        add(ExpressionSpec("length", "Length", ValueType.NUMBER, listOf(ValueType.ANY)))
        add(ExpressionSpec("item", "List item", ValueType.ANY, listOf(ValueType.LIST, ValueType.NUMBER)))
        add(ExpressionSpec("list", "Make list", ValueType.LIST, listOf(ValueType.ANY, ValueType.ANY)))
        add(ExpressionSpec("record", "Make record", ValueType.RECORD, listOf(ValueType.TEXT, ValueType.ANY)))
        add(ExpressionSpec("field", "Record or vector field", ValueType.ANY, listOf(ValueType.ANY), "field"))
        add(ExpressionSpec("block.count", "Number of options", ValueType.NUMBER, keyKind = "block"))
        add(ExpressionSpec("timer.running", "Timer running", ValueType.BOOLEAN, keyKind = "timer"))
        add(ExpressionSpec("timer.remaining", "Timer remaining", ValueType.DURATION, keyKind = "timer"))
        add(ExpressionSpec("native.value", "Behavior value", ValueType.ANY, listOf(ValueType.TEXT), "field"))
        add(ExpressionSpec("sprite.value", "Sprite property", ValueType.ANY, listOf(ValueType.TEXT), "field"))
        add(ExpressionSpec("sprite.touching", "Sprites touching", ValueType.BOOLEAN, listOf(ValueType.TEXT, ValueType.TEXT)))
        add(ExpressionSpec("sprite.edge", "Sprite touches edge", ValueType.BOOLEAN, listOf(ValueType.TEXT)))
    }
    private val indexed = all.associateBy { it.op }
    operator fun get(op: String) = indexed[op]
}

data class InputSpec(val key: String, val title: String, val type: ValueType, val capability: String? = null, val unit: UnitKind = UnitKind.SCALAR)
object InputCatalog {
    val all = listOf(
        InputSpec("time.elapsed", "Elapsed time", ValueType.DURATION), InputSpec("time.wall", "Date and time", ValueType.NUMBER),
        InputSpec("time.hour", "Hour (0–23)", ValueType.NUMBER), InputSpec("time.minute", "Minute", ValueType.NUMBER),
        InputSpec("time.second", "Second", ValueType.NUMBER), InputSpec("time.weekday", "Weekday (1–7)", ValueType.NUMBER),
        InputSpec("sdk.available", "Glyph SDK connected", ValueType.BOOLEAN), InputSpec("session.running", "Glyph session running", ValueType.BOOLEAN),
        InputSpec("orientation.edge", "Phone on its edge", ValueType.BOOLEAN, "orientation"),
        InputSpec("presentation.visible", "Presentation is visible", ValueType.BOOLEAN),
        InputSpec("panel.size", "Panel size", ValueType.NUMBER),
        InputSpec("battery.level", "Battery level", ValueType.NUMBER, "battery", UnitKind.PERCENT), InputSpec("battery.charging", "Charging", ValueType.BOOLEAN, "battery"),
        InputSpec("battery.watts", "Charge power", ValueType.NUMBER, "battery"),
        InputSpec("battery.plugged", "Connected to a charger", ValueType.BOOLEAN, "battery"), InputSpec("battery.full", "Battery full", ValueType.BOOLEAN, "battery"),
        InputSpec("music.playing", "Music playing", ValueType.BOOLEAN, "audio"), InputSpec("music.energy", "Music energy", ValueType.NUMBER, "audio"), InputSpec("music.bands", "Audio spectrum", ValueType.LIST, "audio"),
        InputSpec("notifications.count", "Notification count", ValueType.NUMBER, "notifications"),
        InputSpec("screen.on", "Screen on", ValueType.BOOLEAN, "screen"), InputSpec("device.locked", "Device locked", ValueType.BOOLEAN, "screen"),
        InputSpec("orientation.faceDown", "Face down", ValueType.BOOLEAN, "orientation"), InputSpec("orientation.faceUp", "Face up", ValueType.BOOLEAN, "orientation"),
        InputSpec("orientation.pitch", "Pitch", ValueType.NUMBER, "orientation", UnitKind.DEGREES), InputSpec("orientation.roll", "Roll", ValueType.NUMBER, "orientation", UnitKind.DEGREES),
        InputSpec("sensor.acceleration", "Acceleration", ValueType.VECTOR, "accelerometer", UnitKind.METERS_PER_SECOND_SQUARED), InputSpec("sensor.gyroscope", "Gyroscope", ValueType.VECTOR, "gyroscope", UnitKind.RADIANS_PER_SECOND),
        InputSpec("sensor.gravity", "Gravity", ValueType.VECTOR, "orientation", UnitKind.METERS_PER_SECOND_SQUARED), InputSpec("sensor.light", "Ambient light", ValueType.NUMBER, "light", UnitKind.LUX),
        InputSpec("sensor.proximity", "Proximity near", ValueType.BOOLEAN, "proximity"), InputSpec("sensor.heading", "Compass heading", ValueType.NUMBER, "compass", UnitKind.DEGREES),
        InputSpec("connection.state", "Connection", ValueType.TEXT, "connection"), InputSpec("speed.bytesPerSecond", "Download speed", ValueType.NUMBER, "speed"),
        InputSpec("weather.temperature", "Temperature", ValueType.NUMBER, "weather"), InputSpec("weather.condition", "Weather condition", ValueType.TEXT, "weather"), InputSpec("weather.day", "Daytime", ValueType.BOOLEAN, "weather"),
        InputSpec("location.latitude", "Latitude", ValueType.NUMBER, "location"), InputSpec("location.longitude", "Longitude", ValueType.NUMBER, "location"),
    )
    private val indexed = all.associateBy { it.key }
    operator fun get(key: String) = indexed[key]
}
data class EventSpec(val name: String, val title: String, val capability: String? = null)
object EventCatalog {
    val all = listOf(
        EventSpec("sdk.available", "Glyph SDK connected"), EventSpec("sdk.unavailable", "Glyph SDK disconnected"),
        EventSpec("session.start", "Glyph session started"), EventSpec("session.resume", "Glyph session resumed"), EventSpec("session.suspend", "Glyph session suspended"),
        EventSpec("program.selected", "Toy selected"), EventSpec("program.deselected", "Toy deselected"),
        EventSpec("calendar.daily", "At a daily time"), EventSpec("start", "When started"), EventSpec("stop", "When stopped"), EventSpec("visibility.hidden", "When covered"), EventSpec("visibility.shown", "When revealed"), EventSpec("tick", "On each update"), EventSpec("key.action", "Action key"),
        EventSpec("key.hold", "Key held"), EventSpec("session.aod", "AOD session event"),
        EventSpec("key.down", "Key pressed"), EventSpec("key.up", "Key released"), EventSpec("key.single", "Single press"), EventSpec("key.double", "Double press"), EventSpec("key.triple", "Triple press"),
        EventSpec("shake", "When shaken", "accelerometer"), EventSpec("battery.changed", "Battery changed", "battery"),
        EventSpec("music.changed", "Music changed", "audio"), EventSpec("notification.posted", "Notification received", "notifications"), EventSpec("notification.updated", "Notification updated", "notifications"), EventSpec("notification.removed", "Notification removed", "notifications"),
        EventSpec("screen.changed", "Screen changed", "screen"), EventSpec("orientation.changed", "Orientation changed", "orientation"),
        EventSpec("sensor.changed", "Sensor changed"), EventSpec("connection.changed", "Connection changed", "connection"),
        EventSpec("weather.changed", "Weather changed", "weather"), EventSpec("timer", "Timer finished"), EventSpec("signal", "Signal received"),
        EventSpec("native.event", "Behavior event"), EventSpec("capability.changed", "Permission or capability changed"),
    )
}
