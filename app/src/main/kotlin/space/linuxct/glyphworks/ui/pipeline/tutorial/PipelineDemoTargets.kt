package space.linuxct.glyphworks.ui.pipeline.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import space.linuxct.glyphworks.ui.tutorial.TourTargets

internal val LocalPipelineDemoTargets = compositionLocalOf<TourTargets<String>?> { null }
internal val LocalPipelineDemoFocus = compositionLocalOf<String?> { null }

/** Measurement and guided scrolling only; production editing is identical outside a tutorial. */
@Composable
internal fun Modifier.pipelineDemoTarget(target: String, index: Int = 0): Modifier {
    val targets = LocalPipelineDemoTargets.current ?: return this
    val focused = LocalPipelineDemoFocus.current == target
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused, bringIntoView) {
        if (focused) { withFrameNanos { }; bringIntoView.bringIntoView() }
    }
    DisposableEffect(targets, target, index) {
        onDispose { targets.forget(target, index) }
    }
    return bringIntoViewRequester(bringIntoView).onGloballyPositioned { targets.report(target, index, it.boundsInRoot()) }
}
