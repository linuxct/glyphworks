package space.linuxct.glyphworks.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// Measured off Nothing OS 5 at 1.96 px/dp: an 80x54 px track carrying a 47 px thumb.
private val TRACK_W = 40.dp
private val TRACK_H = 28.dp
private val THUMB = 24.dp
private val THUMB_INSET = 2.dp

// Their off track sits at #929292 on a #FCFCFC card, which is onSurface at this much.
private const val OFF_TRACK_ALPHA = 0.45f


private val SLIDER_TRACK_H = 16.dp
private val SLIDER_THUMB = 26.dp
private val SLIDER_THUMB_RING = 1.5.dp
private const val SLIDER_SPARE_ALPHA = 0.26f
private const val SLIDER_TICK_ALPHA = 0.55f

// Measured: 38 px disc with an 11 px hole, and a 2 px hairline when empty, at 1.96 px/dp.
private val RADIO = 20.dp
private val RADIO_RING = 1.dp
private val RADIO_HOLE = 3.dp
private const val RADIO_EMPTY_ALPHA = 0.25f

// The track stands this far clear of the pill inside it, the same on all four sides. Half the
// touch target a segment reserves beyond the pill it draws, so the ends match the top and bottom.
private val SEGMENT_INSET = 4.dp

private const val SEGMENT_DISABLED_ALPHA = 0.24f

private fun <T> nothingSpring() = spring<T>(
    dampingRatio = 0.55f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * Nothing's switch: one pill, one circular thumb that nearly fills it, no checkmark and no
 * thumb-size change between states.
 */
@Composable
internal fun GlyphSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!MaterialTheme.lucent) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val scheme = MaterialTheme.colorScheme
    // Ink, never the accent: measured off Nothing's own switch, the on track is onSurface and
    // the thumb is the card tone itself, opaque so the track cannot show through it.
    val track = if (checked) scheme.onSurface else scheme.onSurface.copy(alpha = OFF_TRACK_ALPHA)
    val thumb = scheme.surface.copy(alpha = 1f)
    val alpha = if (enabled) 1f else 0.38f
    val travel = TRACK_W - THUMB - THUMB_INSET * 2
    val offset = animateDpAsState(if (checked) travel else 0.dp, nothingSpring(), label = "thumb")
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onCheckedChange == null) {
                    Modifier
                } else {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TRACK_W, TRACK_H)
                .clip(CircleShape)
                .background(track.copy(alpha = track.alpha * alpha)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset((THUMB_INSET + offset.value).roundToPx(), 0) }
                    .size(THUMB)
                    .clip(CircleShape)
                    .background(thumb.copy(alpha = alpha)),
            )
        }
    }
}

/** Nothing's radio: a hairline ring unselected, a thick ring with a solid centre when picked. */
@Composable
internal fun GlyphRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!MaterialTheme.lucent) {
        RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    val ink = scheme.onSurface
    val hole = scheme.surface.copy(alpha = 1f)
    val fill by animateFloatAsState(if (selected) 1f else 0f, nothingSpring(), label = "radioFill")
    Box(
        modifier
            .size(RADIO + 12.dp)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(RADIO).drawBehind {
                val middle = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f
                if (fill < 1f) {
                    val stroke = RADIO_RING.toPx()
                    drawCircle(
                        color = ink.copy(alpha = RADIO_EMPTY_ALPHA * alpha * (1f - fill)),
                        radius = outer - stroke / 2f,
                        center = middle,
                        style = Stroke(width = stroke),
                    )
                }
                if (fill > 0f) {
                    // Solid disc with the middle punched out, not a ring around a dot.
                    drawCircle(ink.copy(alpha = alpha * fill), radius = outer, center = middle)
                    drawCircle(hole.copy(alpha = alpha * fill), radius = RADIO_HOLE.toPx() * fill, center = middle)
                }
            },
        )
    }
}

