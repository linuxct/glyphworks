package space.linuxct.pipeline

import kotlinx.serialization.json.JsonObject

data class PipelineEvent(
    val name: String,
    val values: Map<String, Value> = emptyMap(),
    val atMillis: Long = 0,
    val consumable: Boolean = false,
)

fun interface Cancellation { fun cancel() }

/** Callbacks are serialized by the owner. Cancellation must be idempotent. */
interface TaskScheduler {
    fun post(delayMs: Long, action: () -> Unit): Cancellation
}

data class Diagnostic(val message: String, val blockId: String? = null, val fatal: Boolean = false)
data class TraceEntry(val atMillis: Long, val blockId: String?, val operation: String, val detail: String = "")
data class RuntimeSnapshot(val activeBlocks: Set<String>, val waitingBlocks: Set<String>, val variables: Map<String, Value>)

interface PipelineClock {
    fun elapsedMillis(): Long
    fun wallMillis(): Long
}

interface PipelineRandom {
    fun nextInt(bound: Int): Int
    fun nextDouble(): Double
}

/** A native behavior may draw and post scoped callbacks but may never acquire hardware. */
interface NativeBehavior {
    fun start()
    fun event(event: PipelineEvent) {}
    fun command(name: String, arguments: Map<String, Value>) {}
    fun values(): Map<String, Value> = emptyMap()
    fun suspend() {}
    fun resume() {}
    fun close()
}

data class NativeContext(
    val instanceId: String,
    val size: Int,
    val parameters: Map<String, Value>,
    val bindings: Map<String, AssetBinding>,
    val designs: Map<String, JsonObject>,
    val clock: PipelineClock,
    val random: PipelineRandom,
    val scheduler: TaskScheduler,
    val inputs: () -> Map<String, Value>,
    val emitFrame: (IntArray) -> Unit,
    val emitEvent: (PipelineEvent) -> Unit,
    val action: (String, Map<String, Value>) -> Unit = { _, _ -> },
    val readState: (String) -> Value? = { null },
    val writeState: (String, Value) -> Unit = { _, _ -> },
    /** Pure bounded scene routine: state is local to this presentation and never reaches hardware. */
    val renderDrawing: (String, Map<String, Value>) -> IntArray? = { _, _ -> null },
)

interface PipelineHost {
    val clock: PipelineClock
    val random: PipelineRandom
    val size: Int
    fun inputs(): Map<String, Value>
    fun createNative(type: String, context: NativeContext): NativeBehavior?
    fun output(frame: IntArray)
    /** Active native presentations add their requirements to event/expression subscriptions. */
    fun nativeCapabilities(type: String, parameters: Map<String, Value>): Set<String> = when(type.removePrefix("glyphworks.")) {
        "visualizer" -> setOf("audio")
        "weather", "background.weather" -> setOf("weather")
        "notifications", "background.notifications" -> setOf("notifications")
        "compass" -> setOf("compass")
        "level", "bottle", "background.ball" -> setOf("orientation")
        "solar", "background.sunrise", "background.sunset" -> setOf("location")
        "battery", "background.battery", "background.battery_gauge" -> setOf("battery")
        "speed", "background.speed" -> setOf("speed")
        else -> emptySet()
    }
    fun action(name: String, values: Map<String, Value>) {}
    fun readState(programId: String, variableId: String): Value? = null
    fun writeState(programId: String, variableId: String, value: Value) {}
    fun demand(capabilities: Set<String>) {}
    fun diagnostic(diagnostic: Diagnostic) {}
    /** A host can reject optional native families without coupling the portable VM to them. */
    fun supports(requirement: Requirement): Boolean = requirement.version == 1
    /** Asset pixels are local decoded resources, never disk/network work in this call. */
    fun assetFrame(assetId: String, elapsedMs: Long, size: Int): IntArray? = null
}

data class RuntimeLimits(
    val maxBlocks: Int = 4096,
    val maxDepth: Int = 32,
    val maxFibers: Int = 32,
    val maxTimers: Int = 64,
    val maxSprites: Int = 64,
    val maxListItems: Int = 1024,
    val maxStringLength: Int = 4096,
    val maxInstructionsPerTurn: Int = 2048,
    val maxStarvedTurns: Int = 120,
    val maxQueuedEvents: Int = 256,
    val maxTrace: Int = 256,
)
