package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.dialogSurface
import space.linuxct.glyphworks.ui.theme.glyphCorner
import space.linuxct.glyphworks.ui.theme.lucent
import androidx.compose.material.icons.outlined.*
import java.text.NumberFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.pipeline.runtime.PipelinePrefs
import space.linuxct.glyphworks.pipeline.store.PipelineStore
import space.linuxct.glyphworks.ui.ScreenSettingsDialog
import space.linuxct.glyphworks.ui.rememberPref
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineChapter
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineTutorialActivity
import space.linuxct.pipeline.*

@Composable
internal fun AmbientPipelineSettings() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val revision by rememberPref(PipelinePrefs.LIBRARY_REVISION) { it.getLong(PipelinePrefs.LIBRARY_REVISION, 0) }
    val assigned by rememberPref(PipelinePrefs.AMBIENT_ID) { it.getString(PipelinePrefs.AMBIENT_ID, "") }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<PipelineStore.Snapshot?>(null) }
    var projects by remember { mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList()) }
    var information by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    suspend fun reload() {
        snapshot = withContext(Dispatchers.IO) { Core.pipelineStore.loadApplied(assigned) }
        projects = withContext(Dispatchers.IO) { Core.pipelineStore.list().filter { it.kind == ProgramKind.AMBIENT } }
    }
    LaunchedEffect(revision, assigned, choosing) { reload() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { scope.launch { reload() }; onPauseOrDispose {} }
    fun open(id: String) { context.startActivity(PipelineEditorActivity.intent(context, id)) }
    fun create(copy: Boolean) { scope.launch {
        saving = true
        val source = if (copy) withContext(Dispatchers.IO) { Core.pipelineStore.loadDraft(assigned)?.document ?: Core.pipelineStore.loadApplied(assigned)?.document } else null
        val newDocument = if (source != null) space.linuxct.glyphworks.pipeline.store.PipelineReferences.remap(source).copy(name = "${source.name} copy") else {
            val program = Program(name = "My Ambient", kind = ProgramKind.AMBIENT, scripts = listOf(Script()))
            PipelineDocument(name = "My Ambient", entryPoint = program.id, programs = listOf(program))
        }
        when (val result = withContext(Dispatchers.IO) { Core.pipelineStore.saveDraft(newDocument, 0) }) {
            is PipelineStore.SaveResult.Saved -> open(result.snapshot.document.id)
            is PipelineStore.SaveResult.Failed -> message = result.message
            is PipelineStore.SaveResult.Invalid -> message = result.diagnostics.joinToString("\n") { it.message }
            is PipelineStore.SaveResult.Conflict -> message = "Could not create a new copy. Try again."
        }
        saving = false
    } }
    val doc = snapshot?.document
    val summary = projects.firstOrNull { it.id == assigned }
    val program = doc?.entry()
    val quickSettings = program?.parameters?.filter { it.quickSetting }.orEmpty()
    var edited by remember(doc?.id) { mutableStateOf<Map<String, Value>>(emptyMap()) }
    var editGeneration by remember(doc?.id) { mutableStateOf<Long?>(null) }
    val invalid = quickSettings.any { parameter -> parameterValueError(parameter, edited[parameter.id] ?: program!!.values[parameter.id] ?: parameter.default) != null }
    val ink = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.pipeline_editor_build_your_ambient_display), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pipeline_refine_ambient_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PipelineCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PipelineIconWell(Icons.Outlined.AccountTree)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(doc?.name ?: stringResource(R.string.pipeline_editor_choose_ambient_pipeline), style = MaterialTheme.typography.titleMedium)
                    if (summary != null) Text("Applied · revision ${summary.appliedRevision}" + if (summary.hasDraft) " · unfinished draft" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { manageMenu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.pipeline_refine_manage_pipeline)) }
                    DropdownMenu(manageMenu, { manageMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_choose)) }, onClick = { manageMenu = false; choosing = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_new)) }, enabled = !saving, onClick = { manageMenu = false; create(false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_duplicate)) }, enabled = doc != null && !saving, onClick = { manageMenu = false; create(true) })
                    }
                }
            }
            Button(onClick = { open(assigned) }, enabled = doc != null, modifier = Modifier.fillMaxWidth(), colors = libraryActionColors()) {
                Text(stringResource(R.string.pipeline_editor_open_pipeline_builder)); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
        if (program != null && quickSettings.isNotEmpty()) {
            PipelineEyebrow(stringResource(R.string.pipeline_refine_quick_controls))
            PipelineCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column {
                    quickSettings.forEachIndexed { index, parameter ->
                        val original = program.values[parameter.id] ?: parameter.default
                        QuickSettingRow(parameter, edited[parameter.id] ?: original, enabled = !saving) { value ->
                            if (edited.isEmpty()) editGeneration = snapshot?.generation
                            edited = if (value == original) edited - parameter.id else edited + (parameter.id to value)
                            if (edited.isEmpty()) editGeneration = null
                        }
                        if (index < quickSettings.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = ink.copy(alpha = .07f))
                    }
                }
            }
            if (edited.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pipeline_refine_unsaved_changes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { edited = emptyMap(); editGeneration = null; message = null }, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = ink)) { Text(stringResource(R.string.pipeline_refine_reset)) }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = !invalid && !saving, colors = libraryActionColors(), onClick = { scope.launch {
                            val current = snapshot ?: return@launch
                            saving = true
                            if (current.generation != editGeneration) {
                                message = resources.getString(R.string.pipeline_refine_settings_conflict)
                            } else if (withContext(Dispatchers.IO) { Core.pipelineStore.loadDraft(current.document.id) } != null) {
                                message = resources.getString(R.string.pipeline_refine_settings_draft)
                            } else {
                                val changed = current.document.copy(programs = current.document.programs.map { if (it.id == program.id) it.copy(values = it.values + edited) else it })
                                when (val result = withContext(Dispatchers.IO) { Core.pipelineStore.apply(changed, current.generation) }) {
                                    is PipelineStore.SaveResult.Saved -> { snapshot = result.snapshot; edited = emptyMap(); editGeneration = null; Core.pipeline.onProjectApplied(current.document.id); message = resources.getString(R.string.pipeline_refine_settings_saved) }
                                    is PipelineStore.SaveResult.Conflict -> message = resources.getString(R.string.pipeline_refine_settings_conflict)
                                    is PipelineStore.SaveResult.Invalid -> message = result.diagnostics.joinToString("\n") { it.message }
                                    is PipelineStore.SaveResult.Failed -> message = result.message
                                }
                            }
                            saving = false
                        } }) { Text(stringResource(R.string.pipeline_refine_apply_changes)) }
                    }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        PipelineCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column {
                if (doc != null) PipelineSettingLink(stringResource(R.string.pipeline_refine_sources), Icons.Outlined.VerifiedUser) { permissions = true }
                PipelineSettingLink(stringResource(R.string.pipeline_refine_appearance), Icons.Outlined.Palette) { information = "appearance" }
                PipelineSettingLink(stringResource(R.string.pipeline_refine_help), Icons.Outlined.School) { context.startActivity(PipelineTutorialActivity.intent(context, PipelineChapter.AMBIENT)) }
            }
        }
    }
    if (information == "appearance") AlertDialog(containerColor = dialogSurface(), onDismissRequest = { information = null }, title = { Text(stringResource(R.string.pipeline_refine_appearance)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pipeline_refine_appearance_description))
            listOf("battery" to R.string.pipeline_editor_battery, "notifications" to R.string.pipeline_editor_notifications, "weather" to R.string.pipeline_editor_weather).forEach { (id, label) ->
                PipelineSettingLink(stringResource(label), when (id) { "battery" -> Icons.Outlined.BatteryChargingFull; "weather" -> Icons.Outlined.Cloud; else -> Icons.Outlined.Notifications }) { information = id }
            }
        }
    }, confirmButton = { TextButton(onClick = { information = null }) { Text(stringResource(R.string.pipeline_editor_close)) } })
    information?.takeUnless { it == "appearance" }?.let { ScreenSettingsDialog(it) { information = null } }
    if (permissions && doc != null) PipelineCapabilitiesSheet(doc) { permissions = false }
    if (choosing) AlertDialog(containerColor = dialogSurface(), onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.pipeline_editor_choose_ambient_pipeline)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.pipeline_editor_only_applied_revisions_can_run_unfinished_drafts_stay_in_t))
            projects.forEach { project -> TextButton(enabled = project.appliedRevision != null, onClick = { Core.pipeline.assignAmbient(project.id); choosing = false }) { Text(project.name + if (project.appliedRevision == null) " · draft" else "") } }
            if (projects.isEmpty()) Text(stringResource(R.string.pipeline_editor_create_a_pipeline_apply_it_in_the_builder_then_choose_it_h))
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.pipeline_editor_close)) } })
}

