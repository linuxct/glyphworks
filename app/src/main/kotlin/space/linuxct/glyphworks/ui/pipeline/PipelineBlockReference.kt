package space.linuxct.glyphworks.ui.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.linuxct.glyphworks.core.design.*
import space.linuxct.pipeline.*

/** Human documentation and executable sandbox examples, using the production block catalog. */
internal object PipelineBlockReference {
    private val purposes = mapOf(
        "flow.select" to "Run one of the ordered nested options by index. Index zero selects the first option; wrapping can cycle back to the start.",
        "flow.sequence" to "Run the nested blocks from top to bottom. Group a multi-step branch inside a parallel block.",
        "flow.if" to "Choose one of two branches from a true or false condition.",
        "flow.repeat" to "Run the body a fixed number of times.",
        "flow.while" to "Repeat while the condition is true. Put a wait or another yielding block inside long-running loops.",
        "flow.until" to "Repeat until the condition becomes true.",
        "flow.forever" to "Repeat until this script or program stops. Include a wait to give other events time to run.",
        "flow.wait" to "Pause this script for elapsed time. Other scripts and animations continue.",
        "flow.waitVisible" to "Count time only while this display slot is visible. Temporary overlays pause the count.",
        "flow.waitUntil" to "Pause until a condition becomes true, or the optional timeout expires.",
        "flow.waitEvent" to "Pause for a named event, such as a key press or timer, with an optional timeout.",
        "flow.parallel" to "Start each nested block as an independent branch, then continue after all branches finish.",
        "flow.break" to "Leave the nearest repeat, while, until or forever loop.",
        "flow.return" to "Finish a routine and send a typed result back to its caller.",
        "flow.stop" to "Stop the current event script or the entire program instance.",
        "routine.call" to "Run a reusable sequence with arguments. Optionally store its return value in a variable.",
        "program.run" to "Start another program in a named child instance, with its own event handlers and state.",
        "program.stop" to "Stop the named child instance and release the resources it owns.",
        "variable.set" to "Replace a program or routine variable with a value or expression.",
        "variable.change" to "Add a signed amount to a numeric variable. Negative amounts subtract.",
        "list.add" to "Append an item to a list variable.",
        "list.set" to "Replace a list item. Index zero is the first item.",
        "list.remove" to "Remove one item from a list variable by its zero-based index.",
        "timer.start" to "Start or restart a named timeout. Its expiry emits timer.<name>. Persistent timers can survive a restart.",
        "timer.cancel" to "Cancel a named timer and prevent its old expiration from running.",
        "signal.emit" to "Send signal.<name> to other handlers in this program. Include named values for the receiver.",
        "input.consume" to "Mark this key event as handled so the underlying behavior does not also act on it.",
        "input.pass" to "Allow the displayed behavior to receive this key after this handler.",
        "display.toy" to "Run an existing toy behavior. Inherit its settings or override values and artwork in this block.",
        "display.frame" to "Show one frame from an embedded design. Frames are numbered from zero.",
        "display.animation" to "Play the design's frame sequence, optionally looping, in a named display slot.",
        "display.control" to "Pause, resume or seek an animation already running in a display slot.",
        "display.off" to "Present an unlit frame. Release this slot later to reveal the underlying display.",
        "display.release" to "Remove the presentation owned by a named slot and reveal the current lower-priority display.",
        "display.overlay" to "Run nested display blocks temporarily above the base display. Release them automatically after the duration.",
        "native.command" to "Send a supported command to the behavior in a display slot, such as jump, roll or gaze.",
        "scene.create" to "Create a drawing surface in a display slot. Draw into it, then present it.",
        "scene.clear" to "Fill the scene with a brightness level before drawing the next frame.",
        "scene.pixel" to "Set one scene cell to a brightness level.",
        "scene.line" to "Draw a line between two scene cells.",
        "scene.rect" to "Draw a rectangle, either filled or outlined.",
        "scene.circle" to "Draw a circle around a center cell, either filled or outlined.",
        "scene.clip" to "Limit subsequent drawing to a rectangular region, or disable that restriction.",
        "scene.design" to "Composite a design frame into the scene at a chosen position. Zero cells can remain transparent.",
        "scene.text" to "Draw a number or short text into the scene using the Matrix font.",
        "scene.present" to "Publish the scene's current drawing and sprites to its display slot.",
        "sprite.create" to "Create a named moving design inside the current scene. Its artwork can animate independently.",
        "sprite.set" to "Change a sprite property, such as position, velocity, visibility or collision bounds.",
        "sprite.control" to "Pause, resume or seek the named sprite's animation.",
        "sprite.move" to "Move a sprite by a relative number of columns and rows.",
        "sprite.costume" to "Replace a sprite's artwork while keeping its identity and movement state.",
        "sprite.remove" to "Remove one named sprite from the scene.",
        "sprite.step" to "Advance sprite velocity and acceleration by a fixed time step. Use it in a loop for predictable physics.",
        "host.haptic" to "Request a short haptic cue. Hardware limits still apply; simulation records the request only.",
        "host.chime" to "Request the app's timer chime. Simulation records the request without playing it.",
    )
    fun purpose(spec: BlockSpec): String = purposes[spec.op] ?: spec.description.ifBlank { spec.title }
    fun scope(spec: BlockSpec): String = when (spec.category) {
        "Control" -> "This event script; routine return and loop break apply to their enclosing blocks."
        "Routines" -> "The containing project. Child programs have independent runtime instances."
        "Data" -> "The current routine's locals or the owning program's variables."
        "Events" -> "This program instance. Names identify its signals and timers."
        "Display" -> "A named display slot. Priority chooses which active slot is visible."
        "Scenes", "Sprites" -> "The current program's scene; coordinates are Matrix cells, brightness is 0–4095."
        else -> "The host device. Simulation never invokes physical output."
    }

