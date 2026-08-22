package space.linuxct.glyphworks.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R

/**
 * Android's "Restricted setting" dialog blocks accessibility services for apps installed
 * outside a store, so a Play install never sees any of this. The strings live in
 * `src/github/res/values/strings_sideload.xml`, out of `src/main`'s reach.
 */

private fun appInfoIntent(packageName: String) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:$packageName"),
)

@Composable
internal fun ColumnScope.SideloadHelpCardImpl() {
    val context = LocalContext.current
    Spacer(Modifier.height(20.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.onb_key_sideload_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onb_key_sideload_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                context.startActivity(appInfoIntent(context.packageName))
            }) {
                Text(stringResource(R.string.onb_key_appinfo))
            }
        }
    }
}

/** Holds its own dialog state: `TutorialTab`'s enum is in `src/main` and cannot name this. */
@Composable
internal fun RestrictedSettingsTutorialRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    SetupRow(
        title = stringResource(R.string.tut_restricted_title),
        subtitle = stringResource(R.string.tut_restricted_subtitle),
        good = null,
    ) { open = true }

    if (open) {
        TutorialInfoDialog(
            title = stringResource(R.string.tut_restricted_title),
            intro = stringResource(R.string.tut_restricted_intro),
            steps = listOf(
                stringResource(R.string.tut_restricted_step1),
                stringResource(R.string.tut_restricted_step2),
                stringResource(R.string.tut_restricted_step3),
                stringResource(R.string.tut_restricted_step4),
            ),
            actionLabel = stringResource(R.string.tut_restricted_action),
            onAction = { context.startActivity(appInfoIntent(context.packageName)) },
            onDismiss = { open = false },
        )
    }
}
