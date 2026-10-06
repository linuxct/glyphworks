package space.linuxct.glyphworks.ui.pipeline

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.WeatherPrefs
import space.linuxct.glyphworks.notifications.NotificationAccess
import space.linuxct.glyphworks.ui.*
import space.linuxct.pipeline.*

internal fun pipelineMissingSetup(context: Context, document: PipelineDocument): List<String> {
    val capabilities = PipelineRuntime.capabilities(document)
    return buildList {
        if ("notifications" in capabilities && !NotificationAccess.isGranted(context)) add("notification access")
        if (capabilities.any { it == "audio" || it.startsWith("audio.") } && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add("audio permission")
        if (capabilities.any { it in setOf("location", "weather") } && !locationGranted(context)) add("location permission")
        if ("weather" in capabilities && !Core.prefs.getBoolean(WeatherPrefs.ENABLED, false)) add("Weather setup")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PipelineCapabilitiesSheet(document: PipelineDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var configure by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose {} }
    val capabilities = remember(document.programs, document.routines, document.requires) { PipelineRuntime.capabilities(document) }
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; Core.pipeline.onProjectApplied(document.id) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; Core.weather.onConfigurationChanged(); Core.pipeline.onProjectApplied(document.id) }
    fun appSettings() = openInformationSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text(stringResource(R.string.pipeline_editor_permissions_and_sources), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = onDismiss) { Text(stringResource(R.string.pipeline_editor_done)) } }
            Text(stringResource(R.string.pipeline_editor_editing_and_simulation_work_without_permissions_a_missing), style = MaterialTheme.typography.bodyMedium)
            when (configure) {
                "weather" -> { TextButton(onClick = { configure = null }) { Text(stringResource(R.string.pipeline_editor_back_to_sources)) }; WeatherSettings() }
                "notifications" -> { TextButton(onClick = { configure = null }) { Text(stringResource(R.string.pipeline_editor_back_to_sources)) }; NotificationsSettings() }
                else -> {
                    if ("notifications" in capabilities) {
                        Text(stringResource(R.string.information_notifications_explanation), style = MaterialTheme.typography.bodySmall)
                        val granted = remember(refresh) { NotificationAccess.isGranted(context) }
                        OutlinedButton(onClick = { configure = "notifications" }, modifier = Modifier.fillMaxWidth()) { Text(if (granted) "Notification access · ready" else "Set up notification access") }
                    }
                    if ("weather" in capabilities) {
                        Text(stringResource(R.string.information_weather_consent), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { configure = "weather" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pipeline_editor_weather_and_location_settings)) }
                    }
                    if (capabilities.any { it == "audio" || it.startsWith("audio.") }) {
                        val granted = remember(refresh) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
                        Text(stringResource(R.string.pipeline_editor_audio_visualization_needs_android_s_audio_permission_to_re), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { if (granted) appSettings() else audio.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text(if (granted) "Audio permission · ready" else "Allow audio visualization") }
                    }
                    if ("location" in capabilities && "weather" !in capabilities) {
                        Text(stringResource(R.string.onb_perm_loc_why), style = MaterialTheme.typography.bodySmall)
                        val granted = remember(refresh) { locationGranted(context) }
                        OutlinedButton(onClick = { if (granted) appSettings() else location.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.fillMaxWidth()) { Text(if (granted) "Location permission · ready" else "Allow approximate location") }
                    }
                    if (allEditorBlocks(document).any { it.op == "timer.start" || it.op == "display.toy" && it.arguments["toy"]?.value?.text() == "timer" }) {
                        val exact = remember(refresh) { context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }
                        Text(stringResource(R.string.pipeline_editor_exact_alarms_keep_persistent_countdowns_timely_when_androi), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { openInformationSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }, modifier = Modifier.fillMaxWidth()) { Text(if (exact) "Exact alarms · ready" else "Set up exact alarms") }
                    }
                    val manager = remember { context.getSystemService(SensorManager::class.java) }
                    val sensors = listOf("accelerometer" to (Sensor.TYPE_ACCELEROMETER to "Accelerometer"), "orientation" to (Sensor.TYPE_ACCELEROMETER to "Orientation"), "gyroscope" to (Sensor.TYPE_GYROSCOPE to "Gyroscope"), "light" to (Sensor.TYPE_LIGHT to "Light sensor"), "proximity" to (Sensor.TYPE_PROXIMITY to "Proximity sensor"), "compass" to (Sensor.TYPE_MAGNETIC_FIELD to "Compass"))
                    sensors.filter { it.first in capabilities }.forEach { (_, value) ->
                        val present = remember(value.first) { manager?.getDefaultSensor(value.first) != null }
                        Text("${value.second} · ${if (present) "available" else "not present on this device"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (capabilities.isEmpty()) Text(stringResource(R.string.pipeline_editor_this_pipeline_needs_no_optional_data_sources), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ::appSettings) { Text(stringResource(R.string.pipeline_editor_open_app_permissions)) }
                }
            }
        }
    }
}
