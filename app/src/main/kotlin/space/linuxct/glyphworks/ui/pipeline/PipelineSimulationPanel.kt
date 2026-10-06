package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.ui.design.drawMatrix
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.GlyphSegmentedRow
import space.linuxct.glyphworks.ui.theme.glyphSegmentedColors
import space.linuxct.glyphworks.ui.theme.glyphSegmentedShape
import space.linuxct.glyphworks.ui.theme.dialogSurface
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget
import space.linuxct.pipeline.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoField

/** Same interpreter, controls and view in the editor and guided tour. No production inputs. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PipelineSimulationPanel(
    simulation: PipelineSimulation,
    modifier: Modifier = Modifier,
    autoRun: Boolean = true,
    panelSize: Int = 13,
    onPanelSize: (Int) -> Unit = {},
    onRestart: () -> Unit = {},
    revision: Int = 0,
    onExecutionState: (Set<String>, Set<String>) -> Unit = { _, _ -> },
) {
    var playing by remember(simulation) { mutableStateOf(autoRun) }
    var generation by remember(simulation) { mutableIntStateOf(0) }
    var inputsExpanded by remember { mutableStateOf(false) }
    var inspectorExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(simulation, playing, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (playing) {
                delay(50); simulation.advanceBy(50); generation++
                if (simulation.diagnostics.any { it.fatal }) playing = false
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val redraw = generation + revision
    val reportExecution by rememberUpdatedState(onExecutionState)
    LaunchedEffect(simulation, generation, revision) { reportExecution(simulation.activeBlockIds, simulation.waitingBlockIds) }
    DisposableEffect(simulation) { onDispose { reportExecution(emptySet(), emptySet()) } }
    val frame = simulation.frame.copyOf()
    val colors = MaterialTheme.colorScheme
    val isFaulted = simulation.diagnostics.any { it.fatal }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).pipelineDemoTarget("simulation"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PipelineCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.pipeline_sim_refined_preview), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pipeline_sim_refined_sample_data), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                GlyphSegmentedRow(if (panelSize == 13) 0 else 1, 2, Modifier.width(156.dp), onCard = true) {
                    listOf(13, 25).forEachIndexed { index, size ->
                        SegmentedButton(
                            selected = size == panelSize, onClick = { onPanelSize(size) },
                            shape = glyphSegmentedShape(index, 2),
                            colors = glyphSegmentedColors(size == panelSize, onCard = true),
                            icon = {}, label = { Text(stringResource(R.string.pipeline_simulation_panel, size), style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                val previewLabel = stringResource(R.string.pipeline_sim_refined_matrix_description, panelSize)
                Canvas(Modifier.size(120.dp).semantics { contentDescription = previewLabel }) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color(0xFF080808), size.minDimension / 2f, center)
                    drawMatrix(center, size.minDimension * .44f, panelSize, frame)
                }
                Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Canvas(Modifier.size(5.dp)) { drawCircle(if (playing && !isFaulted) colors.primary else colors.onSurfaceVariant.copy(alpha = .5f)) }
                    Text(stringResource(if (playing && !isFaulted) R.string.pipeline_sim_refined_running else R.string.pipeline_sim_refined_paused),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalButton(onClick = { playing = !playing }, enabled = !isFaulted, contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (playing) R.string.pipeline_simulation_pause else R.string.pipeline_simulation_play), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { playing = false; simulation.advanceBy(50); generation++ }, enabled = !isFaulted,
                    contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
                    Text(stringResource(R.string.pipeline_simulation_step), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { simulation.advanceBy(5000); generation++ }, enabled = !isFaulted,
                    contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
                    Text(stringResource(R.string.pipeline_sim_refined_advance), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onRestart) { Icon(Icons.Outlined.RestartAlt, stringResource(R.string.pipeline_simulation_restart), Modifier.size(21.dp), tint = colors.onSurfaceVariant) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationDisclosure(stringResource(R.string.pipeline_simulation_inputs), Icons.Outlined.Tune, inputsExpanded, { inputsExpanded = !inputsExpanded }, Modifier.weight(1f))
            SimulationDisclosure(stringResource(R.string.pipeline_sim_refined_inspect), Icons.Outlined.DataObject, inspectorExpanded, { inspectorExpanded = !inspectorExpanded }, Modifier.weight(1f))
        }
        if (inputsExpanded) SimulationInputs(simulation) { generation++ }
        if (inspectorExpanded) SimulationInspector(simulation)
        simulation.diagnostics.takeLast(3).forEach { issue ->
            Surface(color = colors.errorContainer.copy(alpha = .55f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = colors.error)
                    Text(issue.message, color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SimulationDisclosure(label: String, icon: ImageVector, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, color = pipelineSurfaceColor()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SimulationInputs(simulation: PipelineSimulation, changed: () -> Unit) {
    var event by remember { mutableStateOf("key.action") }
    var input by remember { mutableStateOf("music.playing") }
    var value by remember { mutableStateOf("true") }
    var invalid by remember { mutableStateOf(false) }
    var eventFields by remember(event) { mutableStateOf(Value.Record()) }
    var fieldsExpanded by remember { mutableStateOf(false) }
    val eventChoices = remember(simulation.document) {
        val known = EventCatalog.all.map { it.name to it.title }
        known + simulation.document.programs.flatMap { p -> p.scripts.map { it.trigger.event } }
            .filter { candidate -> known.none { it.first == candidate } }.distinct().map { it to it }
    }
    PipelineCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        PipelineEyebrow(stringResource(R.string.pipeline_sim_refined_events))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationChoice(eventChoices, event, { event = it }, Modifier.weight(1f))
            TextButton(onClick = { simulation.dispatch(PipelineEvent(event, values = eventFields.fields, consumable = event.startsWith("key."))); changed() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(stringResource(R.string.pipeline_simulation_send), style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(onClick = { fieldsExpanded = !fieldsExpanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Text(stringResource(R.string.pipeline_simulation_event_fields), style = MaterialTheme.typography.labelMedium)
            Icon(if (fieldsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        if (fieldsExpanded) LiteralField(eventFields, ValueType.RECORD, { eventFields = it as Value.Record })
    }
    PipelineCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        PipelineEyebrow(stringResource(R.string.pipeline_sim_refined_device))
        Text(stringResource(R.string.pipeline_sim_refined_sample_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SimulationToggle(stringResource(R.string.pipeline_sim_refined_music), simulation.inputs["music.playing"]?.boolean() == true) { enabled ->
            simulation.inputs["music.energy"] = number(if (enabled) .5 else 0.0)
            updateSimulationInput(simulation, "music.playing", boolean(enabled)); changed()
        }
        SimulationToggle(stringResource(R.string.pipeline_sim_refined_charging), simulation.inputs["battery.charging"]?.boolean() == true) { enabled ->
            simulation.inputs["battery.plugged"] = boolean(enabled)
            updateSimulationInput(simulation, "battery.charging", boolean(enabled)); changed()
        }
        SimulationToggle(stringResource(R.string.pipeline_sim_refined_face_down), simulation.inputs["orientation.faceDown"]?.boolean() == true) { enabled ->
            simulation.inputs["orientation.faceUp"] = boolean(!enabled)
            simulation.inputs["orientation.edge"] = boolean(false)
            simulation.inputs["sensor.gravity"] = Value.Vector(0.0, 0.0, if (enabled) -9.81 else 9.81, UnitKind.METERS_PER_SECOND_SQUARED)
            updateSimulationInput(simulation, "orientation.faceDown", boolean(enabled)); changed()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        PipelineEyebrow(stringResource(R.string.pipeline_sim_refined_all_inputs))
        SimulationChoice(InputCatalog.all.filter { it.key != "time.elapsed" }.map { it.key to it.title }, input, { input = it; value = ""; invalid = false }, Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it; invalid = false }, label = { Text(stringResource(R.string.pipeline_simulation_value)) }, isError = invalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = if (InputCatalog[input]?.type == ValueType.VECTOR) ({ Text(stringResource(R.string.pipeline_sim_refined_vector_hint)) }) else null)
        if (invalid) Text(stringResource(R.string.pipeline_simulation_invalid_value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                val spec = InputCatalog[input]!!
                val parsed = simulationValue(value, spec)
                if (parsed == null || !updateSimulationInput(simulation, input, parsed)) invalid = true else changed()
            }) { Text(stringResource(R.string.pipeline_simulation_update)) }
            TextButton(enabled = !input.startsWith("time."), onClick = { simulation.inputs[input] = Value.Unavailable(); simulation.dispatch(PipelineEvent("capability.changed")); simulation.advanceBy(0); changed() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text(stringResource(R.string.pipeline_simulation_unavailable)) }
        }
    }
}

@Composable
private fun SimulationToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        GlyphSwitch(checked, onChanged)
    }
}

@Composable
private fun SimulationChoice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(options.find { it.first == selected }?.second ?: selected, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = dialogSurface()) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimulationInspector(simulation: PipelineSimulation) {
    var valuesSelected by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    PipelineCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        PipelineEyebrow(stringResource(R.string.pipeline_sim_refined_inspector))
        GlyphSegmentedRow(if (valuesSelected) 1 else 0, 2, Modifier.fillMaxWidth(), onCard = true) {
            listOf(R.string.pipeline_sim_refined_activity, R.string.pipeline_sim_refined_values).forEachIndexed { index, label ->
                SegmentedButton(selected = valuesSelected == (index == 1), onClick = { valuesSelected = index == 1 }, shape = glyphSegmentedShape(index, 2), icon = {},
                    colors = glyphSegmentedColors(valuesSelected == (index == 1), onCard = true)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (valuesSelected) {
            val names = simulation.document.programs.flatMap { it.variables }.associate { it.id to it.name }
            if (simulation.values.isEmpty()) Text(stringResource(R.string.pipeline_sim_refined_no_values), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            simulation.values.forEach { (id, value) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(names[id] ?: id, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(value.display(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            val traces = simulation.trace.takeLast(12).asReversed()
            if (traces.isEmpty()) Text(stringResource(R.string.pipeline_simulation_no_trace), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            traces.forEachIndexed { index, entry ->
                key(entry.atMillis, entry.blockId, entry.operation, index) {
                    var expanded by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.pipeline_sim_refined_elapsed, entry.atMillis / 1000f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(BlockCatalog[entry.operation]?.title ?: EventCatalog.all.firstOrNull { it.name == entry.operation }?.title ?: entry.operation.replace('.', ' '),
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                        }
                        if (expanded) Text(listOf(entry.operation, entry.detail).filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

internal fun simulationValue(raw: String, spec: InputSpec): Value? = when (spec.type) {
    ValueType.BOOLEAN -> when (raw.trim().lowercase()) { "true", "1" -> boolean(true); "false", "0" -> boolean(false); else -> null }
    ValueType.NUMBER, ValueType.DURATION -> raw.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { Value.Number(it, if (spec.type == ValueType.DURATION) UnitKind.MILLISECONDS else spec.unit) }
    ValueType.TEXT -> Value.Text(raw)
    ValueType.VECTOR -> raw.split(',').map { it.trim().toDoubleOrNull() }.takeIf { it.size == 3 && it.all { number -> number != null && number.isFinite() } }?.let { Value.Vector(it[0]!!, it[1]!!, it[2]!!, spec.unit) }
    ValueType.LIST -> raw.split(',').map { it.trim().toDoubleOrNull() }.takeIf { it.all { number -> number != null && number.isFinite() } }?.let { Value.Items(it.map { number -> Value.Number(number!!) }) }
    else -> null
}

private fun inputEvent(key: String) = when (key.substringBefore('.')) {
    "battery" -> "battery.changed"; "music" -> "music.changed"; "notifications" -> "notification.updated"
    "screen", "device" -> "screen.changed"; "orientation" -> "orientation.changed"; "weather" -> "weather.changed"
    "connection" -> "connection.changed"; else -> "sensor.changed"
}

/** Clock-derived values must alter the clock, not an input entry the VM immediately replaces. */
internal fun updateSimulationInput(simulation: PipelineSimulation, key: String, value: Value): Boolean {
    if (key.startsWith("time.")) {
        val number = (value as? Value.Number)?.value ?: return false
        if (!number.isFinite() || number % 1.0 != 0.0) return false
        val now = Instant.ofEpochMilli(simulation.wallTimeMillis).atZone(ZoneId.systemDefault())
        val updated = when (key) {
            "time.wall" -> number.takeIf { it >= -62_135_596_800_000.0 && it <= 253_402_300_799_999.0 }?.toLong()
            "time.hour" -> number.takeIf { it in 0.0..23.0 }?.let { now.withHour(it.toInt()).toInstant().toEpochMilli() }
            "time.minute" -> number.takeIf { it in 0.0..59.0 }?.let { now.withMinute(it.toInt()).toInstant().toEpochMilli() }
            "time.second" -> number.takeIf { it in 0.0..59.0 }?.let { now.withSecond(it.toInt()).toInstant().toEpochMilli() }
            "time.weekday" -> number.takeIf { it in 1.0..7.0 }?.let { now.with(ChronoField.DAY_OF_WEEK, it.toLong()).toInstant().toEpochMilli() }
            else -> null
        } ?: return false
        simulation.setWallTime(updated)
    } else {
        simulation.inputs[key] = value
        simulation.dispatch(PipelineEvent(inputEvent(key)))
        simulation.advanceBy(0)
    }
    return true
}
