package space.linuxct.glyphworks.ui

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import space.linuxct.glyphworks.designs.DesignStore

/**
 * The empty half of the flavour seam, mirroring `src/github`'s signatures so `main` can
 * call them unconditionally and no AI or update code reaches this APK. See
 * docs/TECHNICAL.md.
 */

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun RowScope.AssistantAction(state: space.linuxct.glyphworks.ui.design.EditorState, onEdit: () -> Unit) = Unit

@Composable
internal fun ColumnScope.AiSettingsSection() = Unit

internal fun SectionCardScope.updateSettingsItem() = Unit

@Suppress("UNUSED_PARAMETER")
internal fun scheduleUpdateCheck(context: Context) = Unit

@Suppress("UNUSED_PARAMETER")
internal fun installOptionalHooks(app: Context, store: DesignStore) = Unit

@Composable
internal fun ColumnScope.SideloadHelpCard() = Unit

internal fun SectionCardScope.restrictedSettingsTutorialItem() = Unit
