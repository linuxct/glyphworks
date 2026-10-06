package space.linuxct.glyphworks.ui.pipeline.tutorial

import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.glyphworks.ui.pipeline.*
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.tutorial.*
import space.linuxct.glyphworks.ui.design.DemoTargets
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.LocalDemoTargets
import space.linuxct.glyphworks.ui.design.EditorScaffold
import space.linuxct.glyphworks.designs.DesignStore
import java.io.File

class PipelineTutorialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        val chapter = intent.getStringExtra(EXTRA_CHAPTER)?.let { name -> PipelineChapter.entries.find { it.name == name } } ?: PipelineChapter.CANVAS
        setContent { GlyphWorksTheme { PipelineDemoTour(chapter, ::finish) } }
    }

    companion object {
        private const val EXTRA_CHAPTER = "chapter"
        fun intent(context: Context, chapter: PipelineChapter = PipelineChapter.CANVAS): Intent =
            Intent(context, PipelineTutorialActivity::class.java).putExtra(EXTRA_CHAPTER, chapter.name)
    }
}

/** Used by Tutorials, library and context help. Selection is outside the tour overlay. */
@Composable
fun PipelineTutorialChapters(onChoose: (PipelineChapter) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.pipeline_tutorial_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        PipelineChapter.entries.forEachIndexed { index, chapter ->
            Row(Modifier.fillMaxWidth().clickable { onChoose(chapter) }.padding(horizontal = 16.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(chapter.title), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun PipelineDemoTour(
    chapter: PipelineChapter,
    onClose: () -> Unit,
    initialStep: Int = 0,
    onStepFinished: (Int) -> Unit = {},
) {
    val sandbox = remember(chapter) { PipelineDemoSandbox(chapter) }
    val steps = remember(chapter) { pipelineDemoSteps(chapter) }
    val targets = remember { TourTargets<String>() }
    val ghost = remember { TourGhost() }
    var index by rememberSaveable(chapter) { mutableIntStateOf(initialStep) }
    val at = index.coerceIn(steps.indices)
    val step = steps[at]
    val reportStepFinished by rememberUpdatedState(onStepFinished)

    LaunchedEffect(chapter, at, sandbox) {
        if (sandbox.applied != at) {
            sandbox.reset()
            val replay = TourActor(ghost, targets, instant = true)
            for (earlier in 0 until at) steps[earlier].act(replay, sandbox)
            sandbox.applied = at
        }
        ghost.hide()
        step.target?.let { target ->
            withTimeoutOrNull(800L) { snapshotFlow { targets.unionOf(target) }.filterNotNull().first() }
        }
        step.act(TourActor(ghost, targets, instant = false), sandbox)
        sandbox.applied = at + 1
        ghost.hide()
        reportStepFinished(at)
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPipelineDemoTargets provides targets) {
            PipelineDemoStage(sandbox)
        }
        Box(Modifier.fillMaxSize().swallowTourTouches())
        GuidedTourOverlay(
            caption = step.caption, at = at, total = steps.size, target = step.target,
            targets = targets, ghost = ghost,
            onBack = { if (at > 0) index = at - 1 },
            onNext = { if (at < steps.lastIndex) index = at + 1 else onClose() },
            onSkip = onClose,
        )
    }
}

@Composable
private fun PipelineDemoStage(sandbox: PipelineDemoSandbox) {
    when (sandbox.stage) {
        PipelineDemoStage.EDITOR -> PipelineEditor(
            document = sandbox.document,
            onChange = sandbox::edit,
            onSave = {}, onApply = {}, onClose = {}, controller = sandbox.controller, editorHistory = sandbox.history,
            previewContent = { PipelineDemoSimulation(sandbox) },
        )
        PipelineDemoStage.ASSET_EDITOR -> PipelineDemoAssetEditor(sandbox)
        PipelineDemoStage.SETTINGS -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                CustomControlsSettingsContent(
                    enabled = sandbox.customControls,
                    status = stringResource(when { sandbox.stopped -> R.string.pipeline_custom_stopped; sandbox.customControls -> R.string.pipeline_custom_active; else -> R.string.pipeline_custom_off }),
                    onEnabled = { sandbox.customControls = it }, onEdit = {}, onStop = { sandbox.stopped = true },
                )
            }
        }
        PipelineDemoStage.LOCKED_TOYS -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                CustomControlsLockedToys(onEdit = {}, onSettings = {})
            }
        }
    }
}

@Composable
private fun PipelineDemoSimulation(sandbox: PipelineDemoSandbox) {
    val simulation = remember(sandbox.document, sandbox.panelSize, sandbox.previewSequence) {
        PipelineSimulation(sandbox.document, sandbox.panelSize).apply {
            inputs.putAll(sandbox.previewInputs)
            var time = 0L
            for ((at, event) in sandbox.previewEvents) {
                advanceBy((at - time).coerceAtLeast(0)); time = at
                dispatch(event)
            }
            advanceBy((sandbox.previewTime - time).coerceAtLeast(0))
        }
    }
    DisposableEffect(simulation) { onDispose { simulation.close() } }
    PipelineSimulationPanel(simulation, autoRun = false, panelSize = sandbox.panelSize, revision = sandbox.previewSequence,
        onExecutionState = { active, waiting -> sandbox.controller.activeBlockIds = active; sandbox.controller.waitingBlockIds = waiting })
}

@Composable
private fun PipelineDemoAssetEditor(sandbox: PipelineDemoSandbox) {
    val context = LocalContext.current
    val isolatedStore = remember(context) {
        // EditorScaffold's demo saver is a no-op; even an accidental future read cannot see the library.
        DesignStore(object : ContextWrapper(context) {
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getFilesDir(): File = File(context.cacheDir, "pipeline-tutorial-sandbox")
        })
    }
    val designTargets = remember { DemoTargets() }
    val pipelineTargets = LocalPipelineDemoTargets.current
    DisposableEffect(pipelineTargets) { onDispose { pipelineTargets?.forget("asset-canvas", 0) } }
    LaunchedEffect(designTargets, pipelineTargets) {
        snapshotFlow { designTargets.boundsOf(DemoTarget.CANVAS, 0) }.filterNotNull().collect { pipelineTargets?.report("asset-canvas", 0, it) }
    }
    CompositionLocalProvider(LocalDemoTargets provides designTargets) {
        sandbox.assetState?.let { EditorScaffold(it, isolatedStore, onClose = {}, demo = true) }
    }
}
