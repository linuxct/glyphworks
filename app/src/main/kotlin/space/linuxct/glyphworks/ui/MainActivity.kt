package space.linuxct.glyphworks.ui

import space.linuxct.glyphworks.pipeline.runtime.PipelinePrefs
import space.linuxct.glyphworks.ui.pipeline.*
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineTutorialActivity
import space.linuxct.glyphworks.ui.pipeline.tutorial.PipelineTutorialDialog
import space.linuxct.pipeline.PipelineRuntime

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.ui.theme.DialogBackdropBlur
import space.linuxct.glyphworks.ui.theme.dialogSurface
import space.linuxct.glyphworks.ui.theme.fullContrastListItemColors
import space.linuxct.glyphworks.ui.theme.fullContrastToggleColors
import space.linuxct.glyphworks.ui.theme.fullContrastTopAppBarColors
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.notifications.NotificationAccess
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.demoTarget
import space.linuxct.glyphworks.ui.theme.GlyphRadioButton
import space.linuxct.glyphworks.ui.theme.GlyphSlider
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.GlyphSegmentedRow
import space.linuxct.glyphworks.ui.theme.glyphSegmentedColors
import space.linuxct.glyphworks.ui.theme.glyphSegmentedShape
import space.linuxct.glyphworks.ui.theme.glyphSegmentedIcon
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.theme.Backdrop
import space.linuxct.glyphworks.ui.theme.ThinBrush
import space.linuxct.glyphworks.ui.theme.ThinCap
import space.linuxct.glyphworks.ui.theme.ThinCog
import space.linuxct.glyphworks.ui.theme.ThinDice
import space.linuxct.glyphworks.ui.theme.NAV_BLUR
import space.linuxct.glyphworks.ui.theme.backdropBlur
import space.linuxct.glyphworks.ui.theme.recordBackdrop
import space.linuxct.glyphworks.ui.theme.rememberBackdrop
import space.linuxct.glyphworks.ui.theme.lucent
import space.linuxct.glyphworks.ui.theme.navPill
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        if (!isNothingGlyphDevice(this)) {
            showUnsupportedDevice()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_RESTART_ONBOARDING, false) == true) {
            Core.prefs.putBoolean(PrefKeys.ONBOARDING_DONE, false)
        }
        if (!Core.prefs.getBoolean(PrefKeys.ONBOARDING_DONE, PrefKeys.ONBOARDING_DONE_DEF)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        scheduleUpdateCheck(this)
        enableEdgeToEdge()
        val startTab = requestedStartTab()
        setContent {
            GlyphWorksTheme {
                MainScreen(startTab)
            }
        }
    }

    private fun showUnsupportedDevice() {
        enableEdgeToEdge()
        setContent {
            GlyphWorksTheme {
                UnsupportedDeviceScreen()
            }
        }
    }

    private fun requestedStartTab(): Int {
        val requested = intent?.getIntExtra(EXTRA_TAB, 0)?.coerceIn(Tab.entries.indices) ?: 0
        return if (requested == Tab.TOYS.ordinal && Core.pipeline.controllerMode) Tab.SETTINGS.ordinal else requested
    }

    companion object {
        const val EXTRA_RESTART_ONBOARDING = "restart_onboarding"

        private const val EXTRA_TAB = "tab"

        fun createTabIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, Tab.CREATE.ordinal)
    }
}

internal val CONFIGURABLE =
    SCREEN_DISPLAY_NAMES.keys

internal val NAV_PILL_CLEARANCE = 40.dp

private val NAV_PILL_MARGIN = 14.dp

// Theirs floats noticeably clear of the bottom edge rather than hugging it.
private val NAV_PILL_MARGIN_LUCENT = 44.dp

// One value for all four sides. The chips only look evenly inset while
// chip radius + gap == pill radius, and split padding breaks that at large font scales.
private val NAV_PILL_GAP = 6.dp

private val NAV_CHIP_SHAPE = RoundedCornerShape(percent = 50)

private class NavOverlayPadding(
    private val base: PaddingValues,
    private val extraBottom: () -> Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = base.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding() + extraBottom()
}

private enum class Tab(
    val icon: ImageVector,
    val thinIcon: ImageVector,
    val caption: Int,
    val title: Int,
) {
    TOYS(Icons.Outlined.Casino, ThinDice, R.string.nav_toys, R.string.screens_title),
    CREATE(Icons.Outlined.Brush, ThinBrush, R.string.nav_create, R.string.create_title),
    SETTINGS(Icons.Outlined.Settings, ThinCog, R.string.nav_settings, R.string.settings),
    TUTORIAL(Icons.Outlined.School, ThinCap, R.string.tut_section, R.string.tut_section),
}

internal val CREATE_TAB_INDEX: Int = Tab.CREATE.ordinal

/**
 * A page change has to end, not merely stop looking like it is moving.
 *
 * A spring runs until it is inside its visibility threshold, which defaults to a hundredth of a
 * pixel, and the expressive spatial default is damped at 0.8 so it overshoots on the way. From a
 * page's width that is about three quarters of a second, nearly half of it after the page has
 * visibly stopped — and every bit of it counts as scrolling, so the pager holds its animation and
 * the frosted backdrop redraws the whole screen frame after frame for no visible gain. Critically
 * damped, and done once it is within a pixel, which is already indistinguishable from arrived.
 */
private val PAGE_SETTLE = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PAGE_STIFFNESS,
    visibilityThreshold = 1f,
)

