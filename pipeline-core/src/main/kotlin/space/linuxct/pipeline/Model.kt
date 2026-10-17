package space.linuxct.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

const val PIPELINE_FORMAT = "glyph.pipeline"
const val PIPELINE_FORMAT_VERSION = 1

fun pipelineId(): String = UUID.randomUUID().toString().replace("-", "")

@Serializable
enum class ValueType { NUMBER, BOOLEAN, TEXT, DURATION, VECTOR, LIST, RECORD, ANY }

@Serializable
enum class UnitKind { SCALAR, MILLISECONDS, DEGREES, RADIANS_PER_SECOND, METERS_PER_SECOND_SQUARED, LUX, PERCENT, CELLS }

/** Values are data, never host objects, code, Android intents or file handles. */
@Serializable
sealed class Value {
    @Serializable @SerialName("number") data class Number(val value: Double, val unit: UnitKind = UnitKind.SCALAR) : Value()
    @Serializable @SerialName("boolean") data class Bool(val value: Boolean) : Value()
    @Serializable @SerialName("text") data class Text(val value: String) : Value()
    @Serializable @SerialName("vector") data class Vector(val x: Double, val y: Double, val z: Double = 0.0, val unit: UnitKind = UnitKind.SCALAR) : Value()
    @Serializable @SerialName("list") data class Items(val values: List<Value> = emptyList()) : Value()
    @Serializable @SerialName("record") data class Record(val fields: Map<String, Value> = emptyMap()) : Value()
    @Serializable @SerialName("unavailable") data class Unavailable(val reason: String = "Not available") : Value()

    fun numberOrNull(): Double? = (this as? Number)?.value
    fun number(default: Double = 0.0): Double = numberOrNull() ?: default
    fun boolean(default: Boolean = false): Boolean = (this as? Bool)?.value ?: default
    fun text(default: String = ""): String = (this as? Text)?.value ?: default
    fun display(): String = when (this) {
        is Number -> if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        is Text -> value
        is Vector -> "($x, $y, $z)"
        is Items -> values.joinToString(prefix = "[", postfix = "]") { it.display() }
        is Record -> fields.entries.joinToString { "${it.key}: ${it.value.display()}" }
        is Unavailable -> reason
    }
    fun matches(type: ValueType): Boolean = this is Unavailable || type == ValueType.ANY || when (type) {
        ValueType.NUMBER -> this is Number && unit != UnitKind.MILLISECONDS
        ValueType.DURATION -> this is Number && unit == UnitKind.MILLISECONDS
        ValueType.BOOLEAN -> this is Bool
        ValueType.TEXT -> this is Text
        ValueType.VECTOR -> this is Vector
        ValueType.LIST -> this is Items
        ValueType.RECORD -> this is Record
        ValueType.ANY -> true
    }
}

fun number(value: Number): Value.Number = Value.Number(value.toDouble())
fun duration(ms: Long): Value.Number = Value.Number(ms.toDouble(), UnitKind.MILLISECONDS)
fun text(value: String): Value.Text = Value.Text(value)
fun boolean(value: Boolean): Value.Bool = Value.Bool(value)

/** Prefix expression tree; [key] names a variable/input/field, never executable source. */
@Serializable
data class Expression(
    val op: String = "literal",
    val value: Value = Value.Unavailable(),
    val key: String = "",
    val args: List<Expression> = emptyList(),
) {
    companion object {
        fun literal(value: Value) = Expression(value = value)
        fun num(value: Number) = literal(number(value))
        fun ms(value: Long) = literal(duration(value))
        fun str(value: String) = literal(text(value))
        fun bool(value: Boolean) = literal(boolean(value))
        fun input(key: String) = Expression("input", key = key)
        fun variable(key: String) = Expression("variable", key = key)
        fun parameter(key: String) = Expression("parameter", key = key)
        fun event(key: String) = Expression("event", key = key)
        fun operation(op: String, vararg args: Expression) = Expression(op, args = args.toList())
    }
}

@Serializable
data class Parameter(
    val id: String,
    val name: String,
    val type: ValueType = ValueType.NUMBER,
    val default: Value = Value.Number(0.0),
    val description: String = "",
    val minimum: Double? = null,
    val maximum: Double? = null,
    val choices: List<Value> = emptyList(),
    val quickSetting: Boolean = false,
)

@Serializable
data class Variable(
    val id: String,
    val name: String,
    val type: ValueType = ValueType.NUMBER,
    val initial: Value = Value.Number(0.0),
    val persistent: Boolean = false,
)

