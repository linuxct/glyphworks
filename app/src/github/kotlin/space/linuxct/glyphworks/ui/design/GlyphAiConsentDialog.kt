package space.linuxct.glyphworks.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.MotionDialog
import space.linuxct.glyphworks.ui.dialogCardWidth

/**
 * The one-off disclosure, which `aiGate` puts before the sign-in so nothing has reached
 * OpenAI yet. A dialog rather than an Activity, because pushing one would tear down the
 * editor underneath to ask a single question. Declining is just dismissal; see
 * `AiConsentStore` for why there is no stored "no".
 */
@Composable
internal fun GlyphAiConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    MotionDialog(onDismiss = onDismiss) { dismiss ->
        Surface(
            modifier = Modifier.width(dialogCardWidth()),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    stringResource(R.string.ai_consent_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ai_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.ai_consent_storage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = dismiss) {
                        Text(stringResource(R.string.ai_consent_decline))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Accepting does not dismiss: the gate moves on to the sign-in in the
                    // same window, with no flicker of the editor in between.
                    Button(onClick = onAccept) {
                        Text(stringResource(R.string.ai_consent_accept))
                    }
                }
            }
        }
    }
}
