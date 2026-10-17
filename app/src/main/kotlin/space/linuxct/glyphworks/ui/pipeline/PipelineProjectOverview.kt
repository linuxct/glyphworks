package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R

/** Display-only overview data. Summaries are prepared by the caller; expressions are never interpreted here. */
data class PipelineProjectOverviewPresentation(
    val title: String,
    val mode: String,
    val behaviors: List<Behavior>,
    val routines: List<ReusableSummary> = emptyList(),
    val artwork: List<ReusableSummary> = emptyList(),
    val onAddBehavior: () -> Unit,
    val onEditBehavior: (index: Int) -> Unit,
    val onPreviewBehavior: (index: Int) -> Unit,
    val onOpenCanvas: () -> Unit,
) {
    data class Behavior(
        val title: String,
        /** Human-readable event summary for the When row. */
        val whenSummary: String,
        /** Human-readable condition summary, or null when no conditions are configured. */
        val ifSummary: String? = null,
        /** Human-readable action/result summary for the Then row. */
        val thenSummary: String,
        val enabled: Boolean = true,
    )

    data class ReusableSummary(val title: String, val summary: String? = null)
}

/** Project overview for pipeline authoring. All content and actions are supplied by the host. */
@Composable
fun PipelineProjectOverview(
    presentation: PipelineProjectOverviewPresentation,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PipelineCard {
                PipelineEyebrow(presentation.mode)
                Text(
                    presentation.title.ifBlank { stringResource(R.string.pipeline_overview_untitled) },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (presentation.behaviors.isEmpty()) {
            item {
                PipelineCard {
                    Text(stringResource(R.string.pipeline_overview_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pipeline_overview_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = presentation.onAddBehavior, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pipeline_overview_add_behavior))
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pipeline_overview_behaviors), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = presentation.onAddBehavior) { Text(stringResource(R.string.pipeline_overview_add)) }
                }
            }
            itemsIndexed(presentation.behaviors) { index, behavior ->
                BehaviorCard(
                    behavior = behavior,
                    onEdit = { presentation.onEditBehavior(index) },
                    onPreview = { presentation.onPreviewBehavior(index) },
                )
            }
        }

        item { ReusableSection(stringResource(R.string.pipeline_overview_routines), presentation.routines, Icons.Outlined.AccountTree) }
        item { ReusableSection(stringResource(R.string.pipeline_overview_artwork), presentation.artwork, Icons.Outlined.Brush) }

        item {
            OutlinedButton(onClick = presentation.onOpenCanvas, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Icon(Icons.Outlined.Tune, contentDescription = null)
                Text(stringResource(R.string.pipeline_overview_open_canvas), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BehaviorCard(
    behavior: PipelineProjectOverviewPresentation.Behavior,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
) {
    PipelineCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(behavior.title.ifBlank { stringResource(R.string.pipeline_overview_untitled_behavior) }, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (behavior.enabled) R.string.pipeline_overview_enabled else R.string.pipeline_overview_disabled),
                    color = if (behavior.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = onEdit) {
                androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = null)
                Text(stringResource(R.string.pipeline_overview_edit), modifier = Modifier.padding(start = 4.dp))
            }
        }
        SummaryStep(stringResource(R.string.pipeline_overview_when), behavior.whenSummary, R.string.pipeline_overview_missing_event)
        SummaryStep(stringResource(R.string.pipeline_overview_if), behavior.ifSummary ?: stringResource(R.string.pipeline_overview_no_conditions), R.string.pipeline_overview_missing_condition)
        SummaryStep(stringResource(R.string.pipeline_overview_then), behavior.thenSummary, R.string.pipeline_overview_missing_action)
        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.pipeline_overview_preview), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SummaryStep(label: String, summary: String, missing: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PipelineEyebrow(label)
        Text(
            summary.ifBlank { stringResource(missing) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReusableSection(
    title: String,
    entries: List<PipelineProjectOverviewPresentation.ReusableSummary>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    PipelineCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.pipeline_overview_none_available), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            entries.forEach { entry ->
                Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title.ifBlank { stringResource(R.string.pipeline_overview_untitled) }, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    entry.summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
