package space.linuxct.glyphworks.ui.design

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ai.GlyphAiViewModel
import space.linuxct.glyphworks.ai.SignInFailure
import space.linuxct.glyphworks.ui.MotionDialog
import space.linuxct.glyphworks.ui.dialogCardWidth

/**
 * Every way out calls [GlyphAiViewModel.cancelSignIn]: a sign-in left running behind a
 * dialog nobody can see would hold port 1455 for ten minutes and break the next attempt.
 */
@Composable
internal fun GlyphAiSignInDialog(onDismiss: () -> Unit) {
    val viewModel = glyphAiViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    MotionDialog(
        onDismiss = {
            viewModel.cancelSignIn()
            onDismiss()
        },
    ) { dismiss ->
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
                    stringResource(R.string.ai_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        if (state.signedIn) R.string.ai_body_signed_in else R.string.ai_body_signed_out,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.busy) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.ai_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // The platform's own message goes under ours: "Token request failed 400:
                // invalid_grant" is something a user can act on.
                state.failure?.let { failure ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(failure.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        // Mid-flight the close button is the other useful action: leave the
                        // sign-in running and go back to the browser.
                        state.busy -> TextButton(onClick = { viewModel.cancelSignIn() }) {
                            Text(stringResource(R.string.ai_cancel))
                        }
                        state.signedIn -> TextButton(onClick = { viewModel.signOut() }) {
                            Text(stringResource(R.string.ai_sign_out))
                        }
                        else -> TextButton(
                            onClick = {
                                viewModel.signIn { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    // "Retry" is how the user knows the tap registered.
                                    if (state.failure != null) R.string.ai_retry else R.string.ai_sign_in,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

private fun SignInFailure.messageRes(): Int = when (this) {
    SignInFailure.TIMED_OUT -> R.string.ai_error_timeout
    SignInFailure.PORT_BUSY -> R.string.ai_error_port
    SignInFailure.NO_BROWSER -> R.string.ai_error_no_browser
    SignInFailure.FAILED -> R.string.ai_error_failed
}

/**
 * [ViewModelProvider] rather than the `viewModel()` composable, which lives in
 * `lifecycle-viewmodel-compose` and this app does not depend on it. Same store either way.
 */
@Composable
internal fun glyphAiViewModel(): GlyphAiViewModel {
    val context = LocalContext.current
    val owner = remember(context) {
        requireNotNull(context.findActivity()) { "GlyphAiSignInDialog must be hosted by an Activity" }
    }
    return remember(owner) { ViewModelProvider(owner)[GlyphAiViewModel::class.java] }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
