package space.linuxct.glyphworks.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.screens.CustomScreen
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.DesignEditorActivity
import space.linuxct.glyphworks.ui.design.LocalDemoTargets
import space.linuxct.glyphworks.ui.design.demoTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun CreateTab(
    innerPadding: PaddingValues,
    @Suppress("UNUSED_PARAMETER") listState: LazyListState,
    state: CreateState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Core.designStore }

    LaunchedEffect(state) { state.loadIfNeeded(store) }

    val resumes = remember { intArrayOf(0) }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(state) {
        if (resumes[0]++ > 0) scope.launch { state.refresh(store) }
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { pruneSharedCache(shareCacheDir(context), System.currentTimeMillis()) }
    }

    val designs = state.designs
    val saveFailed = stringResource(R.string.create_save_failed)
    val shareFailed = stringResource(R.string.create_shared_failed)
    val unnamed = stringResource(R.string.pref_custom_unnamed)
    val exportedTemplate = stringResource(R.string.create_exported)
    val exportFailed = stringResource(R.string.create_export_failed)
    val importedTemplate = stringResource(R.string.create_imported)
    val shareChooserTitle = stringResource(R.string.create_share_chooser)
    val showMessage = showOnMatrixMessage(afterEditor = false)

    var importError by rememberSaveable { mutableStateOf<String?>(null) }

    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DESIGN_MIME),
    ) { uri: Uri? ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    val design = store.load(id) ?: return@withContext null
                    if (writeDesign(context, uri, design)) design.name else null
                }
                val message = if (name != null) {
                    exportedTemplate.format(name.ifBlank { unnamed })
                } else {
                    exportFailed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                when (val outcome = state.import(context, store, uri)) {
                    is ImportOutcome.Ok -> {
                        Toast.makeText(
                            context,
                            importedTemplate.format(outcome.design.name.ifBlank { unnamed }),
                            Toast.LENGTH_SHORT,
                        ).show()
                        state.gridState.animateScrollToItem(0)
                    }
                    is ImportOutcome.Failed -> importError = outcome.reason
                }
            }
        }
    }

    val onImport = { importLauncher.launch(arrayOf(DESIGN_MIME)) }

    val clock = remember { PreviewClock() }

    var onScreen by remember { mutableStateOf(false) }

    LaunchedEffect(clock) {
        snapshotFlow { resumed && onScreen && clock.animating }.collectLatest { run ->
            if (!run) return@collectLatest
            while (true) {
                withFrameMillis { clock.advance(it) }
            }
        }
    }

    val columns = designGridColumns(LocalWindowInfo.current.containerDpSize.width)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onScreen = !it.boundsInWindow().isEmpty },
        state = state.gridState,
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE,
        ),
        verticalArrangement = Arrangement.spacedBy(DESIGN_GRID_GUTTER),
    ) {
        when {
            designs == null -> Unit

            designs.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                CreateEmptyState(
                    onStart = { state.newDesignRequested = true },
                    onImport = onImport,
                )
            }

            else -> {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        HintText(stringResource(R.string.create_hint))
                        ImportButton(onImport)
                    }
                }
                itemsIndexed(designs, key = { _, design -> design.id }) { index, design ->
                    val copyName =
                        stringResource(R.string.create_copy_suffix, design.name.ifBlank { unnamed })
                    val art = remember(design.id, design.modifiedAt) {
                        designPreviewArt(design, PokemonCodename.ofSize(Core.glyphLink.size))
                    }
                    val player = rememberPreviewPlayer(art, clock)
                    DesignCard(
                        design = design,
                        art = art,
                        player = player,
                        onRename = { state.pendingRename = design },
                        onOpen = { context.startActivity(DesignEditorActivity.intent(context, design.id)) },
                        onShow = {
                            Toast.makeText(context, showMessage(showDesignOnMatrix(design)), Toast.LENGTH_SHORT)
                                .show()
                        },
                        onDuplicate = {
                            scope.launch {
                                val ok = state.duplicate(store, design, copyName)
                                if (!ok) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { state.pendingDelete = design },
                        onExport = {
                            pendingExportId = design.id
                            exportLauncher.launch(designFileName(design))
                        },
                        onShare = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { writeShareCopy(context, design) }
                                val shared = uri != null &&
                                    startShare(context, uri, design, shareChooserTitle)
                                if (!shared) {
                                    Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        placement = Modifier
                            .animateItem(
                                fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            )
                            .padding(designCellPadding(index % columns, columns)),
                        discSideInset = designDiscSideInset(index % columns, columns),
                    )
                }
            }
        }
    }

    if (state.newDesignRequested) {
        NewDesignDialog(
            suggestedName = remember(designs) {
                generateDesignName(designs.orEmpty().mapTo(HashSet()) { it.name })
            },
            defaultTarget = remember { homeCodename() },
            onDismiss = { state.newDesignRequested = false },
            onCreate = { name, kind, targets ->
                state.newDesignRequested = false
                scope.launch {
                    val ok = state.create(context, store, name, kind, targets)
                    if (ok) {
                        state.gridState.animateScrollToItem(0)
                    } else {
                        Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    state.pendingRename?.let { design ->
        RenameDesignDialog(
            design = design,
            onDismiss = { state.pendingRename = null },
            onRename = { newName ->
                state.pendingRename = null
                scope.launch {
                    val ok = state.rename(store, design.id, newName)
                    if (!ok) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    importError?.let { reason ->
        ImportFailedDialog(reason) { importError = null }
    }

    state.pendingDelete?.let { design ->
        DeleteDesignDialog(
            design = design,
            onDismiss = { state.pendingDelete = null },
            onConfirm = {
                state.pendingDelete = null
                scope.launch { state.delete(store, design) }
            },
        )
    }

    CreateTourOffer(state)
}

@Composable
private fun CreateTourOffer(state: CreateState) {
    val context = LocalContext.current
    val inDemo = LocalDemoTargets.current != null
    var offering by rememberSaveable { mutableStateOf(false) }
    var declined by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.visited, inDemo) {
        val prompted = Core.prefs.getBoolean(
            PrefKeys.CREATE_TOUR_PROMPTED,
            PrefKeys.CREATE_TOUR_PROMPTED_DEF,
        )
        if (!shouldOfferCreateTour(visited = state.visited, prompted = prompted, inDemo = inDemo)) {
            return@LaunchedEffect
        }
        Core.prefs.putBoolean(PrefKeys.CREATE_TOUR_PROMPTED, true)
        offering = true
    }

    if (offering) {
        MotionDialog(onDismiss = { offering = false }) { dismiss ->
            TourOfferCard(
                title = stringResource(R.string.create_tour_title),
                body = stringResource(R.string.create_tour_body),
                confirmLabel = stringResource(R.string.create_tour_watch),
                onConfirm = {
                    offering = false
                    context.startActivity(DesignDemoActivity.intent(context))
                },
                dismissLabel = stringResource(R.string.create_tour_skip),
                onDismiss = {
                    declined = true
                    dismiss()
                },
            )
        }
    } else if (declined) {
        MotionDialog(onDismiss = { declined = false }) { dismiss ->
            TourOfferCard(
                title = stringResource(R.string.create_tour_later_title),
                body = stringResource(R.string.create_tour_later_body),
                confirmLabel = null,
                onConfirm = {},
                dismissLabel = stringResource(R.string.create_tour_later_dismiss),
                onDismiss = dismiss,
            )
        }
    }
}

internal fun shouldOfferCreateTour(visited: Boolean, prompted: Boolean, inDemo: Boolean): Boolean =
    visited && !prompted && !inDemo

@Composable
private fun TourOfferCard(
    title: String,
    body: String,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                if (confirmLabel != null) {
                    TextButton(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

internal fun homeCodename(): PokemonCodename =
    PokemonCodename.ofSize(Core.glyphLink.size) ?: PokemonCodename.BELLSPROUT

internal fun designTargetOptions(): List<Set<PokemonCodename>> =
    PokemonCodename.entries.map { setOf(it) } + listOf(PokemonCodename.entries.toSet())

internal fun seedVariants(
    targets: Set<PokemonCodename>,
    home: PokemonCodename,
): Map<String, DesignVariant> {
    val chosen = targets.ifEmpty { setOf(home) }
    return PokemonCodename.entries.filter { it in chosen }.associate { codename ->
        codename.codename to if (codename == home) {
            DesignVariant(frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))))
        } else {
            DesignVariant()
        }
    }
}

internal class CreateState {

    var designs by mutableStateOf<List<Design>?>(null)
        private set

    val gridState = LazyGridState()

    var newDesignRequested by mutableStateOf(false)

    var visited by mutableStateOf(false)

    var pendingDelete by mutableStateOf<Design?>(null)

    var pendingRename by mutableStateOf<Design?>(null)

    suspend fun loadIfNeeded(store: DesignStore) {
        if (designs == null) reload(store)
    }

    suspend fun refresh(store: DesignStore) = reload(store)

    private suspend fun reload(store: DesignStore) {
        designs = withContext(Dispatchers.IO) { store.list() }
    }

    suspend fun create(
        context: Context,
        store: DesignStore,
        name: String,
        kind: DesignKind,
        targets: Set<PokemonCodename>,
    ): Boolean {
        val now = nowIsoUtc()
        val variants = seedVariants(targets, homeCodename())
        val design = Design(
            format = DESIGN_FORMAT,
            formatVersion = DESIGN_FORMAT_VERSION,
            id = withContext(Dispatchers.IO) { store.allocateId() },
            name = name.take(DesignCodec.MAX_NAME_LENGTH),
            author = Core.prefs.getString(PrefKeys.CREATOR_NAME, PrefKeys.CREATOR_NAME_DEF)
                .take(DesignCodec.MAX_AUTHOR_LENGTH),
            createdAt = now,
            modifiedAt = now,
            createdWith = createdWith(context),
            kind = kind,
            levels = DEFAULT_LEVELS,
            variants = variants,
        )
        val saved = withContext(Dispatchers.IO) { saveRespectingAuthor(store, design) }
        if (saved) reload(store)
        return saved
    }

    suspend fun duplicate(store: DesignStore, design: Design, newName: String): Boolean {
        val now = nowIsoUtc()
        val copy = design.copy(
            id = withContext(Dispatchers.IO) { store.allocateId() },
            name = newName.take(DesignCodec.MAX_NAME_LENGTH),
            createdAt = now,
            modifiedAt = now,
        )
        val saved = withContext(Dispatchers.IO) { saveRespectingAuthor(store, copy) }
        if (saved) reload(store)
        return saved
    }

    suspend fun rename(store: DesignStore, id: String, name: String): Boolean {
        val saved = withContext(Dispatchers.IO) {
            val stored = store.load(id) ?: return@withContext false
            val renamed = stored.copy(
                name = name.take(DesignCodec.MAX_NAME_LENGTH),
                modifiedAt = nowIsoUtc(),
            )
            saveRespectingAuthor(store, renamed)
        }
        if (saved) reload(store)
        return saved
    }

    suspend fun delete(store: DesignStore, design: Design) {
        withContext(Dispatchers.IO) { store.delete(design.id) }
        reload(store)
    }

    suspend fun import(context: Context, store: DesignStore, uri: Uri): ImportOutcome {
        val outcome = withContext(Dispatchers.IO) {
            when (val result = readDesign(context, uri)) {
                is DesignCodec.Result.Invalid -> ImportOutcome.Failed(result.reason)
                is DesignCodec.Result.Ok -> {
                    val design = importedDesign(result.design, store.allocateId(), nowIsoUtc())
                    if (saveRespectingAuthor(store, design)) {
                        ImportOutcome.Ok(design)
                    } else {
                        ImportOutcome.Failed(context.getString(R.string.create_import_save_failed))
                    }
                }
            }
        }
        if (outcome is ImportOutcome.Ok) reload(store)
        return outcome
    }
}

internal sealed interface ImportOutcome {
    data class Ok(val design: Design) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

internal fun saveRespectingAuthor(store: DesignStore, design: Design): Boolean {
    val stored = store.load(design.id)?.author
    val safe = if (!stored.isNullOrEmpty()) design.copy(author = stored) else design
    return store.save(safe)
}

internal sealed interface ShowOnMatrix {

    data object Shown : ShowOnMatrix

    data object ShownWhenEnabled : ShowOnMatrix

    data class NoArt(val codename: PokemonCodename?) : ShowOnMatrix
}

internal fun showDesignOnMatrix(design: Design): ShowOnMatrix {
    val codename = PokemonCodename.ofSize(Core.glyphLink.size)
    val frames = codename?.let { design.variantFor(it)?.frames }.orEmpty()
    if (frames.isEmpty()) return ShowOnMatrix.NoArt(codename)

    Core.prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, design.id)
    Core.prefs.putBoolean(PrefKeys.screenEnabled(CustomScreen.ID), true)
    selectToy(CustomScreen.ID)
    Core.scheduler.run { Core.screenManager.refreshCurrentScreen() }

    val driven = Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF) ||
        Core.arbiter.owner == SessionArbiter.Owner.TOY
    return if (driven) ShowOnMatrix.Shown else ShowOnMatrix.ShownWhenEnabled
}

@Composable
internal fun showOnMatrixMessage(afterEditor: Boolean): (ShowOnMatrix) -> String {
    val shown = stringResource(
        if (afterEditor) R.string.create_shown_on_close else R.string.create_shown,
    )
    val whenEnabled = stringResource(R.string.create_shown_needs_capture)
    val noArt = stringResource(R.string.create_show_no_art)
    val noArtFor = stringResource(R.string.create_show_no_art_for)
    val deviceNames = PokemonCodename.entries.associateWith { stringResource(it.displayNameRes()) }
    return { result ->
        when (result) {
            is ShowOnMatrix.Shown -> shown
            is ShowOnMatrix.ShownWhenEnabled -> whenEnabled
            is ShowOnMatrix.NoArt ->
                result.codename?.let { noArtFor.format(deviceNames[it]) } ?: noArt
        }
    }
}

private fun createdWith(context: Context): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    return "GlyphWorks $version".take(DesignCodec.MAX_CREATED_WITH_LENGTH)
}

private fun designCellPadding(column: Int, columns: Int): PaddingValues = PaddingValues(
    start = if (column == 0) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2,
    end = if (column == columns - 1) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2,
)

private val DESIGN_CARD_CORNER = 20.dp
private val DESIGN_CARD_MENU_BUTTON = DESIGN_CARD_CORNER * 2

private val DESIGN_CARD_MENU_TARGET = 48.dp

@Composable
private fun DesignCard(
    design: Design,
    art: DesignPreviewArt,
    player: PreviewPlayer,
    onOpen: () -> Unit,
    onShow: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    placement: Modifier,
    discSideInset: Dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = design.name.ifBlank { stringResource(R.string.pref_custom_unnamed) }
    val text = rememberDesignCardText(design, name)
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .then(placement),
        shape = RoundedCornerShape(DESIGN_CARD_CORNER),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            DesignPreviewDisc(
                art = art,
                player = player,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        start = discSideInset,
                        end = discSideInset,
                        top = DESIGN_DISC_TOP_INSET,
                    )
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            val menuInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(DESIGN_CARD_MENU_TARGET)
                    .clickable(
                        interactionSource = menuInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = { menuOpen = true },
                    ),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(DESIGN_CARD_MENU_BUTTON)
                        .clip(CircleShape)
                        .indication(menuInteraction, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.create_more),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = MaterialTheme.shapes.large,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_show)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Smartphone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onShow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_rename)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DriveFileRenameOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_duplicate)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_share)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_export)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                .clearAndSetSemantics { contentDescription = text.spoken },
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.credit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Immutable
internal class DesignCardText(
    val meta: String,
    val credit: String,
    val spoken: String,
)

internal class DesignCardStrings(
    val noArt: String,
    val kindStatic: String,
    val kindDynamic: String,
    val frameCount: (Int) -> String,
    val by: (String) -> String,
    val variantEmpty: (String) -> String,
    val deviceName: (PokemonCodename) -> String,
)

@Composable
private fun rememberDesignCardStrings(): DesignCardStrings {
    val resources = LocalResources.current
    return remember(resources) {
        DesignCardStrings(
            noArt = resources.getString(R.string.create_no_art),
            kindStatic = resources.getString(R.string.create_kind_static),
            kindDynamic = resources.getString(R.string.create_kind_dynamic),
            frameCount = { n -> resources.getQuantityString(R.plurals.create_frame_count, n, n) },
            by = { author -> resources.getString(R.string.create_by, author) },
            variantEmpty = { name -> resources.getString(R.string.create_variant_empty, name) },
            deviceName = { codename -> resources.getString(codename.displayNameRes()) },
        )
    }
}

@Composable
private fun rememberDesignCardText(design: Design, name: String): DesignCardText {
    val strings = rememberDesignCardStrings()
    return remember(design.id, design.modifiedAt, name, strings) {
        designCardText(design, name, strings)
    }
}

internal fun designCardText(design: Design, name: String, strings: DesignCardStrings): DesignCardText {
    val frames = designFrameCount(design)
    val date = formatTimestamp(design.modifiedAt)
    return DesignCardText(
        meta = designMeta(frames, strings),
        credit = designCredit(design, date, strings),
        spoken = name + META_SEPARATOR + designSummary(design, frames, strings) +
            META_SEPARATOR + designProvenance(design, date, strings),
    )
}

internal fun designFrameCount(design: Design): Int =
    design.variants.values.maxOfOrNull { it.frames.size } ?: 0

private fun designMeta(frames: Int, strings: DesignCardStrings): String =
    if (frames == 0) strings.noArt else strings.frameCount(frames)

private fun designCredit(design: Design, date: String, strings: DesignCardStrings): String =
    if (design.author.isNotBlank()) strings.by(design.author) else date

private fun designSummary(design: Design, frames: Int, strings: DesignCardStrings): String {
    val kind = if (design.kind == DesignKind.DYNAMIC) strings.kindDynamic else strings.kindStatic
    val parts = mutableListOf(kind, strings.frameCount(frames))
    if (design.author.isNotBlank()) parts += strings.by(design.author)
    return parts.joinToString(META_SEPARATOR)
}

private fun designProvenance(design: Design, date: String, strings: DesignCardStrings): String {
    val present = PokemonCodename.entries.mapNotNull { codename ->
        val variant = design.variantFor(codename) ?: return@mapNotNull null
        val name = strings.deviceName(codename)
        if (variant.frames.isEmpty()) strings.variantEmpty(name) else name
    }
    val variants = if (present.isEmpty()) strings.noArt else present.joinToString(META_SEPARATOR)
    return variants + META_SEPARATOR + date
}

private const val META_SEPARATOR = " · "

internal fun formatTimestamp(iso: String): String = try {
    DATE_FORMAT.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (e: Exception) {
    iso.take(10)
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
internal fun CreateEmptyState(onStart: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        SectionCard {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.create_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.create_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onStart, colors = filledButtonColors()) {
                        Text(stringResource(R.string.create_empty_action))
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onImport, colors = filledButtonColors()) {
                        Icon(
                            Icons.Outlined.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.create_import))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportButton(onImport: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)) {
        Button(onClick = onImport, colors = filledButtonColors()) {
            Icon(
                Icons.Outlined.FileOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.create_import))
        }
    }
}

@Composable
private fun filledButtonColors(): ButtonColors = if (isSystemInDarkTheme()) {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    )
} else {
    ButtonDefaults.buttonColors()
}

