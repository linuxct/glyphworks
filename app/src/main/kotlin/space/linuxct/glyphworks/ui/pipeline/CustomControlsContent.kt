package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.pipeline.tutorial.pipelineDemoTarget
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.glyphCorner

/** Stateless so the tutorial demonstrates the real settings without touching production mode. */
@Composable
fun CustomControlsSettingsContent(
    enabled: Boolean,
    status: String,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onResume: (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val modeLabel = stringResource(R.string.pipeline_custom_enable)
    Column(modifier.padding(horizontal = 20.dp).padding(top = 12.dp).pipelineDemoTarget("custom-settings"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pipeline_custom_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pipeline_refine_controls_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PipelineCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PipelineIconWell(Icons.AutoMirrored.Outlined.AltRoute)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PipelineEyebrow(stringResource(R.string.pipeline_refine_control_mode))
                    Text(stringResource(if (enabled) R.string.pipeline_refine_custom_mode else R.string.pipeline_refine_standard_mode), style = MaterialTheme.typography.titleMedium)
                }
                GlyphSwitch(enabled, onEnabled, modifier = Modifier.pipelineDemoTarget("custom-enable").semantics { contentDescription = modeLabel })
            }
            Text(stringResource(if (enabled) R.string.pipeline_refine_controls_active else R.string.pipeline_refine_controls_standard), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) Surface(shape = glyphCorner(12.dp, 16.dp), color = ink.copy(alpha = .045f)) {
                Text(status, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth(), colors = libraryActionColors()) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pipeline_custom_edit))
            }
            if (enabled) TextButton(onClick = { onEnabled(false) }, colors = ButtonDefaults.textButtonColors(contentColor = ink), modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.pipeline_custom_standard)) }
        }
        PipelineCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PipelineIconWell(if (paused) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.pipeline_refine_recovery), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (paused) R.string.pipeline_refine_recovery_paused else R.string.pipeline_refine_recovery_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { if (paused && onResume != null) onResume() else onStop() }, enabled = !paused || onResume != null, modifier = Modifier.fillMaxWidth().pipelineDemoTarget("custom-stop"), colors = ButtonDefaults.outlinedButtonColors(contentColor = ink)) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (paused) R.string.pipeline_editor_resume_pipelines else R.string.pipeline_editor_stop_pipelines))
            }
        }
    }
}

@Composable
fun CustomControlsLockedToys(onEdit: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp).pipelineDemoTarget("custom-locked"), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        PipelineIconWell(Icons.AutoMirrored.Outlined.AltRoute, size = 72.dp)
        Text(stringResource(R.string.pipeline_custom_locked_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.pipeline_custom_locked_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PipelineCard(Modifier.fillMaxWidth()) {
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth(), colors = libraryActionColors()) { Text(stringResource(R.string.pipeline_custom_edit)); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp)) }
            TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.pipeline_custom_settings)) }
        }
    }
}