// Twice the expressive spatial default, which is 380. Across a page's width that is about a
// quarter of a second of movement and a third of a second of animation, against roughly three
// quarters before — quick, but still slow enough to read as a slide rather than a cut.
private const val PAGE_STIFFNESS = 800f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(startTab: Int = 0) {
    val pagerState = rememberPagerState(initialPage = startTab, pageCount = { Tab.entries.size })
    val scope = rememberCoroutineScope()
    var toyDeckGesture by remember { mutableStateOf(false) }
    val customControls by rememberPref(PipelinePrefs.CONTROLLER_ENABLED) { it.getBoolean(PipelinePrefs.CONTROLLER_ENABLED, false) }
    var pipelineLibrary by rememberSaveable { mutableStateOf(false) }

    var untestedAck by rememberSaveable {
        mutableStateOf(
            !isTestedGlyphDevice() &&
                !Core.prefs.getBoolean(PrefKeys.UNTESTED_DEVICE_ACK, PrefKeys.UNTESTED_DEVICE_ACK_DEF),
        )
    }
    if (untestedAck) {
        UntestedDeviceDialog(onDismiss = {
            untestedAck = false
            Core.prefs.putBoolean(PrefKeys.UNTESTED_DEVICE_ACK, true)
        })
    }
    val pageSpec = PAGE_SETTLE
    val fling = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = pageSpec)
    val headerExpansion = remember { HeaderExpansion() }

    val toysListState = rememberLazyListState()
    val createListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val tutorialScrollState = rememberScrollState()

    val createState = remember { CreateState() }

    val setupContext = LocalContext.current
    var setupTick by remember { mutableIntStateOf(0) }
    val configuredWeather by rememberPref(WeatherPrefs.ENABLED) { it.getBoolean(WeatherPrefs.ENABLED, false) }
    val configuredBackgrounds by rememberPref(PrefKeys.AMBIENT_BACKGROUNDS) { AmbientBackgrounds.readSelection(it) }
    val useBackground by rememberPref(PrefKeys.AMBIENT_USE_BACKGROUND) {
        it.getBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_BACKGROUND_DEF)
    }
    val selectedToy by rememberPref(PrefKeys.CURRENT_SCREEN) { it.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF) }
    val pipelineRevision by rememberPref(PipelinePrefs.LIBRARY_REVISION) { it.getLong(PipelinePrefs.LIBRARY_REVISION, 0) }
    val triggerProjects by rememberPref(PipelinePrefs.TRIGGERS) { it.getString(PipelinePrefs.TRIGGERS, "") }
    val setup = remember(setupTick, setupContext, configuredWeather, pipelineRevision, triggerProjects, customControls, selectedToy) {
        probeSetup(setupContext)
    }
    LifecycleResumeEffect(Unit) {
        setupTick++
        onPauseOrDispose { }
    }

    val headerSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        snapAnimationSpec = headerSpec,
    )

    fun atTopOf(tab: Tab): Boolean = when (tab) {
        Tab.TOYS ->
            toysListState.firstVisibleItemIndex == 0 &&
                toysListState.firstVisibleItemScrollOffset == 0
        Tab.CREATE ->
            createState.gridState.firstVisibleItemIndex == 0 &&
                createState.gridState.firstVisibleItemScrollOffset == 0
        Tab.SETTINGS -> settingsScrollState.value == 0
        Tab.TUTORIAL -> tutorialScrollState.value == 0
    }

    fun busy(tab: Tab): Boolean = when (tab) {
        Tab.TOYS -> toysListState.isScrollInProgress
        Tab.CREATE -> createState.gridState.isScrollInProgress
        Tab.SETTINGS -> settingsScrollState.isScrollInProgress
        Tab.TUTORIAL -> tutorialScrollState.isScrollInProgress
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (Tab.entries[page] == Tab.CREATE) createState.visited = true
        }
    }

    ExpandHeaderOnArrival(
        pagerState = pagerState,
        scrollBehavior = scrollBehavior,
        spec = pageSpec,
        atTopOf = ::atTopOf,
        expansion = headerExpansion,
    )

    var pillHeight by remember { mutableStateOf(0.dp) }

    val lucent = MaterialTheme.lucent
    val backdrop = rememberBackdrop()
    // The frost is only redrawn while something moves; idle frames would burn a full-screen
    // blur for an unchanged picture.
    var backdropTick by remember { mutableIntStateOf(0) }
    val moving by remember {
        derivedStateOf {
            busy(Tab.entries[pagerState.currentPage]) || pagerState.isScrollInProgress
        }
    }

    // Open on arrival, shut on the way down a list, open again the moment the user pulls back
    // up. The state persists once the finger lifts, which is the part scroll-in-progress missed.
    var navExpanded by remember { mutableStateOf(true) }
    val navScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= -NAV_SCROLL_SLOP) navExpanded = false
                if (available.y >= NAV_SCROLL_SLOP) navExpanded = true
                return Offset.Zero
            }
        }
    }
    // Consumes nothing; it is only here to be above the app bar's connection in the chain, which
    // is the one place a drag can still be seen whole. See [HeaderExpansion].
    val yieldHeader = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) headerExpansion.release()
                return Offset.Zero
            }
        }
    }
    // Landing on a tab shows you where you are, so the label has to be readable.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }.collect { navExpanded = true }
    }
    LaunchedEffect(lucent) {
        if (!lucent) return@LaunchedEffect
        snapshotFlow { moving }.collectLatest { live ->
            while (live) {
                withFrameNanos { }
                backdropTick++
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // Order is the point: the leftmost connection is the outermost, and gets each
                // drag before the app bar has taken it.
                .nestedScroll(yieldHeader)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .nestedScroll(navScroll)
                .then(if (lucent) Modifier.recordBackdrop(backdrop) else Modifier),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    title = {
                        Crossfade(
                            targetState = Tab.entries[pagerState.currentPage].title,
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            label = "appBarTitle",
                        ) { title -> Text(stringResource(title)) }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = fullContrastTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { innerPadding ->
            val pagePadding = remember(innerPadding) {
                NavOverlayPadding(innerPadding) {
                    pillHeight + if (lucent) NAV_PILL_MARGIN_LUCENT else NAV_PILL_MARGIN
                }
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !toyDeckGesture,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = fling,
                beyondViewportPageCount = Tab.entries.size - 1,
                overscrollEffect = null,
            ) { page ->
                when (Tab.entries[page]) {
                    Tab.TOYS -> if (customControls) {
                        CustomControlsLockedToys(
                            onEdit = { setupContext.startActivity(PipelineEditorActivity.intent(setupContext, Core.pipeline.controllerId)) },
                            onSettings = { scope.launch { pagerState.animateScrollToPage(Tab.SETTINGS.ordinal) } },
                            modifier = Modifier.padding(pagePadding),
                        )
                    } else ToysTab(
                        pagePadding,
                        toysListState,
                        visible = pagerState.settledPage == Tab.TOYS.ordinal && !pagerState.isScrollInProgress,
                        onDeckGesture = { toyDeckGesture = it },
                    )
                    Tab.CREATE -> Column(Modifier.fillMaxSize().padding(top = pagePadding.calculateTopPadding())) {
                        GlyphSegmentedRow(selected = if (pipelineLibrary) 1 else 0, count = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SegmentedButton(selected = !pipelineLibrary, onClick = { pipelineLibrary = false }, shape = glyphSegmentedShape(0, 2), colors = glyphSegmentedColors(!pipelineLibrary), icon = glyphSegmentedIcon(!pipelineLibrary)) { Text(stringResource(R.string.create_designs_section)) }
                            SegmentedButton(selected = pipelineLibrary, onClick = { pipelineLibrary = true }, shape = glyphSegmentedShape(1, 2), colors = glyphSegmentedColors(pipelineLibrary), icon = glyphSegmentedIcon(pipelineLibrary)) { Text(stringResource(R.string.create_pipelines_section)) }
                        }
                        val createPadding = PaddingValues(bottom = pagePadding.calculateBottomPadding())
                        if (pipelineLibrary) PipelineLibraryScreen(contentPadding = createPadding)
                        else CreateTab(createPadding, createListState, createState)
                    }
                    Tab.SETTINGS -> SettingsTab(
                        pagePadding,
                        settingsScrollState,
                        setup,
                        setupTick,
                    ) { setupTick++ }
                    Tab.TUTORIAL -> TutorialTab(pagePadding, tutorialScrollState)
                }
            }
        }
        FloatingNavBar(
            selected = pagerState.targetPage,
            position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            fabVisible = pagerState.targetPage == Tab.CREATE.ordinal && !pipelineLibrary,
            setupNeedsAttention = setup.needsAttention,
            backdrop = backdrop,
            backdropVersion = { backdropTick },
            expanded = navExpanded,
            onFabClick = { createState.newDesignRequested = true },
            onSelect = { i ->
                scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) }
            },
            onPillHeight = { pillHeight = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandHeaderOnArrival(
    pagerState: PagerState,
    scrollBehavior: TopAppBarScrollBehavior,
    spec: AnimationSpec<Float>,
    atTopOf: (Tab) -> Boolean,
    expansion: HeaderExpansion,
) {
    LaunchedEffect(pagerState, scrollBehavior) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            val tab = Tab.entries[page]
            if (!atTopOf(tab)) return@collectLatest
            val from = scrollBehavior.state.heightOffset
            if (from == 0f) return@collectLatest
            coroutineScope {
                val expand = launch {
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = spec,
                    ) { value, _ -> scrollBehavior.state.heightOffset = value }
                    scrollBehavior.state.contentOffset = 0f
                }
                expansion.job = expand
                expand.join()
                expansion.job = null
            }
        }
    }
}

/**
 * Lets a touch stop the header opening itself.
 *
 * The app bar's own nested scroll connection sits above the pages and takes the whole gesture for
 * as long as its height is somewhere between open and shut. The arrival animation writes that
 * height on every frame, so between the two of them the first drag after a tab change is
 * swallowed whole: the list never starts moving, and waiting for it to start — which is what this
 * used to do — was waiting for the very thing the animation was preventing. So the touch cancels
 * the animation instead, from a connection mounted above the app bar's so that it sees the drag
 * before anything has had the chance to eat it.
 */
private class HeaderExpansion {
    var job: Job? = null

    fun release() {
        job?.cancel()
        job = null
    }
}

@Composable
internal fun FloatingNavBar(
    selected: Int,
    position: () -> Float,
    fabVisible: Boolean,
    onFabClick: () -> Unit,
    onSelect: (Int) -> Unit,
    onPillHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    setupNeedsAttention: Boolean = false,
    backdrop: Backdrop? = null,
    backdropVersion: () -> Int = { 0 },
    expanded: Boolean = true,
) {
    val pill = MaterialTheme.navPill
    val lucent = MaterialTheme.lucent
    val density = LocalDensity.current
    // Their bar grows on both axes off a pinned bottom edge, so it opens upward.
    val springing by animateFloatAsState(
        targetValue = if (!lucent || expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "navExpansion",
    )
    // The spring undershoots past 0 and lerp extrapolates, so the bar would draw smaller than
    // its own collapsed size and then grow back into it. Clamp the geometry, keep the timing.
    val open = springing.coerceIn(0f, 1f)
    // One gap on every side, so the selected circle is inset the same amount from the ends as
    // it is from the top. The label is what makes the bar longer, not extra padding.
    val endGap = if (lucent) lerp(NAV_PAD_TIGHT, NAV_PAD_WIDE, open) else NAV_PILL_GAP
    val chipPad = if (lucent) lerp(NAV_CHIP_PAD_TIGHT, NAV_CHIP_PAD_WIDE, open) else NAV_CHIP_PAD
    val container = Modifier
        .onSizeChanged { onPillHeight(with(density) { it.height.toDp() }) }
        .clip(NAV_CHIP_SHAPE)
    val frosted = if (lucent && backdrop != null) {
        container.backdropBlur(backdrop, NAV_BLUR, backdropVersion).background(pill.container)
    } else {
        container
    }
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = if (lucent) NAV_PILL_MARGIN_LUCENT else NAV_PILL_MARGIN),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = frosted,
                shape = NAV_CHIP_SHAPE,
                color = if (lucent) Color.Transparent else pill.container,
                contentColor = MaterialTheme.colorScheme.onBackground,
                shadowElevation = if (lucent) 0.dp else 8.dp,
            ) {
                // No ripple here: while one plays it holds the tab transition open, and the
                // page underneath cannot be scrolled until it finishes.
                NoRipple {
                    Row(
                        Modifier.padding(endGap),
                        horizontalArrangement = Arrangement.spacedBy(if (lucent) 0.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tab.entries.forEachIndexed { i, t ->
                            NavChip(
                                tab = t,
                                index = i,
                                selected = i == selected,
                                position = position,
                                pad = chipPad,
                                // Collapsed shows icons only; the label belongs to the open bar.
                                labelWidth = { open },
                                badge = setupNeedsAttention && t == Tab.SETTINGS,
                            ) { onSelect(i) }
                        }
                    }
                }
            }

            // The FAB keeps Nothing's red and blue, so it keeps the content-derived ripple too.
            DefaultRipple { NavFab(visible = fabVisible, open = open, onClick = onFabClick) }
        }
    }
}

