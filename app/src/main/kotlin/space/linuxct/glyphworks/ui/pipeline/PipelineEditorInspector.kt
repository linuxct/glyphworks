package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonPrimitive
import space.linuxct.pipeline.*
import space.linuxct.glyphworks.pipeline.native.NativeCatalog
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget

@Composable
internal fun PipelineEditorInspector(
    document: PipelineDocument,
    program: Program?,
    routine: Routine?,
    controller: PipelineEditorController,
    readOnly: Boolean,
    onChange: (PipelineDocument) -> Unit,
    onEditAsset: (String?) -> Unit,
    onImportBlocks: () -> Unit,
    onOpenExample: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    fun update(next: PipelineDocument) { if (!readOnly) onChange(next) }
    fun editProgram(next: Program) = update(document.copy(programs = document.programs.map { if (it.id == next.id) next else it }))
    Column(modifier.pipelineDemoTarget("inspector"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (controller.panel) {
                EditorPanel.BLOCK -> "Block settings"; EditorPanel.SCRIPT -> "Event settings"; EditorPanel.VARIABLES -> "Variables";
                EditorPanel.PARAMETERS -> "Parameters"; EditorPanel.ROUTINES -> "Reusable routines"; EditorPanel.ASSETS -> "Artwork";
                EditorPanel.REFERENCE -> "Block reference"; EditorPanel.SEARCH -> "Find in canvas"; EditorPanel.ISSUES -> "Pipeline checks"; else -> "Project settings"
            }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { controller.panel = EditorPanel.NONE }) { Icon(Icons.Outlined.Close, stringResource(R.string.pipeline_editor_close_settings)) }
        }
        if (readOnly) Text(stringResource(R.string.pipeline_editor_original_template_make_a_copy_to_change_it), style = MaterialTheme.typography.bodySmall)
        when (controller.panel) {
            EditorPanel.BLOCK -> controller.selectedBlock?.let { EditorDocument.block(document, it) }?.let { block ->
                val spec = BlockCatalog[block.op]
                Text(spec?.title ?: block.op, style = MaterialTheme.typography.titleMedium)
                spec?.let { Text(PipelineBlockReference.purpose(it), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { controller.referenceOp = block.op; controller.panel = EditorPanel.REFERENCE }) { Text(stringResource(R.string.pipeline_editor_reference_and_runnable_example)) }
                val owner = EditorDocument.location(document, block.id)?.ownerId
                val blockRoutine = document.routines.firstOrNull { it.id == owner } ?: routine
                val blockProgram = document.programs.firstOrNull { p -> p.scripts.any { it.id == owner } } ?: program
                val arguments = spec?.arguments.orEmpty()
                arguments.forEach { arg ->
                    val value = block.arguments[arg.name] ?: arg.default
                    fun change(expression: Expression) { update(EditorDocument.update(document, block.copy(arguments = block.arguments + (arg.name to expression)))) }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(arg.label, style = MaterialTheme.typography.titleSmall)
                            val choices: List<Pair<String, String>>? = when (arg.reference) {
                                ReferenceKind.ROUTINE -> document.routines.filterNot { it.id == blockRoutine?.id }.map { it.id to it.name }
                                ReferenceKind.PROGRAM -> document.programs.filterNot { it.id == blockProgram?.id }.map { it.id to it.name }
                                ReferenceKind.ASSET -> document.designs.map { (id, json) -> id to (json["name"]?.jsonPrimitive?.content ?: id) }
                                ReferenceKind.BINDING -> document.bindings.keys.map { it to it }
                                ReferenceKind.VARIABLE -> (blockProgram?.variables.orEmpty() + blockRoutine?.variables.orEmpty()).filter { !block.op.startsWith("list.") || it.type == ValueType.LIST }.map { it.id to it.name }
                                null -> if (arg.choices.isNotEmpty()) arg.choices.map { it to it.replace('_', ' ') } else when {
                                    block.op == "display.toy" && arg.name == "toy" -> NativeCatalog.behaviors.map { it.id to it.title }
                                    block.op == "native.command" && arg.name == "command" -> NativeCatalog.behaviors.flatMap { it.commands }.distinct().map { it to it }
                                    arg.name == "event" -> EventCatalog.all.map { it.name to it.title }
                                    arg.name == "resultVariable" -> listOf("" to "Don't store result") + (blockProgram?.variables.orEmpty() + blockRoutine?.variables.orEmpty()).map { it.id to it.name }
                                    else -> null
                                }
                            }
                            var expressionMode by remember(block.id, arg.name) { mutableStateOf(value.op != "literal") }
                            if (block.op == "display.toy" && arg.name == "parameters" && value.op == "literal" && !expressionMode) {
                                NativeSettingsField(block.arguments["toy"]?.value?.text().orEmpty(), (value.value as? Value.Record)?.fields.orEmpty(), readOnly) { change(Expression.literal(Value.Record(it))) }
                                TextButton(onClick = { expressionMode = true }) { Text(stringResource(R.string.pipeline_editor_use_an_expression)) }
                            } else if (block.op == "native.command" && arg.name == "arguments" && value.op == "literal" && !expressionMode) {
                                NativeCommandSettingsField(block, document, (value.value as? Value.Record)?.fields.orEmpty()) { change(Expression.literal(Value.Record(it))) }
                                TextButton(onClick = { expressionMode = true }) { Text(stringResource(R.string.pipeline_editor_use_an_expression)) }
                            } else if (choices != null && !expressionMode) {
                                ChoiceField(arg.label, value.value.text(), choices, { change(Expression.str(it)) })
                                if (arg.reference == ReferenceKind.ASSET) TextButton(onClick = { onEditAsset(value.value.text().takeIf { it in document.designs }) }, enabled = !readOnly) { Text(if (value.value.text() in document.designs) "Edit design" else "Draw a design") }
                                if (arg.reference == null) TextButton(onClick = { expressionMode = true }) { Text(stringResource(R.string.pipeline_editor_use_an_expression)) }
                            } else {
                                ExpressionField(value, arg.type, document, blockProgram, blockRoutine, ::change)
                                if (choices != null) TextButton(onClick = { expressionMode = false; change(arg.default) }) { Text(stringResource(R.string.pipeline_editor_choose_from_list)) }
                            }
                        }
                    }
                }
                if (block.op == "display.toy") NativeCatalog.get(block.arguments["toy"]?.value?.text().orEmpty())?.let { behavior ->
                    Text(stringResource(R.string.pipeline_editor_artwork_slots), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pipeline_editor_replace_the_drawing_while_keeping_the_behavior_configure_c), style = MaterialTheme.typography.bodySmall)
                    behavior.slots.forEach { slot ->
                        ChoiceField(slot.replace('_', ' ').replaceFirstChar(Char::titlecase), block.arguments["binding:$slot"]?.value?.text().orEmpty(), listOf("" to "Original artwork") + document.bindings.keys.map { it to it }, { id ->
                            val args = if (id.isBlank()) block.arguments - "binding:$slot" else block.arguments + ("binding:$slot" to Expression.str(id))
                            update(EditorDocument.update(document, block.copy(arguments = args)))
                        })
                    }
                    TextButton(onClick = { controller.panel = EditorPanel.ASSETS }) { Text(stringResource(R.string.pipeline_editor_manage_artwork)) }
                }
                val referencedParams = when (block.op) {
                    "routine.call" -> document.routines.firstOrNull { it.id == block.arguments["routine"]?.value?.text() }?.parameters
                    "program.run" -> document.programs.firstOrNull { it.id == block.arguments["program"]?.value?.text() }?.parameters
                    else -> null
                }.orEmpty()
                referencedParams.forEach { parameter ->
                    Text(parameter.name, style = MaterialTheme.typography.titleSmall)
                    ExpressionField(block.arguments["arg:${parameter.id}"] ?: Expression.literal(parameter.default), parameter.type, document, blockProgram, blockRoutine, {
                        update(EditorDocument.update(document, block.copy(arguments = block.arguments + ("arg:${parameter.id}" to it))))
                    })
                }
                // Imported extensions are visible and editable without dropping their data on save.
                block.arguments.filterKeys { key -> arguments.none { it.name == key } && !key.startsWith("arg:") && !key.startsWith("binding:") }.forEach { (key, value) ->
                    Text(key, style = MaterialTheme.typography.titleSmall)
                    ExpressionField(value, ValueType.ANY, document, blockProgram, blockRoutine, { update(EditorDocument.update(document, block.copy(arguments = block.arguments + (key to it)))) })
                }
                OutlinedTextField(block.comment, { update(EditorDocument.update(document, block.copy(comment = it))) }, label = { Text(stringResource(R.string.pipeline_editor_comment)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                SwitchRow(stringResource(R.string.pipeline_editor_enabled), block.enabled, !readOnly) { update(EditorDocument.update(document, block.copy(enabled = it))) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.movingBlock = block.id; controller.panel = EditorPanel.NONE }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_move_to)) }
                    TextButton(onClick = { update(EditorDocument.duplicate(document, block.id)); controller.panel = EditorPanel.NONE }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_duplicate)) }
                }
            }
            EditorPanel.SCRIPT -> document.programs.flatMap { it.scripts }.firstOrNull { it.id == controller.selectedScript }?.let { script ->
                fun change(next: Script) = update(EditorDocument.script(document, next))
                OutlinedTextField(script.name, { change(script.copy(name = it)) }, label = { Text(stringResource(R.string.pipeline_editor_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                ChoiceField(stringResource(R.string.pipeline_editor_when), script.trigger.event, EventCatalog.all.map { it.name to it.title }, { event ->
                    val title = EventCatalog.all.firstOrNull { it.name == event }?.title ?: event
                    change(script.copy(name = title, trigger = script.trigger.copy(event = event, calendar = if (event == "calendar.daily") script.trigger.calendar ?: CalendarTrigger() else null)))
                })
                OutlinedTextField(script.trigger.event, { change(script.copy(trigger = script.trigger.copy(event = it))) }, label = { Text(stringResource(R.string.pipeline_editor_event_name)) }, supportingText = { Text(stringResource(R.string.pipeline_editor_you_can_also_use_a_named_signal_timer_or_native_event)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                if (script.trigger.event == "calendar.daily") {
                    val schedule = script.trigger.calendar ?: CalendarTrigger()
                    fun updateSchedule(next: CalendarTrigger) = change(script.copy(trigger = script.trigger.copy(calendar = next)))
                    Text(stringResource(R.string.pipeline_editor_local_time), style = MaterialTheme.typography.titleSmall)
                    NumberField(stringResource(R.string.pipeline_editor_hour_0_23), schedule.hour.toDouble(), { updateSchedule(schedule.copy(hour = it.toInt().coerceIn(0, 23))) }, true)
                    NumberField(stringResource(R.string.pipeline_editor_minute_0_59), schedule.minute.toDouble(), { updateSchedule(schedule.copy(minute = it.toInt().coerceIn(0, 59))) }, true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, name ->
                            val day = index + 1
                            FilterChip(day in schedule.weekdays, {
                                val weekdays = if (day in schedule.weekdays) schedule.weekdays - day else (schedule.weekdays + day).sorted()
                                if (weekdays.isNotEmpty()) updateSchedule(schedule.copy(weekdays = weekdays))
                            }, label = { Text(name) }, enabled = !readOnly)
                        }
                    }
                    Text(stringResource(R.string.pipeline_editor_runs_once_at_this_local_time_on_each_chosen_day_while_the), style = MaterialTheme.typography.bodySmall)
                }
                SwitchRow(stringResource(R.string.pipeline_editor_only_when_a_condition_matches), script.trigger.condition != null, !readOnly) { change(script.copy(trigger = script.trigger.copy(condition = if (it) Expression.bool(true) else null))) }
                script.trigger.condition?.let { condition ->
                    ExpressionField(condition, ValueType.BOOLEAN, document, program, routine, { change(script.copy(trigger = script.trigger.copy(condition = it))) })
                    ChoiceField(stringResource(R.string.pipeline_editor_condition_activation), script.trigger.edge.name, TriggerEdge.entries.map { it.name to when (it) { TriggerEdge.EVENT -> "Every matching event"; TriggerEdge.RISING -> "When it becomes true"; TriggerEdge.FALLING -> "When it becomes false"; TriggerEdge.CHANGE -> "Whenever it changes" } }, { change(script.copy(trigger = script.trigger.copy(edge = TriggerEdge.valueOf(it)))) })
                    SwitchRow(stringResource(R.string.pipeline_editor_run_if_true_when_the_pipeline_starts), script.trigger.initially, !readOnly) { change(script.copy(trigger = script.trigger.copy(initially = it))) }
                    NumberField(stringResource(R.string.pipeline_editor_stable_for_milliseconds), script.trigger.stableForMs.toDouble(), { change(script.copy(trigger = script.trigger.copy(stableForMs = it.toLong().coerceAtLeast(0)))) }, integer = true)
                }
                ChoiceField(stringResource(R.string.pipeline_editor_if_triggered_while_already_running), script.reentry.name, Reentry.entries.map { it.name to when (it) { Reentry.RESTART -> "Restart this script"; Reentry.IGNORE -> "Ignore the new event"; Reentry.QUEUE -> "Run it after the current event"; Reentry.PARALLEL -> "Run another instance" } }, { change(script.copy(reentry = Reentry.valueOf(it))) })
                NumberField(stringResource(R.string.pipeline_editor_priority), script.priority.toDouble(), { change(script.copy(priority = it.toInt())) }, integer = true)
                SwitchRow(stringResource(R.string.pipeline_editor_enabled), script.enabled, !readOnly) { change(script.copy(enabled = it)) }
                TextButton(onClick = { update(EditorDocument.removeScript(document, script.id)); controller.panel = EditorPanel.NONE }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_delete_event_and_its_blocks)) }
            }
            EditorPanel.VARIABLES -> VariablesPanel(routine?.variables ?: program?.variables.orEmpty(), readOnly) { variables ->
                if (routine != null) update(EditorDocument.routine(document, routine.copy(variables = variables))) else if (program != null) editProgram(program.copy(variables = variables))
            }
            EditorPanel.PARAMETERS -> ParametersPanel(routine?.parameters ?: program?.parameters.orEmpty(), readOnly) { parameters ->
                if (routine != null) update(EditorDocument.routine(document, routine.copy(parameters = parameters))) else if (program != null) editProgram(program.copy(parameters = parameters))
            }
            EditorPanel.ROUTINES -> {
                OutlinedButton(onClick = onImportBlocks, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_import_routines_or_programs)) }
                Text(stringResource(R.string.pipeline_editor_name_a_sequence_once_and_call_it_wherever_you_need_it_argu), style = MaterialTheme.typography.bodyMedium)
                document.routines.forEach { item ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(item.name, { update(EditorDocument.routine(document, item.copy(name = it))) }, label = { Text(stringResource(R.string.pipeline_editor_routine_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                            Text("${item.blocks.size} top-level blocks · ${item.parameters.size} arguments", style = MaterialTheme.typography.bodySmall)
                            ChoiceField(stringResource(R.string.pipeline_editor_return_type), item.returns.name, ValueType.entries.map { it.name to typeName(it) }, { update(EditorDocument.routine(document, item.copy(returns = ValueType.valueOf(it)))) })
                            Row {
                                TextButton(onClick = { controller.selectedRoutine = item.id; controller.panel = EditorPanel.NONE }) { Text(stringResource(R.string.pipeline_editor_open_blocks)) }
                                TextButton(onClick = { controller.selectedRoutine = item.id; controller.panel = EditorPanel.PARAMETERS }) { Text(stringResource(R.string.pipeline_editor_arguments)) }
                                TextButton(onClick = { controller.selectedRoutine = item.id; controller.panel = EditorPanel.VARIABLES }) { Text(stringResource(R.string.pipeline_editor_locals)) }
                            }
                            val calls = document.programs.flatMap { it.scripts }.sumOf { countRoutineCalls(it.blocks, item.id) } + document.routines.sumOf { countRoutineCalls(it.blocks, item.id) } + document.bindings.values.count { it.routineId == item.id }
                            TextButton(onClick = { update(document.copy(routines = document.routines.filterNot { it.id == item.id })); if (controller.selectedRoutine == item.id) controller.selectedRoutine = null }, enabled = !readOnly && calls == 0) { Text(if (calls == 0) "Delete routine" else "Used by $calls call(s)") }
                        }
                    }
                }
                Button(onClick = {
                    val added = Routine(name = "My routine ${document.routines.size + 1}")
                    update(document.copy(routines = document.routines + added)); controller.selectedRoutine = added.id; controller.panel = EditorPanel.NONE
                }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_new_routine)) }
            }
            EditorPanel.ASSETS -> ArtworkPanel(document, readOnly, ::update, onEditAsset)
            EditorPanel.REFERENCE -> {
                var query by remember { mutableStateOf("") }
                OutlinedTextField(query, { query = it; controller.referenceOp = null }, label = { Text(stringResource(R.string.pipeline_editor_search_blocks_purpose_or_category)) }, modifier = Modifier.fillMaxWidth())
                val selected = controller.referenceOp?.let { BlockCatalog[it] }
                if (selected != null) {
                    TextButton(onClick = { controller.referenceOp = null }) { Text(stringResource(R.string.pipeline_editor_all_blocks)) }
                    Text(selected.title, style = MaterialTheme.typography.titleMedium)
                    Text(PipelineBlockReference.purpose(selected), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pipeline_editor_scope), style = MaterialTheme.typography.titleSmall)
                    Text(PipelineBlockReference.scope(selected), style = MaterialTheme.typography.bodySmall)
                    if (selected.arguments.isNotEmpty()) Text(stringResource(R.string.pipeline_editor_inputs), style = MaterialTheme.typography.titleSmall)
                    selected.arguments.forEach { argument -> Text("${argument.label} · ${typeName(argument.type)}" + if (argument.type == ValueType.DURATION) " · milliseconds" else "", style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.pipeline_editor_the_example_opens_in_a_separate_read_only_sandbox_simulate), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { onOpenExample(selected.op) }) { Text(stringResource(R.string.pipeline_editor_open_runnable_example)) }
                } else BlockCatalog.all.filter { (it.title + " " + it.category + " " + PipelineBlockReference.purpose(it)).contains(query, true) }.forEach { spec ->
                    TextButton(onClick = { controller.referenceOp = spec.op }) { Text(spec.title + " · " + spec.category) }
                }
            }
            EditorPanel.SEARCH -> {
                var search by remember { mutableStateOf("") }
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.pipeline_editor_block_name_value_or_comment)) }, modifier = Modifier.fillMaxWidth())
                if (search.isNotBlank()) allEditorBlocks(document).filter { block -> (BlockCatalog[block.op]?.title.orEmpty() + " " + block.comment + " " + block.arguments.values.joinToString { it.summary() }).contains(search, true) }.take(100).forEach { block ->
                    TextButton(onClick = { controller.focusRequest = block.id; controller.panel = EditorPanel.NONE }) { Text(BlockCatalog[block.op]?.title ?: block.op) }
                }
            }
            EditorPanel.ISSUES -> {
                val issues = remember(document) { PipelineCodec.validate(document) }
                if (issues.isEmpty()) Text(stringResource(R.string.pipeline_editor_the_pipeline_is_ready_to_apply_missing_device_capabilities), style = MaterialTheme.typography.bodyMedium)
                issues.forEach { issue ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(issue.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (issue.blockId != null) TextButton(onClick = { controller.focusRequest = issue.blockId; controller.panel = EditorPanel.NONE }) { Text(stringResource(R.string.pipeline_editor_show_block)) }
                        }
                    }
                }
                Text(stringResource(R.string.pipeline_editor_you_can_keep_saving_a_draft_while_you_fix_incomplete_block), style = MaterialTheme.typography.bodySmall)
            }
            EditorPanel.PROJECT -> {
                OutlinedTextField(document.name, { update(document.copy(name = it)) }, label = { Text(stringResource(R.string.pipeline_editor_project_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                OutlinedTextField(document.author, { update(document.copy(author = it)) }, label = { Text(stringResource(R.string.pipeline_editor_author)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                if (program != null) {
                    OutlinedTextField(program.name, { editProgram(program.copy(name = it)) }, label = { Text(stringResource(R.string.pipeline_editor_program_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    OutlinedTextField(program.description, { editProgram(program.copy(description = it)) }, label = { Text(stringResource(R.string.pipeline_editor_description)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    Text(roleName(program.kind), style = MaterialTheme.typography.titleSmall)
                    SwitchRow(stringResource(R.string.pipeline_editor_respond_immediately_to_key_down), program.immediateAction, !readOnly) { editProgram(program.copy(immediateAction = it)) }
                    Text(stringResource(R.string.pipeline_editor_use_for_time_sensitive_interactions_such_as_jumping_the_ma), style = MaterialTheme.typography.bodySmall)
                }
                ChoiceField(stringResource(R.string.pipeline_editor_edit_program), program?.id.orEmpty(), document.programs.map { it.id to it.name }, { controller.programId = it; controller.selectedRoutine = null; controller.panel = EditorPanel.NONE })
                if (program != null && program.id != document.entryPoint) {
                    val used = space.linuxct.glyphworks.pipeline.store.PipelineReferences.usages(document, program.id).isNotEmpty()
                    TextButton(enabled = !readOnly && !used, onClick = { update(document.copy(programs = document.programs.filterNot { it.id == program.id })); controller.programId = null }) { Text(if (used) "Program is referenced by blocks" else "Remove unused program") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val child = Program(name = "Subprogram ${document.programs.size + 1}", scripts = listOf(Script()))
                        update(document.copy(programs = document.programs + child)); controller.programId = child.id; controller.selectedRoutine = null; controller.panel = EditorPanel.NONE
                    }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_add_program)) }
                    TextButton(onClick = onImportBlocks, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_import_blocks)) }
                }
                ChoiceField(stringResource(R.string.pipeline_editor_entry_program), document.entryPoint, document.programs.map { it.id to it.name }, { update(document.copy(entryPoint = it)) })
                Text(stringResource(R.string.pipeline_editor_panel_variants), style = MaterialTheme.typography.titleSmall)
                for (size in listOf(13, 25)) SwitchRow(if (size == 13) "Phone (4a) Pro · 13 × 13" else "Phone (3) · 25 × 25", size in document.panels, !readOnly && (document.panels.size > 1 || size !in document.panels)) { selected -> update(document.copy(panels = if (selected) document.panels + size else document.panels - size)) }
                HorizontalDivider()
                Text(stringResource(R.string.pipeline_editor_preview_and_thumbnail), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.pipeline_editor_preview_settings_explanation), style = MaterialTheme.typography.bodySmall)
                ChoiceField(stringResource(R.string.pipeline_editor_card_thumbnail), document.preview.thumbnailAssetId.orEmpty(), listOf("" to stringResource(R.string.pipeline_editor_use_simulated_frame)) + document.designs.map { (id, design) -> id to (design["name"]?.jsonPrimitive?.content ?: id) }, { id ->
                    update(document.copy(preview = document.preview.copy(thumbnailAssetId = id.takeIf { it.isNotEmpty() }, thumbnailFrame = 0)))
                })
                document.preview.thumbnailAssetId?.let { assetId ->
                    val design = remember(document.designs[assetId]) { document.designs[assetId]?.toString()?.let { (DesignCodec.decode(it) as? DesignCodec.Result.Ok)?.design } }
                    val lastFrame = document.panels.minOfOrNull { panel -> (design?.variantForSize(panel)?.frames?.size ?: 0) - 1 }?.coerceAtLeast(0) ?: 0
                    NumberField(stringResource(R.string.pipeline_editor_thumbnail_frame), (document.preview.thumbnailFrame + 1).toDouble(), { frame -> update(document.copy(preview = document.preview.copy(thumbnailFrame = (frame.toInt() - 1).coerceIn(0, lastFrame)))) }, true)
                }
                NumberField(stringResource(R.string.pipeline_editor_preview_elapsed_ms), document.preview.elapsedMs.toDouble(), { elapsed -> update(document.copy(preview = document.preview.copy(elapsedMs = elapsed.toLong().coerceIn(0L, 60_000L)))) }, true)
                SwitchRow(stringResource(R.string.pipeline_editor_preview_initial_action), document.preview.initialAction, !readOnly) { update(document.copy(preview = document.preview.copy(initialAction = it))) }
                Text(stringResource(R.string.pipeline_editor_blocks_remain_a_draft_until_apply_saving_never_replaces_yo), style = MaterialTheme.typography.bodySmall)
            }
            else -> Unit
        }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChecked, enabled = enabled)
    }
}

@Composable
private fun VariablesPanel(variables: List<Variable>, readOnly: Boolean, onChange: (List<Variable>) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.pipeline_editor_store_numbers_conditions_text_and_lists_persistent_values), style = MaterialTheme.typography.bodyMedium)
    variables.forEach { variable ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { selected = if (selected == variable.id) null else variable.id }, verticalAlignment = Alignment.CenterVertically) {
                    Text(variable.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(typeName(variable.type), style = MaterialTheme.typography.labelSmall)
                }
                if (selected == variable.id) {
                    fun update(next: Variable) { if (!readOnly) onChange(variables.map { if (it.id == next.id) next else it }) }
                    OutlinedTextField(variable.name, { update(variable.copy(name = it)) }, label = { Text(stringResource(R.string.pipeline_editor_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    ChoiceField(stringResource(R.string.pipeline_editor_type), variable.type.name, ValueType.entries.map { it.name to typeName(it) }, { val type = ValueType.valueOf(it); update(variable.copy(type = type, initial = defaultValue(type))) })
                    Text(stringResource(R.string.pipeline_editor_initial_value), style = MaterialTheme.typography.labelMedium)
                    LiteralField(variable.initial, variable.type, { update(variable.copy(initial = it)) })
                    SwitchRow(stringResource(R.string.pipeline_editor_remember_between_sessions), variable.persistent, !readOnly) { update(variable.copy(persistent = it)) }
                    TextButton(onClick = { if (!readOnly) onChange(variables.filterNot { it.id == variable.id }) }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_delete_variable)) }
                }
            }
        }
    }
    Button(onClick = { val variable = Variable(pipelineId(), "Variable ${variables.size + 1}"); onChange(variables + variable); selected = variable.id }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_new_variable)) }
}

