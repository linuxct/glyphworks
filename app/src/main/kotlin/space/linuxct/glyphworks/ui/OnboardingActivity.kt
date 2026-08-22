package space.linuxct.glyphworks.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.ui.theme.fullContrastListItemColors
import space.linuxct.glyphworks.ui.theme.fullContrastToggleColors
import space.linuxct.glyphworks.ui.theme.fullContrastTopAppBarColors
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        setContent {
            GlyphWorksTheme {
                OnboardingFlow(
                    onFinished = { completeOnboarding() },
                    onStartDrawing = { completeOnboarding(MainActivity.createTabIntent(this)) },
                )
            }
        }
    }

    private fun completeOnboarding(destination: Intent = Intent(this, MainActivity::class.java)) {
        Core.prefs.putBoolean(PrefKeys.ONBOARDING_DONE, true)
        startActivity(destination)
        finish()
    }
}

private enum class Page { KEY, TOY, PERMS, MODE, CREATE, DONE }

@Composable
private fun OnboardingFlow(onFinished: () -> Unit, onStartDrawing: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val a11yOn = remember(refreshTick) { isEssentialKeyServiceEnabled(context) }

    val pages = if (a11yOn) {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.MODE, Page.CREATE, Page.DONE)
    } else {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.CREATE, Page.DONE)
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var keyArtMorphed by rememberSaveable { mutableStateOf(false) }

    var introDone by rememberSaveable { mutableStateOf(false) }
    var introTravelling by remember { mutableStateOf(introDone) }
    LaunchedEffect(Unit) {
        if (!introDone) {
            delay(ART_INTRO_REVEAL_MS.toLong() + INTRO_HOLD_MS)
            introTravelling = true
            keyArtMorphed = true
            delay(INTRO_STAGGER_MS)
            introDone = true
        }
    }
    val skipIntro = {
        introTravelling = true
        keyArtMorphed = true
        introDone = true
    }
    val introCentred = animateFloatAsState(
        targetValue = if (introTravelling) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "introCentred",
    )
    val introAlpha = animateFloatAsState(
        targetValue = if (introDone) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "introAlpha",
    )

    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        snapAnimationSpec = pageSpec,
                    ),
                ) { i ->
                    when (pages[i]) {
                        Page.KEY -> KeyPage(
                            a11yOn = a11yOn,
                            morphed = keyArtMorphed,
                            artCentred = { introCentred.value },
                            contentAlpha = { introAlpha.value },
                            onMorphed = { keyArtMorphed = true },
                        )
                        Page.TOY -> ToyPage()
                        Page.PERMS -> PermsPage(refreshTick, onRefresh = { refreshTick++ })
                        Page.MODE -> ModePage()
                        Page.CREATE -> CreatePage(onStartDrawing)
                        Page.DONE -> DonePage(refreshTick)
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = introAlpha.value }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (pagerState.currentPage > 0) {
                            TextButton(onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage - 1,
                                        animationSpec = pageSpec,
                                    )
                                }
                            }) {
                                Text(stringResource(R.string.onb_back))
                            }
                        }
                    }
                    PageDots(count = pages.size, current = pagerState.currentPage)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        val last = pagerState.currentPage == pages.lastIndex
                        Button(onClick = {
                            if (last) {
                                onFinished()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage + 1,
                                        animationSpec = pageSpec,
                                    )
                                }
                            }
                        }) {
                            Text(stringResource(if (last) R.string.onb_done else R.string.onb_next))
                        }
                    }
                }
            }

            if (!introDone) SkipIntroOnFirstTouchScrim(onTouch = skipIntro)
        }
    }
}

private val PAGE_DOT_SELECTED_WIDTH = 22.dp
private val PAGE_DOT_WIDTH = 8.dp
private const val PAGE_DOT_UNSELECTED_ALPHA = 0.2f

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dotWidthSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
        val dotColorSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
        val onDot = MaterialTheme.colorScheme.primary
        val offDot = MaterialTheme.colorScheme.onSurface.copy(alpha = PAGE_DOT_UNSELECTED_ALPHA)
        repeat(count) { i ->
            val selected = i == current
            val dotWidth by animateDpAsState(
                targetValue = if (selected) PAGE_DOT_SELECTED_WIDTH else PAGE_DOT_WIDTH,
                animationSpec = dotWidthSpec,
                label = "dotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (selected) onDot else offDot,
                animationSpec = dotColorSpec,
                label = "dotColor",
            )
            Box(
                Modifier
                    .height(PAGE_DOT_WIDTH)
                    .width(dotWidth.coerceAtLeast(0.dp))
                    .background(dotColor, CircleShape),
            )
        }
    }
}

