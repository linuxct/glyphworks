package space.linuxct.glyphworks.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.design.MATRIX_DISC_COLOR
import space.linuxct.glyphworks.ui.design.drawMatrix
import space.linuxct.glyphworks.ui.theme.LUCENT_SURFACE_ALPHA
import space.linuxct.glyphworks.ui.theme.lucent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** UI motion is separate from the transaction that owns the order and stable selection. */
@Stable
internal class ToyDeckState(order: List<String>, initialId: String) {
    private val model = ToyDeckOrder(order, initialId)
    var order by mutableStateOf(model.order)
        private set
    var selectedId by mutableStateOf(model.selectedId)
        private set
    var position by mutableFloatStateOf(model.selectedIndex.toFloat())
        private set
    var heldId by mutableStateOf<String?>(null)
        private set
    var heldOffset by mutableFloatStateOf(0f)
        private set
    var releasedId by mutableStateOf<String?>(null)
        private set
    var releaseOffset by mutableFloatStateOf(0f)
        private set
    private var settle: Job? = null
    private var repeatMove: Job? = null
    private var edge = 0
    private var repeatDirection = 0
    private var swipeStartPosition = position
    val cards: List<String> get() = order + ToyRequest.ID
    val selectedIndex: Int get() = cards.indexOf(selectedId)

    fun beginSwipe() {
        if (heldId == null) {
            settle?.cancel()
            releasedId = null
            // A new touch can interrupt settling: anchor to the visible card, not its target.
            swipeStartPosition = position
            model.select(position.roundToInt())
            selectedId = model.selectedId
        }
    }

    fun swipe(pages: Float) {
        if (heldId != null) return
        position = (position + pages).coerceIn(0f, cards.lastIndex.toFloat())
        model.select(position.roundToInt())
        selectedId = model.selectedId
    }

    fun focus(index: Int, scope: CoroutineScope) {
        if (heldId != null) return
        settle?.cancel()
        releasedId = null
        val target = index.coerceIn(cards.indices)
        // Select now for accessibility; position tracks it during a touch swipe instead.
        model.select(target)
        selectedId = model.selectedId
        settle = scope.launch {
            animate(position, target.toFloat(), animationSpec = spring(
                dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 0.002f,
            )) { value, _ -> position = value.coerceIn(0f, cards.lastIndex.toFloat()) }
        }
    }

    fun finishSwipe(velocity: Float, scope: CoroutineScope) {
        if (heldId == null) {
            focus(toyDeckSnapTarget(position, velocity, cards.size, swipeStartPosition), scope)
        }
    }

    fun beginHold(id: String): Boolean {
        if (abs(position - selectedIndex) > 0.08f || !model.beginReorder(id)) return false
        settle?.cancel()
        releasedId = null
        position = selectedIndex.toFloat()
        heldId = id
        heldOffset = 0f
        edge = 0
        repeatDirection = 0
        return true
    }

    fun dragHeld(delta: Float, stride: Float, scope: CoroutineScope, onStep: () -> Unit) {
        if (heldId == null || stride <= 0f) return
        // A short drag moves exactly one slot. Return toward the center to rearm it;
        // hysteresis prevents hand tremor around the threshold from moving extra slots.
        heldOffset = (heldOffset + delta).coerceIn(-stride * 1.7f, stride * 1.7f)
        val direction = when {
            abs(heldOffset) >= stride * 0.65f -> heldOffset.sign.toInt()
            abs(heldOffset) <= stride * 0.35f -> 0
            else -> edge
        }
        if (direction != edge) {
            edge = direction
            if (direction != 0) moveHeld(direction, onStep)
        }

        // Continuing through the deck needs a deliberate farther reach. Give the user
        // time to release after the first swap, then advance slowly with one haptic per slot.
        val repeat = if (abs(heldOffset) >= stride * 1.25f) heldOffset.sign.toInt() else 0
        if (repeat == repeatDirection) return
        repeatDirection = repeat
        repeatMove?.cancel()
        repeatMove = null
        if (repeat == 0) return
        repeatMove = scope.launch {
            delay(1000)
            while (isActive && heldId != null && repeatDirection == repeat) {
                if (!moveHeld(repeat, onStep)) break
                delay(800)
            }
        }
    }

    private fun moveHeld(direction: Int, onStep: () -> Unit): Boolean {
        if (!model.moveHeld(direction)) return false
        order = model.order
        position = model.selectedIndex.toFloat()
        onStep()
        return true
    }