@Composable
private fun ParametersPanel(parameters: List<Parameter>, readOnly: Boolean, onChange: (List<Parameter>) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.pipeline_editor_parameters_are_the_inputs_to_this_program_or_routine_expos), style = MaterialTheme.typography.bodyMedium)
    parameters.forEach { parameter ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { selected = if (selected == parameter.id) null else parameter.id }) { Text(parameter.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(typeName(parameter.type), style = MaterialTheme.typography.labelSmall) }
                if (selected == parameter.id) {
                    fun update(next: Parameter) { if (!readOnly) onChange(parameters.map { if (it.id == next.id) next else it }) }
                    OutlinedTextField(parameter.name, { update(parameter.copy(name = it)) }, label = { Text(stringResource(R.string.pipeline_editor_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    OutlinedTextField(parameter.description, { update(parameter.copy(description = it)) }, label = { Text(stringResource(R.string.pipeline_editor_description)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    ChoiceField(stringResource(R.string.pipeline_editor_type), parameter.type.name, ValueType.entries.map { it.name to typeName(it) }, { val type = ValueType.valueOf(it); update(parameter.copy(type = type, default = defaultValue(type))) })
                    Text(stringResource(R.string.pipeline_editor_default), style = MaterialTheme.typography.labelMedium)
                    LiteralField(parameter.default, parameter.type, { update(parameter.copy(default = it)) })
                    SwitchRow(stringResource(R.string.pipeline_editor_show_in_toy_quick_settings), parameter.quickSetting, !readOnly) { update(parameter.copy(quickSetting = it)) }
                    if (parameter.type in listOf(ValueType.NUMBER, ValueType.DURATION)) {
                        SwitchRow(stringResource(R.string.pipeline_editor_limit_range), parameter.minimum != null || parameter.maximum != null, !readOnly) { update(parameter.copy(minimum = if (it) 0.0 else null, maximum = if (it) 100.0 else null)) }
                        parameter.minimum?.let { value -> NumberField(stringResource(R.string.pipeline_editor_minimum), value, { update(parameter.copy(minimum = it)) }) }
                        parameter.maximum?.let { value -> NumberField(stringResource(R.string.pipeline_editor_maximum), value, { update(parameter.copy(maximum = it)) }) }
                    }
                    if (parameter.type == ValueType.TEXT) {
                        OutlinedTextField(parameter.choices.joinToString("\n") { it.text() }, { entered -> update(parameter.copy(choices = entered.lines().filter(String::isNotBlank).map(::text))) }, label = { Text(stringResource(R.string.pipeline_editor_choices_one_per_line_optional)) }, modifier = Modifier.fillMaxWidth(), enabled = !readOnly)
                    }
                    TextButton(onClick = { onChange(parameters.filterNot { it.id == parameter.id }) }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_delete_parameter)) }
                }
            }
        }
    }
    Button(onClick = { val parameter = Parameter(pipelineId(), "Parameter ${parameters.size + 1}"); onChange(parameters + parameter); selected = parameter.id }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_new_parameter)) }
}

