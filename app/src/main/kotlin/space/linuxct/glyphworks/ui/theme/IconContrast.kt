package space.linuxct.glyphworks.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun fullContrastListItemColors() = ListItemDefaults.colors(
    leadingIconColor = MaterialTheme.colorScheme.onSurface,
    trailingIconColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun fullContrastToggleColors() = IconButtonDefaults.filledIconToggleButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurface,
    // M3 defaults this to contentColorFor(checkedContainerColor), but naming any argument
    // evaluates that against Color.Unspecified and the checked glyph turns invisible.
    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun fullContrastTopAppBarColors(
    containerColor: Color = Color.Unspecified,
    scrolledContainerColor: Color = containerColor,
) = TopAppBarDefaults.topAppBarColors(
    containerColor = containerColor,
    scrolledContainerColor = scrolledContainerColor,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
)
