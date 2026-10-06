package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import space.linuxct.pipeline.*
import space.linuxct.glyphworks.pipeline.native.NativeCatalog

@Composable
internal fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "Choose…" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onChange(key); open = false }) }
            }
        }
    }
}

internal fun typeName(type: ValueType): String = type.name.lowercase().replaceFirstChar(Char::titlecase)
internal fun defaultValue(type: ValueType): Value = when (type) {
    ValueType.NUMBER -> number(0)
    ValueType.DURATION -> duration(1000)
    ValueType.BOOLEAN -> boolean(false)
    ValueType.TEXT -> text("")
    ValueType.VECTOR -> Value.Vector(0.0, 0.0)
    ValueType.LIST -> Value.Items()
    ValueType.RECORD -> Value.Record()
    ValueType.ANY -> number(0)
}

internal fun Value.type(): ValueType = when (this) {
    is Value.Number -> if (unit == UnitKind.MILLISECONDS) ValueType.DURATION else ValueType.NUMBER
    is Value.Bool -> ValueType.BOOLEAN
    is Value.Text -> ValueType.TEXT
    is Value.Vector -> ValueType.VECTOR
    is Value.Items -> ValueType.LIST
    is Value.Record -> ValueType.RECORD
    is Value.Unavailable -> ValueType.ANY
}

@Composable
internal fun LiteralField(value: Value, expected: ValueType, onChange: (Value) -> Unit, depth: Int = 0) {
    val kind = if (expected == ValueType.ANY) value.type().takeUnless { it == ValueType.ANY } ?: ValueType.NUMBER else expected
    if (expected == ValueType.ANY) ChoiceField(stringResource(R.string.pipeline_editor_value_type), kind.name, ValueType.entries.filterNot { it == ValueType.ANY }.map { it.name to typeName(it) }, { onChange(defaultValue(ValueType.valueOf(it))) })
    when (kind) {
        ValueType.NUMBER, ValueType.DURATION -> {
            val current = value as? Value.Number ?: defaultValue(kind) as Value.Number
            NumberField(if (kind == ValueType.DURATION) "Milliseconds" else "Value", current.value, { onChange(current.copy(value = it)) })
            if (kind == ValueType.NUMBER) ChoiceField(stringResource(R.string.pipeline_editor_unit), current.unit.name, UnitKind.entries.filterNot { it == UnitKind.MILLISECONDS }.map { it.name to it.name.lowercase().replace('_', ' ') }, { onChange(current.copy(unit = UnitKind.valueOf(it))) })
        }
        ValueType.BOOLEAN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (value.boolean()) R.string.pipeline_editor_on else R.string.editor_brush_off), modifier = Modifier.padding(top = 12.dp))
            Switch(checked = value.boolean(), onCheckedChange = { onChange(boolean(it)) })
        }
        ValueType.TEXT -> OutlinedTextField(value.text(), { onChange(text(it)) }, label = { Text(stringResource(R.string.pipeline_editor_text)) }, modifier = Modifier.fillMaxWidth())
        ValueType.VECTOR -> {
            val vector = value as? Value.Vector ?: Value.Vector(0.0, 0.0)
            NumberField(stringResource(R.string.pipeline_editor_x), vector.x, { onChange(vector.copy(x = it)) })
            NumberField(stringResource(R.string.pipeline_editor_y), vector.y, { onChange(vector.copy(y = it)) })
            NumberField(stringResource(R.string.pipeline_editor_z), vector.z, { onChange(vector.copy(z = it)) })
            ChoiceField(stringResource(R.string.pipeline_editor_unit), vector.unit.name, UnitKind.entries.map { it.name to it.name.lowercase().replace('_', ' ') }, { onChange(vector.copy(unit = UnitKind.valueOf(it))) })
        }
        ValueType.LIST -> {
            val items = (value as? Value.Items)?.values.orEmpty()
            items.forEachIndexed { index, item ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text("Item ${index + 1}", modifier = Modifier.weight(1f)); TextButton(onClick = { onChange(Value.Items(items.filterIndexed { at, _ -> at != index })) }) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                        if (depth < 6) LiteralField(item, ValueType.ANY, { replacement -> onChange(Value.Items(items.mapIndexed { at, old -> if (at == index) replacement else old })) }, depth + 1)
                    }
                }
            }
            TextButton(onClick = { onChange(Value.Items(items + number(0))) }, enabled = items.size < 128) { Text(stringResource(R.string.pipeline_editor_add_item)) }
        }
        ValueType.RECORD -> {
            val fields = (value as? Value.Record)?.fields.orEmpty()
            fields.forEach { (key, item) ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text(key, modifier = Modifier.weight(1f)); TextButton(onClick = { onChange(Value.Record(fields - key)) }) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                        if (depth < 6) LiteralField(item, ValueType.ANY, { onChange(Value.Record(fields + (key to it))) }, depth + 1)
                    }
                }
            }
            var fieldName by remember { mutableStateOf("") }
            OutlinedTextField(fieldName, { fieldName = it }, label = { Text(stringResource(R.string.pipeline_editor_new_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onChange(Value.Record(fields + (fieldName.trim() to number(0)))); fieldName = "" }, enabled = fieldName.isNotBlank() && fieldName.trim() !in fields && fields.size < 64) { Text(stringResource(R.string.pipeline_editor_add_field)) }
        }
        ValueType.ANY -> Unit
    }
}