// Measured off Nothing OS 5: 98 px tall open, 86 px closed, at 1.75 px/dp.
private val NAV_PAD_WIDE = 4.dp
private val NAV_PAD_TIGHT = 2.dp
private val NAV_CHIP_PAD_WIDE = 15.dp
private val NAV_CHIP_PAD_TIGHT = 13.dp
private val NAV_CHIP_PAD = 12.dp

private val NAV_FAB_GAP = 10.dp

private val NAV_FAB_SIZE = 56.dp

private val NAV_FAB_SIZE_TIGHT = 48.dp

// Ignore sub-pixel jitter so the bar does not flip state on a stationary finger.
private const val NAV_SCROLL_SLOP = 1.5f

@Composable
private fun NavFab(visible: Boolean, open: Float, onClick: () -> Unit) {
    val pill = MaterialTheme.navPill
    val revealSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) present = true
        val target = if (visible) 1f else 0f
        coroutineScope {
            launch { fade.animateTo(target, fadeSpec) }
            launch { reveal.animateTo(target, revealSpec) }
        }
        if (!visible) present = false
    }
    if (!present) return

    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * reveal.value)
                    .roundToInt()
                    .coerceIn(0, placeable.width)
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
            .padding(start = NAV_FAB_GAP),
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(if (MaterialTheme.lucent) lerp(NAV_FAB_SIZE_TIGHT, NAV_FAB_SIZE, open) else NAV_FAB_SIZE)
                .demoTarget(DemoTarget.FAB)
                .graphicsLayer {
                    val scale = reveal.value.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                    alpha = fade.value.coerceIn(0f, 1f)
                },
            shape = CircleShape,
            containerColor = pill.fabContainer,
            contentColor = pill.fabContent,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiquidFabFill(Modifier.fillMaxSize())
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.create_new))
            }
        }
    }
}