@Composable
private fun SkipIntroOnFirstTouchScrim(onTouch: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    ).consume()
                    onTouch()
                }
            },
    )
}

@Composable
private fun KeyPage(
    a11yOn: Boolean,
    morphed: Boolean,
    artCentred: () -> Float,
    contentAlpha: () -> Float,
    onMorphed: () -> Unit,
) {
    val context = LocalContext.current
    PageScaffold(
        if (morphed) ART_KEY else ART_ICON,
        stringResource(R.string.onb_key_title),
        artCentred = artCentred,
        contentAlpha = contentAlpha,
        revealMillis = if (morphed) ART_REVEAL_MS else ART_INTRO_REVEAL_MS,
    ) {
        BodyText(stringResource(R.string.onb_key_body))
        Spacer(Modifier.height(20.dp))

        var declined by rememberSaveable { mutableStateOf(false) }
        AccessibilityDisclosureCard(
            declined = declined,
            onDecline = { declined = true },
            onAccept = {
                declined = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (a11yOn) R.string.onb_key_status_on else R.string.onb_key_status_off),
            style = MaterialTheme.typography.titleSmall,
            color = if (a11yOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SideloadHelpCard()
    }
}

@Composable
private fun ToyPage() {
    val context = LocalContext.current
    val toys = remember {
        listOf(ART_TOY_COMPASS_NORTH, ART_TOY_CLOCK_1234_RING, ART_TOY_EYES_OPEN, ART_TOY_DICE_5)
    }
    var toy by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ART_CYCLE_HOLD_MS)
            toy = (toy + 1) % toys.size
        }
    }
    PageScaffold(toys[toy], stringResource(R.string.onb_toy_title)) {
        BodyText(stringResource(R.string.onb_toy_body))
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (!openGlyphToySettings(context)) {
                Toast.makeText(context, R.string.glyph_settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.onb_toy_open))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onb_toy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermsPage(refreshTick: Int, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefresh() }

    PageScaffold(ART_LOCK, stringResource(R.string.onb_perms_title)) {
        BodyText(stringResource(R.string.onb_perms_body))
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            PermRow(
                R.string.onb_perm_notif,
                R.string.onb_perm_notif_why,
                granted = remember(refreshTick) { hasAny(context, Manifest.permission.POST_NOTIFICATIONS) },
            ) { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_mic,
                R.string.onb_perm_mic_why,
                granted = remember(refreshTick) { hasAny(context, Manifest.permission.RECORD_AUDIO) },
            ) { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_loc,
                R.string.onb_perm_loc_why,
                granted = remember(refreshTick) {
                    hasAny(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                },
            ) {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_alarm,
                R.string.onb_perm_alarm_why,
                granted = remember(refreshTick) { canExactAlarm(context) },
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ModePage() {
    var menuMode by remember {
        mutableStateOf(Core.prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF))
    }
    var showTutorial by remember { mutableStateOf(false) }
    var showHandover by remember { mutableStateOf(false) }
    fun select(enabled: Boolean) {
        menuMode = enabled
        Core.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, enabled)
    }
    val toggleFrames = remember { (TOGGLE_KNOB_OFF..TOGGLE_KNOB_ON).map { toggleArt(it) } }
    var toggleOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TOGGLE_HOLD_MS)
            toggleOn = !toggleOn
        }
    }
    val throwProgress by animateFloatAsState(
        targetValue = if (toggleOn) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "keyModeToggle",
    )
    val overshootSafeIndex =
        (throwProgress * (toggleFrames.size - 1)).roundToInt().coerceIn(toggleFrames.indices)
    val frame = toggleFrames[overshootSafeIndex]
    PageScaffold(frame, stringResource(R.string.onb_mode_title), revealMillis = SNAP_REVEAL_MS) {
        BodyText(stringResource(R.string.onb_mode_body))
        Spacer(Modifier.height(20.dp))

        HandoverPrerequisiteCard(onShowHandover = { showHandover = true })

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onb_mode_choose),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            selected = !menuMode,
            title = R.string.onb_mode_regular,
            desc = R.string.onb_mode_regular_desc,
        ) { select(false) }
        Spacer(Modifier.height(12.dp))
        ModeCard(
            selected = menuMode,
            title = R.string.onb_mode_menu,
            desc = R.string.onb_mode_menu_desc,
        ) { select(true) }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { showTutorial = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.onb_mode_how))
        }
    }
    if (showTutorial) {
        KeyTutorialDialog(onDismiss = { showTutorial = false })
    }
    if (showHandover) {
        HandoverTutorialDialog(onDismiss = { showHandover = false })
    }
}

