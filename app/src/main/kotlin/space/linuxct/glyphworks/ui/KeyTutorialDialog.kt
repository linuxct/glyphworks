package space.linuxct.glyphworks.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.design.Camera
import space.linuxct.glyphworks.ui.design.DeviceBack
import space.linuxct.glyphworks.ui.design.drawDeviceBack
import space.linuxct.glyphworks.ui.design.drawMatrix

@Composable
fun KeyTutorialDialog(onDismiss: () -> Unit) {
    MotionDialog(onDismiss) { dismiss ->
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(stringResource(R.string.tut_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                KeyTutorialContent()
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

private const val DIALOG_ENTER_SCALE = 0.85f

@Composable
internal fun MotionDialog(
    onDismiss: () -> Unit,
    fullScreen: Boolean = false,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) onDismiss()
    }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Dialog(
        onDismissRequest = { visible.targetState = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = !fullScreen,
            decorFitsSystemWindows = !fullScreen,
        ),
    ) {
        if (fullScreen) MatchDialogWindowSystemBarIconsToTheme()
        AnimatedVisibility(
            visibleState = visible,
            modifier = if (fullScreen) Modifier else Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
            enter = fadeIn(fade) + scaleIn(scale, initialScale = DIALOG_ENTER_SCALE),
            exit = fadeOut(fade) + scaleOut(scale, targetScale = DIALOG_ENTER_SCALE),
            label = "dialogMotion",
        ) {
            content { visible.targetState = false }
        }
    }
}

@Composable
private fun MatchDialogWindowSystemBarIconsToTheme() {
    val dark = isSystemInDarkTheme()
    val dialogView = LocalView.current
    DisposableEffect(dialogView, dark) {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { dialogWindow ->
            WindowCompat.getInsetsController(dialogWindow, dialogView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
}

@Composable
internal fun dialogCardWidth(): Dp {
    val context = LocalContext.current
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    val preferred = remember(context) { platformDialogWidth(context) }
    return dialogCardWidth(preferred, windowWidth)
}

internal fun dialogCardWidth(preferred: Dp, available: Dp): Dp {
    val boundedByMd3 = preferred.coerceIn(DIALOG_MIN_WIDTH, DIALOG_MAX_WIDTH)
    val windowNotMeasuredYet = !available.isSpecified || available <= 0.dp
    if (windowNotMeasuredYet) return boundedByMd3
    return boundedByMd3.coerceAtMost(
        (available - DIALOG_HORIZONTAL_MARGIN * 2).coerceAtLeast(0.dp),
    )
}

internal val DIALOG_MIN_WIDTH = 280.dp
internal val DIALOG_MAX_WIDTH = 560.dp

private val DIALOG_HORIZONTAL_MARGIN = 24.dp

private const val PLATFORM_DIALOG_WIDTH_RES = "config_prefDialogWidth"

private val FALLBACK_DIALOG_WIDTH = 320.dp

@SuppressLint("DiscouragedApi")
private fun platformDialogWidth(context: Context): Dp {
    val resources = context.resources
    val id = resources.getIdentifier(PLATFORM_DIALOG_WIDTH_RES, "dimen", "android")
    if (id == 0) return FALLBACK_DIALOG_WIDTH
    val px = runCatching { resources.getDimension(id) }.getOrNull() ?: return FALLBACK_DIALOG_WIDTH
    if (px <= 0f) return FALLBACK_DIALOG_WIDTH
    return (px / resources.displayMetrics.density).dp
}

@Composable
fun HandoverTutorialDialog(onDismiss: () -> Unit) {
    TutorialInfoDialog(
        title = stringResource(R.string.tut_handover_title),
        intro = stringResource(R.string.tut_handover_intro),
        steps = listOf(
            stringResource(R.string.tut_handover_step1),
            stringResource(R.string.tut_handover_step2),
        ),
        note = stringResource(R.string.tut_handover_note),
        onDismiss = onDismiss,
    )
}

@Composable
fun TutorialInfoDialog(
    title: String,
    intro: String,
    steps: List<String>,
    note: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    MotionDialog(onDismiss) { dismiss ->
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(
                    intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                steps.forEachIndexed { i, step ->
                    Row {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.inverseSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (i != steps.lastIndex) Spacer(Modifier.height(12.dp))
                }
                note?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

@Composable
private fun KeyTutorialContent(modifier: Modifier = Modifier) {
    var menuMode by remember { mutableStateOf(false) }
    val steps = if (menuMode) MENU_STEPS else CLASSIC_STEPS

    Column(modifier) {
        ModeSwitcher(menuMode = menuMode, onModeChange = { menuMode = it })
        Spacer(Modifier.height(8.dp))
        key(menuMode) {
            val pagerState = rememberPagerState(pageCount = { steps.size })
            HorizontalPager(
                state = pagerState,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
            ) { page ->
                TutorialPage(steps[page])
            }
            Spacer(Modifier.height(6.dp))
            StepDots(count = steps.size, current = pagerState.currentPage)
        }
    }
}

@Composable
private fun ModeSwitcher(menuMode: Boolean, onModeChange: (Boolean) -> Unit) {
    NoRipple {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !menuMode,
                onClick = { onModeChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.onb_mode_regular))
            }
            SegmentedButton(
                selected = menuMode,
                onClick = { onModeChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.onb_mode_menu))
            }
        }
    }
}

private val STEP_DOT_SELECTED_WIDTH = 18.dp
private val STEP_DOT_WIDTH = 7.dp
private const val STEP_DOT_UNSELECTED_ALPHA = 0.2f

@Composable
private fun StepDots(count: Int, current: Int) {
    val base = MaterialTheme.colorScheme.onSurface
    val dotWidthSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val dotColorSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val selected = i == current
            val dotWidth by animateDpAsState(
                targetValue = if (selected) STEP_DOT_SELECTED_WIDTH else STEP_DOT_WIDTH,
                animationSpec = dotWidthSpec,
                label = "stepDotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue =
                    if (selected) base else base.copy(alpha = STEP_DOT_UNSELECTED_ALPHA),
                animationSpec = dotColorSpec,
                label = "stepDotColor",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(STEP_DOT_WIDTH)
                    .width(dotWidth.coerceAtLeast(0.dp))
                    .background(dotColor, CircleShape),
            )
        }
    }
}

internal val ILLUSTRATION_HEIGHT = 205.dp

internal fun tutorialCamera(canvas: Size): Camera {
    val zoom = minOf(canvas.width / TUTORIAL_SPAN_X, canvas.height / TUTORIAL_SPAN_Y)
    if (zoom <= 0f) return Camera(0f, Offset.Zero)
    return Camera(
        zoom = zoom,
        focus = Offset(TUTORIAL_FOCUS_X, canvas.height / (2f * zoom) - TUTORIAL_TOP_MARGIN),
    )
}

internal const val TUTORIAL_BODY_WIDTH = 0.62f

internal const val TUTORIAL_MARKER_X = 0.91f

private const val TUTORIAL_SPAN_X = 1f / TUTORIAL_BODY_WIDTH
private const val TUTORIAL_FOCUS_X = 0.53f
private const val TUTORIAL_TOP_MARGIN = 0.059f
private const val TUTORIAL_BOTTOM_MARGIN = 0.034f
private const val TUTORIAL_SPAN_Y =
    TUTORIAL_TOP_MARGIN + DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT + TUTORIAL_BOTTOM_MARGIN

@Composable
private fun TutorialPage(step: TutorialStep) {
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(step) {
        val t0 = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeMs = ((now - t0) / 1_000_000) % step.durationMs }
        }
    }

    val base = MaterialTheme.colorScheme.onSurface
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(ILLUSTRATION_HEIGHT)
                .clipToBounds(),
        ) {
            drawTutorialPhone(base, step, timeMs)
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(step.titleRes), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(step.bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 88.dp),
        )
    }
}