@Composable
private fun NavChip(
    tab: Tab,
    index: Int,
    selected: Boolean,
    position: () -> Float,
    pad: Dp = NAV_CHIP_PAD,
    labelWidth: () -> Float = { 1f },
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val pill = MaterialTheme.navPill
    val tint by animateColorAsState(
        targetValue = if (selected) pill.selectedContent else pill.content,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "navChipTint",
    )
    val fill = pill.selectedContainer
    // Only the draw and layout lambdas below may call this. From the composable body it
    // subscribes the chip to the pager offset and recomposes all four chips per frame.
    fun selectedness(): Float = (1f - abs(position() - index)).coerceIn(0f, 1f)
    Row(
        Modifier
            .clip(NAV_CHIP_SHAPE)
            .drawBehind {
                drawRoundRect(
                    color = fill,
                    alpha = selectedness(),
                    cornerRadius = CornerRadius(size.minDimension / 2f),
                )
            }
            .clickable(onClick = onClick)
            .padding(pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                if (MaterialTheme.lucent) tab.thinIcon else tab.icon,
                contentDescription = stringResource(tab.caption),
                tint = tint,
            )
            if (badge) {
                AttentionBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp),
                )
            }
        }
        Box(
            Modifier
                .clearAndSetSemantics {}
                .graphicsLayer { alpha = selectedness() * labelWidth() }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val width = (placeable.width * selectedness() * labelWidth())
                        .roundToInt()
                        .coerceIn(0, placeable.width)
                    layout(width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            Text(
                stringResource(tab.caption),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val NAV_BADGE_SIZE = 16.dp

private const val BADGE_STROKE_FRACTION = 0.28f
private const val BADGE_BAR_TOP = 0.5f
private const val BADGE_BAR_BOTTOM = 1.0f
private const val BADGE_DOT_CENTER = 1.5f

@Composable
private fun AttentionBadge(modifier: Modifier = Modifier) {
    val pill = MaterialTheme.navPill
    val label = stringResource(R.string.nav_setup_needs_attention)
    Canvas(
        modifier
            .size(NAV_BADGE_SIZE)
            .semantics { contentDescription = label },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(color = pill.badgeContainer, radius = radius)
        val strokeWidth = radius * BADGE_STROKE_FRACTION
        val centerX = size.width / 2f
        drawLine(
            color = pill.badgeContent,
            start = Offset(centerX, radius * BADGE_BAR_TOP),
            end = Offset(centerX, radius * BADGE_BAR_BOTTOM),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = pill.badgeContent,
            radius = strokeWidth / 2f,
            center = Offset(centerX, radius * BADGE_DOT_CENTER),
        )
    }
}

internal fun selectToy(id: String) {
    if (Core.pipeline.controllerMode) return
    DebugLog.i("Ui", "set active toy '$id'")
    Core.arbiter.revive()
    Core.scheduler.run { Core.screenManager.selectScreen(id) }
}

private val SETUP_NOTIFICATION_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

private val SETUP_MICROPHONE_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)

private val SETUP_LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun glyphToyEverBound(): Boolean =
    Core.arbiter.owner == SessionArbiter.Owner.TOY ||
        Core.prefs.getLong(PrefKeys.TOY_LAST_BOUND, PrefKeys.TOY_LAST_BOUND_DEF) > 0L

private fun pipelineRequiredCapabilities(): Set<String> {
    val active = if (Core.pipeline.controllerMode) Core.pipeline.controllerId else Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
    val triggerIds = if (Core.pipeline.controllerMode) emptyList() else Core.prefs.getString(PipelinePrefs.TRIGGERS, "").split(',').mapNotNull { it.substringAfter('=', "").takeIf(String::isNotBlank) }
    return (listOf(active) + triggerIds).mapNotNull(Core.pipeline::document).flatMap { space.linuxct.glyphworks.pipeline.runtime.PipelineController.capabilities(it) }.toSet()
}

private fun probeSetup(context: Context): SetupStatus {
    fun anyGranted(permissions: Array<String>) =
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    return SetupStatus(
        accessibility = isEssentialKeyServiceEnabled(context),
        alwaysOnToy = glyphToyEverBound(),
        toyProbeArmed = Core.prefs.getBoolean(PrefKeys.TOY_PROBE_ARMED, PrefKeys.TOY_PROBE_ARMED_DEF),
        notifications = anyGranted(SETUP_NOTIFICATION_PERMISSIONS),
        microphone = anyGranted(SETUP_MICROPHONE_PERMISSIONS),
        location = anyGranted(SETUP_LOCATION_PERMISSIONS),
        exactAlarms = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
        notificationAccess = NotificationAccess.isGranted(context),
        notificationAccessNeeded = "notifications" in pipelineRequiredCapabilities(),
        weatherEnabled = Core.prefs.getBoolean(WeatherPrefs.ENABLED, false),
        backgroundLocation = backgroundLocationGranted(context),
    )
}

@Composable
private fun SettingsTab(
    innerPadding: PaddingValues,
    scrollState: ScrollState,
    setup: SetupStatus,
    refreshTick: Int,
    onRefresh: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefresh() }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        InitialSetupSection(setup, refreshTick, onRefresh) { permissionLauncher.launch(it) }
        AppSettingsSection(refreshTick)
        AiSettingsSection()
        Spacer(Modifier.height(innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE))
    }
}

@Composable
private fun ColumnScope.InitialSetupSection(
    setup: SetupStatus,
    refreshTick: Int,
    onRefresh: () -> Unit,
    onRequestPermissions: (Array<String>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(setup.needsAttention) }
    var informationSettings by remember { mutableStateOf<String?>(null) }
    CollapsibleSectionHeader(
        text = stringResource(R.string.section_initial_setup),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        label = "initialSetupSection",
    ) {
        Column {
            SectionCard {
                item { AccessibilityChecklistRow(setup.accessibility, refreshTick) }
                item { AlwaysOnToyChecklistRow(setup) }
                item {
                    PermissionRow(
                        stringResource(R.string.checklist_notifications),
                        SETUP_NOTIFICATION_PERMISSIONS,
                        setup.notifications,
                        onRequestPermissions,
                    )
                }
                item {
                    PermissionRow(
                        stringResource(R.string.checklist_mic),
                        SETUP_MICROPHONE_PERMISSIONS,
                        setup.microphone,
                        onRequestPermissions,
                    )
                }
                item {
                    ChecklistRow(
                        stringResource(R.string.information_notification_access),
                        stringResource(
                            when {
                                setup.notificationAccess -> R.string.information_notification_access_on
                                setup.notificationAccessNeeded -> R.string.information_notification_access_off
                                else -> R.string.information_notification_optional
                            },
                        ),
                        if (setup.notificationAccess) true else if (setup.notificationAccessNeeded) false else null,
                    ) { informationSettings = "notifications" }
                }
                item {
                    PermissionRow(
                        stringResource(R.string.checklist_location),
                        SETUP_LOCATION_PERMISSIONS,
                        setup.location,
                        onRequestPermissions,
                    )
                }
                item {
                    ChecklistRow(
                        stringResource(R.string.information_weather_setup),
                        stringResource(
                            when {
                                !setup.weatherEnabled -> R.string.information_weather_optional
                                !setup.location -> R.string.information_weather_location_off
                                !setup.backgroundLocation -> R.string.information_weather_background_off
                                else -> R.string.information_weather_permissions_on
                            },
                        ),
                        if (setup.weatherEnabled && setup.location && setup.backgroundLocation) true else null,
                    ) { informationSettings = "weather" }
                }
                item { ExactAlarmChecklistRow(setup.exactAlarms) }
                item { WalkthroughRow() }
            }
            HintText(stringResource(R.string.checklist_hint_guides))
        }
    }
    informationSettings?.let { id ->
        ScreenSettingsDialog(id) {
            informationSettings = null
            onRefresh()
        }
    }
}

