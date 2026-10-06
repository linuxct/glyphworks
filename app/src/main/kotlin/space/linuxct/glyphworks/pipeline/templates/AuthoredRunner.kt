package space.linuxct.glyphworks.pipeline.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.input
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.num
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.str
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.op
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.variable
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.set
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.branch
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.call
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines.wait
import space.linuxct.pipeline.*

/** A complete small game written only in general blocks; there is no native Dino dependency. */
object AuthoredRunner {
    fun create(): PipelineDocument {
        val size = input("panel.size")
        val ground = op("subtract", size, num(3))
        val playerX = num(3)
        val spriteX = Expression("sprite.value", key = "x", args = listOf(str("obstacle")))
        val spriteY = Expression("sprite.value", key = "y", args = listOf(str("runner")))
        fun spriteSet(name: String, property: String, value: Expression) = Block(op = "sprite.set", arguments = mapOf("name" to str(name), "property" to str(property), "value" to value))
        fun spriteCreate(name: String, asset: String, x: Expression, y: Expression) = Block(op = "sprite.create", arguments = mapOf("name" to str(name), "asset" to str(asset), "x" to x, "y" to y))
        val draw = Routine(id = "runner_draw", name = "Draw track and score", blocks = listOf(
            Block(op = "scene.clear"),
            Block(op = "scene.line", arguments = mapOf("x" to num(0), "y" to op("add", ground, num(2)), "x2" to op("subtract", size, num(1)), "y2" to op("add", ground, num(2)), "level" to num(900))),
            Block(op = "scene.text", arguments = mapOf("text" to variable("score"), "x" to op("subtract", size, num(5)), "y" to num(2), "level" to num(1800))),
            Block(op = "scene.present"),
        ))
        val restart = Routine(id = "runner_restart", name = "Start a new run", blocks = listOf(
            set("running", Expression.bool(true)), set("score", num(0)), set("obstacles", Expression.literal(Value.Items(listOf(text("obstacle"))))),
            Block(op = "scene.create", arguments = mapOf("slot" to str("game"))),
            spriteCreate("runner", "runner_art", playerX, ground),
            spriteSet("runner", "ay", num(20)),
            spriteCreate("obstacle", "obstacle_art", op("add", size, num(12)), ground),
            spriteSet("obstacle", "vx", num(-4)),
            call(draw.id),
        ))
        val lose = listOf(
            set("running", Expression.bool(false)), spriteSet("runner", "visible", Expression.bool(false)), spriteSet("obstacle", "visible", Expression.bool(false)),
            Block(op = "scene.clear"),
            Block(op = "scene.text", arguments = mapOf("text" to variable("score"), "x" to num(4), "y" to op("subtract", op("divide", size, num(2)), num(2)))),
            Block(op = "scene.present"),
        )
        val stepping = listOf(
            Block(op = "sprite.step", arguments = mapOf("duration" to Expression.ms(50))),
            branch(op("greaterEqual", spriteY, ground), listOf(spriteSet("runner", "y", ground), spriteSet("runner", "vy", num(0)))),
            branch(op("less", spriteX, num(-2)), listOf(
                Block(op = "variable.change", arguments = mapOf("variable" to str("score"), "by" to num(1))),
                spriteSet("obstacle", "x", op("add", size, op("random", num(8), num(20)))),
                spriteSet("obstacle", "vx", op("negate", op("min", num(8), op("add", num(4), op("multiply", variable("score"), num(0.2)))))),
                Block(op = "list.set", arguments = mapOf("variable" to str("obstacles"), "index" to num(0), "value" to str("obstacle"))),
            )),
            branch(Expression("sprite.touching", args = listOf(str("runner"), op("item", variable("obstacles"), num(0)))), lose, listOf(call(draw.id))),
        )
        val program = Program(id = "authored_runner", name = "Build-a-runner", description = "A complete editable runner: sprite motion, jumping, random obstacles, collision, score and restart. No built-in game block.", immediateAction = true,
            variables = listOf(Variable("running", "Playing", ValueType.BOOLEAN, boolean(false)), Variable("score", "Score"), Variable("obstacles", "Obstacle sprites", ValueType.LIST, Value.Items())),
            scripts = listOf(
                Script(name = "Start and advance the game", blocks = listOf(call(restart.id), Block(op = "flow.forever", body = listOf(branch(variable("running"), stepping), wait(50))))),
                Script(name = "Jump or restart", trigger = Trigger("key.action"), blocks = listOf(branch(variable("running"), listOf(branch(op("greaterEqual", spriteY, ground), listOf(spriteSet("runner", "vy", num(-9))))), listOf(call(restart.id))), Block(op = "input.consume"))),
            ),
        )
        return BuiltinPipelines.document(program).copy(
            routines = listOf(draw, restart),
            designs = mapOf("runner_art" to asset("runner_art", listOf(".##", "###", "#.#")), "obstacle_art" to asset("obstacle_art", listOf("#", "#"))),
        )
    }
    private fun asset(id: String, rows: List<String>) = Json.parseToJsonElement(DesignCodec.encode(Design(
        id = id, name = id.replace('_', ' '), author = "GlyphWorks", createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z",
        variants = PokemonCodename.entries.associate { panel ->
            val frame = CharArray(panel.cellCount) { '0' }
            val left = panel.size / 2 - rows.first().length / 2
            val top = panel.size / 2 - rows.size / 2
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, cell -> if (cell == '#') frame[(top + y) * panel.size + left + x] = '2' } }
            panel.codename to DesignVariant(listOf(DesignFrame(cells = String(frame))))
        },
    ))).jsonObject
}
