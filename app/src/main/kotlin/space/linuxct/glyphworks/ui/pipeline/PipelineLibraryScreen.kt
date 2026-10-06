package space.linuxct.glyphworks.ui.pipeline

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.pipeline.native.NativeCatalog
import space.linuxct.glyphworks.pipeline.store.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineTutorialActivity
import space.linuxct.pipeline.*

/** Create-tab content. There is deliberately no controller enable toggle here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineLibraryScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 120.dp),
    newRequest: Int = 0,
    onOpen: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    fun uiText(resource: Int, vararg arguments: Any) = resources.getString(resource, *arguments)
    val scope = rememberCoroutineScope()
    val store = Core.pipelineStore
    var projects by remember { mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("Yours") }
    var kindFilter by remember { mutableStateOf<ProgramKind?>(null) }
    var newDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(ProgramKind.TOY) }
    var message by remember { mutableStateOf<String?>(null) }
    var importReview by remember { mutableStateOf<PipelineDocument?>(null) }
    var deleteProject by remember { mutableStateOf<PipelineStore.ProjectSummary?>(null) }
    var renameProject by remember { mutableStateOf<PipelineStore.ProjectSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var historyProject by remember { mutableStateOf<PipelineStore.ProjectSummary?>(null) }
    var revisions by remember { mutableStateOf<List<Int>>(emptyList()) }
    var exportDocument by remember { mutableStateOf<PipelineDocument?>(null) }
    val templates = remember { BuiltinPipelines.all() }
    val starters = remember { BuiltinPipelines.examples() }
    fun open(id: String) { if (onOpen != null) onOpen(id) else context.startActivity(PipelineEditorActivity.intent(context, id)) }
    suspend fun refresh() { projects = withContext(Dispatchers.IO) { store.list() } }
    suspend fun result(result: PipelineStore.SaveResult, openAfter: Boolean = false) {
        when (result) {
            is PipelineStore.SaveResult.Saved -> { refresh(); Core.pipeline.onProjectApplied(result.snapshot.document.id); if (openAfter) open(result.snapshot.document.id) }
            is PipelineStore.SaveResult.Invalid -> message = result.diagnostics.joinToString("\n") { it.message }
            is PipelineStore.SaveResult.Conflict -> message = uiText(R.string.pipeline_library_changed)
            is PipelineStore.SaveResult.Failed -> message = result.message
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(newRequest) { if (newRequest > 0) newDialog = true }
    DisposableEffect(store) {
        val listener: () -> Unit = { scope.launch { refresh() } }
        store.addListener(listener)
        onDispose { store.removeListener(listener) }
    }
    LifecycleResumeEffect(Unit) { scope.launch { refresh() }; onPauseOrDispose {} }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            when (val loaded = withContext(Dispatchers.IO) { PipelineTransfer.read(context, uri) }) {
                is PipelineCodec.Result.Ok -> importReview = loaded.document
                is PipelineCodec.Result.Invalid -> message = loaded.diagnostics.joinToString("\n") { it.message }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PipelineTransfer.MIME)) { uri ->
        val doc = exportDocument
        if (uri != null && doc != null) scope.launch {
            message = if (withContext(Dispatchers.IO) { PipelineTransfer.write(context, uri, doc) }) uiText(R.string.pipeline_library_exported) else uiText(R.string.pipeline_library_export_failed)
            exportDocument = null
        }
    }
    fun share(project: PipelineStore.ProjectSummary, file: Boolean) { scope.launch {
        val source = withContext(Dispatchers.IO) { store.loadApplied(project.id)?.document }
        if (source == null) { message = uiText(R.string.pipeline_library_apply_before_share); return@launch }
        val doc = runCatching { withContext(Dispatchers.IO) { portablePipeline(source, Core.prefs, { Core.ports.design.selected() }, Core.designStore::load) } }.getOrElse { message = it.message ?: uiText(R.string.pipeline_library_artwork_failed); return@launch }
        if (file) { exportDocument = doc; exporter.launch(PipelineTransfer.fileName(doc)) }
        else {
            val uri = withContext(Dispatchers.IO) { PipelineTransfer.shareCopy(context, doc) }
            if (uri == null) message = uiText(R.string.pipeline_library_share_failed)
            else runCatching { context.startActivity(Intent.createChooser(PipelineTransfer.shareIntent(context, uri, doc), uiText(R.string.pipeline_library_share_title))) }.onFailure { message = uiText(R.string.pipeline_library_no_share_app) }
        }
    } }

    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiText(R.string.pipeline_library_heading), style = MaterialTheme.typography.headlineSmall)
                        Text(uiText(R.string.pipeline_library_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { context.startActivity(PipelineTutorialActivity.intent(context)) }) { Icon(Icons.Outlined.School, uiText(R.string.pipeline_library_tutorial)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { newDialog = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(uiText(R.string.pipeline_library_new_button)) }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(uiText(R.string.pipeline_library_import)) }
                }
                OutlinedTextField(search, { search = it }, label = { Text(uiText(R.string.pipeline_library_find)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Yours" to R.string.pipeline_library_yours, "Starters" to R.string.pipeline_library_starters, "Originals" to R.string.pipeline_library_originals).forEach { (key, title) -> FilterChip(section == key, { section = key }, label = { Text(uiText(title)) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kindFilter == null, { kindFilter = null }, label = { Text(uiText(R.string.pipeline_library_all)) })
                    ProgramKind.entries.forEach { kind -> FilterChip(kindFilter == kind, { kindFilter = kind }, label = { Text(roleName(kind)) }) }
                }
            }
        }
        if (section == "Yours") {
            val matching = projects.filter { it.name.contains(search, true) && (kindFilter == null || it.kind == kindFilter) }
            if (matching.isEmpty()) item { Text(if (projects.isEmpty()) uiText(R.string.pipeline_library_empty) else uiText(R.string.pipeline_library_no_match), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium) }
            items(matching, key = { it.id }) { project ->
                var menu by remember(project.id) { mutableStateOf(false) }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { open(project.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccountTree, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(roleName(project.kind), style = MaterialTheme.typography.labelSmall)
                            Text((if (project.hasDraft) uiText(R.string.pipeline_library_draft) else uiText(R.string.pipeline_library_applied, project.appliedRevision ?: 0)) + "  ·  " + project.panels.sorted().joinToString(" / ") { "$it × $it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, uiText(R.string.pipeline_library_actions, project.name)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_open)) }, onClick = { menu = false; open(project.id) })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_rename)) }, onClick = { menu = false; renameText = project.name; renameProject = project })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_duplicate)) }, onClick = { menu = false; scope.launch { result(withContext(Dispatchers.IO) { store.duplicate(project.id, uiText(R.string.pipeline_library_copy_name, project.name)) }, true) } })
                                if (project.kind == ProgramKind.AMBIENT) DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_use_ambient)) }, enabled = project.appliedRevision != null, onClick = { menu = false; Core.pipeline.assignAmbient(project.id); message = uiText(R.string.pipeline_library_ambient_assigned, project.name) })
                                if (project.kind == ProgramKind.CONTROLLER) DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_select_controller)) }, enabled = project.appliedRevision != null, onClick = { menu = false; Core.pipeline.setController(project.id); message = uiText(R.string.pipeline_library_controller_selected) })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_share)) }, enabled = project.appliedRevision != null, onClick = { menu = false; share(project, false) })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_export)) }, enabled = project.appliedRevision != null, onClick = { menu = false; share(project, true) })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_history)) }, enabled = project.appliedRevision != null, onClick = { menu = false; historyProject = project; scope.launch { revisions = withContext(Dispatchers.IO) { store.revisions(project.id) } } })
                                if (project.hasDraft && project.appliedRevision != null) DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_discard)) }, onClick = { menu = false; scope.launch { withContext(Dispatchers.IO) { store.discardDraft(project.id) }; refresh() } })
                                DropdownMenuItem(text = { Text(uiText(R.string.pipeline_library_delete)) }, onClick = { menu = false; deleteProject = project })
                            }
                        }
                    }
                }
            }
        } else {
            val source = if (section == "Originals") templates else starters
            items(source.filter { it.name.contains(search, true) && (kindFilter == null || it.entry()?.kind == kindFilter) }, key = { it.id }) { template ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(template.name, style = MaterialTheme.typography.titleMedium)
                        Text(template.entry()?.description.orEmpty().ifBlank { roleName(template.entry()?.kind ?: ProgramKind.TOY) }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { context.startActivity(PipelineEditorActivity.templateIntent(context, template.id)) }) { Text(uiText(R.string.pipeline_library_view_blocks)) }
                            TextButton(onClick = { scope.launch {
                                val copy = PipelineReferences.remap(template).copy(name = uiText(R.string.pipeline_library_copy_name, template.name), createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}")
                                result(withContext(Dispatchers.IO) { store.saveDraft(copy, 0) }, true)
                            } }) { Text(uiText(R.string.pipeline_library_copy)) }
                        }
                    }
                }
            }
        }
    }
    if (newDialog) PipelineFormDialog(onDismissRequest = { newDialog = false }, title = { Text(uiText(R.string.pipeline_library_new_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(newName, { newName = it }, label = { Text(uiText(R.string.pipeline_library_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceField(uiText(R.string.pipeline_library_build), newKind.name, ProgramKind.entries.map { it.name to roleName(it) }, { newKind = ProgramKind.valueOf(it) })
            Text(when (newKind) { ProgramKind.TOY -> uiText(R.string.pipeline_library_kind_toy); ProgramKind.AMBIENT -> uiText(R.string.pipeline_library_kind_ambient); ProgramKind.CONTROLLER -> uiText(R.string.pipeline_library_kind_controller); ProgramKind.ROUTINE -> uiText(R.string.pipeline_library_kind_routine) }, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { newDialog = false; scope.launch {
        val id = pipelineId(); val now = nowIsoUtc(); val program = Program(name = newName.ifBlank { roleName(newKind) }, kind = newKind, scripts = if (newKind == ProgramKind.ROUTINE) emptyList() else listOf(Script()))
        val doc = PipelineDocument(id = id, name = program.name, createdAt = now, modifiedAt = now, createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}", entryPoint = program.id, programs = listOf(program), routines = if (newKind == ProgramKind.ROUTINE) listOf(Routine(name = program.name)) else emptyList())
        result(withContext(Dispatchers.IO) { store.saveDraft(doc, 0) }, true)
    } }) { Text(uiText(R.string.pipeline_library_create)) } }, dismissButton = { TextButton(onClick = { newDialog = false }) { Text(uiText(R.string.pipeline_library_cancel)) } })
    importReview?.let { incoming -> AlertDialog(onDismissRequest = { importReview = null }, title = { Text(uiText(R.string.pipeline_library_import_title, incoming.name)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.pipeline_library_import_count,
                resources.getQuantityString(R.plurals.pipeline_library_programs, incoming.programs.size, incoming.programs.size),
                resources.getQuantityString(R.plurals.pipeline_library_routines, incoming.routines.size, incoming.routines.size),
                resources.getQuantityString(R.plurals.pipeline_library_designs, incoming.designs.size, incoming.designs.size)))
            if (incoming.requires.isNotEmpty()) Text(uiText(R.string.pipeline_library_import_uses, incoming.requires.joinToString { it.capability }), style = MaterialTheme.typography.bodySmall)
            Text(uiText(R.string.pipeline_library_import_info))
        }
    }, confirmButton = { TextButton(onClick = { importReview = null; scope.launch { result(withContext(Dispatchers.IO) { store.importDocument(incoming) }, true) } }) { Text(uiText(R.string.pipeline_library_import_copy)) } }, dismissButton = { TextButton(onClick = { importReview = null }) { Text(uiText(R.string.pipeline_library_cancel)) } }) }
    renameProject?.let { project -> PipelineFormDialog(onDismissRequest = { renameProject = null }, title = { Text(uiText(R.string.pipeline_library_rename_title)) }, text = { OutlinedTextField(renameText, { renameText = it }, label = { Text(uiText(R.string.pipeline_library_name)) }, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = {
        renameProject = null; scope.launch {
            val snapshot = withContext(Dispatchers.IO) { store.loadDraft(project.id) ?: store.loadApplied(project.id) }
            if (snapshot != null) result(withContext(Dispatchers.IO) { store.saveDraft(snapshot.document.copy(name = renameText.trim()), snapshot.generation) })
        }
    }, enabled = renameText.isNotBlank()) { Text(uiText(R.string.pipeline_library_rename)) } }, dismissButton = { TextButton(onClick = { renameProject = null }) { Text(uiText(R.string.pipeline_library_cancel)) } }) }
    deleteProject?.let { project -> AlertDialog(onDismissRequest = { deleteProject = null }, title = { Text(uiText(R.string.pipeline_library_delete_title, project.name)) }, text = { Text(uiText(R.string.pipeline_library_delete_info)) }, confirmButton = { TextButton(onClick = {
        deleteProject = null; scope.launch { val deleted = withContext(Dispatchers.IO) { Core.pipeline.deleteProject(project.id) }; if (!deleted) message = uiText(R.string.pipeline_library_delete_failed); refresh() }
    }) { Text(uiText(R.string.pipeline_library_delete)) } }, dismissButton = { TextButton(onClick = { deleteProject = null }) { Text(uiText(R.string.pipeline_library_cancel)) } }) }
    historyProject?.let { project -> AlertDialog(onDismissRequest = { historyProject = null }, title = { Text(uiText(R.string.pipeline_library_saved_revisions)) }, text = {
        Column { Text(uiText(R.string.pipeline_library_restore_info), style = MaterialTheme.typography.bodySmall)
            revisions.forEach { revision -> TextButton(onClick = { historyProject = null; scope.launch { result(withContext(Dispatchers.IO) { store.restoreRevision(project.id, revision, store.generation(project.id)) }) } }) { Text(uiText(R.string.pipeline_library_restore_revision, revision)) } }
        }
    }, confirmButton = { TextButton(onClick = { historyProject = null }) { Text(uiText(R.string.pipeline_library_close)) } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text(uiText(R.string.pipeline_library_ok)) } }) }
}

/** Explicit width avoids intrinsic text-field relayout in Material 3's AlertDialog text slot. */
@Composable
private fun PipelineFormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
        Surface(Modifier.width((windowWidth - 48.dp).coerceIn(240.dp, 320.dp)), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                text()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton(); confirmButton() }
            }
        }
    }
}