private const val MINUTE_MS = 60_000L

private fun lastActivitySuffix(): String {
    val beat = Core.prefs.getLong(PrefKeys.SERVICE_HEARTBEAT, PrefKeys.SERVICE_HEARTBEAT_DEF)
    if (beat <= 0) return ""
    val mins = (System.currentTimeMillis() - beat) / MINUTE_MS
    return " (last activity ${if (mins < 1) "just now" else "$mins min ago"})"
}

@Composable
private fun AccessibilityChecklistRow(enabled: Boolean, refreshTick: Int) {
    val context = LocalContext.current
    val onText = stringResource(R.string.checklist_accessibility_on)
    val offText = stringResource(R.string.checklist_accessibility_off)
    val subtitle = remember(refreshTick, onText, offText) {
        if (enabled) onText + lastActivitySuffix() else offText
    }
    ChecklistRow(
        title = stringResource(R.string.checklist_accessibility),
        subtitle = subtitle,
        good = enabled,
    ) {
        context.startActivity(
            if (enabled) {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            } else {
                Intent(context, DisclosureActivity::class.java)
            },
        )
    }
}

@Composable
private fun AlwaysOnToyChecklistRow(setup: SetupStatus) {
    val context = LocalContext.current
    val bound = setup.alwaysOnToy
    ChecklistRow(
        title = stringResource(R.string.checklist_toy),
        subtitle = stringResource(
            when {
                bound -> R.string.checklist_toy_on
                !setup.toyProbeArmed -> R.string.checklist_toy_pending
                else -> R.string.checklist_toy_hint
            },
        ),
        good = if (bound) true else null,
    ) {
        if (!openGlyphToySettings(context)) {
            Toast.makeText(context, R.string.glyph_settings_unavailable, Toast.LENGTH_SHORT)
                .show()
        }
    }
}

@Composable
private fun ExactAlarmChecklistRow(granted: Boolean) {
    val context = LocalContext.current
    ChecklistRow(
        title = stringResource(R.string.checklist_exact_alarm),
        subtitle = stringResource(
            if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant,
        ),
        good = granted,
    ) {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

@Composable
private fun WalkthroughRow() {
    val context = LocalContext.current
    PrefRow(
        lines = PrefRowLines.TWO,
        leading = { PrefIcon(Icons.Outlined.Slideshow) },
        onClick = {
            context.startActivity(
                Intent(context, OnboardingActivity::class.java),
            )
        },
    ) {
        Text(
            stringResource(R.string.checklist_walkthrough),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.checklist_walkthrough_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppSettingsSection(refreshTick: Int) {
    SectionHeader(stringResource(R.string.section_app_settings))
    SectionCard {
        item {
            PrefSwitchRow(
                refreshTick = refreshTick,
                title = stringResource(R.string.master_toggle),
                subtitle = stringResource(R.string.master_toggle_summary),
                key = PrefKeys.MASTER_TOGGLE,
                def = PrefKeys.MASTER_TOGGLE_DEF,
                leading = Icons.Outlined.Key,
            )
        }
        item {
            PrefSwitchRow(
                refreshTick = refreshTick,
                title = stringResource(R.string.pref_menu_mode),
                subtitle = stringResource(R.string.pref_menu_mode_summary),
                key = PrefKeys.MENU_MODE_ENABLED,
                def = PrefKeys.MENU_MODE_ENABLED_DEF,
                leading = Icons.AutoMirrored.Outlined.List,
            )
        }
        item {
            PrefSwitchRow(
                refreshTick = refreshTick,
                title = stringResource(R.string.pref_key_toasts),
                subtitle = stringResource(R.string.pref_key_toasts_summary),
                key = PrefKeys.KEY_ACTION_TOASTS,
                def = PrefKeys.KEY_ACTION_TOASTS_DEF,
                leading = Icons.Outlined.Campaign,
            )
        }
        item {
            PrefSwitchRow(
                refreshTick = refreshTick,
                title = stringResource(R.string.pref_use12h),
                subtitle = null,
                key = PrefKeys.USE_12H,
                def = false,
                leading = Icons.Outlined.Schedule,
            )
        }
        item {
            PrefSwitchRow(
                refreshTick = refreshTick,
                title = stringResource(R.string.pref_lucent),
                subtitle = stringResource(R.string.pref_lucent_summary),
                key = PrefKeys.LUCENT_ENABLED,
                def = PrefKeys.LUCENT_ENABLED_DEF,
                leading = Icons.Outlined.Palette,
            )
        }
        item { BrightnessRow() }
        item { CreatorNameRow() }
        item { PipelineAdvancedSettings() }
        // Adds its own item only in the GitHub build. About remains last in both variants.
        updateSettingsItem()
        item { AboutRow() }
    }
}

@Composable
private fun PrefSwitchRow(
    refreshTick: Int,
    title: String,
    subtitle: String?,
    key: String,
    def: Boolean,
    leading: ImageVector,
) {
    var checked by remember(refreshTick) { mutableStateOf(Core.prefs.getBoolean(key, def)) }
    SwitchRow(title = title, subtitle = subtitle, checked = checked, leading = leading) {
        checked = it
        Core.prefs.putBoolean(key, it)
    }
}

private const val MIN_BRIGHTNESS = 0.05f
private val BRIGHTNESS_ICON_SIZE = 20.dp
private val BRIGHTNESS_TOGGLE_GAP = 8.dp

// The Lucent slider reserves its own room for a bigger thumb, so it needs no spacer of its own.
private val BRIGHTNESS_TOGGLE_GAP_LUCENT = 0.dp

@Composable
private fun BrightnessRow() {
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.BrightnessMedium) }) {
        Text(stringResource(R.string.brightness), style = MaterialTheme.typography.titleMedium)
        val brightness by rememberPref(PrefKeys.BRIGHTNESS) {
            it.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)
        }
        val auto by rememberPref(PrefKeys.AUTO_BRIGHTNESS) {
            it.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoBrightnessToggle(auto)
            Spacer(
                Modifier.width(
                    if (MaterialTheme.lucent) BRIGHTNESS_TOGGLE_GAP_LUCENT else BRIGHTNESS_TOGGLE_GAP,
                ),
            )
            GlyphSlider(
                value = brightness,
                onValueChange = {
                    if (auto) Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, false)
                    Core.prefs.putFloat(PrefKeys.BRIGHTNESS, it.coerceIn(MIN_BRIGHTNESS, 1f))
                },
                valueRange = MIN_BRIGHTNESS..1f,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AutoBrightnessToggle(auto: Boolean) {
    NoRipple {
        FilledIconToggleButton(
            colors = fullContrastToggleColors(),
            checked = auto,
            onCheckedChange = { on -> Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, on) },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.offStateOutline(auto),
        ) {
            Icon(
                Icons.Outlined.BrightnessAuto,
                contentDescription = stringResource(
                    if (auto) R.string.auto_brightness_on else R.string.auto_brightness_off,
                ),
                modifier = Modifier.size(BRIGHTNESS_ICON_SIZE),
            )
        }
    }
}

@Composable
private fun CreatorNameRow() {
    var creator by remember {
        mutableStateOf(Core.prefs.getString(PrefKeys.CREATOR_NAME, PrefKeys.CREATOR_NAME_DEF))
    }
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Person) }) {
        Text(stringResource(R.string.pref_creator_name), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_creator_name_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = creator,
            onValueChange = {
                creator = it.replace('\n', ' ').take(DesignCodec.MAX_AUTHOR_LENGTH)
                Core.prefs.putString(PrefKeys.CREATOR_NAME, creator)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text(stringResource(R.string.pref_creator_name_hint)) },
            singleLine = true,
        )
    }
}