private class MatrixFrame(val cells: IntArray, val on: Boolean)

private class TutorialStep(
    val titleRes: Int,
    val bodyRes: Int,
    val durationMs: Long,
    val presses: List<Long>,
    val countdown: LongRange? = null,
    val matrix: (Long) -> MatrixFrame,
)

private const val PRESS_MS = 170L

private const val MENU_BLINK_ON_MS = 450L
private const val MENU_BLINK_OFF_MS = 300L

private fun blinkOn(sinceMs: Long) =
    sinceMs % (MENU_BLINK_ON_MS + MENU_BLINK_OFF_MS) < MENU_BLINK_ON_MS

private val CLASSIC_STEPS = listOf(
    TutorialStep(R.string.tut_c1_title, R.string.tut_c1_body, 4200, listOf(600)) { t ->
        when {
            t < 950 -> MatrixFrame(DICE_5, true)
            t < 1850 -> MatrixFrame(ROLL[((t - 950) / 180).toInt() % ROLL.size], true)
            else -> MatrixFrame(DICE_5, true)
        }
    },
    TutorialStep(R.string.tut_c2_title, R.string.tut_c2_body, 5200, listOf(600, 960, 2600, 2960)) { t ->
        MatrixFrame(
            when {
                t < 1400 -> DICE_5
                t < 3400 -> CLOCK_1234
                else -> COMPASS_NORTH
            },
            true,
        )
    },
    TutorialStep(R.string.tut_c3_title, R.string.tut_c3_body, 4400, listOf(600, 960, 1320)) { t ->
        MatrixFrame(if (t < 1800) COMPASS_NORTH else AMBIENT_ANALOG_1008, true)
    },
)