@Composable
internal fun NumberField(label: String, value: Double, onChange: (Double) -> Unit, integer: Boolean = false) {
    var entered by remember(value) { mutableStateOf(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()) }
    val parsed = entered.toDoubleOrNull()?.takeIf { it.isFinite() && (!integer || it == it.toLong().toDouble()) }
    OutlinedTextField(
        entered,
        { entered = it; it.toDoubleOrNull()?.takeIf { number -> number.isFinite() && (!integer || number == number.toLong().toDouble()) }?.let(onChange) },
        label = { Text(label) }, isError = parsed == null, singleLine = true,
        supportingText = if (parsed == null) ({ Text(if (integer) "Enter a whole number" else "Enter a number") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}

internal fun parameterValueError(parameter: Parameter, value: Value): String? = when {
    value is Value.Unavailable || !value.matches(parameter.type) -> "Choose a ${typeName(parameter.type).lowercase()} value."
    value is Value.Number && (value.value < (parameter.minimum ?: -1e15) || value.value > (parameter.maximum ?: 1e15)) -> "Enter a value within ${parameter.minimum ?: "−∞"} and ${parameter.maximum ?: "∞"}."
    value is Value.Number && parameter.default is Value.Number && value.unit != (parameter.default as Value.Number).unit -> "Use ${(parameter.default as Value.Number).unit.name.lowercase().replace('_', ' ')} for this setting."
    parameter.choices.isNotEmpty() && value !in parameter.choices -> "Choose one of the available options."
    else -> null
}

@Composable
internal fun ParameterValueField(parameter: Parameter, value: Value, onChange: (Value) -> Unit) {
    if (parameter.choices.isNotEmpty()) ChoiceField(stringResource(R.string.pipeline_editor_value), parameter.choices.indexOf(value).toString(), parameter.choices.mapIndexed { index, choice -> index.toString() to choice.display() }, { parameter.choices.getOrNull(it.toIntOrNull() ?: -1)?.let(onChange) })
    else LiteralField(value, parameter.type, onChange)
}

internal data class ExpressionOperation(val op: String, val title: String, val inputs: List<ValueType>, val result: ValueType)

// The runtime's portable expression vocabulary; unknown imported expressions remain visible.
internal val editorExpressionOperations: List<ExpressionOperation>
    get() = ExpressionCatalog.all.filter { it.op !in listOf("literal", "input", "variable", "parameter", "event") }
        .map { ExpressionOperation(it.op, it.title, it.arguments, it.result) }

internal fun Expression.summary(): String = when (op) {
    "literal" -> value.display().take(70)
    "variable" -> "Variable · $key"
    "parameter" -> "Setting · $key"
    "input" -> key.replace('.', ' ')
    "event" -> "Event · $key"
    else -> (editorExpressionOperations.firstOrNull { it.op == op }?.title ?: op) + args.joinToString(prefix = " (", postfix = ")") { it.summary().take(28) }
}

@Composable
internal fun ExpressionField(
    expression: Expression,
    expected: ValueType,
    document: PipelineDocument,
    program: Program?,
    routine: Routine?,
    onChange: (Expression) -> Unit,
    depth: Int = 0,
) {
    val modes = listOf("literal" to "Value", "input" to "Device input", "variable" to "Variable", "parameter" to "Parameter", "event" to "Event field") +
        editorExpressionOperations.filter { expected == ValueType.ANY || it.result == expected || it.result == ValueType.ANY || (expected == ValueType.DURATION && it.result == ValueType.NUMBER) }.map { it.op to it.title }
    ChoiceField("Expression · ${typeName(expected)}", expression.op, modes, { op ->
        val args = editorExpressionOperations.firstOrNull { it.op == op }?.inputs.orEmpty().map { Expression.literal(defaultValue(it)) }
        onChange(Expression(op = op, value = defaultValue(expected), args = args))
    })
    when (expression.op) {
        "literal" -> LiteralField(expression.value, expected, { onChange(expression.copy(value = it)) })
        "variable" -> {
            val choices = (program?.variables.orEmpty() + routine?.variables.orEmpty()).filter { expected == ValueType.ANY || it.type == expected }.map { it.id to it.name }
            ChoiceField(stringResource(R.string.pipeline_editor_variable), expression.key, choices, { onChange(expression.copy(key = it)) })
            if (choices.isEmpty()) Text(stringResource(R.string.pipeline_editor_create_a_matching_variable_in_variables), style = MaterialTheme.typography.bodySmall)
        }
        "parameter" -> ChoiceField(stringResource(R.string.pipeline_editor_parameter), expression.key, (program?.parameters.orEmpty() + routine?.parameters.orEmpty()).filter { expected == ValueType.ANY || it.type == expected }.map { it.id to it.name }, { onChange(expression.copy(key = it)) })
        "input" -> {
            ChoiceField(stringResource(R.string.pipeline_editor_input), expression.key, InputCatalog.all.filter { expected == ValueType.ANY || it.type == expected }.map { it.key to it.title }, { onChange(expression.copy(key = it)) })
            OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(stringResource(R.string.pipeline_editor_input_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.pipeline_editor_unavailable_inputs_remain_unavailable_use_is_available_or), style = MaterialTheme.typography.bodySmall)
        }
        "event" -> OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(stringResource(R.string.pipeline_editor_field_name)) }, supportingText = { Text(stringResource(R.string.pipeline_editor_for_example_package_value_timer_or_result)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "list" -> {
            expression.args.forEachIndexed { index, item ->
                Row { Text("Item ${index + 1}", Modifier.weight(1f)); TextButton(onClick = { onChange(expression.copy(args = expression.args.filterIndexed { at, _ -> at != index })) }) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                if (depth < 12) ExpressionField(item, ValueType.ANY, document, program, routine, { next -> onChange(expression.copy(args = expression.args.toMutableList().apply { set(index, next) })) }, depth + 1)
            }
            TextButton(onClick = { onChange(expression.copy(args = expression.args + Expression.num(0))) }, enabled = expression.args.size < 128) { Text(stringResource(R.string.pipeline_editor_add_item)) }
        }
        "record" -> {
            val pairs = expression.args.chunked(2)
            pairs.forEachIndexed { index, pair ->
                val key = pair.first().value.text()
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(key, { next -> onChange(expression.copy(args = expression.args.toMutableList().apply { set(index * 2, Expression.str(next)) })) }, label = { Text(stringResource(R.string.pipeline_editor_field_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(expression.copy(args = expression.args.filterIndexed { at, _ -> at / 2 != index })) }) { Text(stringResource(R.string.pipeline_editor_remove)) }
                }
                if (depth < 12) ExpressionField(pair.getOrElse(1) { Expression.num(0) }, ValueType.ANY, document, program, routine, { next ->
                    val args = expression.args.toMutableList(); if (args.size == index * 2 + 1) args.add(next) else args[index * 2 + 1] = next
                    onChange(expression.copy(args = args))
                }, depth + 1)
            }
            TextButton(onClick = { onChange(expression.copy(args = expression.args + listOf(Expression.str("field${pairs.size + 1}"), Expression.num(0)))) }, enabled = pairs.size < 64) { Text(stringResource(R.string.pipeline_editor_add_field)) }
        }
        else -> {
            val spec = editorExpressionOperations.firstOrNull { it.op == expression.op }
            ExpressionCatalog[expression.op]?.keyKind?.let { keyKind ->
                val fields = when {
                    keyKind == "block" -> allEditorBlocks(document).filter { BlockCatalog[it.op]?.body == true }.map { block ->
                        val owner = EditorDocument.location(document, block.id)?.ownerId
                        val ownerName = document.routines.firstOrNull { it.id == owner }?.name ?: document.programs.firstOrNull { p -> p.scripts.any { it.id == owner } }?.name.orEmpty()
                        block.id to (ownerName + " / " + block.comment.ifBlank { BlockCatalog[block.op]?.title ?: block.op })
                    }
                    expression.op == "native.value" -> NativeCatalog.behaviors.flatMap { it.outputs.keys }.distinct().map { it to it }
                    expression.op == "sprite.value" -> listOf("x", "y", "vx", "vy", "ax", "ay", "frame", "visible", "z", "scale", "rotation", "hitX", "hitY", "hitWidth", "hitHeight").map { it to it }
                    else -> emptyList()
                }
                if (fields.isNotEmpty()) ChoiceField(stringResource(R.string.pipeline_editor_property), expression.key, fields, { onChange(expression.copy(key = it)) })
                if (keyKind != "block") OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(if (keyKind == "timer") "Timer name" else "Field or property") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (spec != null && depth < 12) spec.inputs.forEachIndexed { index, type ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Input ${index + 1}", style = MaterialTheme.typography.labelSmall)
                        ExpressionField(expression.args.getOrElse(index) { Expression.literal(defaultValue(type)) }, type, document, program, routine, { value ->
                            val args = spec.inputs.mapIndexed { at, inputType -> if (at == index) value else expression.args.getOrElse(at) { Expression.literal(defaultValue(inputType)) } }
                            onChange(expression.copy(args = args))
                        }, depth + 1)
                    }
                }
            } else Text("Expression: ${expression.op}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal val editorInputs: List<Pair<String, String>> get() = InputCatalog.all.map { it.key to it.title }

/** Compact descriptions use the same labels and identities as the property sheet. */
internal fun blockDisplaySummary(block: Block, document: PipelineDocument): String {
    val spec = BlockCatalog[block.op]
    val owner = EditorDocument.location(document, block.id)?.ownerId
    val program = document.programs.firstOrNull { p -> p.scripts.any { it.id == owner } } ?: document.entry()
    val routine = document.routines.firstOrNull { it.id == owner }
    fun label(value: Expression): String = when (value.op) {
        "variable" -> (routine?.variables.orEmpty() + program?.variables.orEmpty()).firstOrNull { it.id == value.key }?.name ?: "Variable"
        "parameter" -> (routine?.parameters.orEmpty() + program?.parameters.orEmpty()).firstOrNull { it.id == value.key }?.name ?: "Parameter"
        "input" -> InputCatalog[value.key]?.title ?: "Device value"
        "event" -> "Event ${value.key.replace('.', ' ')}"
        "literal" -> when (val literal = value.value) {
            is Value.Number -> if (literal.unit == UnitKind.MILLISECONDS) {
                val milliseconds = literal.value
                if (milliseconds >= 1000 && milliseconds % 1000 == 0.0) "${(milliseconds / 1000).toLong()} s" else "${literal.display()} ms"
            } else literal.display()
            is Value.Bool -> if (literal.value) "On" else "Off"
            is Value.Record -> "${literal.fields.size} settings"
            is Value.Items -> "${literal.values.size} items"
            else -> literal.display()
        }
        else -> (ExpressionCatalog[value.op]?.title ?: "Expression") + value.args.joinToString(prefix = " (", postfix = ")") { label(it).take(22) }
    }
    return block.arguments.entries.filterNot { (key, expression) ->
        expression.op == "literal" && when {
            expression.value is Value.Record -> (expression.value as Value.Record).fields.isEmpty()
            key in listOf("duration", "priority") -> expression.value.numberOrNull() == 0.0
            key == "slot" -> expression.value.text() == "main"
            else -> key.startsWith("binding:")
        }
    }.take(2).joinToString(" · ") { (key, value) ->
        val argument = spec?.arguments?.firstOrNull { it.name == key }
        val raw = value.value.text()
        val display = if (value.op == "literal") when {
            key == "toy" -> NativeCatalog.get(raw)?.title ?: raw.removePrefix("background.").replace('_', ' ').replaceFirstChar(Char::titlecase)
            argument?.reference == ReferenceKind.VARIABLE -> (routine?.variables.orEmpty() + program?.variables.orEmpty()).firstOrNull { it.id == raw }?.name ?: "Choose variable"
            argument?.reference == ReferenceKind.ROUTINE -> document.routines.firstOrNull { it.id == raw }?.name ?: "Choose routine"
            argument?.reference == ReferenceKind.PROGRAM -> document.programs.firstOrNull { it.id == raw }?.name ?: "Choose program"
            argument?.reference == ReferenceKind.ASSET -> document.designs[raw]?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "Choose design"
            else -> label(value)
        } else label(value)
        "${argument?.label?.substringBefore(" (") ?: key.removePrefix("arg:")}: $display"
    }
}