private enum class TutorialTopic { KEY, AMBIENT, HANDOVER, PIPELINE }

@Composable
internal fun TutorialTab(innerPadding: PaddingValues, scrollState: ScrollState) {
    val context = LocalContext.current
    var topic by remember { mutableStateOf<TutorialTopic?>(null) }
    var showAmbientSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        HintText(stringResource(R.string.tut_hint))
        SectionCard {
            item {
                SetupRow(
                    title = stringResource(R.string.tut_title),
                    subtitle = stringResource(R.string.tut_button_subtitle),
                    good = null,
                ) { topic = TutorialTopic.KEY }
            }
            item {
                SetupRow(
                    title = stringResource(R.string.tut_ambient_title),
                    subtitle = stringResource(R.string.tut_ambient_subtitle),
                    good = null,
                ) { topic = TutorialTopic.AMBIENT }
            }
            item {
                SetupRow(
                    title = stringResource(R.string.tut_create_title),
                    subtitle = stringResource(R.string.tut_create_subtitle),
                    good = null,
                ) { context.startActivity(DesignDemoActivity.intent(context)) }
            }
            item {
                SetupRow(
                    title = stringResource(R.string.tut_handover_title),
                    subtitle = stringResource(R.string.tut_handover_subtitle),
                    good = null,
                ) { topic = TutorialTopic.HANDOVER }
            }
            restrictedSettingsTutorialItem()
            item {
                SetupRow(
                    title = stringResource(R.string.pipeline_tutorial_title),
                    subtitle = stringResource(R.string.pipeline_tutorial_subtitle),
                    good = null,
                ) { topic = TutorialTopic.PIPELINE }
            }
        }
        Spacer(Modifier.height(innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE))
    }

    when (topic) {
        TutorialTopic.KEY -> KeyTutorialDialog(onDismiss = { topic = null })
        TutorialTopic.AMBIENT -> AmbientTutorialDialog(
            onDismiss = { topic = null },
            onOpenAmbientSettings = {
                topic = null
                showAmbientSettings = true
            },
        )
        TutorialTopic.HANDOVER -> HandoverTutorialDialog(onDismiss = { topic = null })
        TutorialTopic.PIPELINE -> PipelineTutorialDialog(onDismiss = { topic = null }, onChoose = { chapter ->
            topic = null
            context.startActivity(PipelineTutorialActivity.intent(context, chapter))
        })
        null -> {}
    }
    if (showAmbientSettings) {
        ScreenSettingsDialog("ambient") { showAmbientSettings = false }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = SECTION_HEADER_START, top = 24.dp, bottom = 8.dp),
    )
}

private val SECTION_HEADER_START = 24.dp

@Composable
private fun CollapsibleSectionHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "sectionHeaderChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = SECTION_HEADER_START, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.section_collapse else R.string.section_expand,
            ),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

internal enum class SectionItemPosition { ONLY, FIRST, MIDDLE, LAST }

internal fun sectionItemPosition(index: Int, count: Int): SectionItemPosition = when {
    count <= 1 -> SectionItemPosition.ONLY
    index <= 0 -> SectionItemPosition.FIRST
    index >= count - 1 -> SectionItemPosition.LAST
    else -> SectionItemPosition.MIDDLE
}

// Measured off Nothing OS's own Settings and Gallery, at 1.25 px per dp. The gap between
// two cards of a group is bare page background, which is why there are no dividers.
private val SECTION_OUTER_CORNER = 16.dp
private val SECTION_INNER_CORNER = 3.dp
private val SECTION_ITEM_GAP = 2.dp

// Measured off Nothing OS 5 at 1.96 px/dp: 43 px outer corner, 8 px inner, 4 px between rows,
// 35 px from the screen edge. The inner corner is far tighter than Material groups them.
private val LUCENT_SECTION_OUTER_CORNER = 22.dp
private val LUCENT_SECTION_INNER_CORNER = 4.dp
private val LUCENT_SECTION_ITEM_GAP = 2.dp
private val LUCENT_SECTION_HORIZONTAL_MARGIN = 18.dp
private val SECTION_HORIZONTAL_MARGIN = 16.dp

@Composable
private fun SectionItemPosition.shape(): RoundedCornerShape {
    val lucent = MaterialTheme.lucent
    val outer = if (lucent) LUCENT_SECTION_OUTER_CORNER else SECTION_OUTER_CORNER
    val inner = if (lucent) LUCENT_SECTION_INNER_CORNER else SECTION_INNER_CORNER
    return when (this) {
        SectionItemPosition.ONLY -> RoundedCornerShape(outer)
        SectionItemPosition.FIRST -> RoundedCornerShape(
            topStart = outer,
            topEnd = outer,
            bottomStart = inner,
            bottomEnd = inner,
        )
        SectionItemPosition.MIDDLE -> RoundedCornerShape(inner)
        SectionItemPosition.LAST -> RoundedCornerShape(
            topStart = inner,
            topEnd = inner,
            bottomStart = outer,
            bottomEnd = outer,
        )
    }
}

internal class SectionCardScope internal constructor() {
    private val entries = mutableListOf<@Composable () -> Unit>()

    internal val items: List<@Composable () -> Unit> get() = entries

    fun item(content: @Composable () -> Unit) {
        entries += content
    }
}

@Composable
internal fun SectionCard(content: SectionCardScope.() -> Unit) {
    val items = SectionCardScope().apply(content).items
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = if (MaterialTheme.lucent) {
                LUCENT_SECTION_HORIZONTAL_MARGIN
            } else {
                SECTION_HORIZONTAL_MARGIN
            },
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (MaterialTheme.lucent) LUCENT_SECTION_ITEM_GAP else SECTION_ITEM_GAP,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(index) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = sectionItemPosition(index, items.size).shape(),
                ) {
                    item()
                }
            }
        }
    }
}

