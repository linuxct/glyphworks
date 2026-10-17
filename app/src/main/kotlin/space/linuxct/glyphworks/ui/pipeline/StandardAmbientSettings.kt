package space.linuxct.glyphworks.ui.pipeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import space.linuxct.glyphworks.ui.InformationSwitch
import space.linuxct.glyphworks.ui.InformationToySetupRows
import space.linuxct.glyphworks.ui.ScreenSettingsDialog
import space.linuxct.glyphworks.ui.rememberPref

/** Standard, preference-backed Ambient controls. Opening this component never activates a pipeline. */
@Composable
internal fun StandardAmbientSettings(
    onOpenPipelineBuilder: () -> Unit,
    onStandardSettingsChanged: () -> Unit = {},
) {
    val selected by rememberPref(PrefKeys.AMBIENT_BACKGROUNDS) { AmbientBackgrounds.readSelection(it) }
    val automatic by rememberPref(PrefKeys.AMBIENT_AUTO_CYCLE) {
        it.getBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, PrefKeys.AMBIENT_AUTO_CYCLE_DEF)
    }
    val night by rememberPref(PrefKeys.AMBIENT_NIGHT_VISIBLE) {
        it.getBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, PrefKeys.AMBIENT_NIGHT_VISIBLE_DEF)
    }
    val shake by rememberPref(PrefKeys.AMBIENT_SHAKE_ACTIVATE) {
        it.getBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, PrefKeys.AMBIENT_SHAKE_ACTIVATE_DEF)
    }
    val charging by rememberPref(PrefKeys.AMBIENT_USE_CHARGING) {
        it.getBoolean(PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_USE_CHARGING_DEF)
    }
    var informationSettings by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.ambient_cycle_backgrounds), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.ambient_cycle_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val labels = listOf(
            R.string.ambient_cycle_digital_clock, R.string.ambient_cycle_analog_clock,
            R.string.ambient_cycle_connection, R.string.ambient_cycle_battery_text,
            R.string.ambient_cycle_speed, R.string.ambient_cycle_tilt_ball,
            R.string.ambient_cycle_pixel_clock, R.string.ambient_cycle_battery_gauge,
            R.string.ambient_cycle_solar_path, R.string.ambient_cycle_moon_phase,
            R.string.screen_notifications, R.string.screen_weather,
        )
        AmbientBackgrounds.orderedIds.forEachIndexed { index, id ->
            InformationSwitch(stringResource(labels[index]), id in selected) { checked ->
                Core.prefs.putString(
                    PrefKeys.AMBIENT_BACKGROUNDS,
                    AmbientBackgrounds.encode(if (checked) selected + id else selected - id),
                )
                onStandardSettingsChanged()
            }
        }
        InformationSwitch(stringResource(R.string.ambient_cycle_automatic), automatic) {
            Core.prefs.putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, it)
            onStandardSettingsChanged()
        }
        Text(
            stringResource(R.string.ambient_cycle_automatic_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InformationToySetupRows(
            notifications = AmbientBackgrounds.NOTIFICATIONS in selected,
            weather = AmbientBackgrounds.WEATHER in selected,
            onConfigure = { informationSettings = it },
        )
        InformationSwitch(stringResource(R.string.pref_ambient_night), night) {
            Core.prefs.putBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, it)
            onStandardSettingsChanged()
        }
        InformationSwitch(stringResource(R.string.pref_ambient_shake), shake) {
            Core.prefs.putBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, it)
            onStandardSettingsChanged()
        }
        InformationSwitch(stringResource(R.string.pref_ambient_charging), charging) {
            Core.prefs.putBoolean(PrefKeys.AMBIENT_USE_CHARGING, it)
            onStandardSettingsChanged()
        }
        Text(
            stringResource(R.string.pref_ambient_shared_settings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        androidx.compose.material3.TextButton(onClick = { informationSettings = "battery" }) {
            Text(stringResource(R.string.pref_ambient_battery_settings))
        }
        Text(stringResource(R.string.standard_ambient_advanced_features_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenPipelineBuilder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.standard_ambient_customize_with_pipeline_builder))
        }
    }
    informationSettings?.let { id -> ScreenSettingsDialog(id) { informationSettings = null } }
}
