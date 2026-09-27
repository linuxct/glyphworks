package space.linuxct.glyphworks.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.ui.theme.DialogBackdropBlur
import space.linuxct.glyphworks.ui.theme.dialogSurface
import java.time.Clock

@Volatile
private var reviewPolicy: ReviewPromptPolicy? = null

/** Runs only after a successful lit frame, including Ambient frames while the app is closed. */
internal fun createReviewUsageRecorder(context: Context, prefs: Prefs): ((IntArray) -> Boolean)? {
    val installedAt = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .lastUpdateTime
    }.getOrDefault(0L)
    val policy = ReviewPromptPolicy(prefs, BuildConfig.VERSION_CODE, installedAt)
    reviewPolicy = policy
    if (!policy.needsUsageEvidence()) return null
    val recorder = ReviewUsageRecorder(prefs, policy, Clock.systemDefaultZone(), SystemClock::elapsedRealtime)
    return recorder::onFrameDisplayed
}

/** Observe touches without consuming them, and offer the dialog only after five quiet seconds. */
@Composable
internal fun Modifier.reviewPromptWhenIdle(available: Boolean, activityKey: Any?): Modifier {
    val policy = reviewPolicy ?: return this
    val context = LocalContext.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var touching by remember { mutableStateOf(false) }
    var touchEnd by remember { mutableIntStateOf(0) }
    var showing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(available, activityKey, windowFocused, touching, touchEnd, showing) {
        if (available && windowFocused && !touching && !showing) {
            delay(5_000)
            if (policy.markShownIfEligible(System.currentTimeMillis())) showing = true
        }
    }

    if (showing) {
        ReviewDialog(
            onDismiss = { showing = false },
            onNever = { policy.neverAskAgain(); showing = false },
            onReview = { showing = false; openStoreListing(context) },
        )
    }

    return pointerInput(Unit) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    touching = event.changes.any { it.pressed }
                    if (!touching) touchEnd++
                }
            }
        } finally {
            touching = false
        }
    }
}

@Composable
private fun ReviewDialog(onDismiss: () -> Unit, onNever: () -> Unit, onReview: () -> Unit) {
    AlertDialog(
        containerColor = dialogSurface(),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.RateReview, null, Modifier.size(28.dp)) },
        title = { Text(stringResource(R.string.review_title)) },
        text = { Text(stringResource(R.string.review_body)) },
        // Three full-width actions remain readable at large font sizes, without a cramped button row.
        confirmButton = {
            DialogBackdropBlur()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_leave))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_not_now))
                }
                TextButton(onClick = onNever, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_never))
                }
            }
        },
    )
}

private fun openStoreListing(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, reviewListingUrl(BuildConfig.APPLICATION_ID).toUri())
    try {
        context.startActivity(Intent(intent).setPackage("com.android.vending"))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.review_store_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun reviewListingUrl(applicationId: String): String =
    "https://play.google.com/store/apps/details?id=$applicationId"
