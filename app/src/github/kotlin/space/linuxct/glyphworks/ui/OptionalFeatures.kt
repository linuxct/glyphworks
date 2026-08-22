package space.linuxct.glyphworks.ui

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import space.linuxct.glyphworks.ai.DesignChatCleanup
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.update.UpdateCheckWorker

/**
 * The real half of the flavour seam. `src/play` declares the same signatures empty and
 * `main` calls them unconditionally; keep the two in step. See docs/TECHNICAL.md.
 */

@Composable
internal fun RowScope.AssistantAction(state: space.linuxct.glyphworks.ui.design.EditorState, onEdit: () -> Unit) {
    AssistantActionImpl(state, onEdit)
}

@Composable
internal fun ColumnScope.AiSettingsSection() {
    AiSettingsSectionImpl()
}

internal fun SectionCardScope.updateSettingsItem() {
    item { UpdateSettingsRowImpl() }
}

internal fun scheduleUpdateCheck(context: Context) {
    UpdateCheckWorker.schedule(context)
}

internal fun installOptionalHooks(app: Context, store: DesignStore) {
    DesignChatCleanup.install(app, store)
}

@Composable
internal fun ColumnScope.SideloadHelpCard() {
    SideloadHelpCardImpl()
}

internal fun SectionCardScope.restrictedSettingsTutorialItem() {
    item { RestrictedSettingsTutorialRow() }
}
