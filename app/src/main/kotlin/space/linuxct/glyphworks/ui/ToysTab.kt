package space.linuxct.glyphworks.ui

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.preview.ToyPreview
import space.linuxct.glyphworks.screens.ScreenRegistry

private val INTERACTIVE_PREVIEWS = ScreenRegistry.create().filter { it.interactive }.map { it.id }.toSet()

@Composable
internal fun ToysTab(
    innerPadding: PaddingValues,
    listState: LazyListState,
    visible: Boolean,
    onDeckGesture: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val descriptionStyle = MaterialTheme.typography.bodyMedium
    // Android's nonlinear font scaling and font metrics can exceed lineHeight * 2.
    // Measure two actual lines so large text neither truncates early nor moves the deck.
    val descriptionHeight = with(density) {
        textMeasurer.measure("Ag\nAg", style = descriptionStyle, maxLines = 2).size.height.toDp()
    }
    val titleStyle = MaterialTheme.typography.headlineSmall
    val titleHeight = with(density) { textMeasurer.measure("Ag", style = titleStyle).size.height.toDp() }
    val actionStyle = MaterialTheme.typography.labelLarge
    val playLabels = listOf(stringResource(R.string.toys_play), stringResource(R.string.toys_active),
        stringResource(R.string.toys_request_send))
    // All primary labels occupy the same action slot.
    val playLabelWidth = with(density) {
        playLabels.maxOf { label ->
            textMeasurer.measure(label, style = actionStyle).size.width
        }.toDp()
    }
    var savedFocus by rememberSaveable {
        mutableStateOf(Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
    }
    val deck = remember {
        ToyDeckState(
            normalizeToyOrder(
                Core.prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF),
                SCREEN_DISPLAY_NAMES.keys.toList(),
            ), savedFocus,
        )
    }
    val requestSelected = deck.selectedId == ToyRequest.ID
    LaunchedEffect(deck.selectedId) { savedFocus = deck.selectedId }
    // A canceled gesture (including leaving the screen mid-drag) is never persisted.
    DisposableEffect(deck) { onDispose { deck.stop(); onDeckGesture(false) } }
    LaunchedEffect(visible) { if (!visible) { deck.stop(); onDeckGesture(false) } }

    var dialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    LifecycleResumeEffect(visible) {
        resumed = true
        if (visible) revision++
        motion = ValueAnimator.areAnimatorsEnabled()
        onPauseOrDispose { resumed = false; deck.stop(); onDeckGesture(false) }
    }
    var design by remember { mutableStateOf<Design?>(null) }
    LaunchedEffect(revision) {
        design = withContext(Dispatchers.IO) { Core.ports.design.selected() }
    }
    val panelSize = Core.glyphLink.size
    val requestFrame = remember(panelSize) { mutableStateOf(ToyRequest.previewFrame(panelSize)) }
    val player = rememberToyPlayer(
        deck.selectedId, panelSize, revision, design,
        visible && resumed && dialogId == null && !requestSelected, motion,
    )
    var thumbnails by remember { mutableStateOf<Map<String, IntArray>>(emptyMap()) }
    LaunchedEffect(panelSize, revision, design) {
        thumbnails = withContext(Dispatchers.Default) {
            SCREEN_DISPLAY_NAMES.keys.associateWith { id ->
                ToyPreview.thumbnail(id, panelSize, Core.prefs, design)
            } + (ToyRequest.ID to requestFrame.value)
        }
    }
    val currentToy by rememberPref(PrefKeys.CURRENT_SCREEN) {
        it.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
    }
    val enabled = if (requestSelected) false else {
        val checked by rememberPref(PrefKeys.screenEnabled(deck.selectedId)) {
            it.getBoolean(PrefKeys.screenEnabled(deck.selectedId), true)
        }
        checked
    }
    fun persistOrder(order: List<String>) {
        Core.prefs.putString(PrefKeys.SCREEN_ORDER, order.joinToString(","))
    }
    fun toggle(id: String, checked: Boolean) {
        if (id !in SCREEN_DISPLAY_NAMES) return
        if (!checked && deck.order.none { it != id && Core.prefs.getBoolean(PrefKeys.screenEnabled(it), true) }) {
            Toast.makeText(context, R.string.toys_keep_one, Toast.LENGTH_SHORT).show()
            return
        }
        Core.prefs.putBoolean(PrefKeys.screenEnabled(id), checked)
        // Keep the active indicator and the hardware selection in agreement when its toy
        // leaves the rotation. Browsing and enabling never select a toy implicitly.
        if (!checked && currentToy == id) {
            val next = deck.order.first { Core.prefs.getBoolean(PrefKeys.screenEnabled(it), true) }
            Core.scheduler.run { Core.screenManager.selectScreen(next) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxHeight - innerPadding.calculateTopPadding() - innerPadding.calculateBottomPadding()
        val stageHeight = (available * 0.43f).coerceIn(190.dp, 310.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item("stage") {
                ToyStage(
                    if (requestSelected) requestFrame else player.frame, panelSize,
                    Modifier.fillMaxWidth().height(stageHeight).padding(horizontal = 16.dp),
                    onInteract = if (deck.selectedId in INTERACTIVE_PREVIEWS) ({ player.interact() }) else null,
                )
            }
            item("caption") {
                Column(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(titleHeight), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (requestSelected) R.string.toys_request_title else
                                SCREEN_DISPLAY_NAMES.getValue(deck.selectedId)),
                            style = titleStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp).height(descriptionHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(toyDescription(deck.selectedId)),
                            style = descriptionStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item("actions") {
                ToyActions(
                    hasSettings = deck.selectedId in CONFIGURABLE,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (requestSelected) openToyRequest(context) else selectToy(deck.selectedId)
                        },
                        enabled = (requestSelected || enabled) && deck.heldId == null,
                    ) {
                        Icon(
                            when {
                                requestSelected -> Icons.Outlined.Email
                                currentToy == deck.selectedId && enabled -> Icons.Outlined.Check
                                else -> Icons.Outlined.PlayArrow
                            },
                            null, Modifier.size(18.dp),
                        )
                        Text(stringResource(
                            when {
                                requestSelected -> R.string.toys_request_send
                                currentToy == deck.selectedId && enabled -> R.string.toys_active
                                else -> R.string.toys_play
                            },
                        ), Modifier.padding(start = 8.dp).widthIn(min = playLabelWidth), textAlign = TextAlign.Center)
                    }
                    TextButton(
                        onClick = { dialogId = deck.selectedId },
                        enabled = deck.selectedId in CONFIGURABLE && deck.heldId == null,
                    ) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.toys_settings), Modifier.padding(start = 8.dp))
                    }
                }
            }
            item("deck") {
                ToyDeck(deck, panelSize, thumbnails, ::toggle, ::persistOrder, onDeckGesture)
            }
            item("hint") {
                Text(
                    stringResource(when {
                        requestSelected -> R.string.toys_request_hint
                        deck.heldId != null -> R.string.toys_drag_hint
                        else -> R.string.toys_deck_hint
                    }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    dialogId?.let { id ->
        ScreenSettingsDialog(id) { dialogId = null; revision++ }
    }
}

private fun openToyRequest(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, ToyRequest.mailtoUri.toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(ToyRequest.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, ToyRequest.SUBJECT)
        putExtra(Intent.EXTRA_TEXT, ToyRequest.BODY)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.toys_request_no_email, Toast.LENGTH_SHORT).show()
    }
}

/** Measure both actions even when Settings is absent. Unplaced actions have no hit target or semantics. */
@Composable
private fun ToyActions(hasSettings: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val play = measurables[0].measure(loose)
        val settings = measurables[1].measure(loose)
        val gap = 10.dp.roundToPx()
        val rowWidth = play.width + gap + settings.width
        val horizontal = rowWidth <= constraints.maxWidth
        val height = if (horizontal) maxOf(play.height, settings.height)
            else play.height + 6.dp.roundToPx() + settings.height
        layout(constraints.maxWidth, height) {
            if (horizontal && hasSettings) {
                val left = (constraints.maxWidth - rowWidth) / 2
                play.placeRelative(left, (height - play.height) / 2)
                settings.placeRelative(left + play.width + gap, (height - settings.height) / 2)
            } else {
                play.placeRelative((constraints.maxWidth - play.width) / 2, if (horizontal) (height - play.height) / 2 else 0)
                if (hasSettings) settings.placeRelative((constraints.maxWidth - settings.width) / 2, play.height + 6.dp.roundToPx())
            }
        }
    }
}