@Composable
internal fun PipelineAdvancedSettings() {
    val context=LocalContext.current
    val resources = LocalResources.current
    val enabled by rememberPref(PipelinePrefs.CONTROLLER_ENABLED){it.getBoolean(PipelinePrefs.CONTROLLER_ENABLED,false)}
    val stopped by rememberPref(PipelinePrefs.STOPPED){it.getBoolean(PipelinePrefs.STOPPED,false)}
    val diagnostic by rememberPref(PipelinePrefs.DIAGNOSTIC){it.getString(PipelinePrefs.DIAGNOSTIC,"")}
    val selected by rememberPref(PipelinePrefs.CONTROLLER_ID){it.getString(PipelinePrefs.CONTROLLER_ID,"")}
    val pocket by rememberPref(PipelinePrefs.POCKET_PROTECTION){it.getBoolean(PipelinePrefs.POCKET_PROTECTION,true)}
    val revision by rememberPref(PipelinePrefs.LIBRARY_REVISION){it.getLong(PipelinePrefs.LIBRARY_REVISION,0)}
    var projects by remember{mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList())}
    LaunchedEffect(revision){projects=withContext(Dispatchers.IO){Core.pipelineStore.list().filter{it.kind==ProgramKind.CONTROLLER&&it.appliedRevision!=null}}}
    var choosing by remember{mutableStateOf(false)}
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val status = when {
            stopped -> stringResource(if (enabled) R.string.pipeline_editor_controller_stopped else R.string.pipeline_editor_standard_pipelines_stopped) + diagnostic.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            diagnostic.isNotBlank() -> diagnostic
            else -> projects.find { it.id == selected }?.name ?: stringResource(R.string.pipeline_editor_choose_apply_controller)
        }
        CustomControlsSettingsContent(enabled, status,
            onEnabled = { if (!it || projects.any { p -> p.id == selected }) Core.pipeline.setControllerEnabled(it) else choosing = true },
            onEdit = { if (selected.isNotBlank()) context.startActivity(PipelineEditorActivity.intent(context, selected)) else choosing = true },
            onStop = { Core.pipeline.stop() }, paused = stopped, onResume = { Core.pipeline.resume() })
        PipelineCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), padding = 0.dp) {
            PipelineSettingLink(stringResource(R.string.pipeline_editor_choose_controller), Icons.Outlined.AccountTree, projects.find { it.id == selected }?.name) { choosing = true }
            if (enabled) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(stringResource(R.string.pipeline_editor_pocket_protection), style = MaterialTheme.typography.bodyLarge); Text(stringResource(R.string.pipeline_editor_pause_output_when_the_proximity_sensor_is_covered), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    GlyphSwitch(pocket, { Core.pipeline.setPocketProtection(it) }, modifier = Modifier.semantics { contentDescription = resources.getString(R.string.pipeline_editor_pocket_protection) })
                }
            }
        }
    }
    if(choosing)AlertDialog(containerColor = dialogSurface(), onDismissRequest={choosing=false},title={Text(stringResource(R.string.pipeline_editor_custom_controls_and_menus))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.pipeline_editor_apply_a_controller_in_the_builder_then_select_it_here_enab))
        projects.forEach {p->TextButton(onClick={Core.pipeline.setController(p.id);choosing=false}){Text(p.name)}}
        if(projects.isEmpty())Text(stringResource(R.string.pipeline_editor_start_with_the_custom_menu_template_in_create_pipelines))
    }},confirmButton={TextButton(onClick={choosing=false}){Text(stringResource(R.string.pipeline_editor_close))}})
}

