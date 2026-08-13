package space.linuxct.glyphworks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R

/**
 * The prominent disclosure for the AccessibilityService, in one place.
 *
 * ## Why this is a shared component and not a screen
 *
 * Google Play's User Data policy requires the disclosure to be "displayed in the
 * normal usage of the app and not require the user to navigate through a menu or
 * settings", and to be "immediately preceded" by nothing else before the consent
 * request. This app used to satisfy neither: the only disclosure lived in a
 * dedicated activity reachable ONLY from Settings -> Initial setup -> tap a row,
 * while first-run onboarding sent the user straight to the system accessibility
 * screen with no disclosure at all. The 3.0.0 submission was rejected for exactly
 * that ("we could not locate prominent disclosure of your use of the
 * AccessibilityService API in your app").
 *
 * So the disclosure is now a component with two homes:
 *
 * - **`OnboardingActivity`'s key page**, which is `Page.KEY` — the *first* page,
 *   so it is on screen seconds after first launch with nothing to navigate.
 * - **[DisclosureActivity]**, still reached from the Settings checklist, for
 *   anyone who skipped onboarding and comes back later.
 *
 * One component rather than two copies because the text is a compliance artefact:
 * two copies drift, and the copy that drifts is the one the reviewer reads.
 *
 * ## The rules the buttons encode
 *
 * Consent "must require affirmative user action" and the app "must not interpret
 * navigation away from the disclosure (including tapping away or pressing the
 * back or home button) as consent". Hence a real decline button that does
 * something visible, and an accept button that is the ONLY thing that opens the
 * system accessibility screen. Nothing here auto-dismisses.
 */

/**
 * The disclosure itself: heading and body, no actions.
 *
 * Split out so a surface with nothing to consent to — the unsupported-device
 * screen, where the app cannot run at all — can still show what the service
 * would do. A reviewer on non-Nothing hardware sees that screen and nothing else,
 * and "nothing else" is how the last submission failed.
 */
@Composable
internal fun AccessibilityDisclosureText(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.disclosure_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Decline and accept, in that order — accept last so it is the emphasised action
 * without the decline being hard to find.
 */
@Composable
internal fun AccessibilityDisclosureActions(onDecline: () -> Unit, onAccept: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDecline) {
            Text(stringResource(R.string.disclosure_decline))
        }
        Button(onClick = onAccept) {
            Text(stringResource(R.string.disclosure_accept))
        }
    }
}

/**
 * The disclosure as a self-contained card, for use inside a page that has other
 * content — onboarding's key page.
 *
 * A card rather than loose text on purpose: it draws a border around "this is the
 * disclosure", so a reviewer scanning the first screen of the app finds it
 * without reading the whole page, and a user can tell the compliance text from
 * the copy around it.
 *
 * [declined] shows the acknowledgement in place of nothing at all. Declining must
 * be visible — an app that appears to ignore "Not now" is worse than one that
 * never offered it — and it must be reversible, because the accept button stays
 * right there.
 */
@Composable
internal fun AccessibilityDisclosureCard(
    declined: Boolean,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            AccessibilityDisclosureText()
            if (declined) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.disclosure_declined_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            AccessibilityDisclosureActions(onDecline = onDecline, onAccept = onAccept)
        }
    }
}
