package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.ui.design.drawMatrix
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget
import space.linuxct.pipeline.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoField

/** Same interpreter, controls and view in the editor and guided tour. No production inputs. */
@OptIn(ExperimentalLayoutApi::class)
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
    var traceExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(simulation, playing, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (playing) { delay(50); simulation.advanceBy(50); generation++ }
        }
    }
    // Reads give Compose an explicit invalidation boundary around a pure non-Compose VM.
    @Suppress("UNUSED_VARIABLE") val redraw = generation + revision
    val reportExecution by rememberUpdatedState(onExecutionState)
    LaunchedEffect(simulation, generation, revision) { reportExecution(simulation.activeBlockIds, simulation.waitingBlockIds) }
    DisposableEffect(simulation) { onDispose { reportExecution(emptySet(), emptySet()) } }
    val frame = simulation.frame.copyOf()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).pipelineDemoTarget("simulation")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(104.dp)) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color(0xFF080808), radius, center)
                drawMatrix(center, radius * .88f, panelSize, frame)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pipeline_simulation), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.pipeline_simulation_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { playing = !playing }) { Text(stringResource(if (playing) R.string.pipeline_simulation_pause else R.string.pipeline_simulation_play)) }
                    TextButton(onClick = { playing = false; simulation.advanceBy(50); generation++ }) { Text(stringResource(R.string.pipeline_simulation_step)) }
                    TextButton(onClick = { simulation.advanceBy(5000); generation++ }) { Text(stringResource(R.string.pipeline_simulation_time)) }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(13, 25).forEach { size -> FilterChip(selected = size == panelSize, onClick = { onPanelSize(size) }, label = { Text(stringResource(R.string.pipeline_simulation_panel, size)) }, modifier = Modifier.padding(end = 8.dp)) }
            TextButton(onClick = onRestart) { Text(stringResource(R.string.pipeline_simulation_restart)) }
            TextButton(onClick = { inputsExpanded = !inputsExpanded }) { Text(stringResource(R.string.pipeline_simulation_inputs)) }
            TextButton(onClick = { traceExpanded = !traceExpanded }) { Text(stringResource(R.string.pipeline_simulation_trace)) }
        }
        if (inputsExpanded) SimulationInputs(simulation) { generation++ }
        if (traceExpanded) {
            simulation.values.forEach { (name, value) -> Text("$name = ${value.display()}", style = MaterialTheme.typography.bodySmall) }
            val traces = simulation.trace.takeLast(12)
            if (traces.isEmpty()) Text(stringResource(R.string.pipeline_simulation_no_trace), style = MaterialTheme.typography.bodySmall)
            traces.forEach { entry -> Text("${entry.atMillis} ms · ${entry.operation} ${entry.detail}", style = MaterialTheme.typography.bodySmall) }
        }
        simulation.diagnostics.takeLast(3).forEach { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimulationChoice(eventChoices, event, { event = it }, Modifier.weight(1f))
        TextButton(onClick = { simulation.dispatch(PipelineEvent(event, values = eventFields.fields, consumable = event.startsWith("key."))); changed() }) { Text(stringResource(R.string.pipeline_simulation_send)) }
    }
    TextButton(onClick = { fieldsExpanded = !fieldsExpanded }) { Text(stringResource(R.string.pipeline_simulation_event_fields)) }
    if (fieldsExpanded) LiteralField(eventFields, ValueType.RECORD, { eventFields = it as Value.Record })
    SimulationChoice(InputCatalog.all.filter { it.key != "time.elapsed" }.map { it.key to it.title }, input, { input = it; value = ""; invalid = false }, Modifier.fillMaxWidth())
    OutlinedTextField(value, { value = it; invalid = false }, label = { Text(stringResource(R.string.pipeline_simulation_value)) }, isError = invalid, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (invalid) Text(stringResource(R.string.pipeline_simulation_invalid_value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    Row {
        TextButton(onClick = {
            val spec = InputCatalog[input]!!
            val parsed = simulationValue(value, spec)
            if (parsed == null || !updateSimulationInput(simulation, input, parsed)) invalid = true else changed()
        }) { Text(stringResource(R.string.pipeline_simulation_update)) }
        TextButton(enabled = !input.startsWith("time."), onClick = { simulation.inputs[input] = Value.Unavailable(); simulation.dispatch(PipelineEvent("capability.changed")); simulation.advanceBy(0); changed() }) { Text(stringResource(R.string.pipeline_simulation_unavailable)) }
    }
}

@Composable
private fun SimulationChoice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(options.find { it.first == selected }?.second ?: selected) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false }) }
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