@Composable
private fun ArtworkPanel(document: PipelineDocument, readOnly: Boolean, onChange: (PipelineDocument) -> Unit, onEditAsset: (String?) -> Unit) {
    Text(stringResource(R.string.pipeline_editor_designs_travel_with_the_pipeline_bind_a_design_to_a_behavi), style = MaterialTheme.typography.bodyMedium)
    document.designs.forEach { (id, json) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(json["name"]?.jsonPrimitive?.content ?: id, style = MaterialTheme.typography.titleSmall); Text(stringResource(R.string.pipeline_editor_design_asset), style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { onEditAsset(id) }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_edit)) }
            val used = document.bindings.values.any { it.assetId == id } || space.linuxct.glyphworks.pipeline.store.PipelineReferences.usages(document, id).isNotEmpty()
            if (!used) IconButton(onClick = { onChange(document.copy(designs = document.designs - id)) }, enabled = !readOnly) { Icon(Icons.Outlined.Delete, stringResource(R.string.pipeline_editor_remove_unused_design)) }
        }
    }
    Button(onClick = { onEditAsset(null) }, enabled = !readOnly) { Text(stringResource(R.string.pipeline_editor_draw_or_import_design)) }
    HorizontalDivider()
    var selected by remember { mutableStateOf<String?>(null) }
    var slotName by remember { mutableStateOf("") }
    Text(stringResource(R.string.pipeline_editor_behavior_artwork_slots), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pipeline_editor_for_example_dino_jump_dino_run_dice_face_1_coin_heads_or_w), style = MaterialTheme.typography.bodySmall)
    document.bindings.forEach { (key, binding) ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(key, Modifier.fillMaxWidth().clickable { selected = if (selected == key) null else key }, style = MaterialTheme.typography.titleSmall)
                if (selected == key) {
                    fun change(next: AssetBinding) { if (!readOnly) onChange(document.copy(bindings = document.bindings + (key to next))) }
                    ChoiceField(stringResource(R.string.pipeline_editor_source), if (binding.routineId != null) "routine" else "design", listOf("design" to "Design or animation", "routine" to "Drawing routine"), { source ->
                        change(if (source == "routine") binding.copy(assetId = "", routineId = document.routines.firstOrNull()?.id ?: "") else binding.copy(assetId = document.designs.keys.firstOrNull().orEmpty(), routineId = null))
                    })
                    if (binding.routineId != null) {
                        val checked = document.routines.associateWith { DrawingRoutineRenderer.validate(document, it.id) }
                        ChoiceField(stringResource(R.string.pipeline_editor_drawing_routine), binding.routineId.orEmpty(), checked.filterValues { it.isEmpty() }.keys.map { it.id to it.name }, { change(binding.copy(routineId = it)) })
                        checked.filterValues { it.isNotEmpty() }.forEach { (routine, issues) -> Text("${routine.name}: ${issues.first().message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text(stringResource(R.string.pipeline_editor_draw_a_frame_immediately_using_scene_sprite_variable_list), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.pipeline_editor_the_behavior_supplies_phase_progress_elapsedms_phasedurati), style = MaterialTheme.typography.bodySmall)
                        if (document.routines.isEmpty()) Text(stringResource(R.string.pipeline_editor_create_a_routine_in_routines_then_choose_it_here), style = MaterialTheme.typography.bodySmall)
                    } else ChoiceField(stringResource(R.string.pipeline_editor_design), binding.assetId, document.designs.map { (id, design) -> id to (design["name"]?.jsonPrimitive?.content ?: id) }, { change(binding.copy(assetId = it)) })
                    ChoiceField(stringResource(R.string.pipeline_editor_playback), binding.playback.name, AnimationFit.entries.map { it.name to when (it) { AnimationFit.NATURAL -> "Original frame timing"; AnimationFit.STRETCH_TO_PHASE -> "Fit to behavior phase"; AnimationFit.LOOP -> "Loop while active"; AnimationFit.HOLD_LAST -> "Play once, hold last frame" } }, { change(binding.copy(playback = AnimationFit.valueOf(it))) })
                    SwitchRow(stringResource(R.string.pipeline_editor_zero_brightness_is_transparent), binding.transparentZero, !readOnly) { change(binding.copy(transparentZero = it)) }
                    for (panel in document.panels.sorted()) {
                        val variantKey = PokemonCodename.ofSize(panel)!!.codename
                        val geometry = binding.variants[variantKey] ?: SpriteGeometry()
                        var expanded by remember(key, panel) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text("$panel × $panel · crop, anchor and collision") }
                        if (expanded) {
                            fun geometry(next: SpriteGeometry) = change(binding.copy(variants = binding.variants + (variantKey to next)))
                            GeometryPreview(panel, geometry, document.designs[binding.assetId]?.toString())
                            NumberField(stringResource(R.string.pipeline_editor_crop_column), geometry.x.toDouble(), { geometry(geometry.copy(x = it.toInt())) }, true)
                            NumberField(stringResource(R.string.pipeline_editor_crop_row), geometry.y.toDouble(), { geometry(geometry.copy(y = it.toInt())) }, true)
                            NumberField(stringResource(R.string.pipeline_editor_crop_width_0_full), geometry.width.toDouble(), { geometry(geometry.copy(width = it.toInt().coerceAtLeast(0))) }, true)
                            NumberField(stringResource(R.string.pipeline_editor_crop_height_0_full), geometry.height.toDouble(), { geometry(geometry.copy(height = it.toInt().coerceAtLeast(0))) }, true)
                            NumberField(stringResource(R.string.pipeline_editor_anchor_x), geometry.anchorX, { geometry(geometry.copy(anchorX = it)) })
                            NumberField(stringResource(R.string.pipeline_editor_anchor_y), geometry.anchorY, { geometry(geometry.copy(anchorY = it)) })
                            NumberField(stringResource(R.string.pipeline_editor_collision_x), geometry.hitX, { geometry(geometry.copy(hitX = it)) })
                            NumberField(stringResource(R.string.pipeline_editor_collision_y), geometry.hitY, { geometry(geometry.copy(hitY = it)) })
                            NumberField(stringResource(R.string.pipeline_editor_collision_width_0_original), geometry.hitWidth, { geometry(geometry.copy(hitWidth = it.coerceAtLeast(0.0))) })
                            NumberField(stringResource(R.string.pipeline_editor_collision_height_0_original), geometry.hitHeight, { geometry(geometry.copy(hitHeight = it.coerceAtLeast(0.0))) })
                        }
                    }
                    val used = space.linuxct.glyphworks.pipeline.store.PipelineReferences.usages(document, key).isNotEmpty()
                    TextButton(onClick = { onChange(document.copy(bindings = document.bindings - key)) }, enabled = !readOnly && !used) { Text(if (used) "Used by behavior blocks" else "Remove unused binding") }
                }
            }
        }
    }
    OutlinedTextField(slotName, { slotName = it }, label = { Text(stringResource(R.string.pipeline_editor_new_slot_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !readOnly)
    TextButton(onClick = { val key = slotName.trim(); onChange(document.copy(bindings = document.bindings + (key to AssetBinding(document.designs.keys.firstOrNull().orEmpty())))); selected = key; slotName = "" }, enabled = !readOnly && slotName.isNotBlank() && slotName.trim() !in document.bindings) { Text(stringResource(R.string.pipeline_editor_bind_artwork)) }
}

@Composable
private fun GeometryPreview(panel: Int, geometry: SpriteGeometry, assetJson: String?) {
    val frame = remember(assetJson, panel) {
        val design = assetJson?.let { (DesignCodec.decode(it) as? DesignCodec.Result.Ok)?.design }
        design?.variantForSize(panel)?.frames?.firstOrNull()?.let { DesignFrames.decode(it.cells, design.levels, panel) }
    }
    val ink = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF16191C))) {
        val cell = size.minDimension / panel
        val left = (size.width - cell * panel) / 2
        frame?.forEachIndexed { index, level -> if (level > 0) {
            drawRect(Color.White.copy(alpha = (level / 4095f).coerceIn(0f, 1f)), Offset(left + (index % panel) * cell + cell * .1f, (index / panel) * cell + cell * .1f), androidx.compose.ui.geometry.Size(cell * .8f, cell * .8f))
        } }
        for (x in 0..panel) drawLine(ink.copy(alpha = .15f), Offset(left + x * cell, 0f), Offset(left + x * cell, cell * panel))
        for (y in 0..panel) drawLine(ink.copy(alpha = .15f), Offset(left, y * cell), Offset(left + cell * panel, y * cell))
        drawRect(ink, Offset(left + geometry.x * cell, geometry.y * cell), androidx.compose.ui.geometry.Size((geometry.width.takeIf { it > 0 } ?: panel) * cell, (geometry.height.takeIf { it > 0 } ?: panel) * cell), style = Stroke(2.dp.toPx()))
        if (geometry.hitWidth > 0 && geometry.hitHeight > 0) drawRect(outline, Offset(left + geometry.hitX.toFloat() * cell, geometry.hitY.toFloat() * cell), androidx.compose.ui.geometry.Size(geometry.hitWidth.toFloat() * cell, geometry.hitHeight.toFloat() * cell), style = Stroke(2.dp.toPx()))
        val anchor = Offset(left + geometry.anchorX.toFloat() * cell, geometry.anchorY.toFloat() * cell)
        drawCircle(Color.Red, cell * .35f, anchor)
    }
    Text(stringResource(R.string.pipeline_editor_outline_crop_second_outline_collision_dot_anchor), style = MaterialTheme.typography.labelSmall)
}

