package space.linuxct.glyphworks.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.core.weather.WeatherStatus
import space.linuxct.glyphworks.notifications.NotificationAccess
import space.linuxct.glyphworks.notifications.NotificationSource
import space.linuxct.glyphworks.ui.theme.GlyphSwitch
import space.linuxct.glyphworks.ui.theme.fullContrastListItemColors

/** Polls only in a resumed settings UI; all reads return cached data without fetching. */
@Composable
internal fun rememberInformationRefresh(): Int {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        NotificationSource.refreshAccess(context)
        Core.weather.onConfigurationChanged()
        val job = scope.launch {
            while (isActive) {
                tick++
                delay(1_000L)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    return tick
}

internal fun locationGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun backgroundLocationGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun openInformationSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.information_settings_unavailable, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun InformationSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        onClick = { onCheckedChange(!checked) },
        colors = fullContrastListItemColors(),
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            NoRipple { GlyphSwitch(checked = checked, onCheckedChange = onCheckedChange) }
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InformationExplanation(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
internal fun NotificationsSettings() {
    val context = LocalContext.current
    val style by rememberPref(NotificationPrefs.STYLE) { NotificationPrefs.style(it) }
    val tick = rememberInformationRefresh()
    val granted = remember(tick) { NotificationAccess.isGranted(context) }
    val count = remember(tick) { Core.ports.notifications.count() }
    Text(stringResource(R.string.information_notification_style), style = MaterialTheme.typography.labelLarge)
    val labels = listOf(
        R.string.information_notification_envelope, R.string.information_notification_text,
        R.string.information_notification_dot, R.string.information_notification_bell,
    )
    NotificationPrefs.styles.forEachIndexed { index, value ->
        ChoiceRow(stringResource(labels[index]), style == value) {
            Core.prefs.putString(NotificationPrefs.STYLE, value)
        }
    }
    InformationExplanation(stringResource(R.string.information_notification_style_hint))
    if (style == NotificationPrefs.BELL) {
        InformationExplanation(stringResource(R.string.information_notification_bell_hint))
    }
    InformationExplanation(stringResource(R.string.information_notifications_explanation))
    SetupRow(
        title = stringResource(R.string.information_notification_access),
        subtitle = stringResource(
            when {
                !granted -> R.string.information_notification_access_off
                count == null -> R.string.information_notification_connecting
                else -> R.string.information_notification_access_on
            },
        ),
        good = if (granted && count == null) null else granted,
    ) { openInformationSettings(context, NotificationAccess.settingsIntent(context)) }
    InformationExplanation(stringResource(R.string.information_notifications_display))
}

@Composable
internal fun WeatherSettings() {
    val context = LocalContext.current
    val tick = rememberInformationRefresh()
    val enabled by rememberPref(WeatherPrefs.ENABLED) { it.getBoolean(WeatherPrefs.ENABLED, false) }
    val unit by rememberPref(WeatherPrefs.UNIT) { it.getString(WeatherPrefs.UNIT, WeatherPrefs.CELSIUS) }
    val coarse = remember(tick) { locationGranted(context) }
    val background = remember(tick) { backgroundLocationGranted(context) }
    val snapshot = remember(tick) { Core.ports.weather.snapshot() }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Core.weather.onConfigurationChanged()
    }

    InformationExplanation(stringResource(R.string.information_weather_consent))
    InformationSwitch(stringResource(R.string.information_weather_enable), enabled) {
        Core.prefs.putBoolean(WeatherPrefs.ENABLED, it)
    }
    if (enabled) {
        SetupRow(
            title = stringResource(R.string.information_weather_location),
            subtitle = stringResource(if (coarse) R.string.checklist_granted else R.string.information_weather_location_off),
            good = coarse,
        ) {
            if (coarse) {
                openInformationSettings(context, appPermissionSettingsIntent(context))
            } else {
                foregroundLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (!coarse) {
            // A permanently denied runtime permission can only be changed in Settings.
            TextButton(onClick = { openInformationSettings(context, appPermissionSettingsIntent(context)) }) {
                Text(stringResource(R.string.information_app_permissions))
            }
        }
        if (coarse) {
            InformationExplanation(stringResource(R.string.information_weather_background_explanation))
            SetupRow(
                title = stringResource(R.string.information_weather_background),
                subtitle = stringResource(
                    if (background) R.string.checklist_granted else R.string.information_weather_background_off,
                ),
                good = if (background) true else null,
            ) { openInformationSettings(context, appPermissionSettingsIntent(context)) }
        }
        if (coarse && !background) InformationExplanation(stringResource(R.string.information_weather_foreground_only))

        Text(stringResource(R.string.information_weather_units), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(stringResource(R.string.information_weather_celsius), unit != WeatherPrefs.FAHRENHEIT) {
            Core.prefs.putString(WeatherPrefs.UNIT, WeatherPrefs.CELSIUS)
        }
        ChoiceRow(stringResource(R.string.information_weather_fahrenheit), unit == WeatherPrefs.FAHRENHEIT) {
            Core.prefs.putString(WeatherPrefs.UNIT, WeatherPrefs.FAHRENHEIT)
        }
        InformationExplanation(stringResource(weatherStatusText(snapshot.status)))
        TextButton(onClick = { Core.weather.refresh() }, enabled = coarse) {
            Text(stringResource(R.string.information_weather_refresh))
        }
    }
    TextButton(onClick = {
        openInformationSettings(context, Intent(Intent.ACTION_VIEW, "https://open-meteo.com/".toUri()))
    }) {
        Text(stringResource(R.string.information_weather_attribution))
    }
}

private fun appPermissionSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

private fun weatherStatusText(status: WeatherStatus): Int = when (status) {
    WeatherStatus.DISABLED -> R.string.information_weather_status_disabled
    WeatherStatus.NO_PERMISSION -> R.string.information_weather_status_no_permission
    WeatherStatus.NO_LOCATION -> R.string.information_weather_status_no_location
    WeatherStatus.LOADING -> R.string.information_weather_status_loading
    WeatherStatus.UNAVAILABLE -> R.string.information_weather_status_unavailable
    WeatherStatus.READY -> R.string.information_weather_status_ready
    WeatherStatus.STALE -> R.string.information_weather_status_stale
}

@Composable
internal fun InformationToySetupRows(
    notifications: Boolean,
    weather: Boolean,
    onConfigure: (String) -> Unit,
) {
    if (!notifications && !weather) return
    val context = LocalContext.current
    val tick = rememberInformationRefresh()
    val enabled by rememberPref(WeatherPrefs.ENABLED) { it.getBoolean(WeatherPrefs.ENABLED, false) }
    Column {
        if (notifications) {
            val granted = remember(tick) { NotificationAccess.isGranted(context) }
            SetupRow(
                stringResource(R.string.screen_notifications),
                stringResource(if (granted) R.string.information_notification_access_on else R.string.information_notification_access_off),
                granted,
            ) { onConfigure("notifications") }
        }
        if (weather) {
            val snapshot = remember(tick) { Core.ports.weather.snapshot() }
            SetupRow(
                stringResource(R.string.screen_weather),
                stringResource(if (!enabled) R.string.information_weather_status_disabled else weatherStatusText(snapshot.status)),
                if (enabled && snapshot.status == WeatherStatus.READY) true else null,
            ) { onConfigure("weather") }
        }
    }
}