@Composable
internal fun selectedRowColors(): ListItemColors = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedLeadingContentColor = MaterialTheme.colorScheme.onSurface,
    selectedTrailingContentColor = MaterialTheme.colorScheme.onSurface,
    selectedOverlineContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
internal fun NoRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
}

@Composable
internal fun DefaultRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(), content = content)
}

@Composable
internal fun Modifier.offStateOutline(checked: Boolean): Modifier {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TOGGLE_OUTLINE_ALPHA)
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offStateOutline",
    )
    return size(TOGGLE_CONTAINER_SIZE).drawWithContent {
        drawContent()
        val checkedFraction = progress.value.coerceIn(0f, 1f)
        if (checkedFraction >= 1f) return@drawWithContent
        val stroke = TOGGLE_OUTLINE_WIDTH.toPx()
        // Surface appends minimumInteractiveComponentSize() after this modifier, so these
        // bounds are the 48 dp touch target. Centre the container inside them.
        val containerSide = TOGGLE_CONTAINER_SIZE.toPx().coerceAtMost(size.minDimension)
        val containerLeft = (size.width - containerSide) / 2f
        val containerTop = (size.height - containerSide) / 2f
        val corner = lerp(containerSide / 2f, TOGGLE_CHECKED_CORNER.toPx(), checkedFraction)
        drawRoundRect(
            color = ink.copy(alpha = ink.alpha * (1f - checkedFraction)),
            topLeft = Offset(containerLeft + stroke / 2f, containerTop + stroke / 2f),
            size = Size(containerSide - stroke, containerSide - stroke),
            cornerRadius = CornerRadius((corner - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}

private val TOGGLE_OUTLINE_WIDTH = 1.dp

private const val TOGGLE_OUTLINE_ALPHA = 0.75f

private val TOGGLE_CHECKED_CORNER = 12.dp

internal val TOGGLE_CONTAINER_SIZE = 36.dp

internal val DIALOG_VERTICAL_MARGIN = 40.dp

// The platform dialog width leaves a toy's options crammed into the middle of the screen, so
// these size themselves off the window instead and keep only a margin at the edges.
private val DIALOG_HORIZONTAL_MARGIN = 20.dp

@Composable
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

// Material3's own ListItem metrics, restated because [PrefRow] cannot use ListItem:
// ListItem top-aligns its leading slot on a three-line row and nothing overrides that.
internal enum class PrefRowLines { ONE, TWO, THREE }

internal val PrefRowLines.verticalPadding: Dp
    get() = if (this == PrefRowLines.THREE) 12.dp else 8.dp

internal val PrefRowLines.minHeight: Dp
    get() = when (this) {
        PrefRowLines.ONE -> 56.dp
        PrefRowLines.TWO -> 72.dp
        PrefRowLines.THREE -> 88.dp
    }

private val PREF_ROW_PADDING = 16.dp

internal val PREF_ROW_ICON_SIZE = 24.dp

@Composable
internal fun PrefRow(
    lines: PrefRowLines,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = lines.minHeight)
            .padding(horizontal = PREF_ROW_PADDING, vertical = lines.verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                content = leading,
            )
            Spacer(Modifier.width(PREF_ROW_PADDING))
        }
        Column(Modifier.weight(1f), content = content)
        if (trailing != null) {
            Spacer(Modifier.width(PREF_ROW_PADDING))
            trailing()
        }
    }
}

@Composable
internal fun PrefIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(PREF_ROW_ICON_SIZE))
}

@Composable
private fun rowSubtitleColor(good: Boolean?, label: String): Color {
    val color by animateColorAsState(
        targetValue = when (good) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = label,
    )
    return color
}

@Composable
internal fun SetupRow(
    title: String,
    subtitle: String,
    good: Boolean?,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
    val subtitleColor = rowSubtitleColor(good, "setupRowSubtitleTint")
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = if (subtitleLines > 1) PrefRowLines.THREE else PrefRowLines.TWO,
        leading = leading?.let { { PrefIcon(it) } },
        onClick = onClick,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        AnimatedContent(
            targetState = subtitle,
            transitionSpec = {
                (fadeIn(fade) togetherWith fadeOut(fade))
                    .using(SizeTransform(clip = false) { _, _ -> resize })
            },
            label = "setupRowSubtitle",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                onTextLayout = { subtitleLines = it.lineCount },
            )
        }
    }
}

private const val CHECKLIST_MARK_SCALE = 0.6f

@Composable
private fun ChecklistRow(title: String, subtitle: String, good: Boolean?, onClick: () -> Unit) {
    val subtitleColor = rowSubtitleColor(good, "checklistRowSubtitleTint")
    val iconFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val iconScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = if (subtitleLines > 1) PrefRowLines.THREE else PrefRowLines.TWO,
        leading = {
            AnimatedContent(
                targetState = good == true,
                transitionSpec = {
                    (fadeIn(iconFade) + scaleIn(iconScale, initialScale = CHECKLIST_MARK_SCALE)) togetherWith
                        (fadeOut(iconFade) + scaleOut(iconScale, targetScale = CHECKLIST_MARK_SCALE))
                },
                label = "checklistRowMark",
            ) { ok ->
                Icon(
                    if (ok) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(PREF_ROW_ICON_SIZE),
                )
            }
        },
        onClick = onClick,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = subtitleColor,
            onTextLayout = { subtitleLines = it.lineCount },
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    permissions: Array<String>,
    granted: Boolean,
    onRequest: (Array<String>) -> Unit,
) {
    ChecklistRow(
        title = title,
        subtitle = stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
        good = granted,
    ) { onRequest(permissions) }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    leading: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = when {
            subtitle == null -> PrefRowLines.ONE
            subtitleLines > 1 -> PrefRowLines.THREE
            else -> PrefRowLines.TWO
        },
        leading = leading?.let { { PrefIcon(it) } },
        trailing = { NoRipple { GlyphSwitch(checked = checked, onCheckedChange = onChange) } },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { subtitleLines = it.lineCount },
            )
        }
    }
}