private fun countRoutineCalls(blocks: List<Block>, id: String): Int = blocks.sumOf { block ->
    (if (block.op == "routine.call" && block.arguments["routine"]?.value?.text() == id) 1 else 0) + countRoutineCalls(block.body, id) + countRoutineCalls(block.otherwise, id)
}

@Composable
private fun NativeSettingsField(type: String, fields: Map<String, Value>, readOnly: Boolean, onChange: (Map<String, Value>) -> Unit) {
    val behavior = NativeCatalog.get(type)
    if (behavior == null) { Text(stringResource(R.string.pipeline_editor_choose_a_behavior_first), style = MaterialTheme.typography.bodySmall); return }
    Text(stringResource(R.string.pipeline_editor_use_the_individual_toy_settings_or_override_a_value_for_th), style = MaterialTheme.typography.bodySmall)
    behavior.parameters.forEach { parameter ->
        SwitchRow("Override ${parameter.name.lowercase()}", parameter.id in fields, !readOnly) { enabled ->
            onChange(if (enabled) fields + (parameter.id to parameter.default) else fields - parameter.id)
        }
        fields[parameter.id]?.let { value ->
            if (parameter.choices.isNotEmpty()) ChoiceField(parameter.name, value.display(), parameter.choices.map { it.display() to it.display() }, { chosen ->
                onChange(fields + (parameter.id to parameter.choices.first { it.display() == chosen }))
            }) else LiteralField(value, parameter.type, { next ->
                val validated = if (next is Value.Number) next.copy(value = next.value.coerceIn(parameter.minimum ?: -Double.MAX_VALUE, parameter.maximum ?: Double.MAX_VALUE)) else next
                onChange(fields + (parameter.id to validated))
            })
        }
    }
    if (behavior.parameters.isEmpty()) Text(stringResource(R.string.pipeline_editor_this_behavior_has_no_additional_settings), style = MaterialTheme.typography.bodySmall)
}