@Composable
internal fun PipelineToySettings(toyId:String) {
    val context=LocalContext.current
    var chooseTrigger by remember{mutableStateOf(false)}
    var projects by remember{mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList())}
    LaunchedEffect(chooseTrigger){if(chooseTrigger)projects=withContext(Dispatchers.IO){Core.pipelineStore.list().filter{it.appliedRevision!=null&&it.kind!=ProgramKind.CONTROLLER}}}
    Spacer(Modifier.height(12.dp))
    PipelineCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column {
            PipelineSettingLink(stringResource(R.string.pipeline_refine_original), Icons.Outlined.AccountTree, stringResource(R.string.pipeline_refine_original_detail)) { context.startActivity(PipelineEditorActivity.templateIntent(context, "builtin_$toyId")) }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
            PipelineSettingLink(stringResource(R.string.pipeline_editor_activation_rules), Icons.Outlined.Bolt) { chooseTrigger = true }
        }
    }
    if(chooseTrigger)AlertDialog(containerColor = dialogSurface(), onDismissRequest={chooseTrigger=false},title={Text(stringResource(R.string.pipeline_editor_activation_rules))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.pipeline_editor_choose_an_applied_pipeline_to_watch_for_events_while_a_gly))
        TextButton(onClick={Core.pipeline.setTrigger(toyId,null);chooseTrigger=false}){Text(stringResource(R.string.pipeline_editor_no_additional_rules))}
        projects.forEach {p->TextButton(onClick={Core.pipeline.setTrigger(toyId,p.id);chooseTrigger=false}){Text(p.name)}}
        if(projects.isEmpty())Text(stringResource(R.string.pipeline_editor_create_a_pipeline_or_copy_an_event_template_in_create_pipe))
    }},confirmButton={TextButton(onClick={chooseTrigger=false}){Text(stringResource(R.string.pipeline_editor_close))}})
}


