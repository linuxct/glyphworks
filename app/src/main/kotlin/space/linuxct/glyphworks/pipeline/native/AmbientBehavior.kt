package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.pipeline.*

/** Native composition uses the same editable Ambient graph, not a second legacy state machine. */
internal class AmbientBehavior(private val context: NativeContext, private val ports: Ports, prefs: Prefs) : NativeBehavior {
    private val document = BuiltinPipelines.ambient(prefs, (context.parameters["backgrounds"] as? Value.Items)?.values?.map { it.text() }).let { source -> source.copy(programs = source.programs.map { program ->
        program.copy(values = program.values + context.parameters.filterKeys { key -> program.parameters.any { it.id == key } })
    }) }
    private var closed = false
    private var suspended = false
    private var tick: Cancellation? = null
    private var frame = IntArray(context.size * context.size)
    private val artwork = NativeArtwork(context) { values() }
    private val startedAt = context.clock.elapsedMillis()
    private val runtime = PipelineRuntime(document, object : PipelineHost {
        override val size = context.size
        override val clock = context.clock
        override val random = context.random
        override fun inputs(): Map<String, Value> {
            val supplied = context.inputs()
            val energy = supplied["music.energy"] ?: ports.spectrum.bands(size)?.maxOrNull()?.let(::number) ?: Value.Unavailable()
            return supplied + mapOf("music.energy" to energy, "battery.level" to number(ports.battery.levelPercent()), "battery.charging" to boolean(ports.battery.isCharging()))
        }
        override fun createNative(type: String, child: NativeContext): NativeBehavior? = NativeLibrary.create(type, child, ports, prefs)
        override fun output(frame: IntArray) {
            this@AmbientBehavior.frame = (artwork.frame("frame", clock.elapsedMillis() - startedAt) ?: frame).copyOf()
            if (!suspended && !closed) context.emitFrame(this@AmbientBehavior.frame)
        }
        override fun action(name: String, values: Map<String, Value>) = context.action(name, values)
        override fun readState(programId: String, variableId: String) = context.readState("ambient:$programId:$variableId")
        override fun writeState(programId: String, variableId: String, value: Value) = context.writeState("ambient:$programId:$variableId", value)
    })
    override fun start() { runtime.start(); runtime.advance(); schedule() }
    private fun schedule() {
        if (closed) return
        tick = context.scheduler.post(25) { if (!closed) { runtime.advance(); schedule() } }
    }
    override fun event(event: PipelineEvent) { if (!closed && !suspended) runtime.dispatch(event) }
    override fun command(name: String, arguments: Map<String, Value>) {
        if (!closed && name in setOf("action", "next")) runtime.dispatch(PipelineEvent("key.action", atMillis = context.clock.elapsedMillis(), consumable = true))
    }
    override fun values(): Map<String, Value> = runtime.values() + ("phase" to text("ambient"))
    override fun suspend() { suspended = true; runtime.externallyCovered(true) }
    override fun resume() { suspended = false; runtime.externallyCovered(false); context.emitFrame(frame.copyOf()) }
    override fun close() { if (closed) return; closed = true; tick?.cancel(); tick = null; runtime.close() }
}