internal fun allEditorBlocks(document: PipelineDocument): List<Block> {
    fun flatten(blocks: List<Block>): List<Block> = blocks.flatMap { listOf(it) + flatten(it.body) + flatten(it.otherwise) }
    return document.programs.flatMap { p -> p.scripts.flatMap { flatten(it.blocks) } } + document.routines.flatMap { flatten(it.blocks) }
}

@Composable
private fun NativeCommandSettingsField(block: Block, document: PipelineDocument, fields: Map<String, Value>, onChange: (Map<String, Value>) -> Unit) {
    val command = block.arguments["command"]?.value?.text() ?: "action"
    val slot = block.arguments["slot"]?.value?.text() ?: "main"
    val nativeType = allEditorBlocks(document).firstOrNull { it.op == "display.toy" && (it.arguments["slot"]?.value?.text() ?: "main") == slot }?.arguments?.get("toy")?.value?.text()
        ?: NativeCatalog.behaviors.firstOrNull { command in it.commands }?.id.orEmpty()
    val parameters = NativeCatalog.commandParameters(nativeType, command)
    if (parameters.isEmpty()) LiteralField(Value.Record(fields), ValueType.RECORD, { onChange((it as Value.Record).fields) })
    parameters.forEach { parameter ->
        Text(parameter.name, style = MaterialTheme.typography.labelMedium)
        LiteralField(fields[parameter.id] ?: parameter.default, parameter.type, { onChange(fields + (parameter.id to it)) })
    }
}
