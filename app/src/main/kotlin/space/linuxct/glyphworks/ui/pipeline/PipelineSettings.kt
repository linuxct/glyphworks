package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    Text(stringResource(R.string.pipeline_editor_build_your_ambient_display), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pipeline_editor_arrange_backgrounds_and_define_what_happens_when_music_pla), style = MaterialTheme.typography.bodyMedium)
    Text(doc?.name ?: "Choose an Ambient pipeline", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (summary != null) Text("Applied · revision ${summary.appliedRevision}" + if (summary.hasDraft) " · unfinished draft" else "", style = MaterialTheme.typography.bodySmall)
    FilledTonalButton(onClick = { open(assigned) }, enabled = doc != null, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Icon(Icons.Outlined.AccountTree, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pipeline_editor_open_pipeline_builder))
    }
    Row { TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.pipeline_editor_choose)) }; TextButton(onClick = { create(false) }, enabled = !saving) { Text(stringResource(R.string.pipeline_editor_new)) }; TextButton(onClick = { create(true) }, enabled = doc != null && !saving) { Text(stringResource(R.string.pipeline_editor_duplicate)) } }
    if (doc != null) {
        val program = doc.entry()
        program?.parameters?.filter { it.quickSetting }?.forEach { parameter ->
            val value = program.values[parameter.id] ?: parameter.default
            var edited by remember(doc.id, snapshot?.generation, parameter.id) { mutableStateOf(value) }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(parameter.name, style = MaterialTheme.typography.titleSmall)
                    if (parameter.description.isNotBlank()) Text(parameter.description, style = MaterialTheme.typography.bodySmall)
                    ParameterValueField(parameter, edited) { edited = it }
                    val invalid = parameterValueError(parameter, edited)
                    invalid?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = invalid == null && !saving && edited != value, onClick = { scope.launch {
                        val current = snapshot ?: return@launch
                        saving = true
                        if (withContext(Dispatchers.IO) { Core.pipelineStore.loadDraft(current.document.id) } != null) {
                            message = "This pipeline has an unfinished draft. Open the builder to keep those edits before changing its settings."
                        } else {
                            val changed = current.document.copy(programs = current.document.programs.map { if (it.id == program.id) it.copy(values = it.values + (parameter.id to edited)) else it })
                            when (val result = withContext(Dispatchers.IO) { Core.pipelineStore.apply(changed, current.generation) }) {
                                is PipelineStore.SaveResult.Saved -> { snapshot = result.snapshot; Core.pipeline.onProjectApplied(doc.id); message = null }
                                is PipelineStore.SaveResult.Conflict -> message = "This pipeline has newer edits. Open the builder to review them."
                                is PipelineStore.SaveResult.Invalid -> message = result.diagnostics.joinToString("\n") { it.message }
                                is PipelineStore.SaveResult.Failed -> message = result.message
                            }
                        }
                        saving = false
                    } }) { Text(stringResource(R.string.pipeline_editor_apply_setting)) }
                }
            }
        }
        TextButton(onClick = { permissions = true }) { Text(stringResource(R.string.pipeline_editor_permissions_and_sources)) }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text(stringResource(R.string.pipeline_editor_appearance_comes_from_each_toy_s_settings_unless_you_overr), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    Row { TextButton(onClick = { information = "battery" }) { Text(stringResource(R.string.pipeline_editor_battery)) }; TextButton(onClick = { information = "notifications" }) { Text(stringResource(R.string.pipeline_editor_notifications)) }; TextButton(onClick = { information = "weather" }) { Text(stringResource(R.string.pipeline_editor_weather)) } }
    TextButton(onClick = { context.startActivity(PipelineTutorialActivity.intent(context, PipelineChapter.AMBIENT)) }) { Text(stringResource(R.string.pipeline_editor_learn_to_build_an_ambient_pipeline)) }
    information?.let { ScreenSettingsDialog(it) { information = null } }
    if (permissions && doc != null) PipelineCapabilitiesSheet(doc) { permissions = false }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.pipeline_editor_choose_ambient_pipeline)) }, text = {
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
    val enabled by rememberPref(PipelinePrefs.CONTROLLER_ENABLED){it.getBoolean(PipelinePrefs.CONTROLLER_ENABLED,false)}
    val stopped by rememberPref(PipelinePrefs.STOPPED){it.getBoolean(PipelinePrefs.STOPPED,false)}
    val diagnostic by rememberPref(PipelinePrefs.DIAGNOSTIC){it.getString(PipelinePrefs.DIAGNOSTIC,"")}
    val selected by rememberPref(PipelinePrefs.CONTROLLER_ID){it.getString(PipelinePrefs.CONTROLLER_ID,"")}
    val pocket by rememberPref(PipelinePrefs.POCKET_PROTECTION){it.getBoolean(PipelinePrefs.POCKET_PROTECTION,true)}
    val revision by rememberPref(PipelinePrefs.LIBRARY_REVISION){it.getLong(PipelinePrefs.LIBRARY_REVISION,0)}
    var projects by remember{mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList())}
    LaunchedEffect(revision){projects=withContext(Dispatchers.IO){Core.pipelineStore.list().filter{it.kind==ProgramKind.CONTROLLER&&it.appliedRevision!=null}}}
    var choosing by remember{mutableStateOf(false)}
    Column {
        val status = when {
            stopped -> stringResource(if (enabled) R.string.pipeline_editor_controller_stopped else R.string.pipeline_editor_standard_pipelines_stopped) + diagnostic.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            diagnostic.isNotBlank() -> diagnostic
            else -> projects.find { it.id == selected }?.name ?: stringResource(R.string.pipeline_editor_choose_apply_controller)
        }
        CustomControlsSettingsContent(enabled,status,
            onEnabled={if(!it||projects.any{p->p.id==selected})Core.pipeline.setControllerEnabled(it)else choosing=true},
            onEdit={if(selected.isNotBlank())context.startActivity(PipelineEditorActivity.intent(context,selected))else choosing=true},onStop={Core.pipeline.stop()})
        Row(Modifier.padding(horizontal=20.dp)){TextButton(onClick={choosing=true}){Text(stringResource(R.string.pipeline_editor_choose_controller))};if(stopped)TextButton(onClick={Core.pipeline.resume()}){Text(stringResource(R.string.pipeline_editor_resume_pipelines))}else if(!enabled)TextButton(onClick={Core.pipeline.stop()}){Text(stringResource(R.string.pipeline_editor_stop_pipelines))}}
        if(enabled)Row(Modifier.padding(horizontal=20.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(stringResource(R.string.pipeline_editor_pocket_protection));Text(stringResource(R.string.pipeline_editor_pause_output_when_the_proximity_sensor_is_covered),style=MaterialTheme.typography.bodySmall)};Switch(pocket,{Core.pipeline.setPocketProtection(it)})}
    }
    if(choosing)AlertDialog(onDismissRequest={choosing=false},title={Text(stringResource(R.string.pipeline_editor_custom_controls_and_menus))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
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
    HorizontalDivider(Modifier.padding(vertical=12.dp))
    TextButton(onClick={context.startActivity(PipelineEditorActivity.templateIntent(context,"builtin_$toyId"))}){Text(stringResource(R.string.pipeline_editor_view_original_pipeline_make_a_copy))}
    TextButton(onClick={chooseTrigger=true}){Text(stringResource(R.string.pipeline_editor_activation_rules))}
    if(chooseTrigger)AlertDialog(onDismissRequest={chooseTrigger=false},title={Text(stringResource(R.string.pipeline_editor_activation_rules))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.pipeline_editor_choose_an_applied_pipeline_to_watch_for_events_while_a_gly))
        TextButton(onClick={Core.pipeline.setTrigger(toyId,null);chooseTrigger=false}){Text(stringResource(R.string.pipeline_editor_no_additional_rules))}
        projects.forEach {p->TextButton(onClick={Core.pipeline.setTrigger(toyId,p.id);chooseTrigger=false}){Text(p.name)}}
        if(projects.isEmpty())Text(stringResource(R.string.pipeline_editor_create_a_pipeline_or_copy_an_event_template_in_create_pipe))
    }},confirmButton={TextButton(onClick={chooseTrigger=false}){Text(stringResource(R.string.pipeline_editor_close))}})
}