    fun example(op: String): PipelineDocument? {
        val spec = BlockCatalog[op] ?: return null
        fun block(name: String, vararg args: Pair<String, Expression>, body: List<Block> = emptyList()) = Block(op = name, arguments = args.toMap(), body = body)
        fun show() = block("display.toy", "toy" to Expression.str("clock"))
        fun pause() = block("flow.wait", "duration" to Expression.ms(1000))
        val design = Design(id = "example_art", name = "Pulse", kind = DesignKind.DYNAMIC, createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z", variants = PokemonCodename.entries.associate { panel ->
            panel.codename to DesignVariant(listOf(1, 2).map { level -> DesignFrame(durationMs = 500, cells = CharArray(panel.cellCount) { index -> if (index == panel.cellCount / 2) level.digitToChar() else '0' }.concatToString()) })
        })
        var target = Block(id = "example_target", op = op, arguments = spec.arguments.associate { argument -> argument.name to when (argument.reference) {
            ReferenceKind.VARIABLE -> Expression.str(if (op.startsWith("list.")) "items" else "counter")
            ReferenceKind.ROUTINE -> Expression.str("shared")
            ReferenceKind.PROGRAM -> Expression.str("child")
            ReferenceKind.ASSET -> Expression.str(design.id)
            ReferenceKind.BINDING -> Expression.str("artwork")
            null -> argument.default
        } }, body = if (spec.body) listOf(show(), pause()) else emptyList(), otherwise = if (spec.otherwise) listOf(block("display.off")) else emptyList())
        val prefix = mutableListOf<Block>()
        val suffix = mutableListOf<Block>()
        if (op.startsWith("scene.") || op.startsWith("sprite.")) {
            if (op != "scene.create") prefix += block("scene.create")
            if (op.startsWith("sprite.") && op != "sprite.create") prefix += block("sprite.create", "asset" to Expression.str(design.id))
            if (op == "scene.present" || op == "scene.clear" || op == "scene.clip") prefix += block("scene.circle")
            if (op != "scene.present") suffix += block("scene.present")
        } else if (op !in setOf("display.toy", "display.frame", "display.animation", "display.off", "display.overlay")) prefix += show()
        when (op) {
            "flow.select" -> target = target.copy(body = listOf(show(), block("display.toy", "toy" to Expression.str("eyes"))))
            "flow.while", "flow.until" -> target = target.copy(arguments = mapOf("condition" to Expression.operation(if (op == "flow.while") "less" else "greaterEqual", Expression.variable("counter"), Expression.num(3))), body = listOf(block("variable.change", "variable" to Expression.str("counter")), pause()))
            "flow.waitUntil" -> target = target.copy(arguments = mapOf("condition" to Expression.input("orientation.faceDown")))
            "flow.break" -> target = block("flow.repeat", "count" to Expression.num(3), body = listOf(target))
            "display.control" -> prefix += block("display.animation", "asset" to Expression.str(design.id))
            "native.command" -> { prefix.clear(); prefix += block("display.toy", "toy" to Expression.str("dino")); target = target.copy(arguments = mapOf("command" to Expression.str("jump"))) }
            "program.stop" -> prefix += block("program.run", "program" to Expression.str("child"))
            "timer.cancel" -> prefix += block("timer.start")
            "scene.pixel" -> target = target.copy(arguments = target.arguments + mapOf("x" to Expression.num(6), "y" to Expression.num(6)))
            "scene.text" -> target = target.copy(arguments = target.arguments + mapOf("text" to Expression.str("Hi"), "x" to Expression.num(3), "y" to Expression.num(4)))
        }
        val routine = Routine(id = "shared", name = "Shared sequence", blocks = if (op == "flow.return") listOf(target) else listOf(show(), pause()))
        val statements = if (op == "flow.return") prefix + block("routine.call", "routine" to Expression.str(routine.id), "resultVariable" to Expression.str("result")) else prefix + target + suffix
        return PipelineDocument(id = "reference_${op.replace('.', '_')}", name = spec.title, entryPoint = "example", programs = listOf(
            Program(id = "example", name = spec.title, variables = listOf(Variable("counter", "Count", initial = number(0)), Variable("items", "Items", ValueType.LIST, Value.Items(listOf(number(1), number(2)))), Variable("result", "Result", ValueType.ANY)), scripts = listOf(Script(id = "example_start", blocks = statements))),
            Program(id = "child", name = "Child program", scripts = listOf(Script(blocks = listOf(show())))),
        ), routines = listOf(routine), designs = mapOf(design.id to (Json.parseToJsonElement(DesignCodec.encode(design)) as JsonObject)), bindings = mapOf("artwork" to AssetBinding(design.id)))
    }
}
