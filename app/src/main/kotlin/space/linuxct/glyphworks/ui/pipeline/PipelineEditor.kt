package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.glyphworks.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import space.linuxct.pipeline.*
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget
import space.linuxct.glyphworks.ui.pipeline.tutorial.LocalPipelineDemoTargets
import kotlin.math.abs
import kotlin.math.roundToInt

/** All transient UI state is injectable so the guided tour uses the production editor. */
@Stable
class PipelineEditorController {
    var panel by mutableStateOf(EditorPanel.NONE)
    var referenceOp by mutableStateOf<String?>(null)
    var selectedBlock by mutableStateOf<String?>(null)
    var selectedScript by mutableStateOf<String?>(null)
    var selectedRoutine by mutableStateOf<String?>(null)
    var programId by mutableStateOf<String?>(null)
    var insertion by mutableStateOf<BlockLocation?>(null)
    var previewExpanded by mutableStateOf(false)
    var viewportOverride by mutableStateOf<Viewport?>(null)
    var clipboard by mutableStateOf<Block?>(null)
    var movingBlock by mutableStateOf<String?>(null)
    var movingStack by mutableStateOf(false)
    var focusRequest by mutableStateOf<String?>(null)
    var activeBlockIds by mutableStateOf<Set<String>>(emptySet())
    var waitingBlockIds by mutableStateOf<Set<String>>(emptySet())
}

enum class EditorPanel { NONE, PALETTE, BLOCK, SCRIPT, VARIABLES, PARAMETERS, ROUTINES, ASSETS, PROJECT, SEARCH, ISSUES, REFERENCE }

@Composable
fun rememberPipelineEditorController(): PipelineEditorController = remember { PipelineEditorController() }

private data class EditorDrag(val block: Block? = null, val sourceId: String? = null, val stackId: String? = null, val start: Offset, val pointer: Offset, val origin: Position? = null)

