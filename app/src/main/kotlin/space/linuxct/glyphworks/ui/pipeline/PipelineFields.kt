package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.glyphCorner
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

internal val LocalPipelineFieldsEnabled = staticCompositionLocalOf { true }

/** A dialog cannot accept the last valid model value while a visible field is incomplete. */
internal class PipelineFieldValidation {
    private val invalidFields = mutableStateMapOf<Any, Unit>()
    val hasErrors: Boolean get() = invalidFields.isNotEmpty()
    fun update(field: Any, valid: Boolean) {
        if (valid) invalidFields.remove(field) else invalidFields[field] = Unit
    }
    fun remove(field: Any) { invalidFields.remove(field) }
}

internal val LocalPipelineFieldValidation = staticCompositionLocalOf<PipelineFieldValidation?> { null }

/** Resolve shadowing before filtering by type, just as the runtime's current call scope does. */
internal fun scopedEditorVariables(program: Program?, routine: Routine?): List<Variable> =
    (routine?.variables.orEmpty() + program?.variables.orEmpty()).distinctBy { it.id }

internal fun scopedEditorParameters(program: Program?, routine: Routine?): List<Parameter> =
    (routine?.parameters.orEmpty() + program?.parameters.orEmpty()).distinctBy { it.id }

/** Named, searchable choices instead of internal identifiers or a screen-height dropdown. */
@Composable
internal fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier, selectedLabel: String? = null) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Invalid imported drafts must remain inspectable without duplicate LazyColumn keys.
    val uniqueChoices = choices.distinctBy { it.first }
    val title = uniqueChoices.firstOrNull { it.first == value }?.second ?: value.ifEmpty { stringResource(R.string.pipeline_field_choose) }
    Surface(
        modifier = modifier.fillMaxWidth(), shape = glyphCorner(14.dp, 18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f),
    ) {
        Row(Modifier.semantics { if (selectedLabel != null) stateDescription = title }.clickable(enabled = LocalPipelineFieldsEnabled.current && uniqueChoices.isNotEmpty()) { query = ""; open = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selectedLabel ?: title, style = MaterialTheme.typography.bodyLarge, maxLines = if (selectedLabel != null) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
    if (open) PipelineFormDialog(
        onDismissRequest = { open = false }, title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uniqueChoices.size > 7) OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.pipeline_field_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
                val filtered = uniqueChoices.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
                if (filtered.isEmpty()) Text(stringResource(R.string.pipeline_field_no_matches), style = MaterialTheme.typography.bodyMedium)
                val selectedIndex = filtered.indexOfFirst { it.first == value }.coerceAtLeast(0)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
                LaunchedEffect(query) { listState.scrollToItem(if (query.isBlank()) selectedIndex else 0) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered, key = { it.first }) { (key, name) ->
                        Surface(shape = glyphCorner(12.dp, 16.dp), color = if (key == value) MaterialTheme.colorScheme.onSurface.copy(alpha = .055f) else androidx.compose.ui.graphics.Color.Transparent) {
                            Row(Modifier.fillMaxWidth().selectable(selected = key == value, enabled = LocalPipelineFieldsEnabled.current, role = Role.RadioButton) { onChange(key); open = false }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (key == value) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.pipeline_editor_close)) } },
        dismissButton = {},
    )
}

/** Modified settings open automatically; a collapsed section always describes what it contains. */
@Composable
internal fun FieldSection(title: String, summary: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) { if (initiallyExpanded) expanded = true }
    PipelineCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, stringResource(if (expanded) R.string.pipeline_field_collapse else R.string.pipeline_field_expand), modifier = Modifier.size(20.dp))
        }
        if (expanded) content()
    }
}