/** Nothing thickens the track and rides it with the same circular thumb the switch uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlyphSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    // Ticks on the empty half of the track only, the way Nothing's volume panel draws them.
    if (!MaterialTheme.lucent) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
            ),
            modifier = modifier,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    // Ink track, surface knob, same as the switch. The spare track has to carry enough grey for
    // a white knob to read against it, or the thumb vanishes on the unfilled side.
    val ink = scheme.onSurface
    val spare = ink.copy(alpha = SLIDER_SPARE_ALPHA)
    val knob = scheme.surface.copy(alpha = 1f)
    val tick = ink.copy(alpha = SLIDER_TICK_ALPHA)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = knob,
            activeTrackColor = ink,
            inactiveTrackColor = spare,
            activeTickColor = Color.Transparent,
            inactiveTickColor = tick,
        ),
        thumb = {
            Box(
                Modifier
                    .size(SLIDER_THUMB)
                    .clip(CircleShape)
                    .background(knob)
                    .border(SLIDER_THUMB_RING, ink.copy(alpha = SLIDER_SPARE_ALPHA), CircleShape),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(SLIDER_TRACK_H),
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                drawStopIndicator = null,
                colors = SliderDefaults.colors(
                    activeTrackColor = ink,
                    inactiveTrackColor = spare,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = tick,
                ),
            )
        },
    )
}

/**
 * Nothing's segmented control is three layers, measured off theirs: a track pill in one surface
 * tone, and the picked option wearing the tone of whatever the control is sitting on, so it
 * reads as a window cut through the track. Unpicked options draw no background at all and dim
 * their label instead.
 *
 * [onCard] is which way round those two tones go. Sat on the page the track is the card tone and
 * the picked option is the page, exactly as theirs; sat on a card or a dialog that would make the
 * track invisible, so the pair swaps and the picked option becomes the card again.
 */
@Composable
internal fun GlyphSegmentedRow(
    selected: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onCard: Boolean = false,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) {
    if (!MaterialTheme.lucent) {
        SingleChoiceSegmentedButtonRow(modifier, content = content)
        return
    }
    // The pill belongs to the track, not to any one option: a segment painting its own would have
    // to appear in the new slot and vanish from the old one in the same frame. Drawn here it is a
    // single rectangle with a position to animate.
    val picked = segmentPicked(onCard)
    val slot by animateFloatAsState(
        targetValue = selected.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat(),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "segmentedPill",
    )
    Box(
        modifier
            .clip(PillShape)
            .background(segmentTrack(onCard))
            // Sides only. A segment draws its pill at the base height but claims the taller touch
            // target, so it already sits [SEGMENT_INSET] clear top and bottom; padding there too
            // would make the gap twice the one at the ends.
            .padding(horizontal = SEGMENT_INSET)
            .drawBehind {
                if (count <= 0) return@drawBehind
                val inset = SEGMENT_INSET.toPx()
                val width = size.width / count
                val height = size.height - inset * 2f
                drawRoundRect(
                    color = picked,
                    topLeft = Offset(slot * width, inset),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f),
                )
            },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp, content = content)
    }
}

@Composable
internal fun glyphSegmentedColors(selected: Boolean, onCard: Boolean = false): SegmentedButtonColors {
    val scheme = MaterialTheme.colorScheme
    if (!MaterialTheme.lucent) return SegmentedButtonDefaults.colors()
    // Theirs runs the label from full ink when picked, to about 60 per cent when not, to a
    // quarter when the option cannot be chosen at all.
    val faded = scheme.onSurface.copy(alpha = SEGMENT_DISABLED_ALPHA)
    // Both branches carry the same colour so that the label crosses over on its own clock rather
    // than on the click, which would leave it dark before the pill had reached it.
    val label by animateColorAsState(
        targetValue = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "segmentedLabel",
    )
    return SegmentedButtonDefaults.colors(
        activeContainerColor = Color.Transparent,
        activeContentColor = label,
        activeBorderColor = Color.Transparent,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = label,
        inactiveBorderColor = Color.Transparent,
        disabledActiveContainerColor = Color.Transparent,
        disabledActiveContentColor = faded,
        disabledActiveBorderColor = Color.Transparent,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = faded,
        disabledInactiveBorderColor = Color.Transparent,
    )
}

@Composable
private fun segmentTrack(onCard: Boolean): Color = with(MaterialTheme.colorScheme) {
    if (onCard) background else surface.copy(alpha = 1f)
}

@Composable
private fun segmentPicked(onCard: Boolean): Color = with(MaterialTheme.colorScheme) {
    if (onCard) surface.copy(alpha = 1f) else background
}

@Composable
internal fun glyphSegmentedShape(index: Int, count: Int): Shape =
    if (MaterialTheme.lucent) PillShape else SegmentedButtonDefaults.itemShape(index, count)

@Composable
internal fun glyphSegmentedIcon(selected: Boolean): @Composable () -> Unit =
    if (MaterialTheme.lucent) ({ }) else ({ SegmentedButtonDefaults.Icon(selected) })
