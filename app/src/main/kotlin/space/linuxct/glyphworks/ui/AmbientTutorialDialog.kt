package space.linuxct.glyphworks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.linuxct.glyphworks.R

/** Uses the shared tutorial presentation, including motion and both app themes. */
@Composable
fun AmbientTutorialDialog(
    onDismiss: () -> Unit,
    onOpenAmbientSettings: () -> Unit,
) {
    TutorialInfoDialog(
        title = stringResource(R.string.tut_ambient_title),
        intro = stringResource(R.string.tut_ambient_intro),
        steps = listOf(
            stringResource(R.string.tut_ambient_step1),
            stringResource(R.string.tut_ambient_step2),
            stringResource(R.string.tut_ambient_step3),
            stringResource(R.string.tut_ambient_step4),
            stringResource(R.string.tut_ambient_step5),
            stringResource(R.string.tut_ambient_step6),
        ),
        note = stringResource(R.string.tut_ambient_note),
        actionLabel = stringResource(R.string.tut_ambient_action),
        onAction = onOpenAmbientSettings,
        onDismiss = onDismiss,
    )
}
