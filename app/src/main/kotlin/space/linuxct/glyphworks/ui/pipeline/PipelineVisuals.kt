package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.linuxct.glyphworks.ui.theme.glyphCorner
import space.linuxct.glyphworks.ui.theme.lucent

/** Workspace furniture shares the app's Lucent material; color is reserved for state. */
@Composable
internal fun pipelineSurfaceColor(): Color = MaterialTheme.colorScheme.surface.copy(
    alpha = if (MaterialTheme.lucent) .86f else 1f,
)

@Composable
internal fun PipelineCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = glyphCorner(20.dp, 28.dp),
        color = pipelineSurfaceColor(),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .055f)),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun PipelineEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PipelineIconWell(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    Surface(modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * .48f), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

internal fun pipelineCategoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "control", "time" -> Icons.Outlined.Tune
    "events" -> Icons.Outlined.Bolt
    "display" -> Icons.Outlined.GridView
    "drawing", "scene", "sprites" -> Icons.Outlined.Brush
    "variables", "data" -> Icons.Outlined.DataObject
    "routines", "reuse" -> Icons.Outlined.AccountTree
    else -> Icons.Outlined.Widgets
}