private val MENU_STEPS = listOf(
    TutorialStep(R.string.tut_m1_title, R.string.tut_m1_body, 5600, listOf(600, 960)) { t ->
        if (t < 1400) MatrixFrame(CLOCK_1234, true) else MatrixFrame(CLOCK_1234, blinkOn(t - 1400))
    },
    TutorialStep(R.string.tut_m2_title, R.string.tut_m2_body, 7200, listOf(1800, 3800)) { t ->
        when {
            t < 2100 -> MatrixFrame(CLOCK_1234, blinkOn(t))
            t < 4100 -> MatrixFrame(DICE_5, blinkOn(t - 2100))
            else -> MatrixFrame(COMPASS_NORTH, blinkOn(t - 4100))
        }
    },
    TutorialStep(R.string.tut_m3_title, R.string.tut_m3_body, 5600, listOf(1800, 2160)) { t ->
        if (t < 2600) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(DICE_5, true)
    },
    TutorialStep(
        R.string.tut_m4_title, R.string.tut_m4_body, 7400, emptyList(),
        countdown = 800L..5800L,
    ) { t ->
        if (t < 5800) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(DICE_5, true)
    },
    TutorialStep(R.string.tut_m5_title, R.string.tut_m5_body, 5400, listOf(1400, 1760, 2120)) { t ->
        if (t < 2600) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(AMBIENT_ANALOG_1008, true)
    },
)

private const val TUTORIAL_MATRIX_SIZE = 13

private const val COUNTDOWN_RING_ALPHA = 0.65f
private val COUNTDOWN_RING_GAP = 7.dp
private val COUNTDOWN_RING_STROKE = 3.dp
private const val COUNTDOWN_RING_START_ANGLE = -90f

private const val RIPPLE_MS = 480L
private const val RIPPLE_START_ALPHA = 0.5f
private val RIPPLE_START_RADIUS = 10.dp
private val RIPPLE_GROWTH = 26.dp
private val RIPPLE_STROKE = 2.dp

