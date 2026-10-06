package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget

/** Stateless so the tutorial demonstrates the real settings without touching production mode. */
@Composable
fun CustomControlsSettingsContent(
    enabled: Boolean,
    status: String,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp).pipelineDemoTarget("custom-settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.AccountTree, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.pipeline_custom_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pipeline_custom_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pipeline_custom_enable), style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, onEnabled, modifier = Modifier.pipelineDemoTarget("custom-enable"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onEdit) { Text(stringResource(R.string.pipeline_custom_edit)) }
            OutlinedButton(onClick = onStop, enabled = enabled, modifier = Modifier.pipelineDemoTarget("custom-stop")) { Text(stringResource(R.string.pipeline_custom_stop)) }
        }
        if (enabled) TextButton(onClick = { onEnabled(false) }) { Text(stringResource(R.string.pipeline_custom_standard)) }
    }
}

@Composable
fun CustomControlsLockedToys(onEdit: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp).pipelineDemoTarget("custom-locked"), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AccountTree, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.pipeline_custom_locked_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pipeline_custom_locked_description), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onEdit) { Text(stringResource(R.string.pipeline_custom_edit)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.pipeline_custom_settings)) }
    }
}
