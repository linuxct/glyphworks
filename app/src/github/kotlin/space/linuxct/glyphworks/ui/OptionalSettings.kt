package space.linuxct.glyphworks.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiMaxRounds
import space.linuxct.glyphworks.core.ai.aiReasoningEffort
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.ReasoningEffort
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.demoTarget
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.theme.NavPillColors
import space.linuxct.glyphworks.ui.theme.navPill
import space.linuxct.glyphworks.update.UpdateChecker
import space.linuxct.glyphworks.update.UpdateCheckWorker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The escape hatch for [ChatWire.MODEL], which is a guess about somebody else's backend
 * and moves: when it stops being accepted every message fails, and a working id typed here
 * revives the feature on the next message with no update and no restart. Empty is the
 * reset.
 */
@Composable
private fun AiModelRow() {
    var model by remember {
        mutableStateOf(Core.prefs.getString(AiPrefKeys.MODEL, AiPrefKeys.MODEL_DEF))
    }
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.AutoAwesome) }) {
        Text(stringResource(R.string.pref_ai_model), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_model_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = model,
            onValueChange = {
                // One token, straight into a request body, and a paste can carry anything.
                model = it.replace('\n', ' ').take(ChatWire.MODEL_MAX_LENGTH)
                Core.prefs.putString(AiPrefKeys.MODEL, model)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text(stringResource(R.string.pref_ai_model_hint, ChatWire.MODEL)) },
            // Resolved by the same function the request uses, so the two cannot disagree.
            supportingText = {
                Text(stringResource(R.string.pref_ai_model_current, ChatWire.resolveModel(model)))
            },
            singleLine = true,
        )
    }
}

/**
 * A slider, so "", "-1" and "99999" are not expressible; [aiMaxRounds] still clamps on
 * read, for other writers of the store.
 */