private const val SEPARATE_GESTURE_GAP_MS = 600L
private const val MARKER_LEAD_IN_MS = 400L
private const val MARKER_LIT_AFTER_PRESS_MS = 90L
private const val MARKER_UNLIT_BEFORE_LOOP_MS = 250L
private const val MARKER_LIT_ALPHA = 0.85f
private const val MARKER_UNLIT_ALPHA = 0.18f
private val MARKER_RADIUS = 3.dp
private val MARKER_SPACING = 12.dp

private fun DrawScope.drawTutorialPhone(base: Color, step: TutorialStep, t: Long) {
    val camera = tutorialCamera(size)
    val keyPressed = step.presses.any { t in it..(it + PRESS_MS) }
    val disc = drawDeviceBack(base, camera, keyPressed = keyPressed)

    val frame = step.matrix(t)
    drawMatrix(
        disc.center,
        disc.radius,
        TUTORIAL_MATRIX_SIZE,
        if (frame.on) frame.cells else BLANK_MATRIX,
    )

    step.countdown?.let { drawCountdownRing(base, disc.center, disc.radius, it, t) }

    val keyCenter = camera.map(
        Offset(
            DeviceBack.KEY_LEFT + DeviceBack.KEY_WIDTH / 2f,
            DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT / 2f,
        ),
        size,
    )
    drawPressRipples(base, keyCenter, step.presses, t)
    drawPressCounter(base, keyCenter, camera.map(Offset(TUTORIAL_MARKER_X, 0f), size).x, step, t)
}

private fun DrawScope.drawCountdownRing(
    base: Color,
    center: Offset,
    radius: Float,
    range: LongRange,
    t: Long,
) {
    if (t < range.first) return
    val span = (range.last - range.first).toFloat()
    val remaining = 1f - ((t - range.first) / span).coerceIn(0f, 1f)
    if (remaining <= 0f) return
    val gap = COUNTDOWN_RING_GAP.toPx()
    drawArc(
        base.copy(alpha = COUNTDOWN_RING_ALPHA),
        startAngle = COUNTDOWN_RING_START_ANGLE,
        sweepAngle = 360f * remaining,
        useCenter = false,
        topLeft = Offset(center.x - radius - gap, center.y - radius - gap),
        size = Size((radius + gap) * 2f, (radius + gap) * 2f),
        style = Stroke(width = COUNTDOWN_RING_STROKE.toPx()),
    )
}

private fun DrawScope.drawPressRipples(
    base: Color,
    keyCenter: Offset,
    presses: List<Long>,
    t: Long,
) {
    presses.forEach { press ->
        val sincePress = t - press
        if (sincePress !in 0..RIPPLE_MS) return@forEach
        val progress = sincePress / RIPPLE_MS.toFloat()
        drawCircle(
            base.copy(alpha = (1f - progress) * RIPPLE_START_ALPHA),
            radius = RIPPLE_START_RADIUS.toPx() + RIPPLE_GROWTH.toPx() * progress,
            center = keyCenter,
            style = Stroke(width = RIPPLE_STROKE.toPx()),
        )
    }
}

private fun DrawScope.drawPressCounter(
    base: Color,
    keyCenter: Offset,
    markerX: Float,
    step: TutorialStep,
    t: Long,
) {
    val bursts = pressBursts(step.presses)
    val burst = bursts.lastOrNull { t >= it.first() - MARKER_LEAD_IN_MS } ?: bursts.firstOrNull()
    burst?.forEachIndexed { i, press ->
        val lit = t >= press + MARKER_LIT_AFTER_PRESS_MS &&
            t <= step.durationMs - MARKER_UNLIT_BEFORE_LOOP_MS
        drawCircle(
            base.copy(alpha = if (lit) MARKER_LIT_ALPHA else MARKER_UNLIT_ALPHA),
            radius = MARKER_RADIUS.toPx(),
            center = Offset(
                markerX,
                keyCenter.y + (i - (burst.size - 1) / 2f) * MARKER_SPACING.toPx(),
            ),
        )
    }
}