@Composable
private fun HandoverPrerequisiteCard(onShowHandover: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.onb_mode_handover_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onb_mode_handover_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onShowHandover) {
                Text(stringResource(R.string.onb_mode_handover_action))
            }
        }
    }
}

@Composable
private fun CreatePage(onStartDrawing: () -> Unit) {
    var art by remember { mutableStateOf(ART_DRAW) }
    LaunchedEffect(Unit) {
        for (next in listOf(ART_FLAME, ART_HI, ART_DRAW)) {
            delay(ART_CYCLE_HOLD_MS)
            art = next
        }
    }
    PageScaffold(art, stringResource(R.string.onb_create_title)) {
        BodyText(stringResource(R.string.onb_create_body))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStartDrawing) {
            Text(stringResource(R.string.onb_create_action))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onb_create_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DonePage(refreshTick: Int) {
    val context = LocalContext.current
    PageScaffold(ART_SMILE, stringResource(R.string.onb_done_title)) {
        BodyText(stringResource(R.string.onb_done_body))
        Spacer(Modifier.height(20.dp))
        val recap = remember(refreshTick) {
            listOf(
                R.string.onb_recap_key to isEssentialKeyServiceEnabled(context),
                R.string.onb_perm_notif to hasAny(context, Manifest.permission.POST_NOTIFICATIONS),
                R.string.onb_perm_mic to hasAny(context, Manifest.permission.RECORD_AUDIO),
                R.string.onb_perm_loc to hasAny(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                R.string.onb_perm_alarm to canExactAlarm(context),
            )
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                recap.forEach { (label, ok) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (ok) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageScaffold(
    art: String,
    title: String,
    artCentred: () -> Float = { 0f },
    contentAlpha: () -> Float = { 1f },
    revealMillis: Int = ART_REVEAL_MS,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var travelPx by remember { mutableFloatStateOf(NOT_MEASURED_YET) }
    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                travelPx = with(density) {
                    ((size.height - ART_SIZE.toPx()) / 2f - ART_TOP_GAP.toPx()).coerceAtLeast(0f)
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(ART_TOP_GAP))
        MatrixArt(
            art,
            Modifier.graphicsLayer {
                val centred = artCentred()
                val measured = !travelPx.isNaN()
                translationY = if (measured) centred * travelPx else 0f
                alpha = if (centred > 0f && !measured) 0f else 1f
            },
            revealMillis = revealMillis,
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha() }) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermRow(title: Int, why: Int, granted: Boolean, onClick: () -> Unit) {
    ListItem(
        colors = fullContrastListItemColors(),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = {
            Text(stringResource(why), style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Text(
                stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
}

private val MODE_CARD_SHAPE = RoundedCornerShape(20.dp)

@Composable
private fun ModeCard(selected: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    NoRipple {
        ListItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            supportingContent = {
                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall)
            },
            shapes = ListItemDefaults.shapes(
                shape = MODE_CARD_SHAPE,
                selectedShape = MODE_CARD_SHAPE,
            ),
            colors = selectedRowColors(),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun hasAny(context: Context, vararg permissions: String): Boolean =
    permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun canExactAlarm(context: Context): Boolean =
    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

private const val NOT_MEASURED_YET = Float.NaN

private const val PIXEL_FRACTION = 0.80f

private val ART_SIZE = 220.dp

private val ART_TOP_GAP = 24.dp

private const val ART_REVEAL_MS = 800

private const val SNAP_REVEAL_MS = 0

private const val ART_GRID = 25
private const val ART_MASK_INSET = 0.2f
private const val PIXEL_CORNER_FRACTION = 0.16f
private const val SHIMMER_PERIOD_MS = 3000
private const val SHIMMER_FLOOR = 0.85f
private const val SHIMMER_DEPTH = 0.15f
private const val SHIMMER_CELL_LAG = 0.6f
private const val UNLIT_ALPHA = 0.08f

private const val TURN_ON_ROW_STEP = 7
private const val TURN_ON_COL_STEP = 13
private const val TURN_ON_STEPS = 29

private fun turnOnThreshold(row: Int, col: Int): Float =
    ((row * TURN_ON_ROW_STEP + col * TURN_ON_COL_STEP) % TURN_ON_STEPS) / TURN_ON_STEPS.toFloat()

@Composable
private fun MatrixArt(
    pattern: String,
    modifier: Modifier = Modifier,
    revealMillis: Int = ART_REVEAL_MS,
) {
    val rows = remember(pattern) { pattern.trim().lines() }
    val cols = remember(pattern) { rows.maxOf { it.length } }

    val snapped = revealMillis <= SNAP_REVEAL_MS
    val reveal = remember(pattern) { Animatable(if (snapped) 1f else 0f) }
    LaunchedEffect(pattern) {
        if (snapped) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = revealMillis))
    }
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing)),
        label = "phase",
    )
    val lit = MaterialTheme.colorScheme.onSurface
    val unlit = MaterialTheme.colorScheme.onSurface.copy(alpha = UNLIT_ALPHA)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ART_SIZE)) {
            val cell = size.minDimension / ART_GRID
            val rowOff = (ART_GRID - rows.size) / 2
            val colOff = (ART_GRID - cols) / 2
            val maskRadius = ART_GRID / 2f - ART_MASK_INSET
            val px = cell * PIXEL_FRACTION
            val pxSize = Size(px, px)
            val pxCorner = CornerRadius(px * PIXEL_CORNER_FRACTION)
            for (r in 0 until ART_GRID) {
                for (c in 0 until ART_GRID) {
                    val dx = c + 0.5f - ART_GRID / 2f
                    val dy = r + 0.5f - ART_GRID / 2f
                    if (dx * dx + dy * dy > maskRadius * maskRadius) continue
                    val on = rows.getOrNull(r - rowOff)?.getOrNull(c - colOff) == '#'
                    val center = Offset((c + 0.5f) * cell, (r + 0.5f) * cell)
                    val topLeft = Offset(center.x - px / 2f, center.y - px / 2f)
                    if (on && reveal.value > turnOnThreshold(r, c)) {
                        val pulse = SHIMMER_FLOOR +
                            SHIMMER_DEPTH * sin(shimmer + (r + c) * SHIMMER_CELL_LAG)
                        drawRoundRect(lit.copy(alpha = pulse), topLeft, pxSize, pxCorner)
                    } else {
                        drawRoundRect(unlit, topLeft, pxSize, pxCorner)
                    }
                }
            }
        }
    }
}

// Patterns are '#' on square cells, so a shape must be 1:1 in dots or it renders
// stretched.

private const val ART_ICON = """
.###....###....###.
#####..#####..#####
#####..#####..#####
#####..#####..#####
.###....###....###.
...................
...................
.###...........###.
#####...###...#####
#####...###...#####
#####...###...#####
.###...........###.
...................
...................
.###....###........
#####..#####...###.
#####..#####...###.
#####..#####...###.
.###....###........
"""

private const val ART_INTRO_REVEAL_MS = 1300

private const val INTRO_HOLD_MS = 300L

private const val INTRO_STAGGER_MS = 200L

private const val ART_KEY = """
..###............
.#...#...........
#.....#..........
#..#..###########
#.....#.....#..#.
.#...#......#..#.
..###............
"""

private const val ART_TOY_COMPASS_NORTH = """
............#............
.........................
.........................
............#............
....#.......#.......#....
............#............
............#............
............#............
............#............
............#............
............#............
............#............
#...........#...........#
............#............
............#............
............#............
............#............
............#............
.........................
.........................
....#...............#....
.........................
.........................
.........................
............#............
"""

private const val ART_TOY_CLOCK_1234_RING = """
............####.........
................##.......
..................##.....
....................#....
.....................#...
......................#..
......................#..
.......................#.
.......................#.
#.......................#
#....#..###...###.#.#...#
#...##....#.#...#.#.#...#
#....#..###....##.###...#
#....#..#...#...#...#...#
#...###.###...###...#...#
#.......................#
.#.....................#.
.#.....................#.
..#...................#..
..#...................#..
...#.................#...
....#...............#....
.....##...........##.....
.......##.......##.......
.........#######.........
"""

private const val ART_TOY_EYES_OPEN = """
.....###.........###.....
....#...#.......#...#....
...#.....#.....#.....#...
...#.....#.....#.....#...
..#.......#...#.......#..
..#..###..#...#..###..#..
..#..###..#...#..###..#..
..#..###..#...#..###..#..
..#.......#...#.......#..
...#.....#.....#.....#...
...#.....#.....#.....#...
....#...#.......#...#....
.....###.........###.....
"""

private const val ART_TOY_DICE_5 = """
.....###.........###.....
.....###.........###.....
.....###.........###.....
.........................
.........................
.........................
...........###...........
...........###...........
...........###...........
.........................
.........................
.........................
.....###.........###.....
.....###.........###.....
.....###.........###.....
"""

private const val ART_LOCK = """
...######...
..#......#..
..#......#..
.##########.
.#........#.
.#........#.
.#...##...#.
.#...##...#.
.#....#...#.
.#....#...#.
.#........#.
.#........#.
.##########.
"""

private fun toggleArt(centre: Int): String {
    val grid = TOGGLE_TRACK.map { it.toCharArray() }
    TOGGLE_KNOB.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            val x = centre - TOGGLE_KNOB_RADIUS + c
            if (ch == '#' && x in grid[0].indices) grid[r + TOGGLE_KNOB_TOP][x] = '#'
        }
    }
    return grid.joinToString("\n") { String(it) }
}

private val TOGGLE_TRACK = listOf(
    "...###############...",
    "..##.............##..",
    ".#.................#.",
    "##.................##",
    "#...................#",
    "#...................#",
    "#...................#",
    "##.................##",
    ".#.................#.",
    "..##.............##..",
    "...###############...",
)

private val TOGGLE_KNOB =
    listOf("..###..", ".#####.", "#######", "#######", "#######", ".#####.", "..###..")

private const val TOGGLE_KNOB_RADIUS = 3
private const val TOGGLE_KNOB_TOP = 2

private const val TOGGLE_KNOB_OFF = 5

private const val TOGGLE_KNOB_ON = 15

private const val TOGGLE_HOLD_MS = 1900L

private const val ART_DRAW = """
..............#..
.............###.
......#.....#####
......#......###.
.....###......#..
.....###.........
....#####........
..#########......
#############....
..#########......
....#####........
.....###......#..
.....###.....###.
......#.....#####
......#......###.
..............#..
"""

private const val ART_CYCLE_HOLD_MS = 2600L

private const val ART_FLAME = """
..........#..........
........###..........
.......####..........
......#####..........
.....######..........
....#######...##.....
...##############....
...###############...
..#################..
..########.########..
.########...########.
.#######.....#######.
.######.......######.
.#####....#....#####.
.#####...###...#####.
..###...#####...###..
..###..#######..###..
..###..#######..###..
...##..#######..##...
....#...#####...#....
.........###.........
"""

private const val ART_HI = """
##..........##
##..........##
##......##..##
##......##..##
##..........##
##..........##
######..##..##
##..##..##..##
##..##..##..##
##..##..##....
##..##..##....
##..##..##..##
##..##..##..##
"""

private const val ART_SMILE = """
....#######....
..##.......##..
.#...........#.
.#...........#.
#...##...##...#
#...##...##...#
#.............#
#.............#
#..#.......#..#
#...#.....#...#
#....#####....#
.#...........#.
.#...........#.
..##.......##..
....#######....
"""