@Composable
private fun UntestedDeviceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = dialogSurface(),
        onDismissRequest = onDismiss,
        confirmButton = {
            DialogBackdropBlur()
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.untested_dismiss)) }
        },
        title = { Text(stringResource(R.string.untested_title)) },
        text = {
            Text(
                stringResource(
                    R.string.untested_body,
                    Build.MODEL ?: "unknown",
                    Core.glyphLink.matrixLength,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

// Keep the disclosure. This is the only screen a Play reviewer sees when they sideload the
// APK onto a phone with no Glyph Matrix, and it is where the AccessibilityService is declared.
@Composable
private fun UnsupportedDeviceScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                // The activity is edge to edge, so without this the title sits under the clock and
                // the disclosure runs off under the navigation bar with nothing left to scroll.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        ) {
            Text(
                stringResource(R.string.unsupported_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.unsupported_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            AccessibilityDisclosureText()
        }
    }
}

@Composable
internal fun ScreenSettingsDialog(id: String, onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = dialogSurface(),
        modifier = Modifier
            .padding(horizontal = DIALOG_HORIZONTAL_MARGIN, vertical = DIALOG_VERTICAL_MARGIN)
            .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        confirmButton = {
            DialogBackdropBlur()
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = { Text(stringResource(SCREEN_DISPLAY_NAMES[id] ?: R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (id) {
                    "clock" -> ClockSettings()
                    "dice" -> DiceSettings()
                    "coin" -> CoinSettings()
                    "battery" -> BatterySettings()
                    "breathing" -> BreathingSettings()
                    "timer" -> TimerSettings()
                    "visualizer" -> VisualizerSettings()
                    "ambient" -> AmbientSettings()
                    "custom" -> CustomDesignSettings()
                    "notifications" -> NotificationsSettings()
                    "weather" -> WeatherSettings()
                }
                if (id in SCREEN_DISPLAY_NAMES && id != "ambient") PipelineToySettings(id)
                if (id.startsWith("pipeline_")) {
                    val context = LocalContext.current
                    TextButton(onClick = { context.startActivity(PipelineEditorActivity.intent(context, id.removePrefix("pipeline_"))) }) { Text(stringResource(R.string.pipeline_editor_open_pipeline_builder)) }
                }
            }
        },
    )
}

@Composable
private fun ChoiceGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = modifier)
}

@Composable
private fun ClockSettings() {
    IntChoiceGroup(
        optionsInStoredOrder = listOf(
            "Plain digits",
            "Digits + battery bar",
            "Digits + battery ring",
            "Analog",
        ),
        key = PrefKeys.CLOCK_THEME,
        def = PrefKeys.CLOCK_THEME_DEF,
    )
}

@Composable
private fun DiceSettings() {
    StringChoiceGroup(
        options = listOf("D4", "D6", "D8", "D12", "D20"),
        key = PrefKeys.SELECTED_DICE,
        def = PrefKeys.SELECTED_DICE_DEF,
    )
}

@Composable
private fun CoinSettings() {
    ChoiceGroupLabel(stringResource(R.string.pref_coin_design))
    IntChoiceGroup(
        optionsInStoredOrder = listOf("Letters (H/T)", "Portrait & numeral"),
        key = PrefKeys.COIN_DESIGN,
        def = PrefKeys.COIN_DESIGN_DEF,
    )
}

@Composable
private fun BatterySettings() {
    PrefSwitch(
        stringResource(R.string.pref_battery_watts),
        PrefKeys.BATTERY_SHOW_WATTS,
        PrefKeys.BATTERY_SHOW_WATTS_DEF,
    )
}

@Composable
private fun BreathingSettings() {
    ChoiceGroupLabel(stringResource(R.string.pref_breathing_pace))
    StringChoiceGroup(
        options = listOf("2", "3", "4", "6", "8"),
        key = PrefKeys.BREATHING_PACE,
        def = PrefKeys.BREATHING_PACE_DEF,
    )
}

@Composable
private fun TimerSettings() {
    ChoiceGroupLabel(stringResource(R.string.pref_timer_duration))
    IntValueChoiceGroup(
        storedValues = PrefKeys.TIMER_DURATION_OPTIONS,
        labels = listOf("1 min", "3 min", "5 min", "7 min", "10 min", "13 min"),
        key = PrefKeys.TIMER_DURATION,
        def = PrefKeys.TIMER_DURATION_DEF,
    )
}

@Composable
private fun VisualizerSettings() {
    ChoiceGroupLabel(stringResource(R.string.pref_visualizer_theme))
    IntChoiceGroup(
        optionsInStoredOrder = listOf("Bars", "Mirrored bars", "Palette"),
        key = PrefKeys.VISUALIZER_THEME,
        def = PrefKeys.VISUALIZER_THEME_DEF,
    )
    ChoiceGroupLabel(
        stringResource(R.string.pref_visualizer_tuning),
        Modifier.padding(top = 12.dp),
    )
    IntValueChoiceGroup(
        storedValues = (1..6).toList(),
        labels = listOf("1 — calmest", "2", "3", "4", "5", "6 — snappiest"),
        key = PrefKeys.VISUALIZER_TUNING,
        def = PrefKeys.VISUALIZER_TUNING_DEF,
    )
}

@Composable
private fun CustomDesignSettings() {
    val designs = remember { Core.designStore.list() }
    if (designs.isEmpty()) {
        Text(
            stringResource(R.string.pref_custom_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    ChoiceGroupLabel(stringResource(R.string.pref_custom_design))
    val unnamed = stringResource(R.string.pref_custom_unnamed)
    var selected by remember {
        mutableStateOf(
            Core.prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
                .takeIf { id -> designs.any { it.id == id } }
                ?: designs.first().id,
        )
    }
    designs.forEach { design ->
        ChoiceRow(design.name.ifBlank { unnamed }, selected == design.id) {
            selected = design.id
            Core.prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, design.id)
        }
    }
}

@Composable
private fun AmbientSettings() { AmbientPipelineSettings() }

@Composable
private fun IntChoiceGroup(optionsInStoredOrder: List<String>, key: String, def: Int) {
    var selected by remember(key) { mutableIntStateOf(Core.prefs.getInt(key, def)) }
    Column {
        optionsInStoredOrder.forEachIndexed { storedValue, label ->
            ChoiceRow(label, selected == storedValue) {
                selected = storedValue
                Core.prefs.putInt(key, storedValue)
            }
        }
    }
}

@Composable
private fun IntValueChoiceGroup(
    storedValues: List<Int>,
    labels: List<String>,
    key: String,
    def: Int,
) {
    var selected by remember(key) { mutableIntStateOf(Core.prefs.getInt(key, def)) }
    Column {
        storedValues.forEachIndexed { i, value ->
            ChoiceRow(labels[i], selected == value) {
                selected = value
                Core.prefs.putInt(key, value)
            }
        }
    }
}

@Composable
private fun StringChoiceGroup(options: List<String>, key: String, def: String) {
    var selected by remember(key) { mutableStateOf(Core.prefs.getString(key, def)) }
    Column {
        options.forEach { value ->
            ChoiceRow(value, selected == value) {
                selected = value
                Core.prefs.putString(key, value)
            }
        }
    }
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    NoRipple {
        ListItem(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { GlyphRadioButton(selected = selected, onClick = null) },
            colors = selectedRowColors(),
            contentPadding = CHOICE_ROW_PADDING,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val CHOICE_ROW_PADDING = PaddingValues(horizontal = 0.dp, vertical = 2.dp)

private val PREF_SWITCH_PADDING = PaddingValues(horizontal = 0.dp, vertical = 4.dp)

@Composable
private fun PrefSwitch(title: String, key: String, def: Boolean) {
    var checked by remember(key) { mutableStateOf(Core.prefs.getBoolean(key, def)) }
    ListItem(
        colors = fullContrastListItemColors(),
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            NoRipple {
                GlyphSwitch(checked = checked, onCheckedChange = {
                    checked = it
                    Core.prefs.putBoolean(key, it)
                })
            }
        },
        contentPadding = PREF_SWITCH_PADDING,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}
