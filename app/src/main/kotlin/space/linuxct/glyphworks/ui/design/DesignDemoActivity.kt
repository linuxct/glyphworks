package space.linuxct.glyphworks.ui.design

import space.linuxct.glyphworks.ui.tutorial.GuidedTourOverlay
import space.linuxct.glyphworks.ui.tutorial.swallowTourTouches
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import space.linuxct.glyphworks.ui.theme.dialogSurface
import space.linuxct.glyphworks.ui.theme.glyphCorner
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.CREATE_TAB_INDEX
import space.linuxct.glyphworks.ui.CreateEmptyState
import space.linuxct.glyphworks.ui.DIALOG_VERTICAL_MARGIN
import space.linuxct.glyphworks.ui.FloatingNavBar
import space.linuxct.glyphworks.ui.NewDesignFields
import space.linuxct.glyphworks.ui.dialogCardWidth
import space.linuxct.glyphworks.ui.homeCodename
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme

class DesignDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        setContent {
            GlyphWorksTheme {
                DesignDemoTour(onClose = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DesignDemoActivity::class.java)
    }
}

@Composable
private fun DesignDemoTour(onClose: () -> Unit) {
    val home = remember { homeCodename() }
    val sandbox = remember(home) { DemoSandbox(home) }
    val targets = remember { DemoTargets() }
    val ghost = remember { DemoGhost() }

    var index by rememberSaveable { mutableIntStateOf(0) }
    val at = index.coerceIn(DEMO_STEPS.indices)
    val step = DEMO_STEPS[at]

    LaunchedEffect(at, sandbox) {
        if (sandbox.applied != at) {
            sandbox.reset()
            val replay = DemoActor(ghost, targets, instant = true)
            for (earlier in 0 until at) DEMO_STEPS[earlier].act(replay, sandbox)
            sandbox.applied = at
        }
        ghost.hide()
        step.target?.let { target ->
            withTimeoutOrNull(TARGET_TIMEOUT_MS) {
                snapshotFlow { targets.unionOf(target) }.filterNotNull().first()
            }
        }
        step.act(DemoActor(ghost, targets, instant = false), sandbox)
        sandbox.applied = at + 1
        ghost.hide()
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDemoTargets provides targets) {
            DemoStageContent(step.stage, sandbox)
        }
        Box(Modifier.fillMaxSize().swallowTourTouches())
        GuidedTourOverlay(
            caption = step.caption,
            total = DEMO_STEPS.size,
            target = step.target,
            targetIndex = step.targetIndex,
            at = at,
            targets = targets,
            ghost = ghost,
            onBack = { if (at > 0) index = at - 1 },
            onNext = { if (at < DEMO_STEPS.lastIndex) index = at + 1 else onClose() },
            onSkip = onClose,
        )
    }
}

private const val TARGET_TIMEOUT_MS = 800L

@Composable
private fun DemoStageContent(stage: DemoStage, sandbox: DemoSandbox) {
    Crossfade(
        targetState = stage == DemoStage.EDITOR || stage == DemoStage.SETTINGS,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "demoStage",
    ) { editor ->
        if (editor) {
            EditorScaffold(
                state = sandbox.state,
                store = Core.designStore,
                onClose = {},
                demo = true,
            )
        } else {
            DemoCreateStage()
        }
    }
    DemoSheet(visible = stage == DemoStage.DIALOG) { DemoNewDesignSheet(sandbox) }
    DemoSheet(visible = stage == DemoStage.SETTINGS) {
        DesignSettingsCard(sandbox.state, onChanged = {}, onClose = {})
    }
}

@Composable
private fun DemoSheet(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = SHEET_ENTER_SCALE),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            scaleOut(MaterialTheme.motionScheme.defaultSpatialSpec(), targetScale = SHEET_ENTER_SCALE),
        label = "demoSheet",
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = DIALOG_VERTICAL_MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private const val SHEET_ENTER_SCALE = 0.85f

@Composable
private fun DemoCreateStage() {
    // Keep the Surface. It publishes LocalContentColor; on a bare Box the title falls back to
    // black and vanishes in dark mode.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Text(
                    stringResource(R.string.create_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
                )
                CreateEmptyState(onStart = {}, onImport = {})
            }
            FloatingNavBar(
                selected = CREATE_TAB_INDEX,
                position = { CREATE_TAB_INDEX.toFloat() },
                fabVisible = true,
                onFabClick = {},
                onSelect = {},
                onPillHeight = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun DemoNewDesignSheet(sandbox: DemoSandbox) {
    Surface(
        modifier = Modifier.width(dialogCardWidth()),
        shape = glyphCorner(28.dp, 36.dp),
        color = dialogSurface(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                stringResource(R.string.create_new),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            NewDesignFields(
                name = sandbox.name,
                onName = { sandbox.name = it },
                dynamic = sandbox.dynamic,
                onDynamic = { sandbox.dynamic = it },
                target = sandbox.target,
                onTarget = { sandbox.target = it },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {}) { Text(stringResource(R.string.create_cancel)) }
                TextButton(
                    onClick = {},
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_CREATE),
                ) {
                    Text(stringResource(R.string.create_create))
                }
            }
        }
    }
}
