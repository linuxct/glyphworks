package space.linuxct.glyphworks.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.MarqueePlan
import space.linuxct.glyphworks.core.design.MarqueeText
import space.linuxct.glyphworks.ui.DIALOG_VERTICAL_MARGIN

@Composable
internal fun MarqueeDialog(state: EditorState, onDismiss: () -> Unit, onGenerated: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf<Int?>(null) }

    val plan = remember(state, text, step) {
        val defaultSpeedPlan = marqueePlanFor(state, text)
        val fasterIsNeeded = step != null && defaultSpeedPlan !is MarqueePlan.Ready
        if (fasterIsNeeded) marqueePlanFor(state, text, step) else defaultSpeedPlan
    }
    val ready = plan as? MarqueePlan.Ready
    val drawnFrames = remember(state) { drawnFrameCount(state) }

    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marquee_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace('\n', ' ') },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.marquee_field)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                MarqueeStatus(
                    plan = plan,
                    drawnFrames = drawnFrames,
                    onTrim = { text = it },
                    onFaster = { step = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready != null,
                onClick = {
                    val frames = ready?.frames
                    if (frames != null && applyMarquee(state, frames)) onGenerated()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.marquee_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.marquee_cancel)) }
        },
    )
}

@Composable
private fun MarqueeStatus(
    plan: MarqueePlan,
    drawnFrames: Int,
    onTrim: (String) -> Unit,
    onFaster: (Int) -> Unit,
) {
    val caption = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (plan) {
        is MarqueePlan.Ready -> Column {
            Text(
                pluralStringResource(
                    R.plurals.marquee_ready,
                    plan.frames.size,
                    plan.frames.size,
                    formatTotalValue(plan.frames.sumOf { it.durationMs }),
                ),
                style = caption,
                color = muted,
            )
            if (drawnFrames > 0) {
                Text(
                    pluralStringResource(R.plurals.marquee_replaces, drawnFrames, drawnFrames),
                    style = caption,
                    color = muted,
                )
            }
        }

        is MarqueePlan.Unsupported -> Text(
            stringResource(
                R.string.marquee_unsupported,
                plan.characters.joinToString(" ") { "“$it”" },
            ),
            style = caption,
            color = muted,
        )

        is MarqueePlan.TooLong -> Column {
            Text(
                pluralStringResource(
                    R.plurals.marquee_too_long,
                    plan.framesNeeded,
                    plan.framesNeeded,
                    plan.maxFrames,
                ),
                style = caption,
                color = muted,
            )
            Row(Modifier.fillMaxWidth()) {
                if (plan.prefix.isNotEmpty()) {
                    TextButton(onClick = { onTrim(plan.prefix) }) {
                        Text(stringResource(R.string.marquee_trim, plan.prefix))
                    }
                }
                val faster = plan.stepThatFits
                if (faster != null) {
                    TextButton(onClick = { onFaster(faster) }) {
                        Text(stringResource(R.string.marquee_faster))
                    }
                }
            }
        }

        MarqueePlan.Blank -> Text(
            stringResource(R.string.marquee_hint),
            style = caption,
            color = muted,
        )
    }
}

internal fun marqueePlanFor(state: EditorState, text: String, step: Int? = null): MarqueePlan {
    val size = state.codename.size
    val brightestLevel = state.design.levels.lastIndex
    val paletteCanDrawText = brightestLevel >= 1
    if (!paletteCanDrawText) return MarqueePlan.Blank
    return MarqueeText.plan(
        text = text,
        size = size,
        paletteIndex = state.brushIndex.takeIf { it in 1..brightestLevel } ?: brightestLevel,
        step = step ?: MarqueeText.defaultStep(size),
    )
}

internal fun applyMarquee(state: EditorState, frames: List<DesignFrame>): Boolean {
    if (frames.isEmpty()) return false
    val current = state.composed()
    val variant = current.variantFor(state.codename) ?: DesignVariant()
    val next: Design = current.copy(
        kind = DesignKind.DYNAMIC,
        loop = true,
        variants = current.variants + (state.codename.codename to variant.copy(frames = frames)),
    )
    return state.replaceDesign(next, recordUndo = true) != null
}

internal fun drawnFrameCount(state: EditorState): Int =
    state.frames.count { entry -> entry.frame.copyOfCells().any { it != 0 } }
