package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.RandomPort
import space.linuxct.glyphworks.screens.DinoGame
import space.linuxct.glyphworks.screens.DinoScreen
import space.linuxct.pipeline.NativeBehavior
import space.linuxct.pipeline.NativeContext
import space.linuxct.pipeline.PipelineEvent
import space.linuxct.pipeline.Value
import kotlin.math.ceil

/** The native runner exposes state/commands independently from replaceable phase artwork. */
internal class DinoBehavior(private val context: NativeContext) : NativeBehavior {
    private val time = NativeTime(context)
    private val scheduler = NativeScheduling(context, time)
    private val art = NativeArtwork(context) { values() }
    private val random = object : RandomPort {
        override fun nextInt(bound: Int) = context.random.nextInt(bound)
        override fun nextFloat() = context.random.nextDouble().toFloat()
    }
    private fun number(key: String, default: Number, min: Double, max: Double) = context.parameters[key]?.number(default.toDouble())?.coerceIn(min, max) ?: default.toDouble()
    private val tuning = DinoGame.Tuning(
        jumpVelocity = number("jumpVelocity", DinoGame.JUMP_V0, 0.01, 3.0).toFloat(),
        gravity = number("gravity", DinoGame.GRAVITY, 0.001, 1.0).toFloat(),
        startSpeed = number("startSpeed", DinoGame.START_SPEED, 0.01, 3.0).toFloat(),
        maxSpeed = number("maxSpeed", DinoGame.MAX_SPEED, 0.01, 3.0).toFloat(),
        speedRamp = number("speedRamp", DinoGame.SPEED_RAMP, 0.0, 1.0).toFloat(),
        minimumGap = number("minimumGap", DinoGame.MIN_GAP_UNITS, 1.0, 1000.0).toInt(),
        gapSpread = number("gapSpread", DinoGame.GAP_SPREAD_UNITS, 0.0, 1000.0).toInt(),
    )
    private var game: DinoGame? = null
    private var startedAt = 0L
    private var phaseAt = 0L
    private var phase = "idle"
    private var suspended = false
    private var closed = false
    private var blink = true
    private var lastFrame = IntArray(context.size * context.size)
    override fun start() { if (!closed) { startedAt = time.elapsed(); phaseAt = startedAt; render() } }
    private fun transition(next: String) {
        if (phase == next) return
        phase = next; phaseAt = time.elapsed()
        context.emitEvent(PipelineEvent("native.event", values() + mapOf("kind" to Value.Text("phase"), "instance" to Value.Text(context.instanceId)), context.clock.elapsedMillis()))
    }
    override fun event(event: PipelineEvent) {
        if (!suspended && event.name in setOf("key.action", "key.press", "change", "glyph.change")) command("action", emptyMap())
    }
    override fun command(name: String, arguments: Map<String, Value>) {
        if (closed) return
        when (name) {
            "action" -> if (game?.state == DinoGame.State.RUNNING) jump() else restart()
            "start", "restart" -> restart()
            "jump" -> jump()
            "reset" -> { scheduler.clearTicker(); game = null; transition("idle"); render() }
        }
    }
    private fun restart() {
        game = DinoGame(context.size, random, tuning) {
            val slot = if (game?.isAirborne == true) "jump" else "run"
            art.geometry(slot)?.takeIf { it.hitWidth > 0 && it.hitHeight > 0 }?.let {
                DinoGame.Hitbox((it.hitX - it.anchorX).toFloat(), (it.hitY - it.anchorY).toFloat(), it.hitWidth.toFloat(), it.hitHeight.toFloat())
            }
        }
        startedAt = time.elapsed(); transition("run"); blink = true
        scheduler.setTicker(DinoScreen.TICK_MS) { tick() }
    }
    private fun jump() {
        val run = game ?: return
        if (run.isAirborne || run.state != DinoGame.State.RUNNING) return
        run.jump(); transition("jump")
    }
    private fun tick() {
        val run = game ?: return
        if (run.state == DinoGame.State.RUNNING) {
            run.step()
            if (run.state == DinoGame.State.OVER) {
                transition("game_over")
                context.emitEvent(PipelineEvent("native.event", values() + mapOf("kind" to Value.Text("result"), "instance" to Value.Text(context.instanceId)), context.clock.elapsedMillis()))
                blink = false
                scheduler.setTicker(DinoScreen.BLINK_MS) { blink = !blink; render() }
                return
            }
            transition(if (run.isAirborne) "jump" else "run")
        }
        render()
    }
    private fun render() {
        if (closed) return
        val run = game
        val boundCharacter = art.binding(phase) != null
        val frame = when {
            run == null -> DinoScreen.renderIdle(context.size, !boundCharacter)
            run.state == DinoGame.State.OVER -> DinoScreen.renderGameOver(context.size, run.score, blink)
            else -> DinoScreen.renderRun(context.size, run.jumpCells(), run.legPhase(), run.groundPhase(), run.obstacleCells(), !boundCharacter, art.binding("obstacle") == null, art.binding("ground") == null)
        }
        val elapsed = time.elapsed() - phaseAt
        if (phase == "game_over") {
            if (art.binding(phase) != null) { frame.fill(0); if (blink) art.compose(phase, frame, elapsed) }
        } else {
            art.compose("ground", frame, time.elapsed() - startedAt)
            run?.obstacleCells()?.forEach { art.compose("obstacle", frame, time.elapsed() - startedAt, it.x.toDouble(), DinoScreen.standRow(context.size).toDouble()) }
            val jumpDuration = ceil(2.0 * tuning.jumpVelocity / tuning.gravity + 1).toLong() * DinoScreen.TICK_MS
            art.compose(phase, frame, elapsed, DinoScreen.charX(context.size).toDouble(), (DinoScreen.standRow(context.size) - (run?.jumpCells() ?: 0)).toDouble(), if (phase == "jump") jumpDuration else null)
        }
        art.frame("frame", time.elapsed() - startedAt)?.copyInto(frame)
        lastFrame = frame
        if (!suspended) context.emitFrame(frame)
    }
    override fun values(): Map<String, Value> = mapOf(
        "phase" to Value.Text(phase), "score" to Value.Number((game?.score ?: 0).toDouble()),
        "height" to Value.Number((game?.height() ?: 0f).toDouble()),
        "velocity" to Value.Number((game?.verticalVelocity() ?: 0f).toDouble()),
        "speed" to Value.Number((game?.speed() ?: 0f).toDouble()),
        "airborne" to Value.Bool(game?.isAirborne == true),
        "obstacles" to Value.Items(game?.obstacleCells()?.map { Value.Record(mapOf("x" to Value.Number(it.x.toDouble()), "width" to Value.Number(it.w.toDouble()), "height" to Value.Number(it.h.toDouble()))) }.orEmpty()),
    )
    override fun suspend() { if (!closed && !suspended) { suspended = true; scheduler.suspend() } }
    override fun resume() { if (!closed && suspended) { suspended = false; scheduler.resume(); context.emitFrame(lastFrame.copyOf()) } }
    override fun close() { closed = true; scheduler.close(); game = null }
}