@Serializable
data class Block(
    val id: String = pipelineId(),
    val op: String,
    val arguments: Map<String, Expression> = emptyMap(),
    val body: List<Block> = emptyList(),
    val otherwise: List<Block> = emptyList(),
    val comment: String = "",
    val enabled: Boolean = true,
)

@Serializable
enum class Reentry { RESTART, IGNORE, QUEUE, PARALLEL }

@Serializable
enum class TriggerEdge { EVENT, RISING, FALLING, CHANGE }

@Serializable
data class CalendarTrigger(val hour: Int = 8, val minute: Int = 0, val weekdays: List<Int> = (1..7).toList())

@Serializable
data class Trigger(
    val event: String = "start",
    val condition: Expression? = null,
    val edge: TriggerEdge = TriggerEdge.EVENT,
    /** A true level at initialization can activate a rule without inventing an event. */
    val initially: Boolean = false,
    val stableForMs: Long = 0,
    val calendar: CalendarTrigger? = null,
)

@Serializable
data class Script(
    val id: String = pipelineId(),
    val name: String = "When started",
    val trigger: Trigger = Trigger(),
    val blocks: List<Block> = emptyList(),
    val reentry: Reentry = Reentry.RESTART,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

@Serializable
data class Routine(
    val id: String = pipelineId(),
    val name: String,
    val parameters: List<Parameter> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val returns: ValueType = ValueType.ANY,
    val blocks: List<Block> = emptyList(),
    val revision: Int = 1,
)

@Serializable
enum class ProgramKind { TOY, AMBIENT, CONTROLLER, ROUTINE }

@Serializable
data class Program(
    val id: String = pipelineId(),
    val name: String,
    val kind: ProgramKind = ProgramKind.TOY,
    val parameters: List<Parameter> = emptyList(),
    val values: Map<String, Value> = emptyMap(),
    val variables: List<Variable> = emptyList(),
    val scripts: List<Script> = emptyList(),
    val description: String = "",
    val template: String? = null,
    val templateVersion: Int = 1,
    val immediateAction: Boolean = false,
)

@Serializable
enum class AnimationFit { NATURAL, STRETCH_TO_PHASE, LOOP, HOLD_LAST }

/** Artwork remains glyph.design v1; composition metadata lives in this binding. */
@Serializable
data class AssetBinding(
    val assetId: String = "",
    val playback: AnimationFit = AnimationFit.NATURAL,
    val transparentZero: Boolean = true,
    val variants: Map<String, SpriteGeometry> = emptyMap(),
    val routineId: String? = null,
)

@Serializable
data class SpriteGeometry(
    val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0,
    val anchorX: Double = 0.0, val anchorY: Double = 0.0,
    val hitX: Double = 0.0, val hitY: Double = 0.0,
    val hitWidth: Double = 0.0, val hitHeight: Double = 0.0,
)

@Serializable
data class Position(val x: Float = 0f, val y: Float = 0f)

@Serializable
data class Viewport(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f)

@Serializable
data class EditorMetadata(
    val positions: Map<String, Position> = emptyMap(),
    val collapsed: Set<String> = emptySet(),
    val viewport: Viewport = Viewport(),
    val selected: String? = null,
)

@Serializable
data class Requirement(val capability: String, val version: Int = 1)

@Serializable
data class PreviewSettings(
    val thumbnailAssetId: String? = null,
    val thumbnailFrame: Int = 0,
    val elapsedMs: Long = 700,
    val initialAction: Boolean = false,
)

@Serializable
data class PipelineDocument(
    val format: String = PIPELINE_FORMAT,
    val formatVersion: Int = PIPELINE_FORMAT_VERSION,
    val id: String = pipelineId(),
    val name: String = "Untitled pipeline",
    val author: String = "",
    val createdAt: String = "",
    val modifiedAt: String = "",
    val createdWith: String = "",
    val entryPoint: String = "",
    val programs: List<Program> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val designs: Map<String, JsonObject> = emptyMap(),
    val bindings: Map<String, AssetBinding> = emptyMap(),
    val requires: List<Requirement> = emptyList(),
    val panels: Set<Int> = setOf(13, 25),
    val editor: EditorMetadata = EditorMetadata(),
    val preview: PreviewSettings = PreviewSettings(),
) {
    fun entry(): Program? = programs.firstOrNull { it.id == entryPoint }
}