@Composable
internal fun PipelineSettingLink(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingRow(parameter: Parameter, value: Value, enabled: Boolean, onChange: (Value) -> Unit) {
    var editing by remember(parameter.id) { mutableStateOf(false) }
    val isBoolean = parameter.type == ValueType.BOOLEAN
    Row(Modifier.fillMaxWidth().testTag("pipeline-quick-setting:${parameter.id}").then(if (isBoolean) Modifier.toggleable(value.boolean(), enabled = enabled, role = Role.Switch) { onChange(boolean(it)) } else Modifier.clickable(enabled = enabled) { editing = true }).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(parameter.name, style = MaterialTheme.typography.bodyLarge)
            if (parameter.description.isNotBlank()) Text(parameter.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isBoolean) GlyphSwitch(value.boolean(), null, enabled = enabled)
        else {
            Text(if (parameter.type == ValueType.DURATION) durationLabel(value.number()) else value.display().take(32), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.widthIn(max = 120.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.pipeline_refine_edit_value), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(value) }
        val fieldValidation = remember { PipelineFieldValidation() }
        var seconds by remember { mutableStateOf(formatSettingNumber(value.number() / 1000.0)) }
        val parsedSeconds = seconds.replace(',', '.').toDoubleOrNull()
        val candidate = if (parameter.type == ValueType.DURATION && parsedSeconds != null && parsedSeconds.isFinite()) duration((parsedSeconds * 1000).toLong()) else draft
        val invalid = if (parameter.type == ValueType.DURATION && (parsedSeconds == null || !parsedSeconds.isFinite())) stringResource(R.string.pipeline_refine_enter_number) else parameterValueError(parameter, candidate)
        PipelineFormDialog(onDismissRequest = { editing = false }, title = { Text(parameter.name) }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (parameter.type == ValueType.DURATION) {
                    OutlinedTextField(seconds, { seconds = it }, label = { Text(stringResource(R.string.pipeline_refine_seconds)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5L, 15L, 30L, 60L).filter { parameterValueError(parameter, duration(it * 1000)) == null }.forEach { preset -> SuggestionChip(onClick = { seconds = preset.toString() }, label = { Text(durationLabel(preset * 1000.0)) }) }
                    }
                } else CompositionLocalProvider(LocalPipelineFieldValidation provides fieldValidation) {
                    ParameterValueField(parameter, draft) { draft = it }
                }
                invalid?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = invalid == null && !fieldValidation.hasErrors, onClick = { onChange(candidate); editing = false }) { Text(stringResource(R.string.pipeline_refine_done)) } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.pipeline_library_cancel)) } })
    }
}

private fun formatSettingNumber(value: Double): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3; isGroupingUsed = false }.format(value)

@Composable
private fun durationLabel(milliseconds: Double): String = if (milliseconds >= 60_000 && milliseconds % 60_000 == 0.0) stringResource(R.string.pipeline_refine_minutes_short, formatSettingNumber(milliseconds / 60_000)) else stringResource(R.string.pipeline_refine_seconds_short, formatSettingNumber(milliseconds / 1000))