private fun pressBursts(presses: List<Long>): List<List<Long>> {
    val bursts = mutableListOf<MutableList<Long>>()
    presses.forEach { press ->
        val startsNewBurst =
            bursts.isEmpty() || press - bursts.last().last() > SEPARATE_GESTURE_GAP_MS
        if (startsNewBurst) bursts += mutableListOf(press) else bursts.last() += press
    }
    return bursts
}

//
// Charset: '#' full, '+' mid, ':' dim, anything else off. Rows must be 13 characters.
// The goldens in app/src/test/resources/goldens/ write off as ' ' and dim as '.', so
// transcribe them with a script, not by eye.

private val BLANK_MATRIX = IntArray(TUTORIAL_MATRIX_SIZE * TUTORIAL_MATRIX_SIZE)

private const val LEVEL_FULL = 4095
private const val LEVEL_MID = 2252
private const val LEVEL_DIM = 1024
private const val LEVEL_OFF = 0

private fun charsetFrame(rows: List<String>): IntArray {
    val size = rows.size
    val out = IntArray(size * size)
    for (r in 0 until size) {
        val row = rows[r]
        for (c in 0 until size) {
            out[r * size + c] = when (row[c]) {
                '#' -> LEVEL_FULL
                '+' -> LEVEL_MID
                ':' -> LEVEL_DIM
                else -> LEVEL_OFF
            }
        }
    }
    return out
}

private val DICE_2 = charsetFrame(
    listOf(
        ".............",
        ".............",
        "..##.........",
        "..##.........",
        ".............",
        ".............",
        ".............",
        ".............",
        ".............",
        ".........##..",
        ".........##..",
        ".............",
        ".............",
    ),
)

private val DICE_3 = charsetFrame(
    listOf(
        ".............",
        ".............",
        "..##.........",
        "..##.........",
        ".............",
        ".....##......",
        ".....##......",
        ".............",
        ".............",
        ".........##..",
        ".........##..",
        ".............",
        ".............",
    ),
)

private val DICE_5 = charsetFrame(
    listOf(
        ".............",
        ".............",
        "..##.....##..",
        "..##.....##..",
        ".............",
        ".....##......",
        ".....##......",
        ".............",
        ".............",
        "..##.....##..",
        "..##.....##..",
        ".............",
        ".............",
    ),
)

private val DICE_6 = charsetFrame(
    listOf(
        ".............",
        ".............",
        "..##.....##..",
        "..##.....##..",
        ".............",
        "..##.....##..",
        "..##.....##..",
        ".............",
        ".............",
        "..##.....##..",
        "..##.....##..",
        ".............",
        ".............",
    ),
)

private val ROLL = listOf(DICE_3, DICE_6, DICE_2, DICE_6, DICE_3)

private val COMPASS_NORTH = charsetFrame(
    listOf(
        "......#......",
        "......#......",
        "..:...#...:..",
        "......#......",
        "......#......",
        "......#......",
        "+.....+.....+",
        "......:......",
        "......:......",
        "......:......",
        "..:.......:..",
        ".............",
        "......+......",
    ),
)

private val CLOCK_1234 = charsetFrame(
    listOf(
        ".............",
        "....#..###...",
        "...##....#...",
        "....#..###...",
        "....#..#.....",
        "...###.###...",
        ".............",
        "...###.#.#...",
        ".....#.#.#...",
        "....##.###...",
        ".....#...#...",
        "...###...#...",
        ".............",
    ),
)

private val AMBIENT_ANALOG_1008 = charsetFrame(
    listOf(
        "....:::::....",
        "..::.....::..",
        ".:........+:.",
        ".:#......+.:.",
        ":..##...+...:",
        ":....#.+....:",
        ":.....#.....:",
        ":...........:",
        ":...........:",
        ".:.........:.",
        ".:.........:.",
        "..::.....::..",
        "....:::::....",
    ),
)