    fun finishHold(commit: Boolean): List<String>? {
        if (heldId == null) return null
        repeatMove?.cancel()
        repeatMove = null
        val result = model.finishReorder(commit)
        order = model.order
        selectedId = model.selectedId
        position = model.selectedIndex.toFloat()
        heldId = null
        heldOffset = 0f
        edge = 0
        repeatDirection = 0
        return if (commit) result else null
    }

    fun drop(scope: CoroutineScope): List<String>? {
        val id = heldId ?: return null
        val from = heldOffset
        val order = finishHold(true)
        releasedId = id
        releaseOffset = from
        settle = scope.launch {
            try {
                animate(from, 0f, animationSpec = spring<Float>(
                    dampingRatio = 0.85f, stiffness = 650f, visibilityThreshold = 0.5f,
                )) { value, _ -> releaseOffset = value }
            } finally { releasedId = null }
        }
        return order
    }

    fun stop() {
        settle?.cancel()
        finishHold(false)
        releasedId = null
        position = selectedIndex.toFloat()
    }

    fun moveSelected(direction: Int): Boolean {
        if (!model.moveSelected(direction)) return false
        settle?.cancel()
        order = model.order
        position = model.selectedIndex.toFloat()
        return true
    }
}

@Composable
internal fun ToyDeck(
    state: ToyDeckState,
    panelSize: Int,
    thumbnails: Map<String, IntArray>,
    onToggle: (String, Boolean) -> Unit,
    onPersistOrder: (List<String>) -> Unit,
    onGesture: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val velocity = remember { ToyDeckVelocity() }
    var swiping by remember { mutableStateOf(false) }
    val moveBefore = stringResource(R.string.toys_move_earlier)
    val moveAfter = stringResource(R.string.toys_move_later)
    val reportGesture by rememberUpdatedState(onGesture)
    val cardShape = RoundedCornerShape(28.dp)
    BoxWithConstraints(modifier.fillMaxWidth().height(218.dp).clipToBounds()) {
        val cardWidth = (maxWidth * 0.39f).coerceIn(132.dp, 170.dp)
        val stride = with(density) { (cardWidth * 0.66f).toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(state) {
                // Reserve touches in the deck before either horizontal gesture reaches its
                // slop. The enclosing tab pager must not compete for this same swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Grabbing a moving deck stops it immediately, before crossing touch
                    // slop. The next flick is anchored to what the finger actually caught.
                    swiping = false
                    state.beginSwipe()
                    reportGesture(true)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // A tap in the empty deck gutter must not leave an interrupted
                        // animation halfway between cards. Drags finish in their own handler.
                        if (!swiping) state.finishSwipe(0f, scope)
                        reportGesture(false)
                    }
                }
            }.pointerInput(state, stride, sign, state.heldId != null) {
                // Long-press reordering owns movement until release; cancel the competing
                // swipe recognizer without replacing the held card's pointer-input node.
                if (state.heldId != null) return@pointerInput
                detectDragGestures(
                    orientationLock = Orientation.Horizontal,
                    onDragStart = { down, _, _ ->
                        swiping = true
                        velocity.reset(down.uptimeMillis, down.position.x)
                    },
                    onDragEnd = { up ->
                        // Lift-off coordinates can jump as contact with the glass shrinks.
                        // Use sustained drag motion before release, never the UP position.
                        state.finishSwipe(-velocity.atRelease(up.uptimeMillis) * sign / stride, scope)
                        swiping = false
                    },
                    onDragCancel = {
                        state.finishSwipe(0f, scope)
                        swiping = false
                    },
                ) { change, amount ->
                    change.consume()
                    change.historical.forEach { velocity.add(it.uptimeMillis, it.position.x) }
                    velocity.add(change.uptimeMillis, change.position.x)
                    state.swipe(-amount.x * sign / stride)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            state.cards.forEachIndexed { index, id -> key(id) {
                // Only five cards plus the next entering pair are composed.
                // The key must wrap the visibility group, so moving an ID preserves its
                // in-flight gesture instead of disposing it and canceling the reorder.
                if (abs(index - state.position) <= 3.2f || state.heldId == id) {
                    val centered = id == state.selectedId
                    val held = state.heldId == id
                    val request = id == ToyRequest.ID
                    val enabled = if (request) false else {
                        val checked by rememberPref(PrefKeys.screenEnabled(id)) {
                            it.getBoolean(PrefKeys.screenEnabled(id), true)
                        }
                        checked
                    }
                    val name = if (request) stringResource(R.string.toys_request_title) else names[id] ?: stringResource(SCREEN_DISPLAY_NAMES[id] ?: R.string.screen_custom)
                    val status = if (request) null else stringResource(if (enabled) R.string.toys_enabled else R.string.toys_disabled)
                    val movingSlot by animateFloatAsState(
                        targetValue = index - state.position,
                        animationSpec = if (state.heldId != null) spring(stiffness = 550f) else spring(stiffness = 10000f),
                        label = "toyDeckSlot",
                    )
                    val sideBlur = remember(density) {
                        val px = with(density) { 0.5.dp.toPx() }
                        BlurEffect(px, px, TileMode.Decal)
                    }
                    var holdModifier: Modifier = Modifier
                    if (!request && (centered || held)) {
                        holdModifier = Modifier.pointerInput(id, state, stride, sign) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (state.beginHold(id)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { state.drop(scope)?.let(onPersistOrder) },
                                onDragCancel = { state.finishHold(false) },
                            ) { change, amount ->
                                if (state.heldId == id) {
                                    change.consume()
                                    state.dragHeld(amount.x * sign, stride, scope) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        // Surface places its indication inside the rounded clip. An outer
                        // clickable modifier draws the ripple outside that boundary.
                        onClick = { state.focus(index, scope) },
                        modifier = Modifier
                            .width(cardWidth).height(184.dp)
                            .zIndex(if (held || state.releasedId == id) 100f else 10f - abs(index - state.position))
                            .graphicsLayer {
                                val slot = if (state.heldId != null) movingSlot else index - state.position
                                val distance = abs(slot).coerceAtMost(3f)
                                val offset = when {
                                    held -> state.heldOffset
                                    state.releasedId == id -> state.releaseOffset
                                    else -> slot * stride
                                }
                                translationX = offset * sign
                                translationY = if (held) -8.dp.toPx() else distance * 9.dp.toPx()
                                val scale = if (held) 1.045f else 1f - distance * 0.13f
                                scaleX = scale
                                scaleY = scale
                                rotationZ = if (held) (state.heldOffset / stride) * 3f * sign else slot.coerceIn(-2f, 2f) * 3f * sign
                                alpha = if (held) 1f else (1f - distance * 0.24f).coerceAtLeast(0.12f)
                            }
                            // A drawn shadow avoids elevation-layer cutouts behind the
                            // thumbnail on translucent cards, and fits within the deck gutter.
                            .dropShadow(cardShape, Shadow(
                                radius = if (held) 5.dp else 3.dp,
                                color = Color.Black.copy(alpha = if (held) 0.16f else if (centered) 0.10f else 0f),
                                offset = DpOffset(0.dp, 2.dp),
                            ))
                            .then(holdModifier)
                            .semantics {
                                selected = centered
                                if (status != null) stateDescription = status
                                if (centered && !request) customActions = listOf(
                                    CustomAccessibilityAction(moveBefore) {
                                        state.moveSelected(-1).also { if (it) onPersistOrder(state.order) }
                                    },
                                    CustomAccessibilityAction(moveAfter) {
                                        state.moveSelected(1).also { if (it) onPersistOrder(state.order) }
                                    },
                                )
                            },
                        shape = cardShape,
                        color = if (MaterialTheme.lucent) {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = LUCENT_SURFACE_ALPHA)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = when {
                            centered -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
                            MaterialTheme.lucent -> null
                            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.fillMaxSize().padding(top = 26.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val thumbnail = thumbnails[id]
                                Box(Modifier.weight(1f).width(92.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.size(92.dp)
                                        .clip(CircleShape)
                                        .background(MATRIX_DISC_COLOR)
                                        .graphicsLayer {
                                            // Blur the LEDs inside a sharp circular disc. Decal
                                            // samples transparent pixels beyond the layer instead
                                            // of repeating its edge pixels into visible streaks.
                                            renderEffect = if (!centered && abs(index - state.position) >= 0.85f) sideBlur else null
                                        },
                                    ) {
                                        if (thumbnail != null) drawMatrix(center, size.minDimension / 2f, panelSize, thumbnail)
                                    }
                                }
                                Text(
                                    name,
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(24.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Normal,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Side cards can be focused by tap, but only the center exposes a toggle.
                            // This keeps overlapping hit targets from enabling an unseen toy.
                            if (!request && centered && state.heldId == null) {
                                IconToggleButton(
                                    checked = enabled,
                                    onCheckedChange = { onToggle(id, it) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(48.dp),
                                ) {
                                    ToyEnabledMark(enabled, stringResource(
                                        if (enabled) R.string.toys_disable else R.string.toys_enable, name,
                                    ))
                                }
                            } else if (!request) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) { ToyEnabledMark(enabled, null) }
                            }
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun ToyEnabledMark(enabled: Boolean, description: String?) {
    Box(
        Modifier.size(27.dp).background(
            if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            CircleShape,
        ), contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Check, description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