@Composable
internal fun DurationField(label: String, milliseconds: Double, onChange: (Double) -> Unit) {
    var scale by remember(label) { mutableDoubleStateOf(if (milliseconds >= 1000 || milliseconds == 0.0) 1000.0 else 1.0) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        NumberField(label, milliseconds / scale, { onChange(it * scale) }, modifier = Modifier.weight(1f))
        ChoiceField(stringResource(R.string.pipeline_field_unit), scale.toInt().toString(), listOf("1" to stringResource(R.string.pipeline_field_ms), "1000" to stringResource(R.string.pipeline_field_seconds), "60000" to stringResource(R.string.pipeline_field_minutes)), { scale = it.toDouble() }, Modifier.width(118.dp),
            selectedLabel = stringResource(when (scale) { 1.0 -> R.string.pipeline_field_ms; 1000.0 -> R.string.pipeline_field_seconds_short; else -> R.string.pipeline_field_minutes_short }))
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
            if (kind == ValueType.DURATION) DurationField(stringResource(R.string.pipeline_field_duration), current.value) { onChange(Value.Number(it, UnitKind.MILLISECONDS)) }
            else {
                var unitEditor by remember { mutableStateOf(current.unit != UnitKind.SCALAR) }
                NumberField(stringResource(R.string.pipeline_editor_value), current.value, { onChange(current.copy(value = it)) }, trailingIcon = if (!unitEditor && current.unit == UnitKind.SCALAR) ({
                    IconButton(onClick = { unitEditor = true }, enabled = LocalPipelineFieldsEnabled.current) { Icon(Icons.Outlined.Straighten, stringResource(R.string.pipeline_field_add_unit), Modifier.size(20.dp)) }
                }) else null)
                if (unitEditor || current.unit != UnitKind.SCALAR) ChoiceField(stringResource(R.string.pipeline_editor_unit), current.unit.name, UnitKind.entries.filterNot { it == UnitKind.MILLISECONDS }.map { it.name to it.name.lowercase().replace('_', ' ') }, { onChange(current.copy(unit = UnitKind.valueOf(it))) })
            }
        }
        ValueType.BOOLEAN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (value.boolean()) R.string.pipeline_editor_on else R.string.editor_brush_off), modifier = Modifier.padding(top = 12.dp))
            GlyphSwitch(checked = value.boolean(), onCheckedChange = { onChange(boolean(it)) }, enabled = LocalPipelineFieldsEnabled.current)
        }
        ValueType.TEXT -> OutlinedTextField(value.text(), { onChange(text(it)) }, label = { Text(stringResource(R.string.pipeline_editor_text)) }, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
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
                Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .03f), shape = glyphCorner(14.dp, 18.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text("Item ${index + 1}", modifier = Modifier.weight(1f)); TextButton(onClick = { onChange(Value.Items(items.filterIndexed { at, _ -> at != index })) }, enabled = LocalPipelineFieldsEnabled.current) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                        if (depth < 6) LiteralField(item, ValueType.ANY, { replacement -> onChange(Value.Items(items.mapIndexed { at, old -> if (at == index) replacement else old })) }, depth + 1)
                    }
                }
            }
            TextButton(onClick = { onChange(Value.Items(items + number(0))) }, enabled = LocalPipelineFieldsEnabled.current && items.size < 128) { Text(stringResource(R.string.pipeline_editor_add_item)) }
        }
        ValueType.RECORD -> {
            val fields = (value as? Value.Record)?.fields.orEmpty()
            fields.forEach { (key, item) ->
                Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .03f), shape = glyphCorner(14.dp, 18.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text(key, modifier = Modifier.weight(1f)); TextButton(onClick = { onChange(Value.Record(fields - key)) }, enabled = LocalPipelineFieldsEnabled.current) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                        if (depth < 6) LiteralField(item, ValueType.ANY, { onChange(Value.Record(fields + (key to it))) }, depth + 1)
                    }
                }
            }
            var fieldName by remember { mutableStateOf("") }
            OutlinedTextField(fieldName, { fieldName = it }, label = { Text(stringResource(R.string.pipeline_editor_new_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
            TextButton(onClick = { onChange(Value.Record(fields + (fieldName.trim() to number(0)))); fieldName = "" }, enabled = LocalPipelineFieldsEnabled.current && fieldName.isNotBlank() && fieldName.trim() !in fields && fields.size < 64) { Text(stringResource(R.string.pipeline_editor_add_field)) }
        }
        ValueType.ANY -> Unit
    }
}

@Composable
internal fun NumberField(label: String, value: Double, onChange: (Double) -> Unit, integer: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), trailingIcon: @Composable (() -> Unit)? = null) {
    fun formatted(number: Double) = if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
    fun parsed(text: String) = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && (!integer || it == it.toLong().toDouble()) }
    var entered by remember(label) { mutableStateOf(formatted(value)) }
    LaunchedEffect(value, label) { if (parsed(entered) != value) entered = formatted(value) }
    val valid = parsed(entered) != null
    val validation = LocalPipelineFieldValidation.current
    val field = remember { Any() }
    SideEffect { validation?.update(field, valid) }
    DisposableEffect(validation, field) { onDispose { validation?.remove(field) } }
    OutlinedTextField(
        entered,
        { entered = it; parsed(it)?.let(onChange) },
        label = { Text(label) }, isError = !valid, singleLine = true, trailingIcon = trailingIcon,
        supportingText = if (!valid) ({ Text(if (integer) "Enter a whole number" else "Enter a number") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier, enabled = LocalPipelineFieldsEnabled.current,
    )
}

internal fun parameterValueError(parameter: Parameter, value: Value): String? = when {
    value is Value.Unavailable || !value.matches(parameter.type) -> "Choose a ${typeName(parameter.type).lowercase()} value."
    value is Value.Number && !value.value.isFinite() -> "Enter a finite number."
    value is Value.Number && (value.value < (parameter.minimum ?: -1e15) || value.value > (parameter.maximum ?: 1e15)) -> {
        fun bound(number: Double): String = if (parameter.type == ValueType.DURATION) "${space.linuxct.pipeline.number(number / 1000).display()} s" else space.linuxct.pipeline.number(number).display()
        "Enter a value from ${bound(parameter.minimum ?: -1e15)} to ${bound(parameter.maximum ?: 1e15)}."
    }
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
    var chooseSource by remember(depth) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (expression.op == "literal") stringResource(R.string.pipeline_field_fixed_value) else modes.firstOrNull { it.first == expression.op }?.second ?: expression.op,
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { chooseSource = !chooseSource }, enabled = LocalPipelineFieldsEnabled.current) {
            Icon(Icons.Outlined.SwapHoriz, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.pipeline_field_change_source))
        }
    }
    if (chooseSource) ChoiceField(stringResource(R.string.pipeline_field_value_source), expression.op, modes, { op ->
        val args = editorExpressionOperations.firstOrNull { it.op == op }?.inputs.orEmpty().map { Expression.literal(defaultValue(it)) }
        onChange(Expression(op = op, value = defaultValue(expected), args = args)); chooseSource = false
    })
    when (expression.op) {
        "literal" -> LiteralField(expression.value, expected, { onChange(expression.copy(value = it)) })
        "variable" -> {
            val choices = scopedEditorVariables(program, routine).filter { expected == ValueType.ANY || it.type == expected }.map { it.id to it.name }
            ChoiceField(stringResource(R.string.pipeline_editor_variable), expression.key, choices, { onChange(expression.copy(key = it)) })
            if (choices.isEmpty()) Text(stringResource(R.string.pipeline_editor_create_a_matching_variable_in_variables), style = MaterialTheme.typography.bodySmall)
        }
        "parameter" -> ChoiceField(stringResource(R.string.pipeline_editor_parameter), expression.key, scopedEditorParameters(program, routine).filter { expected == ValueType.ANY || it.type == expected }.map { it.id to it.name }, { onChange(expression.copy(key = it)) })
        "input" -> {
            ChoiceField(stringResource(R.string.pipeline_editor_input), expression.key, InputCatalog.all.filter { expected == ValueType.ANY || it.type == expected }.map { it.key to it.title }, { onChange(expression.copy(key = it)) })
            var customInput by remember { mutableStateOf(expression.key.isNotEmpty() && InputCatalog[expression.key] == null) }
            if (customInput) OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(stringResource(R.string.pipeline_editor_input_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
            else TextButton(onClick = { customInput = true }, enabled = LocalPipelineFieldsEnabled.current) { Text(stringResource(R.string.pipeline_field_custom_input)) }
            Text(stringResource(R.string.pipeline_editor_unavailable_inputs_remain_unavailable_use_is_available_or), style = MaterialTheme.typography.bodySmall)
        }
        "event" -> OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(stringResource(R.string.pipeline_editor_field_name)) }, supportingText = { Text(stringResource(R.string.pipeline_editor_for_example_package_value_timer_or_result)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
        "list" -> {
            expression.args.forEachIndexed { index, item ->
                Row { Text("Item ${index + 1}", Modifier.weight(1f)); TextButton(onClick = { onChange(expression.copy(args = expression.args.filterIndexed { at, _ -> at != index })) }, enabled = LocalPipelineFieldsEnabled.current) { Text(stringResource(R.string.pipeline_editor_remove)) } }
                if (depth < 12) ExpressionField(item, ValueType.ANY, document, program, routine, { next -> onChange(expression.copy(args = expression.args.toMutableList().apply { set(index, next) })) }, depth + 1)
            }
            TextButton(onClick = { onChange(expression.copy(args = expression.args + Expression.num(0))) }, enabled = LocalPipelineFieldsEnabled.current && expression.args.size < 128) { Text(stringResource(R.string.pipeline_editor_add_item)) }
        }
        "record" -> {
            val pairs = expression.args.chunked(2)
            pairs.forEachIndexed { index, pair ->
                val key = pair.first().value.text()
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(key, { next -> onChange(expression.copy(args = expression.args.toMutableList().apply { set(index * 2, Expression.str(next)) })) }, label = { Text(stringResource(R.string.pipeline_editor_field_name)) }, singleLine = true, modifier = Modifier.weight(1f), enabled = LocalPipelineFieldsEnabled.current)
                    TextButton(onClick = { onChange(expression.copy(args = expression.args.filterIndexed { at, _ -> at / 2 != index })) }, enabled = LocalPipelineFieldsEnabled.current) { Text(stringResource(R.string.pipeline_editor_remove)) }
                }
                if (depth < 12) ExpressionField(pair.getOrElse(1) { Expression.num(0) }, ValueType.ANY, document, program, routine, { next ->
                    val args = expression.args.toMutableList(); if (args.size == index * 2 + 1) args.add(next) else args[index * 2 + 1] = next
                    onChange(expression.copy(args = args))
                }, depth + 1)
            }
            TextButton(onClick = { onChange(expression.copy(args = expression.args + listOf(Expression.str("field${pairs.size + 1}"), Expression.num(0)))) }, enabled = LocalPipelineFieldsEnabled.current && pairs.size < 64) { Text(stringResource(R.string.pipeline_editor_add_field)) }
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
                if (keyKind != "block") OutlinedTextField(expression.key, { onChange(expression.copy(key = it)) }, label = { Text(if (keyKind == "timer") "Timer name" else "Field or property") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = LocalPipelineFieldsEnabled.current)
            }
            if (spec != null && depth < 12) spec.inputs.forEachIndexed { index, type ->
                Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .03f), shape = glyphCorner(14.dp, 18.dp)) {
                    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(expressionOperandLabel(expression.op, index), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        "variable" -> scopedEditorVariables(program, routine).firstOrNull { it.id == value.key }?.name ?: "Variable"
        "parameter" -> scopedEditorParameters(program, routine).firstOrNull { it.id == value.key }?.name ?: "Parameter"
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
        "and", "or" -> {
            fun terms(expression: Expression): List<Expression> = if (expression.op == value.op) expression.args.flatMap(::terms) else listOf(expression)
            val parts = terms(value)
            val compact = "${if (value.op == "and") "All" else "Any"} ${parts.size} conditions"
            if (parts.size > 2) compact else parts.joinToString(if (value.op == "and") " and " else " or ") { label(it) }.takeIf { it.length <= 88 } ?: compact
        }
        "equal", "notEqual", "greater", "greaterEqual", "less", "lessEqual", "add", "subtract", "multiply", "divide", "modulo" -> {
            val symbol = when (value.op) { "equal" -> "="; "notEqual" -> "≠"; "greater" -> ">"; "greaterEqual" -> "≥"; "less" -> "<"; "lessEqual" -> "≤"; "add" -> "+"; "subtract" -> "−"; "multiply" -> "×"; "divide" -> "÷"; else -> "mod" }
            value.args.joinToString(" $symbol ") { label(it) }
        }
        "not" -> "Not ${value.args.firstOrNull()?.let(::label).orEmpty()}"
        "timer.running" -> "${value.key.replace('_', ' ')} is running"
        "timer.remaining" -> "Time left on ${value.key.replace('_', ' ')}"
        "block.count" -> "Number of options"
        "list", "record" -> "${value.args.size / if (value.op == "record") 2 else 1} ${if (value.op == "record") "fields" else "items"}"
        else -> {
            val title = ExpressionCatalog[value.op]?.title ?: "Expression"
            val inputs = value.args.take(3).joinToString { label(it) }
            if (inputs.isEmpty() || inputs.length > 90 || value.args.size > 3) title else "$title ($inputs)"
        }
    }
    val optionalDefaults = setOf("slot", "priority", "wrap", "loop", "persistent", "timeout", "resultVariable", "z") + if (block.op.startsWith("display.")) setOf("duration") else emptySet()
    return block.arguments.entries.filterNot { (key, expression) ->
        val argument = spec?.arguments?.firstOrNull { it.name == key }
        key.startsWith("binding:") || (key in optionalDefaults && argument?.default == expression) ||
            (expression.op == "literal" && expression.value is Value.Record && (expression.value as Value.Record).fields.isEmpty()) ||
            (key == "toy" && expression.op == "literal" && NativeCatalog.get(expression.value.text()) != null)
    }.take(2).joinToString(" · ") { (key, value) ->
        val argument = spec?.arguments?.firstOrNull { it.name == key }
        val raw = value.value.text()
        val display = if (value.op == "literal") when {
            key == "toy" -> NativeCatalog.get(raw)?.title ?: raw.removePrefix("background.").replace('_', ' ').replaceFirstChar(Char::titlecase)
            argument?.reference == ReferenceKind.VARIABLE -> scopedEditorVariables(program, routine).firstOrNull { it.id == raw }?.name ?: "Choose variable"
            argument?.reference == ReferenceKind.ROUTINE -> document.routines.firstOrNull { it.id == raw }?.name ?: "Choose routine"
            argument?.reference == ReferenceKind.PROGRAM -> document.programs.firstOrNull { it.id == raw }?.name ?: "Choose program"
            argument?.reference == ReferenceKind.ASSET -> document.designs[raw]?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "Choose design"
            else -> label(value)
        } else label(value)
        "${argument?.label?.substringBefore(" (") ?: key.removePrefix("arg:")}: $display"
    }
}

private fun expressionOperandLabel(op: String, index: Int): String = when (op) {
    "choose" -> listOf("Condition", "When true", "When false").getOrNull(index)
    "clamp", "between" -> listOf("Value", "Minimum", "Maximum").getOrNull(index)
    "random" -> listOf("From", "To").getOrNull(index)
    "format" -> listOf("Number", "Decimal places").getOrNull(index)
    "native.value" -> "Display slot"
    "sprite.value" -> "Sprite name"
    "item" -> listOf("List", "Item index").getOrNull(index)
    "and", "or" -> listOf("First condition", "Second condition").getOrNull(index)
    else -> listOf("First value", "Second value", "Third value").getOrNull(index)
} ?: "Value ${index + 1}"
