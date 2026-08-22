package space.linuxct.glyphworks.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ai.AiGate
import space.linuxct.glyphworks.ai.GlyphApplyResult
import space.linuxct.glyphworks.ai.GlyphEditorBridge
import space.linuxct.glyphworks.ai.aiGate
import space.linuxct.glyphworks.core.ai.GlyphToolContext
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.ui.design.EditorState
import space.linuxct.glyphworks.ui.design.GlyphAiChatSheet
import space.linuxct.glyphworks.ui.design.GlyphAiConsentDialog
import space.linuxct.glyphworks.ui.design.GlyphAiSignInDialog
import space.linuxct.glyphworks.ui.design.glyphAiViewModel

/**
 * The whole assistant in one composable, so `main` never names [GlyphEditorBridge],
 * [GlyphApplyResult], [AiGate] or [GlyphToolContext]. It fits in the app bar's `Row`
 * because `MotionDialog` renders in its own window. [onEdit] is the editor's ordinary
 * change callback, so an assistant's change gets every guarantee an edit gets.
 */
@Composable
internal fun RowScope.AssistantActionImpl(state: EditorState, onEdit: () -> Unit) {
    // `rememberSaveable`: a sign-in leaves for the browser and comes back minutes later,
    // quite possibly after a rotation.
    var aiOpen by rememberSaveable { mutableStateOf(false) }

    // Outside the chat modal, so a turn survives it closing.
    val ai = glyphAiViewModel()
    val aiState by ai.state.collectAsStateWithLifecycle()

    // Registered, not injected: a turn started before a rotation must apply to the editor
    // that exists after it. `clearEditor` is identity-checked for the frame where both do.
    val bridge = remember(state) {
        object : GlyphEditorBridge {
            override fun snapshot(): GlyphToolContext = GlyphToolContext(
                design = state.composed(),
                openVariant = state.codename,
                selectedFrameIndex = state.selectedIndex,
            )

            override fun apply(design: Design): GlyphApplyResult {
                // No `recordUndo`: the way back from a turn is the revert banner, which is
                // what `previous` feeds. See `EditorState.replaceDesign`.
                val previous = state.replaceDesign(design)
                    // Model-facing, not user-facing: it returns as a failed tool result.
                    ?: return GlyphApplyResult.Refused(
                        "The editor could not open that document, so nothing was changed.",
                    )
                onEdit()
                return GlyphApplyResult.Applied(previous)
            }
        }
    }
    DisposableEffect(bridge) {
        ai.setEditor(bridge)
        onDispose { ai.clearEditor(bridge) }
    }

    IconButton(onClick = { aiOpen = true }) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = stringResource(R.string.ai_action),
        )
    }

    if (aiOpen) {
        when (aiGate(consented = aiState.consented, signedIn = aiState.signedIn)) {
            AiGate.CONSENT -> GlyphAiConsentDialog(
                onAccept = { ai.acceptConsent() },
                onDismiss = { aiOpen = false },
            )

            AiGate.SIGN_IN -> GlyphAiSignInDialog(onDismiss = { aiOpen = false })

            AiGate.CHAT -> GlyphAiChatSheet(
                designId = state.design.id,
                onDismiss = { aiOpen = false },
            )
        }
    }
}