@Composable
private fun NewDesignDialog(
    suggestedName: String,
    defaultTarget: PokemonCodename,
    onDismiss: () -> Unit,
    onCreate: (String, DesignKind, Set<PokemonCodename>) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var dynamic by remember { mutableStateOf(false) }
    var target by remember(defaultTarget) { mutableStateOf(setOf(defaultTarget)) }
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_new)) },
        text = {
            NewDesignFields(
                name = name,
                onName = { name = it },
                dynamic = dynamic,
                onDynamic = { dynamic = it },
                target = target,
                onTarget = { target = it },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        name.trim(),
                        if (dynamic) DesignKind.DYNAMIC else DesignKind.STATIC,
                        target,
                    )
                },
            ) {
                Text(stringResource(R.string.create_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) }
        },
    )
}

@Composable
internal fun NewDesignFields(
    name: String,
    onName: (String) -> Unit,
    dynamic: Boolean,
    onDynamic: (Boolean) -> Unit,
    target: Set<PokemonCodename>,
    onTarget: (Set<PokemonCodename>) -> Unit,
) {
    val targetOptions = remember { designTargetOptions() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DesignNameField(
            name = name,
            onName = onName,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        NoRipple {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !dynamic,
                    onClick = { onDynamic(false) },
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_KIND, 0),
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.create_kind_static))
                }
                SegmentedButton(
                    selected = dynamic,
                    onClick = { onDynamic(true) },
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_KIND, 1),
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.create_kind_dynamic))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (dynamic) R.string.create_kind_hint_dynamic else R.string.create_kind_hint_static,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.create_target_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            targetOptions.forEach { option ->
                ChoiceRow(
                    label = targetLabel(option),
                    selected = option == target,
                    onSelect = { onTarget(option) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                if (target.size > 1) {
                    R.string.create_target_hint_both
                } else {
                    R.string.create_target_hint_one
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DesignNameField(name: String, onName: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = name,
        onValueChange = { onName(it.replace('\n', ' ').take(DesignCodec.MAX_NAME_LENGTH)) },
        modifier = modifier,
        label = { Text(stringResource(R.string.create_name_label)) },
        singleLine = true,
    )
}

@Composable
private fun RenameDesignDialog(design: Design, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var typed by remember(design.id) { mutableStateOf(design.name) }
    val renamed = renamedName(design.name, typed)
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_rename_title)) },
        text = {
            DesignNameField(
                name = typed,
                onName = { typed = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = renamed != null, onClick = { renamed?.let(onRename) }) {
                Text(stringResource(R.string.create_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) }
        },
    )
}

internal fun renamedName(current: String, typed: String): String? {
    val cleaned = typed.replace('\n', ' ').take(DesignCodec.MAX_NAME_LENGTH).trim()
    if (cleaned.isEmpty()) return null
    if (cleaned == current) return null
    return cleaned
}

@Composable
private fun targetLabel(option: Set<PokemonCodename>): String =
    option.singleOrNull()?.let { stringResource(it.displayNameRes()) }
        ?: stringResource(R.string.create_target_both)

@Composable
private fun DeleteDesignDialog(design: Design, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val unnamed = stringResource(R.string.pref_custom_unnamed)
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_delete_title, design.name.ifBlank { unnamed })) },
        text = { Text(stringResource(R.string.create_delete_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.create_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) } },
    )
}

@Composable
private fun ImportFailedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_import_failed_title)) },
        text = { Text(reason) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_import_dismiss)) }
        },
    )
}

internal fun generateDesignName(taken: Set<String>): String {
    repeat(8) {
        val candidate = "${NAME_ADJECTIVES.random()} ${NAME_NOUNS.random()}"
        if (candidate !in taken) return candidate
    }
    return "${NAME_ADJECTIVES.random()} ${NAME_NOUNS.random()}"
}

private val NAME_ADJECTIVES = listOf(
    "Slow", "Quiet", "Soft", "Bright", "Late", "Deep", "Pale", "Warm", "Still", "Sharp",
    "Faint", "Idle", "Lone", "Calm", "Bold", "Low", "Half", "Near", "First", "Last",
)

private val NAME_NOUNS = listOf(
    "Ember", "Comet", "Signal", "Drift", "Echo", "Beacon", "Cinder", "Pulse", "Halo", "Orbit",
    "Spark", "Lantern", "Tide", "Vapour", "Marker", "Lattice", "Current", "Flare", "Shutter", "Relay",
)
