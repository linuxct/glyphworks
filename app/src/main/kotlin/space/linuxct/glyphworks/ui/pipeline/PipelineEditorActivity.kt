package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.pipeline.store.*
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.ui.design.EditorScaffold
import space.linuxct.glyphworks.ui.design.EditorState
import space.linuxct.glyphworks.ui.homeCodename
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineTutorialActivity
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineChapter
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.pipeline.*

/** Draft ownership and file operations live here; the canvas and simulator stay pure. */
class PipelineEditorActivity : ComponentActivity() {
    private var document by mutableStateOf<PipelineDocument?>(null)
    private var generation = 0L
    private var savedDocument: PipelineDocument? = null
    private var readOnly by mutableStateOf(false)
    private var status by mutableStateOf("Loading…")
    private var error by mutableStateOf<String?>(null)
    private var conflict by mutableStateOf(false)
    private val writeMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        lifecycleScope.launch { load() }
        setContent {
            GlyphWorksTheme {
                val doc = document
                if (doc == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else {
                    val assetId = intent.getStringExtra(EXTRA_ASSET)
                    if (assetId != null) AssetEditor(doc, assetId)
                    else Editor(doc)
                }
                error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.pipeline_editor_pipeline)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.pipeline_editor_ok)) } }) }
                if (conflict) AlertDialog(
                    onDismissRequest = { conflict = false }, title = { Text(stringResource(R.string.pipeline_editor_this_project_changed_elsewhere)) },
                    text = { Text(stringResource(R.string.pipeline_editor_your_edits_are_still_here_reload_the_newer_draft_or_keep_y)) },
                    confirmButton = { TextButton(onClick = { conflict = false; lifecycleScope.launch { saveCopy() } }) { Text(stringResource(R.string.pipeline_editor_save_a_copy)) } },
                    dismissButton = { Row { TextButton(onClick = { conflict = false; lifecycleScope.launch { load() } }) { Text(stringResource(R.string.pipeline_editor_reload)) }; TextButton(onClick = { conflict = false }) { Text(stringResource(R.string.pipeline_editor_keep_editing)) } } },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush a recent canvas edit before the process can be reclaimed. Artwork has its
        // own production editor flush, which also merges its frames into this document.
        if (!readOnly && intent.getStringExtra(EXTRA_ASSET) == null) lifecycleScope.launch {
            withContext(NonCancellable) { save() }
        }
    }

    private suspend fun load() {
        val example = intent.getStringExtra(EXTRA_EXAMPLE)
        val template = intent.getStringExtra(EXTRA_TEMPLATE)
        if (example != null) {
            document = withContext(Dispatchers.Default) { PipelineBlockReference.example(example) }
            readOnly = true; status = "Runnable example"
        } else if (template != null) {
            document = withContext(Dispatchers.Default) { (BuiltinPipelines.all() + BuiltinPipelines.examples()).firstOrNull { it.id == template || it.entry()?.template == template } }
            readOnly = true; status = "Original template"
        } else {
            val id = intent.getStringExtra(EXTRA_ID).orEmpty()
            val snapshot = withContext(Dispatchers.IO) { Core.pipelineStore.loadDraft(id) ?: Core.pipelineStore.loadApplied(id) }
            document = snapshot?.document; generation = snapshot?.generation ?: 0
            savedDocument = snapshot?.document
            readOnly = false; status = if (snapshot?.revision != null) "Saved · revision ${snapshot.revision}" else "Draft saved"
        }
        if (document == null) { Toast.makeText(this, "This pipeline could not be opened.", Toast.LENGTH_LONG).show(); finish() }
    }

    private suspend fun save(apply: Boolean = false): Boolean = writeMutex.withLock {
        if (readOnly) return@withLock true
        val snapshot = document ?: return@withLock false
        if (!apply && snapshot == savedDocument) return@withLock true
        val normalized = snapshot.copy(createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}", createdAt = snapshot.createdAt.ifBlank(::nowIsoUtc))
        status = if (apply) "Applying…" else "Saving…"
        val result = withContext(Dispatchers.IO) {
            if (apply) Core.pipelineStore.apply(normalized, generation) else Core.pipelineStore.saveDraft(normalized, generation)
        }
        when (result) {
            is PipelineStore.SaveResult.Saved -> {
                generation = result.snapshot.generation; savedDocument = result.snapshot.document
                if (document == snapshot) document = result.snapshot.document
                status = if (apply) "Applied · revision ${result.snapshot.revision}" else "Draft saved"
                if (apply) Core.pipeline.onProjectApplied(snapshot.id)
                true
            }
            is PipelineStore.SaveResult.Conflict -> { conflict = true; status = "Unsaved · conflict"; false }
            is PipelineStore.SaveResult.Invalid -> { error = result.diagnostics.joinToString("\n") { it.message }; status = "Draft needs attention"; false }
            is PipelineStore.SaveResult.Failed -> { error = result.message; status = "Not saved"; false }
        }
    }

    private suspend fun saveCopy() {
        val source = document ?: return
        val copy = PipelineReferences.remap(source).copy(name = "${source.name} copy")
        when (val result = withContext(Dispatchers.IO) { Core.pipelineStore.saveDraft(copy, 0) }) {
            is PipelineStore.SaveResult.Saved -> {
                intent = intent(this, copy.id)
                readOnly = false; document = result.snapshot.document; savedDocument = result.snapshot.document; generation = result.snapshot.generation; status = "Draft saved"
            }
            else -> error = "Could not save the copy. Your edits are unchanged."
        }
    }

    private fun closeEditor() { lifecycleScope.launch { if (save()) finish() } }

    @Composable
    private fun Editor(doc: PipelineDocument) {
        val controller = rememberPipelineEditorController()
        var assetPicker by remember { mutableStateOf(false) }
        var tourOffer by remember { mutableStateOf(false) }
        fun tutorialChapter(): PipelineChapter = when (doc.entry()?.kind) {
            ProgramKind.AMBIENT -> PipelineChapter.AMBIENT
            ProgramKind.CONTROLLER -> PipelineChapter.MENU
            ProgramKind.ROUTINE -> PipelineChapter.REUSE
            else -> PipelineChapter.CANVAS
        }
        LaunchedEffect(doc.id, readOnly) {
            if (!readOnly && !Core.prefs.getBoolean(TOUR_OFFERED, false)) {
                Core.prefs.putBoolean(TOUR_OFFERED, true)
                tourOffer = true
            }
        }
        var importBlocks by remember { mutableStateOf(false) }
        var capabilitiesOpen by remember { mutableStateOf(false) }
        var permissionsRevision by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) { permissionsRevision++; onPauseOrDispose {} }
        val missingSetup = remember(doc.programs, doc.routines, doc.requires, permissionsRevision, capabilitiesOpen) { pipelineMissingSetup(this@PipelineEditorActivity, doc) }
        var panelSize by remember { mutableIntStateOf(13) }
        var previewVersion by remember { mutableIntStateOf(0) }
        var previewDocument by remember(doc.id) { mutableStateOf(doc) }
        val simulation = remember(previewDocument, panelSize, previewVersion) { PipelineSimulation(previewDocument, panelSize) }
        DisposableEffect(simulation) { onDispose { simulation.close() } }
        LaunchedEffect(doc.programs, doc.routines, doc.designs, doc.bindings, controller.previewExpanded) {
            if (controller.previewExpanded) { delay(350); previewDocument = doc }
        }
        LaunchedEffect(doc.id) {
            if (doc.entry()?.kind == ProgramKind.ROUTINE && controller.selectedRoutine == null) controller.selectedRoutine = doc.routines.firstOrNull()?.id
        }
        BackHandler(onBack = ::closeEditor)
        LaunchedEffect(doc, readOnly) { if (!readOnly && doc != savedDocument) { delay(800); withContext(NonCancellable) { save() } } }
        val assetEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch { load() }
        }
        fun openAsset(id: String) { lifecycleScope.launch { if (save()) assetEditor.launch(assetIntent(this@PipelineEditorActivity, document!!.id, id)) } }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (readOnly) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pipeline_editor_original_changes_start_with_a_copy), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { lifecycleScope.launch { saveCopy() } }) { Text(stringResource(R.string.pipeline_editor_make_a_copy)) }
                    }
                }
                if (missingSetup.isNotEmpty()) TextButton(onClick = { capabilitiesOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Setup needed: ${missingSetup.joinToString()}", style = MaterialTheme.typography.labelMedium) }
                PipelineEditor(
                    doc, { document = it; status = "Unsaved draft" }, onSave = { lifecycleScope.launch { save() } },
                    onApply = { lifecycleScope.launch { save(apply = true) } }, onClose = ::closeEditor,
                    controller = controller, onEditAsset = { id -> if (id == null) assetPicker = true else openAsset(id) },
                    onPreview = { previewDocument = it; previewVersion++ },
                    previewContent = {
                        PipelineSimulationPanel(simulation, panelSize = panelSize, onPanelSize = { panelSize = it }, onRestart = { previewDocument = document ?: doc; previewVersion++ }, onExecutionState = { active, waiting -> controller.activeBlockIds = active; controller.waitingBlockIds = waiting })
                    },
                    onImportBlocks = { importBlocks = true }, onCapabilities = { capabilitiesOpen = true },
                    onOpenExample = { op -> startActivity(exampleIntent(this@PipelineEditorActivity, op)) },
                    onTutorial = { startActivity(PipelineTutorialActivity.intent(this@PipelineEditorActivity, tutorialChapter())) }, readOnly = readOnly, statusText = status,
                )
            }
        }
        if (tourOffer) AlertDialog(onDismissRequest = { tourOffer = false }, title = { Text(stringResource(R.string.pipeline_editor_meet_pipeline_builder)) }, text = { Text(stringResource(R.string.pipeline_editor_take_a_guided_tour_of_the_canvas_events_and_reusable_block)) }, confirmButton = { TextButton(onClick = { tourOffer = false; startActivity(PipelineTutorialActivity.intent(this@PipelineEditorActivity, tutorialChapter())) }) { Text(stringResource(R.string.pipeline_editor_start_tutorial)) } }, dismissButton = { TextButton(onClick = { tourOffer = false }) { Text(stringResource(R.string.pipeline_editor_not_now)) } })
        if (capabilitiesOpen) PipelineCapabilitiesSheet(doc) { capabilitiesOpen = false }
        if (importBlocks) PipelineDependencyPicker(doc.id, onDismiss = { importBlocks = false }, onImport = { incoming ->
            document = EditorDocument.merge(document ?: doc, incoming); status = "Unsaved draft"; importBlocks = false
        })
        if (assetPicker) PipelineAssetPicker(onDismiss = { assetPicker = false }, onDesign = { art ->
            assetPicker = false
            val encoded = Json.parseToJsonElement(DesignCodec.encode(art)) as JsonObject
            document = (document ?: doc).copy(designs = (document ?: doc).designs + (art.id to encoded))
            openAsset(art.id)
        })
    }

    @Composable
    private fun AssetEditor(doc: PipelineDocument, id: String) {
        val source = remember(doc.id, id) { doc.designs[id]?.let { (DesignCodec.decode(it.toString()) as? DesignCodec.Result.Ok)?.design } }
        if (source == null) { LaunchedEffect(Unit) { error = "This design is unavailable." }; return }
        val state = remember(doc.id, id) { EditorState(source, openingPanel(source)) }
        EditorScaffold(state, Core.designStore, onClose = { setResult(RESULT_OK); finish() }, onSaveAsset = { art ->
            val latest = document ?: doc
            document = latest.copy(designs = latest.designs + (id to (Json.parseToJsonElement(DesignCodec.encode(art)) as JsonObject)))
            save()
        })
    }

    companion object {
        private const val EXTRA_ID = "pipelineProject"
        private const val EXTRA_EXAMPLE = "pipelineBlockExample"
        private const val TOUR_OFFERED = "pipelineEditorTourOffered"
        private const val EXTRA_TEMPLATE = "pipelineTemplate"
        private const val EXTRA_ASSET = "pipelineAsset"
        fun intent(context: Context, projectId: String) = Intent(context, PipelineEditorActivity::class.java).putExtra(EXTRA_ID, projectId)
        fun exampleIntent(context: Context, op: String) = Intent(context, PipelineEditorActivity::class.java).putExtra(EXTRA_EXAMPLE, op)
        fun templateIntent(context: Context, templateId: String) = Intent(context, PipelineEditorActivity::class.java).putExtra(EXTRA_TEMPLATE, templateId)
        private fun assetIntent(context: Context, projectId: String, assetId: String) = intent(context, projectId).putExtra(EXTRA_ASSET, assetId)
        private fun openingPanel(design: Design): PokemonCodename = homeCodename().takeIf { design.variantFor(it) != null } ?: PokemonCodename.entries.first { design.variantFor(it) != null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineAssetPicker(onDismiss: () -> Unit, onDesign: (Design) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("New artwork") }
    var dynamic by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf<List<Design>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { Core.designStore.list() } }
    fun copy(design: Design) = onDesign(design.copy(id = newDesignId(), modifiedAt = nowIsoUtc()))
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { DesignCodec.decode(it) } }.getOrNull() }
            when (result) { is DesignCodec.Result.Ok -> copy(result.design); is DesignCodec.Result.Invalid -> message = result.reason; else -> message = "Cannot read this design." }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pipeline_editor_add_artwork), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.pipeline_editor_name)) }, modifier = Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.pipeline_editor_animated_design), dynamic) { dynamic = it }
            Button(onClick = {
                val now = nowIsoUtc()
                onDesign(Design(id = newDesignId(), name = name.ifBlank { "Artwork" }, createdAt = now, modifiedAt = now, createdWith = "GlyphWorks ${BuildConfig.VERSION_NAME}", kind = if (dynamic) DesignKind.DYNAMIC else DesignKind.STATIC,
                    variants = PokemonCodename.entries.associate { it.codename to DesignVariant(listOf(DesignFrame(cells = DesignFrames.blank(it)))) }))
            }) { Text(stringResource(R.string.pipeline_editor_draw_new_artwork)) }
            TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.pipeline_editor_import_design_json)) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saved.isNotEmpty()) Text(stringResource(R.string.pipeline_editor_copy_a_saved_design), style = MaterialTheme.typography.titleMedium)
            saved.forEach { design -> TextButton(onClick = { copy(design) }) { Text(design.name) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineDependencyPicker(projectId: String, onDismiss: () -> Unit, onImport: (PipelineDocument) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<PipelineStore.ProjectSummary>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) { projects = withContext(Dispatchers.IO) { Core.pipelineStore.list().filterNot { it.id == projectId } } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { when (val result = withContext(Dispatchers.IO) { PipelineTransfer.read(context, uri) }) {
            is PipelineCodec.Result.Ok -> onImport(result.document)
            is PipelineCodec.Result.Invalid -> message = result.diagnostics.joinToString("\n") { it.message }
        } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pipeline_editor_reuse_a_pipeline), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.pipeline_editor_import_independent_copies_of_its_programs_routines_and_art), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.pipeline_editor_import_pipeline_json)) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            projects.forEach { project -> TextButton(onClick = { scope.launch {
                val source = withContext(Dispatchers.IO) { Core.pipelineStore.loadDraft(project.id) ?: Core.pipelineStore.loadApplied(project.id) }
                source?.document?.let(onImport)
            } }) { Text(project.name) } }
            Text(stringResource(R.string.pipeline_editor_built_in_toys), style = MaterialTheme.typography.titleMedium)
            BuiltinPipelines.all().forEach { template -> TextButton(onClick = { onImport(template) }) { Text(template.name) } }
        }
    }
}