@Stable
private class ToyPlayerState(size: Int) {
    val frame = mutableStateOf(IntArray(size * size))
    var engine: ToyPreview? = null
    fun interact() {
        engine?.let { it.interact(); frame.value = it.frame }
    }
}

@Composable
private fun rememberToyPlayer(
    id: String,
    size: Int,
    revision: Int,
    design: Design?,
    running: Boolean,
    motion: Boolean,
): ToyPlayerState {
    val state = remember(size) { ToyPlayerState(size) }
    LaunchedEffect(id, size, revision, design, running, motion) {
        if (!running) return@LaunchedEffect
        val engine = ToyPreview(size, Core.prefs, design)
        state.engine = engine
        try {
            engine.select(id)
            engine.advance(700)
            state.frame.value = engine.frame
            if (motion) {
                var previous = System.nanoTime()
                while (isActive) {
                    delay(50)
                    val now = System.nanoTime()
                    engine.advance(((now - previous) / 1_000_000).coerceIn(1, 150))
                    previous = now
                    val frame = engine.frame
                    if (!state.frame.value.contentEquals(frame)) state.frame.value = frame
                }
            } else {
                // Keep tap interaction available without advancing an automatic animation.
                kotlinx.coroutines.awaitCancellation()
            }
        } finally {
            state.engine = null
            engine.close()
        }
    }
    return state
}

private fun toyDescription(id: String): Int = when (id) {
    ToyRequest.ID -> R.string.toys_request_description
    "ambient" -> R.string.toys_desc_ambient
    "clock" -> R.string.toys_desc_clock
    "eyes" -> R.string.toys_desc_eyes
    "speed" -> R.string.toys_desc_speed
    "battery" -> R.string.toys_desc_battery
    "notifications" -> R.string.toys_desc_notifications
    "weather" -> R.string.toys_desc_weather
    "solar" -> R.string.toys_desc_solar
    "moon" -> R.string.toys_desc_moon
    "dice" -> R.string.toys_desc_dice
    "coin" -> R.string.toys_desc_coin
    "dino" -> R.string.toys_desc_dino
    "bottle" -> R.string.toys_desc_bottle
    "counter" -> R.string.toys_desc_counter
    "breathing" -> R.string.toys_desc_breathing
    "timer" -> R.string.toys_desc_timer
    "compass" -> R.string.toys_desc_compass
    "level" -> R.string.toys_desc_level
    "visualizer" -> R.string.toys_desc_visualizer
    else -> R.string.toys_desc_custom
}
