package space.linuxct.glyphworks.review

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import space.linuxct.glyphworks.core.Prefs

// The GitHub build has no review UI, usage storage, or Google Play dependency.
@Suppress("UNUSED_PARAMETER")
internal fun createReviewUsageRecorder(context: Context, prefs: Prefs): ((IntArray) -> Boolean)? = null

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.reviewPromptWhenIdle(available: Boolean, activityKey: Any?): Modifier = this