@Composable
private fun AiRoundsRow() {
    val rounds by rememberPref(AiPrefKeys.MAX_ROUNDS) { it.aiMaxRounds() }
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Route) }) {
        Text(stringResource(R.string.pref_ai_rounds), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_rounds_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                if (rounds == AiPrefKeys.MAX_ROUNDS_DEF) {
                    R.string.pref_ai_rounds_default
                } else {
                    R.string.pref_ai_rounds_value
                },
                rounds,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = rounds.toFloat(),
            onValueChange = {
                Core.prefs.putInt(AiPrefKeys.MAX_ROUNDS, it.roundToInt())
            },
            valueRange = AiPrefKeys.MAX_ROUNDS_MIN.toFloat()..AiPrefKeys.MAX_ROUNDS_MAX.toFloat(),
            // `steps` counts the detents between the two ends, not the selectable values.
            steps = (AiPrefKeys.MAX_ROUNDS_MAX - AiPrefKeys.MAX_ROUNDS_MIN) /
                AiPrefKeys.MAX_ROUNDS_STEP - 1,
            // Ticks on the empty half of the track only, the way Nothing's volume panel
            // draws them. Transparent active ticks rather than a custom `track`, so
            // material3 keeps handling the geometry.
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.LOW -> R.string.pref_ai_effort_low
    ReasoningEffort.MEDIUM -> R.string.pref_ai_effort_medium
    ReasoningEffort.HIGH -> R.string.pref_ai_effort_high
    ReasoningEffort.XHIGH -> R.string.pref_ai_effort_xhigh
    ReasoningEffort.MAX -> R.string.pref_ai_effort_max
    ReasoningEffort.ULTRA -> R.string.pref_ai_effort_ultra
}

/**
 * A warning, not an error: an unverified level is unproven, not broken. Not red, because
 * the app's palette keeps only three colour exceptions and this is not one.
 */
@Composable
private fun UnverifiedEffortIcon() {
    Icon(
        Icons.Outlined.WarningAmber,
        contentDescription = stringResource(R.string.pref_ai_effort_unverified),
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * Unverified levels stay selectable: an unknown `effort` is rejected by the request, the
 * chat shows the server's own words, and the supporting line here names the token sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiEffortRow() {
    val effort by rememberPref(AiPrefKeys.REASONING_EFFORT) { it.aiReasoningEffort() }
    var open by remember { mutableStateOf(false) }

    // `ExposedDropdownMenuBox` focuses its anchor to open and never unfocuses it, so the
    // field would stay looking active for the rest of the visit. Guarded on a real
    // true-to-false change, or first composition steals focus from the row above.
    val focusManager = LocalFocusManager.current
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) focusManager.clearFocus()
        wasOpen = open
    }
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Psychology) }) {
        Text(stringResource(R.string.pref_ai_effort), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_effort_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = stringResource(effort.labelRes())
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { open = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = if (effort == ReasoningEffort.DEFAULT) {
                    stringResource(R.string.pref_ai_effort_default, label)
                } else {
                    label
                },
                // Read-only, not disabled: disabled would grey the text and drop the field
                // out of the tab order.
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                leadingIcon = if (effort.unverified) {
                    { UnverifiedEffortIcon() }
                } else {
                    null
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                // Labels get translated; this is the token that shows up in a server error.
                supportingText = {
                    Text(stringResource(R.string.pref_ai_effort_current, effort.wire))
                },
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                // `MenuDefaults.shape` still reads the pre-expressive
                // `MenuTokens.ContainerShape` (4 dp); `SegmentedMenuTokens` says
                // `CornerLarge` = 16 dp and nothing routes it to `DropdownMenu` yet.
                shape = MaterialTheme.shapes.large,
            ) {
                // The enum is the only list, so the menu cannot fall out of step with the
                // wire. Declaration order is ascending effort.
                ReasoningEffort.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(stringResource(level.labelRes())) },
                        trailingIcon = if (level.unverified) {
                            { UnverifiedEffortIcon() }
                        } else {
                            null
                        },
                        onClick = {
                            // The token, never the enum name or ordinal: what is stored is
                            // what is sent.
                            Core.prefs.putString(AiPrefKeys.REASONING_EFFORT, level.wire)
                            open = false
                        },
                        // Not `DropdownMenuItem`'s defaults: these line the items up with
                        // the anchored field's text.
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val version: String, val url: String) : UpdateUiState
    data class Failed(val reason: String) : UpdateUiState
}

@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    SetupRow(
        title = stringResource(R.string.update_check_title),
        subtitle = when (val s = state) {
            UpdateUiState.Idle -> stringResource(R.string.update_idle, installed)
            UpdateUiState.Checking -> stringResource(R.string.update_checking)
            UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date, installed)
            is UpdateUiState.Available -> stringResource(R.string.update_available, s.version)
            is UpdateUiState.Failed -> stringResource(R.string.update_failed, s.reason)
        },
        good = when (state) {
            is UpdateUiState.Available -> true
            is UpdateUiState.Failed -> false
            else -> null
        },
        leading = Icons.Outlined.SystemUpdate,
    ) {
        when (val s = state) {
            is UpdateUiState.Available ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
            UpdateUiState.Checking -> {}
            else -> scope.launch {
                state = UpdateUiState.Checking
                val result = withContext(Dispatchers.IO) { UpdateChecker.check(installed) }
                state = when (result) {
                    is UpdateChecker.Result.UpdateAvailable ->
                        UpdateUiState.Available(result.version, result.url)
                    UpdateChecker.Result.UpToDate -> UpdateUiState.UpToDate
                    is UpdateChecker.Result.Failed -> UpdateUiState.Failed(result.reason)
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.AiSettingsSectionImpl() {
    SectionHeader(stringResource(R.string.section_ai_settings))
    SectionCard {
        item { AiModelRow() }
        item { AiRoundsRow() }
        item { AiEffortRow() }
    }
    HintText(stringResource(R.string.pref_ai_effort_hint))
}

@Composable
internal fun UpdateSettingsRowImpl() {
    UpdateRow()
}