/** The full-window editor has no Core, store, device, or Android activity dependency. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineEditor(
    document: PipelineDocument,
    onChange: (PipelineDocument) -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    controller: PipelineEditorController = rememberPipelineEditorController(),
    onEditAsset: (String?) -> Unit = {},
    onPreview: (PipelineDocument) -> Unit = {},
    previewContent: (@Composable () -> Unit)? = null,
    onTutorial: () -> Unit = {},
    readOnly: Boolean = false,
    statusText: String = "Draft",
    editorHistory: EditorHistory? = null,
    onImportBlocks: () -> Unit = {},
    onCapabilities: () -> Unit = {},
    onOpenExample: (String) -> Unit = {},
) {
    val history = editorHistory ?: remember(document.id) { EditorHistory(document) }
    var current by remember(document.id) { mutableStateOf(document) }
    // Parent updates include asset-editor returns and the tutorial's deterministic replay.
    LaunchedEffect(document) {
        if (document != current) { current = history.change(document, record = false) }
    }
    fun update(next: PipelineDocument, record: Boolean = true) {
        current = history.change(next, record)
        onChange(current)
    }
    val program = current.programs.firstOrNull { it.id == controller.programId } ?: current.entry() ?: current.programs.firstOrNull()
    val routine = current.routines.firstOrNull { it.id == controller.selectedRoutine }
    val issues = remember(current.programs, current.routines, current.designs, current.bindings, current.entryPoint, current.name, current.panels) { PipelineCodec.validate(current) }
    val viewport = controller.viewportOverride ?: current.editor.viewport
    val density = LocalDensity.current.density
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<EditorDrag?>(null) }
    var dropTarget by remember { mutableStateOf<BlockLocation?>(null) }
    val targets = remember { mutableMapOf<BlockLocation, Rect>() }
    val stackBounds = remember { mutableMapOf<String, Rect>() }
    val stackSizes = remember { mutableMapOf<String, IntSize>() }
    val blockBounds = remember { mutableMapOf<String, Rect>() }
    var paletteSearch by remember { mutableStateOf("") }
    var paletteCategory by remember { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }

    fun setViewport(next: Viewport) {
        controller.viewportOverride = null
        update(current.copy(editor = current.editor.copy(viewport = next)), record = false)
    }
    fun targetAt(point: Offset): BlockLocation? = targets.entries
        .filter { (_, rect) -> rect.inflate(10f * density).contains(point) }
        .filter { (at, _) -> drag?.sourceId?.let { EditorDocument.canMove(current, it, at) } != false }
        .minByOrNull { (_, rect) -> abs(rect.center.y - point.y) + abs(rect.center.x - point.x) * .12f }?.key
    fun moveDrag(delta: Offset) { drag = drag?.copy(pointer = drag!!.pointer + delta); dropTarget = drag?.let { targetAt(it.pointer) } }
    fun finishDrag(cancel: Boolean = false) {
        val active = drag
        if (!cancel && active != null) {
            if (active.stackId != null && active.origin != null) {
                val change = (active.pointer - active.start) / (density * viewport.scale)
                update(current.copy(editor = current.editor.copy(positions = current.editor.positions + (active.stackId to Position(active.origin.x + change.x, active.origin.y + change.y)))))
            } else dropTarget?.let { at ->
                val next = if (active.sourceId != null) EditorDocument.move(current, active.sourceId, at) else active.block?.let { EditorDocument.insert(current, at, it) }
                if (next != null) update(next)
            }
        }
        drag = null
        dropTarget = null
    }
    fun place(block: Block) {
        val destination = controller.insertion ?: routine?.let { BlockLocation(it.id, index = it.blocks.size) }
            ?: program?.scripts?.firstOrNull()?.let { BlockLocation(it.id, index = it.blocks.size) }
        if (destination != null) update(EditorDocument.insert(current, destination, block))
        else if (program != null) {
            val script = Script(blocks = listOf(block))
            update(EditorDocument.addScript(current, program.id, script, Position((24f - viewport.x) / viewport.scale, (24f - viewport.y) / viewport.scale)))
        }
        controller.panel = EditorPanel.NONE
        controller.insertion = null
    }
    fun insertAt(location: BlockLocation) {
        val moving = controller.movingBlock
        val clipboard = controller.clipboard
        when {
            moving != null -> {
                update(if (controller.movingStack) EditorDocument.moveStack(current, moving, location) else EditorDocument.move(current, moving, location))
                controller.movingBlock = null; controller.movingStack = false
            }
            clipboard != null -> { update(EditorDocument.insert(current, location, EditorDocument.fresh(clipboard))); controller.clipboard = null }
            else -> { controller.insertion = location; controller.panel = EditorPanel.PALETTE }
        }
    }
    fun fitAll() {
        val positions = if (routine != null) listOf(routine.id to Position(24f, 24f)) else program?.scripts.orEmpty().mapIndexed { index, script -> script.id to (current.editor.positions[script.id] ?: Position(24f + index * 348f, 24f)) }
        if (positions.isEmpty()) { setViewport(Viewport()); return }
        val minX = positions.minOf { it.second.x }; val maxX = positions.maxOf { it.second.x + (stackSizes[it.first]?.width?.div(density) ?: 312f) }
        val minY = positions.minOf { it.second.y }; val maxY = positions.maxOf { it.second.y + (stackSizes[it.first]?.height?.div(density) ?: 320f) }
        val scale = minOf((canvasSize.width / density - 40) / (maxX - minX), (canvasSize.height / density - 40) / (maxY - minY)).coerceIn(.3f, 1.5f)
        setViewport(Viewport(20 - minX * scale, 20 - minY * scale, scale))
    }
    fun centerSelection() {
        val selected = controller.selectedBlock?.let { blockBounds[it] } ?: controller.selectedScript?.let { stackBounds[it] }
        if (selected != null) setViewport(viewport.copy(x = viewport.x + (canvasBounds.center.x - selected.center.x) / density, y = viewport.y + (canvasBounds.center.y - selected.center.y) / density)) else fitAll()
    }

    LaunchedEffect(controller.focusRequest) {
        val id = controller.focusRequest ?: return@LaunchedEffect
        val location = EditorDocument.location(current, id)
        if (location != null) {
            controller.selectedBlock = id
            controller.selectedRoutine = current.routines.firstOrNull { it.id == location.ownerId }?.id
            val owningProgram = current.programs.firstOrNull { p -> p.scripts.any { it.id == location.ownerId } }
            if (owningProgram != null) controller.programId = owningProgram.id
            val position = current.editor.positions[location.ownerId] ?: Position(24f + (owningProgram?.scripts?.indexOfFirst { it.id == location.ownerId } ?: 0).coerceAtLeast(0) * 348f, 24f)
            var ancestors = setOf(location.ownerId)
            var parent = location.parentId
            while (parent != null) { ancestors += parent; parent = EditorDocument.location(current, parent)?.parentId }
            update(current.copy(editor = current.editor.copy(collapsed = current.editor.collapsed - ancestors)), false)
            setViewport(Viewport(24f - position.x * viewport.scale, 24f - position.y * viewport.scale, viewport.scale))
            withFrameNanos { }; withFrameNanos { }
            centerSelection()
        }
        controller.focusRequest = null
    }

    // Edge movement is capped in distance per frame and has no fling on pointer-up.
    LaunchedEffect(drag != null) {
        while (drag != null) {
            delay(32)
            val active = drag ?: break
            if (active.stackId != null) continue
            val margin = 48f * density
            fun axis(position: Float, minimum: Float, maximum: Float): Float = when {
                position < minimum + margin -> ((minimum + margin - position) / margin).coerceIn(0f, 1f) * 5f
                position > maximum - margin -> -((position - maximum + margin) / margin).coerceIn(0f, 1f) * 5f
                else -> 0f
            }
            val dx = axis(active.pointer.x, canvasBounds.left, canvasBounds.right)
            val dy = axis(active.pointer.y, canvasBounds.top, canvasBounds.bottom)
            if (dx != 0f || dy != 0f) { val vp = current.editor.viewport; setViewport(vp.copy(x = vp.x + dx, y = vp.y + dy)); dropTarget = targetAt(active.pointer) }
        }
    }
    BackHandler(enabled = controller.panel != EditorPanel.NONE || controller.movingBlock != null || routine != null) {
        when { controller.panel != EditorPanel.NONE -> controller.panel = EditorPanel.NONE; controller.movingBlock != null -> controller.movingBlock = null; else -> controller.selectedRoutine = null }
    }

    Box(modifier.fillMaxSize()) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxWidth().pipelineDemoTarget("toolbar")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.pipeline_editor_close_editor)) }
                    Column(Modifier.weight(1f).clickable { controller.panel = EditorPanel.PROJECT }) {
                        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(if (routine != null) "Routine · ${routine.name}" else program?.kind?.let(::roleName).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { controller.previewExpanded = !controller.previewExpanded; if (controller.previewExpanded) onPreview(current) }, modifier = Modifier.pipelineDemoTarget("preview")) { Icon(Icons.Outlined.PlayCircle, stringResource(R.string.pipeline_editor_simulate)) }
                    if (!readOnly) TextButton(onClick = onApply, enabled = issues.isEmpty(), modifier = Modifier.pipelineDemoTarget("apply")) { Text(stringResource(R.string.pipeline_editor_apply)) }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.pipeline_editor_editor_menu)) }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_save_draft)) }, enabled = !readOnly, onClick = { overflow = false; onSave() }, modifier = Modifier.pipelineDemoTarget("save"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_project_settings)) }, onClick = { overflow = false; controller.panel = EditorPanel.PROJECT })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_parameters)) }, onClick = { overflow = false; controller.panel = EditorPanel.PARAMETERS })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_assets_and_artwork)) }, onClick = { overflow = false; controller.panel = EditorPanel.ASSETS })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_block_reference_and_examples)) }, onClick = { overflow = false; controller.panel = EditorPanel.REFERENCE })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_find_in_canvas)) }, onClick = { overflow = false; controller.panel = EditorPanel.SEARCH })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_permissions_and_sources)) }, onClick = { overflow = false; onCapabilities() })
                            DropdownMenuItem(text = { Text("Check pipeline (${issues.size})") }, onClick = { overflow = false; controller.panel = EditorPanel.ISSUES })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_center_selection)) }, onClick = { overflow = false; centerSelection() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_reset_zoom)) }, onClick = { overflow = false; setViewport(viewport.copy(scale = 1f)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_interactive_tutorial)) }, onClick = { overflow = false; onTutorial() })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { current = history.undo(); onChange(current) }, enabled = history.canUndo && !readOnly, modifier = Modifier.pipelineDemoTarget("undo")) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.pipeline_editor_undo)) }
                    IconButton(onClick = { current = history.redo(); onChange(current) }, enabled = history.canRedo && !readOnly, modifier = Modifier.pipelineDemoTarget("redo")) { Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.pipeline_editor_redo)) }
                    IconButton(onClick = ::fitAll) { Icon(Icons.Outlined.ZoomOutMap, stringResource(R.string.pipeline_editor_fit_all)) }
                    TextButton(onClick = { controller.panel = EditorPanel.VARIABLES }, modifier = Modifier.pipelineDemoTarget("variables")) { Text(stringResource(R.string.pipeline_editor_variables)) }
                    TextButton(onClick = { controller.panel = EditorPanel.ROUTINES }, modifier = Modifier.pipelineDemoTarget("routines")) { Text(stringResource(R.string.pipeline_editor_routines)) }
                    TextButton(onClick = { controller.panel = EditorPanel.ASSETS }, modifier = Modifier.pipelineDemoTarget("assets")) { Text(stringResource(R.string.pipeline_editor_artwork)) }
                }
            }
            if (routine != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller.selectedRoutine = null }) { Text("${program?.name ?: "Program"} / ") }
                Text(routine.name, style = MaterialTheme.typography.labelMedium)
            }
            if (controller.previewExpanded && previewContent != null) Surface(Modifier.fillMaxWidth().heightIn(max = 250.dp), color = MaterialTheme.colorScheme.surfaceContainer) { previewContent() }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("pipeline-canvas").pipelineDemoTarget("canvas")
                .onGloballyPositioned { canvasBounds = it.boundsInRoot(); canvasSize = it.size }
                .pointerInput(drag != null) {
                    if (drag != null) return@pointerInput
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val before = current.editor.viewport
                        val scale = (before.scale * zoom).coerceIn(.3f, 2f)
                        val anchor = centroid / density
                        val x = anchor.x - (anchor.x - before.x) * scale / before.scale + pan.x / density
                        val y = anchor.y - (anchor.y - before.y) * scale / before.scale + pan.y / density
                        setViewport(Viewport(x, y, scale))
                    }
                }) {
                val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .11f)
                Canvas(Modifier.fillMaxSize()) {
                    val step = 24.dp.toPx() * viewport.scale
                    val startX = (viewport.x * density % step + step) % step
                    val startY = (viewport.y * density % step + step) % step
                    var x = startX
                    while (x < size.width) { var y = startY; while (y < size.height) { drawCircle(gridColor, 1.dp.toPx(), Offset(x, y)); y += step }; x += step }
                }
                val stacks = if (routine != null) listOf(WorkspaceStack(routine.id, routine.name, routine.blocks, null)) else program?.scripts.orEmpty().map { WorkspaceStack(it.id, it.name, it.blocks, it) }
                if (stacks.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AccountTree, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.pipeline_editor_start_with_an_event), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.pipeline_editor_choose_when_your_pipeline_runs_then_add_the_blocks_it_shou), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                stacks.forEachIndexed { index, stack ->
                    val base = current.editor.positions[stack.id] ?: Position(24f + index * 348f, 24f)
                    val activeDrag = drag?.takeIf { it.stackId == stack.id }
                    val shift = activeDrag?.let { (it.pointer - it.start) / (density * viewport.scale) } ?: Offset.Zero
                    val position = Position(base.x + shift.x, base.y + shift.y)
                    val atX = (viewport.x + position.x * viewport.scale) * density
                    val atY = (viewport.y + position.y * viewport.scale) * density
                    // Horizontal culling avoids laying out distant scripts, without truncating a tall active stack.
                    if (atX + 340 * density * viewport.scale < -120 * density || atX > canvasSize.width + 120 * density) return@forEachIndexed
                    Column(Modifier.offset { IntOffset(atX.roundToInt(), atY.roundToInt()) }.requiredWidth(312.dp).wrapContentHeight(Alignment.Top, unbounded = true)
                        .graphicsLayer { scaleX = viewport.scale; scaleY = viewport.scale; transformOrigin = TransformOrigin(0f, 0f) }
                        .onGloballyPositioned { stackBounds[stack.id] = it.boundsInRoot(); stackSizes[stack.id] = it.size }) {
                        var headerCoordinates by remember(stack.id) { mutableStateOf<LayoutCoordinates?>(null) }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 6.dp),
                            modifier = Modifier.fillMaxWidth().testTag("pipeline-script:${stack.id}").pipelineDemoTarget("script:${stack.id}").onGloballyPositioned { headerCoordinates = it }
                                .pointerInput(stack.id, readOnly, base, viewport.scale) { if (!readOnly) detectDragGesturesAfterLongPress(onDragStart = { local ->
                                    val point = headerCoordinates?.localToRoot(local) ?: return@detectDragGesturesAfterLongPress
                                    drag = EditorDrag(stackId = stack.id, start = point, pointer = point, origin = base)
                                }, onDrag = { change, amount -> change.consume(); moveDrag(amount * viewport.scale) }, onDragEnd = { finishDrag() }, onDragCancel = { finishDrag(true) }) }
                                .clickable { if (stack.script != null) { controller.selectedScript = stack.id; controller.panel = EditorPanel.SCRIPT } else controller.panel = EditorPanel.ROUTINES }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (stack.script == null) Icons.Outlined.AccountTree else Icons.Outlined.Bolt, null, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(stack.title, style = MaterialTheme.typography.titleSmall); stack.script?.let { event -> EventCatalog.all.firstOrNull { it.name == event.trigger.event }?.title?.takeIf { it != stack.title }?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } }
                                IconButton(onClick = { val collapsed = current.editor.collapsed; update(current.copy(editor = current.editor.copy(collapsed = if (stack.id in collapsed) collapsed - stack.id else collapsed + stack.id)), false) }, modifier = Modifier.size(28.dp)) { Icon(if (stack.id in current.editor.collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, "Fold event") }
                            }
                        }
                        if (stack.id !in current.editor.collapsed) BlockList(current, stack.blocks, BlockLocation(stack.id), controller, targets, blockBounds, dropTarget, drag?.sourceId, readOnly,
                            onInsert = ::insertAt, onChange = { update(it) }, onDragStart = { block, point -> drag = EditorDrag(block = block, sourceId = block.id, start = point, pointer = point) },
                            onDrag = ::moveDrag, onDragEnd = { finishDrag() }, onDragCancel = { finishDrag(true) }, scale = viewport.scale)
                    }
                }
                drag?.block?.let { block ->
                    val point = drag!!.pointer - canvasBounds.topLeft
                    Surface(Modifier.offset { IntOffset((point.x - 110 * density).roundToInt(), (point.y - 28 * density).roundToInt()) }.width(220.dp),
                        shadowElevation = 8.dp, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(BlockCatalog[block.op]?.title ?: block.op, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text("${(viewport.scale * 100).roundToInt()}%", Modifier.align(Alignment.BottomEnd).padding(14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (controller.movingBlock != null || controller.clipboard != null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pipeline_editor_choose_an_insertion_point), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.movingBlock = null; controller.clipboard = null }) { Text(stringResource(R.string.pipeline_editor_cancel)) }
            }
            if (controller.panel == EditorPanel.PALETTE) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).pipelineDemoTarget("palette")) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(paletteSearch, { paletteSearch = it }, label = { Text(stringResource(R.string.pipeline_editor_find_a_block)) }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(onClick = { controller.panel = EditorPanel.NONE }) { Icon(Icons.Outlined.Close, stringResource(R.string.pipeline_editor_close_blocks)) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(paletteCategory == null, { paletteCategory = null }, label = { Text(stringResource(R.string.pipeline_editor_all)) })
                            BlockCatalog.all.map { it.category }.distinct().forEach { category -> FilterChip(paletteCategory == category, { paletteCategory = category }, label = { Text(category) }) }
                        }
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BlockCatalog.all.filter { (paletteCategory == null || it.category == paletteCategory) && (it.title.contains(paletteSearch, true) || it.description.contains(paletteSearch, true)) }.forEach { spec ->
                                var coordinates by remember(spec.op) { mutableStateOf<LayoutCoordinates?>(null) }
                                Surface(color = categoryColor(spec.category), shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates = it }.pipelineDemoTarget("palette:${spec.op}")
                                        .pointerInput(spec.op) { detectDragGesturesAfterLongPress(onDragStart = { local ->
                                            val point = coordinates?.localToRoot(local) ?: return@detectDragGesturesAfterLongPress
                                            drag = EditorDrag(block = Block(op = spec.op, arguments = spec.arguments.associate { it.name to it.default }), start = point, pointer = point)
                                        }, onDrag = { change, delta -> change.consume(); moveDrag(delta) }, onDragEnd = { finishDrag() }, onDragCancel = { finishDrag(true) }) }
                                        .clickable { place(Block(op = spec.op, arguments = spec.arguments.associate { it.name to it.default })) }) {
                                    Row(Modifier.padding(12.dp)) { Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(spec.category, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (readOnly) "Original · make a copy to edit" else if (issues.isNotEmpty()) "${issues.size} issue(s) · tap to review" else statusText, Modifier.weight(1f).clickable { controller.panel = EditorPanel.ISSUES }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!readOnly) {
                    if (routine == null) TextButton(onClick = {
                        if (program != null) {
                            val script = Script()
                            update(EditorDocument.addScript(current, program.id, script, Position((24f - viewport.x) / viewport.scale, (24f - viewport.y) / viewport.scale)))
                            controller.selectedScript = script.id; controller.panel = EditorPanel.SCRIPT
                        }
                    }, modifier = Modifier.pipelineDemoTarget("add-event")) { Text(stringResource(R.string.pipeline_editor_event)) }
                    FilledTonalButton(onClick = { controller.panel = if (controller.panel == EditorPanel.PALETTE) EditorPanel.NONE else EditorPanel.PALETTE }, modifier = Modifier.pipelineDemoTarget("blocks")) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(stringResource(R.string.pipeline_editor_blocks)) }
                }
            }
        }
    }
    if (controller.panel !in listOf(EditorPanel.NONE, EditorPanel.PALETTE)) {
        val content: @Composable () -> Unit = {
            PipelineEditorInspector(current, program, routine, controller, readOnly, onChange = { update(it) }, onEditAsset = onEditAsset, onImportBlocks = onImportBlocks, onOpenExample = onOpenExample,
                modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp))
        }
        if (LocalPipelineDemoTargets.current != null) {
            // A dialog window would cover the shared tutorial spotlight and touch interceptor.
            // Keep the actual production inspector inline, as the design-editor tutorial does.
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.65f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 4.dp, shadowElevation = 12.dp) { content() }
        } else ModalBottomSheet(onDismissRequest = { controller.panel = EditorPanel.NONE }) { content() }
    }
    }
}

private data class WorkspaceStack(val id: String, val title: String, val blocks: List<Block>, val script: Script?)

internal fun roleName(kind: ProgramKind): String = when (kind) { ProgramKind.TOY -> "Interactive toy"; ProgramKind.AMBIENT -> "Ambient background"; ProgramKind.CONTROLLER -> "Custom controls and menus"; ProgramKind.ROUTINE -> "Reusable routine" }

@Composable
internal fun categoryColor(category: String): Color {
    val base = when (category.lowercase()) { "control" -> Color(0xFFE6B870); "events" -> Color(0xFFE4C863); "display", "drawing", "scene" -> Color(0xFF80B5C7); "variables", "data" -> Color(0xFFB8A3C9); "routines" -> Color(0xFFC799B0); else -> Color(0xFF8ABB9E) }
    return base.copy(alpha = .15f).compositeOver(MaterialTheme.colorScheme.surface)
}

@Composable
private fun BlockList(
    document: PipelineDocument, blocks: List<Block>, location: BlockLocation, controller: PipelineEditorController,
    targets: MutableMap<BlockLocation, Rect>, blockBounds: MutableMap<String, Rect>, dropTarget: BlockLocation?, draggingId: String?, readOnly: Boolean,
    onInsert: (BlockLocation) -> Unit, onChange: (PipelineDocument) -> Unit,
    onDragStart: (Block, Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit, onDragCancel: () -> Unit, scale: Float,
) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            InsertionZone(location.copy(index = index), targets, dropTarget, readOnly, blocks.isEmpty(), onInsert)
            val spec = BlockCatalog[block.op]
            val folded = block.id in document.editor.collapsed
            var coordinates by remember(block.id) { mutableStateOf<LayoutCoordinates?>(null) }
            var menu by remember(block.id) { mutableStateOf(false) }
            val selected = controller.selectedBlock == block.id
            val running = block.id in controller.activeBlockIds
            val waiting = block.id in controller.waitingBlockIds
            Surface(color = categoryColor(spec?.category.orEmpty()), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()
                .graphicsLayer { alpha = if (block.id == draggingId || !block.enabled) .48f else 1f }
                .then(if (selected || running || waiting) Modifier.border(if (running || waiting) 2.dp else 1.dp, if (waiting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                .onGloballyPositioned { blockBounds[block.id] = it.boundsInRoot() }.pipelineDemoTarget("block:${block.id}")
                .semantics { customActions = listOf(
                    CustomAccessibilityAction("Edit block") { controller.selectedBlock = block.id; controller.panel = EditorPanel.BLOCK; true },
                    CustomAccessibilityAction("Move block") { controller.movingBlock = block.id; true },
                    CustomAccessibilityAction("Duplicate block") { if (!readOnly) onChange(EditorDocument.duplicate(document, block.id)); !readOnly },
                ) }) {
                Column {
                    Row(Modifier.fillMaxWidth().testTag("pipeline-block:${block.id}").onGloballyPositioned { coordinates = it }
                        .pointerInput(block.id, readOnly, block, scale) { if (!readOnly) detectDragGesturesAfterLongPress(onDragStart = { point -> coordinates?.localToRoot(point)?.let { onDragStart(block, it) } }, onDrag = { change, delta -> change.consume(); onDrag(delta * scale) }, onDragEnd = onDragEnd, onDragCancel = onDragCancel) }
                        .clickable { controller.selectedBlock = block.id; controller.panel = EditorPanel.BLOCK }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(spec?.title ?: block.op, style = MaterialTheme.typography.labelLarge)
                            if (running || waiting) Text(if (waiting) "Waiting" else "Running", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (block.arguments.isNotEmpty()) Text(blockDisplaySummary(block, document), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (block.comment.isNotBlank()) Text(block.comment, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (spec?.body == true || block.body.isNotEmpty() || block.otherwise.isNotEmpty()) IconButton(onClick = { onChange(document.copy(editor = document.editor.copy(collapsed = if (folded) document.editor.collapsed - block.id else document.editor.collapsed + block.id))) }, modifier = Modifier.size(32.dp)) { Icon(if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, "Fold block", modifier = Modifier.size(18.dp)) }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.pipeline_editor_block_actions), Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_edit)) }, onClick = { menu = false; controller.selectedBlock = block.id; controller.panel = EditorPanel.BLOCK })
                                if (!readOnly) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_move_to)) }, onClick = { menu = false; controller.movingBlock = block.id; controller.movingStack = false })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_move_this_and_following_blocks)) }, onClick = { menu = false; controller.movingBlock = block.id; controller.movingStack = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_duplicate)) }, onClick = { menu = false; onChange(EditorDocument.duplicate(document, block.id)) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_copy)) }, onClick = { menu = false; controller.clipboard = block })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_cut)) }, onClick = { menu = false; controller.clipboard = block; onChange(EditorDocument.remove(document, block.id)) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_make_reusable_routine)) }, onClick = { menu = false; onChange(EditorDocument.extractRoutine(document, block.id, spec?.title ?: "My routine")) })
                                    DropdownMenuItem(text = { Text(if (block.enabled) "Disable" else "Enable") }, onClick = { menu = false; onChange(EditorDocument.update(document, block.copy(enabled = !block.enabled))) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pipeline_editor_delete)) }, onClick = { menu = false; onChange(EditorDocument.remove(document, block.id)) })
                                }
                            }
                        }
                    }
                    if (!folded) {
                        if (spec?.body == true || block.body.isNotEmpty()) Column(Modifier.padding(start = 14.dp, end = 6.dp, bottom = 4.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(4.dp)) {
                            BlockList(document, block.body, BlockLocation(location.ownerId, block.id), controller, targets, blockBounds, dropTarget, draggingId, readOnly, onInsert, onChange, onDragStart, onDrag, onDragEnd, onDragCancel, scale)
                        }
                        if (spec?.otherwise == true || block.otherwise.isNotEmpty()) {
                            Text(stringResource(R.string.pipeline_editor_otherwise), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
                            Column(Modifier.padding(start = 14.dp, end = 6.dp, bottom = 6.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(4.dp)) {
                                BlockList(document, block.otherwise, BlockLocation(location.ownerId, block.id, BlockBranch.OTHERWISE), controller, targets, blockBounds, dropTarget, draggingId, readOnly, onInsert, onChange, onDragStart, onDrag, onDragEnd, onDragCancel, scale)
                            }
                        }
                    }
                }
            }
        }
        InsertionZone(location.copy(index = blocks.size), targets, dropTarget, readOnly, blocks.isEmpty(), onInsert)
    }
}

@Composable
private fun InsertionZone(location: BlockLocation, targets: MutableMap<BlockLocation, Rect>, selected: BlockLocation?, readOnly: Boolean, empty: Boolean, onInsert: (BlockLocation) -> Unit) {
    DisposableEffect(location) { onDispose { targets.remove(location) } }
    val active = selected == location
    Box(Modifier.fillMaxWidth().testTag("pipeline-insertion:${location.ownerId}:${location.parentId}:${location.branch}:${location.index}").height(if (empty) 42.dp else 16.dp).onGloballyPositioned { targets[location] = it.boundsInRoot() }
        .clip(RoundedCornerShape(4.dp)).background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else Color.Transparent)
        .clickable(enabled = !readOnly) { onInsert(location) }, contentAlignment = Alignment.Center) {
        if (empty || active) Text(if (active) "Drop here" else "+ Add block", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        else Box(Modifier.width(36.dp).height(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
