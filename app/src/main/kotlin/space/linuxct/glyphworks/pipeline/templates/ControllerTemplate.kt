package space.linuxct.glyphworks.pipeline.templates

import space.linuxct.pipeline.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.and
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.branch
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.call
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.command
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.input
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.num
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.op
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.param
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.release
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.set
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.str
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.timer
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.variable

/** The menu is authored selection/drawing/timer logic, not a call into KeyActionRouter. */
object ControllerTemplate {
    fun create(): PipelineDocument {
        val entries = Value.Items(listOf("clock", "eyes", "dice", "dino", "weather").map(::text))
        val selected = op("item", param("toys"), variable("selection"))
        val select = Routine(id = "controller_show", name = "Show selected toy", blocks = listOf(
            release("menu"),
            Block(op = "display.toy", arguments = mapOf("toy" to selected, "slot" to str("main"))),
        ))
        val drawMenu = Routine(id = "controller_draw_menu", name = "Draw my menu", blocks = listOf(
            Block(op = "scene.create", arguments = mapOf("slot" to str("menu"), "priority" to num(30))),
            Block(op = "scene.clear"),
            branch(variable("blink"), listOf(
                Block(op = "scene.rect", arguments = mapOf("x" to num(2), "y" to num(2), "width" to op("subtract", input("panel.size"), num(4)), "height" to op("subtract", input("panel.size"), num(4)), "level" to num(1600), "filled" to Expression.bool(false))),
                Block(op = "scene.text", arguments = mapOf("text" to op("add", variable("selection"), num(1)), "x" to op("subtract", op("floor", op("divide", input("panel.size"), num(2))), num(1)), "y" to op("subtract", op("floor", op("divide", input("panel.size"), num(2))), num(2)), "level" to num(4095))),
            )),
            Block(op = "scene.present"),
        ))
        val close = Routine(id = "controller_close", name = "Confirm menu choice", blocks = listOf(
            set("menu", Expression.bool(false)), Block(op = "timer.cancel", arguments = mapOf("name" to str("menuBlink"))),
            Block(op = "timer.cancel", arguments = mapOf("name" to str("menuCommit"))), call(select.id),
        ))
        val advance = set("selection", op("modulo", op("add", variable("selection"), num(1)), op("max", num(1), op("length", param("toys")))))
        val program = Program(id = "controller_example", name = "Custom controls and menus", kind = ProgramKind.CONTROLLER,
            description = "Single press: use toy. Double press: open or confirm the menu. Triple press: home. Edit every rule and menu frame.",
            parameters = listOf(Parameter("toys", "Menu toys", ValueType.LIST, entries, quickSetting = true)),
            variables = listOf(Variable("selection", "Selected toy"), Variable("menu", "Menu open", ValueType.BOOLEAN, boolean(false)), Variable("blink", "Menu lit", ValueType.BOOLEAN, boolean(true))),
            scripts = listOf(
                Script(name = "Show the home toy", blocks = listOf(call(select.id))),
                Script(name = "Action or next menu item", trigger = Trigger("key.action"), blocks = listOf(branch(variable("menu"), listOf(advance, set("blink", Expression.bool(true)), call(drawMenu.id), timer("menuCommit", 5000)), listOf(command("action"))), Block(op = "input.consume"))),
                Script(name = "Open or confirm my menu", trigger = Trigger("key.double"), blocks = listOf(branch(variable("menu"), listOf(call(close.id)), listOf(set("menu", Expression.bool(true)), set("blink", Expression.bool(true)), call(drawMenu.id), timer("menuBlink", 450), timer("menuCommit", 5000))), Block(op = "input.consume"))),
                Script(name = "Return home", trigger = Trigger("key.triple"), blocks = listOf(set("selection", num(0)), call(close.id), Block(op = "input.consume"))),
                Script(name = "Blink menu", trigger = Trigger("timer", op("equal", Expression.event("name"), str("menuBlink"))), blocks = listOf(branch(variable("menu"), listOf(set("blink", op("not", variable("blink"))), call(drawMenu.id), Block(op = "timer.start", arguments = mapOf("name" to str("menuBlink"), "duration" to op("choose", variable("blink"), Expression.ms(450), Expression.ms(300)))))))),
                Script(name = "Confirm after five seconds", trigger = Trigger("timer", op("equal", Expression.event("name"), str("menuCommit"))), blocks = listOf(branch(variable("menu"), listOf(call(close.id))))),
            ),
        )
        return BuiltinPipelines.document(program).copy(routines = listOf(select, drawMenu, close))
    }
}
