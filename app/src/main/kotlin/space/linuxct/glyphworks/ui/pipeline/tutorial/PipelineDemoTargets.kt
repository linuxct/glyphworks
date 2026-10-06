package space.linuxct.glyphworks.ui.pipeline.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import space.linuxct.glyphworks.ui.tutorial.TourTargets

internal val LocalPipelineDemoTargets = compositionLocalOf<TourTargets<String>?> { null }

/** A measurement-only hook: production editing is identical outside a tutorial. */
@Composable
internal fun Modifier.pipelineDemoTarget(target: String, index: Int = 0): Modifier {
    val targets = LocalPipelineDemoTargets.current ?: return this
    DisposableEffect(targets, target, index) {
        onDispose { targets.forget(target, index) }
    }
    return onGloballyPositioned { targets.report(target, index, it.boundsInRoot()) }
}
