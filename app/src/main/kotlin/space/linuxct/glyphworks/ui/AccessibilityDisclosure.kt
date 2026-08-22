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
