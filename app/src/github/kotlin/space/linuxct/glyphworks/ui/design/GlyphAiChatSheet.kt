package space.linuxct.glyphworks.ui.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ai.AttachedImage
import space.linuxct.glyphworks.ai.ChatFailure
import space.linuxct.glyphworks.ai.canReset
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatRole
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTrace
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphworks.core.ai.GlyphAiTools
import space.linuxct.glyphworks.ui.DIALOG_VERTICAL_MARGIN
import space.linuxct.glyphworks.ui.MotionDialog

/**
 * A `fullScreen` [MotionDialog], which measures the window against the display and stops it
 * fitting system windows, so the keyboard arrives as an inset rather than a resize;
 * [safeDrawingPadding] below is then the only thing accounting for the IME. A dialog and
 * not an Activity, because pushing one would tear down the editor underneath, and the
 * assistant's job is to change the canvas that is on screen now.
 */
@Composable
internal fun GlyphAiChatSheet(designId: String, onDismiss: () -> Unit) {
    val viewModel = glyphAiViewModel()
    // Held as the `State` as well as through the delegate: the auto-scroll below reads the
    // conversation from a `snapshotFlow`, which needs a state object to observe.
    val chatState = viewModel.chat.collectAsStateWithLifecycle()
    val chat by chatState
    val context = LocalContext.current

    var draft by rememberSaveable { mutableStateOf("") }

    // The first thing in the process to touch credential-protected chat storage, and it
    // runs only from an Activity, never from anywhere `Core.init` reaches. See `ChatStore`.
    LaunchedEffect(designId) { viewModel.openChat(designId) }

    // No permission, no dialog, no manifest entry: the picker returns a one-shot read
    // grant for exactly the image the user chose.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.attach(it) } }

    MotionDialog(onDismiss = onDismiss, fullScreen = true) { dismiss ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                var resetAsked by rememberSaveable { mutableStateOf(false) }

                ChatHeader(
                    onClose = dismiss,
                    canReset = chat.canReset(),
                    onReset = { resetAsked = true },
                    onSignOut = {
                        // Closed outright, not through the exit transition. The moment the
                        // token goes the gate falls back to the sign-in door, which would
                        // replace this window with a "Sign in" button mid-animation.
                        viewModel.signOut()
                        onDismiss()
                    },
                )

                if (resetAsked) {
                    ResetChatDialog(
                        onDismiss = { resetAsked = false },
                        onConfirm = {
                            resetAsked = false
                            viewModel.resetChat()
                        },
                    )
                }

                val listState = rememberLazyListState()
                var bottomBarPx by remember { mutableIntStateOf(0) }
                val bottomBarHeight = with(LocalDensity.current) { bottomBarPx.toDp() }

                // Two effects, because the two things that grow the thread grow it at
                // different rates: a message, a step or a failure is worth animating to,
                // while a text delta is thirty events a second, so those snap. See
                // [ThreadEnd] for why both run off a `snapshotFlow` and not effect keys.
                // [bottomBarPx] is a trigger because the bottom stack changing height moves
                // the floor out from under a list already scrolled to its end.
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val state = chatState.value
                        ThreadEnd(
                            lastIndex = info.totalItemsCount - 1,
                            viewportHeight = info.viewportSize.height,
                            messages = state.messages.size,
                            steps = state.steps.size,
                            trace = state.trace,
                            failure = state.failure,
                            bottomBarPx = bottomBarPx,
                        )
                    }.collectLatest { end ->
                        if (end.lastIndex < 0) return@collectLatest
                        // Past the end, and let the list clamp. See [ThreadEnd].
                        listState.animateScrollToItem(end.lastIndex, end.viewportHeight)
                        // Then settle, because the trigger above cannot fire again for this
                        // growth: appending a step row leaves the item count alone and only
                        // makes one item taller. Waiting a frame lets that measure, and
                        // `canScrollForward` then says whether anything is still below.
                        var settles = 0
                        while (settles++ < SETTLE_PASSES) {
                            withFrameNanos {}
                            if (!listState.canScrollForward) break
                            listState.animateScrollToItem(
                                listState.layoutInfo.totalItemsCount - 1,
                                end.viewportHeight,
                            )
                        }
                    }
                }
                // Streaming text changes one item's height and not the count, so it needs
                // its own trigger. The count still has to be the measured one: the first
                // delta lands as the item is created, exactly when a directly read
                // `totalItemsCount` is a frame behind.
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.layoutInfo.totalItemsCount - 1 to chatState.value.streaming.length
                    }.collectLatest { (lastIndex, streamed) ->
                        if (lastIndex < 0 || streamed == 0) return@collectLatest
                        listState.scrollToItem(lastIndex, PAST_THE_END)
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = LIST_HORIZONTAL_PADDING,
                            end = LIST_HORIZONTAL_PADDING,
                            top = LIST_VERTICAL_PADDING,
                            // The measured height of what is pinned below, plus the gap.
                            bottom = LIST_VERTICAL_PADDING + bottomBarHeight,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (chat.restored && chat.messages.isEmpty() && !chat.sending) {
                            item("empty") { ChatEmptyState() }
                        }
                        // Keyed by position, not content: the list only grows at the end,
                        // and two identical messages must not share a key.
                        chat.messages.forEachIndexed { index, message ->
                            item(key = "m$index") { ChatBubble(message) }
                        }
                        if (chat.steps.isNotEmpty()) {
                            item("steps") { StepList(chat.steps) }
                        }
                        if (chat.streaming.isNotEmpty()) {
                            item("streaming") { AssistantText(chat.streaming) }
                        }
                        chat.trace?.let { trace ->
                            item("trace") { TraceRow(trace, chat.startedAtMs) }
                        }
                        chat.failure?.let { failure ->
                            item("failure") {
                                FailureCard(
                                    failure = failure,
                                    onRetry = { viewModel.retry() },
                                    onCopy = { copyToClipboard(context, failure.detail) },
                                    onDismiss = { viewModel.dismissFailure() },
                                )
                            }
                        }
                    }

                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Opaque, and the page's own colour. The list runs underneath
                            // this, so a scrolled message must not show through.
                            .background(MaterialTheme.colorScheme.background)
                            .onSizeChanged { bottomBarPx = it.height },
                    ) {
                        // Pinned above the composer, not left in the scrollback: the change
                        // it undoes happened on the canvas behind this window.
                        if (chat.canRevert) {
                            RevertBanner(onRevert = { viewModel.revertLastChange() })
                        }

                        if (chat.attachFailed) {
                            NoticeRow(
                                text = stringResource(R.string.ai_chat_attach_failed),
                                onDismiss = { viewModel.clearAttachError() },
                            )
                        }

                        if (chat.attachments.isNotEmpty()) {
                            AttachmentRow(
                                attachments = chat.attachments,
                                onRemove = { viewModel.removeAttachment(it) },
                            )
                        }

                        Composer(
                            draft = draft,
                            onDraftChange = { draft = it },
                            sending = chat.sending,
                            // Never before the transcript has been read, or the turn would
                            // go out with no history at all.
                            canSend = chat.restored &&
                                (draft.isNotBlank() || chat.attachments.isNotEmpty()),
                            onAttach = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            // Cleared only if the turn actually started, or the sentence
                            // vanishes into a refusal nobody saw.
                            onSend = { if (viewModel.send(draft)) draft = "" },
                            onStop = { viewModel.stopTurn() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A menu rather than two text buttons, so the title keeps the width at every font scale.
 * [canReset] greys reset out rather than hiding it, so it does not appear and vanish
 * depending on whether a turn is running.
 */
@Composable
private fun ChatHeader(
    onClose: () -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    onSignOut: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tut_close))
        }
        Text(
            stringResource(R.string.ai_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.ai_chat_menu),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // The same 16 dp container the design list's overflow menu takes:
                // `MenuDefaults.shape` is still the pre-expressive 4 dp token.
                shape = MaterialTheme.shapes.large,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_chat_reset)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    enabled = canReset,
                    onClick = {
                        menuOpen = false
                        onReset()
                    },
                )
                // The only way out of a sign-in: once the gate opens straight onto this,
                // the sign-in dialog is unreachable.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_sign_out)) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSignOut()
                    },
                )
            }
        }
    }
}

@Composable
private fun ResetChatDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_chat_reset_title)) },
        text = { Text(stringResource(R.string.ai_chat_reset_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ai_chat_reset_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) }
        },
    )
}

@Composable
private fun ChatEmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            stringResource(R.string.ai_chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ai_chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.USER -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.text.isNotBlank()) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The images are not stored; `ChatTranscript` keeps only a count.
                    if (message.imageCount > 0) {
                        if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.ai_chat_photo_count,
                                message.imageCount,
                                message.imageCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ChatRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            AssistantText(message.text)
            message.tools.forEachIndexed { index, note ->
                Spacer(Modifier.height(4.dp))
                ToolNoteRow(note, attemptOf(message.tools, index))
            }
            // A checkpoint that outlived the process that wrote it. Said, not hidden: a
            // reply stopping mid-sentence otherwise looks like a model that trailed off.
            if (message.partial) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ai_chat_interrupted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A turn takes a minute or two, most of it the model drawing, failing a check and drawing
 * again. One status line repeating "Checking the design…" looks like a hang, so each
 * attempt lands as its own line the moment it resolves.
 */
@Composable
private fun StepList(steps: List<ChatToolNote>) {
    Column(
        Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                if (steps.last().ok) R.string.ai_steps_title else R.string.ai_steps_title_retrying,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        steps.forEachIndexed { index, note -> ToolNoteRow(note, attemptOf(steps, index)) }
    }
}

/**
 * The leading glyph carries the outcome without a second colour. A refresh arrow and not a
 * cross for a step that did not stick, because a failed check is the assistant redrawing,
 * not an error to act on.
 */
@Composable
private fun ToolNoteRow(note: ChatToolNote, attempt: Int) {
    val text = if (note.ok) okText(note, attempt) else failedText(note, attempt)
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (note.ok) Icons.Outlined.Check else Icons.Outlined.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A passing check says the document is legal, not that it is good, and the assistant may
 * still draw another. So a success is numbered like a failure, or the two lines read as
 * the validator approving and the assistant overruling.
 */
@Composable
private fun okText(note: ChatToolNote, attempt: Int): String {
    val res = toolNoteRes(note.name)
    return when {
        note.changedDesign -> stringResource(R.string.ai_tool_applied)
        // A name this build has no string for came from a newer one, so its stored wording
        // is the only truthful thing left to show.
        res == 0 -> note.label
        else -> {
            val arg = stepOkArg(note, attempt)
            if (arg == null) stringResource(res) else stringResource(res, arg)
        }
    }
}

@Composable
private fun failedText(note: ChatToolNote, attempt: Int): String {
    val res = stepFailureRes(note.name)
    val arg = stepFailureArg(note, attempt)
    return if (arg == null) stringResource(res) else stringResource(res, arg)
}

@Composable
private fun TraceRow(trace: ChatTrace, startedAtMs: Long) {
    val arg = trace.messageArg()
    val res = trace.messageRes()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            if (arg == null) stringResource(res) else stringResource(res, arg),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ElapsedLabel(startedAtMs)
    }
}

/**
 * Derived from a start instant in the ViewModel rather than counted here, so a rotation or
 * a reopened modal shows the true age of the turn.
 */
@Composable
private fun ElapsedLabel(startedAtMs: Long) {
    if (startedAtMs <= 0L) return
    var now by remember(startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((now - startedAtMs) / 1_000L).coerceAtLeast(0L)
    if (seconds < ELAPSED_AFTER_SECONDS) return
    Text(
        stringResource(R.string.ai_elapsed, seconds / 60L, seconds % 60L),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The detail is shown verbatim, which is the point of this card: a wrong model id, a
 * rejected header and a stale token all fail as "it didn't work", and only the status and
 * the server's own sentence tell them apart.
 */
@Composable
private fun FailureCard(
    failure: ChatFailure,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(failure.reason.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (failure.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    failure.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.ai_chat_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_dismiss)) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.ai_retry)) }
            }
        }
    }
}

@Composable
private fun RevertBanner(onRevert: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ai_chat_changed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevert) { Text(stringResource(R.string.ai_chat_undo)) }
    }
}

@Composable
private fun NoticeRow(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_dismiss)) }
    }
}

@Composable
private fun AttachmentRow(attachments: List<AttachedImage>, onRemove: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { image ->
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(THUMBNAIL_SIZE),
                ) {
                    image.thumbnail?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                IconButton(
                    onClick = { onRemove(image.id) },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ai_chat_remove_photo),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Centred, not bottom-aligned: an [IconButton] is a 48 dp target around a 24 dp glyph and
 * the field is 56 dp tall, so `Alignment.Bottom` leaves both icons four pixels low. The
 * stop glyph is [Icons.Outlined.StopCircle], not `Stop`, which is a bare 12x12 square in a
 * 24 dp box and looked like the control shrank as it became the only one that does anything.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    canSend: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach, enabled = !sending) {
            Icon(
                Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.ai_chat_attach),
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_chat_hint)) },
            enabled = !sending,
            // Grows to a few lines and then scrolls, so the field cannot swallow the
            // thread it sits under.
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
        )
        // One button, two jobs: while a turn runs the socket read cannot be interrupted,
        // but the user can be let go of it.
        if (sending) {
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Outlined.StopCircle,
                    contentDescription = stringResource(R.string.ai_chat_stop),
                )
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.ai_chat_send),
                )
            }
        }
    }
}

/**
 * No `else`, so adding a trace kind in `core/` breaks this build rather than shipping a
 * blank status line. Tool names are strings and cannot get the same guarantee, so an
 * unknown one falls through to a format string that says the name.
 */
internal fun ChatTrace.messageRes(): Int = when (this) {
    ChatTrace.Thinking -> R.string.ai_trace_thinking
    ChatTrace.Processing -> R.string.ai_trace_processing
    is ChatTrace.RunningTool -> when (name) {
        GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_trace_reading
        GlyphAiTools.APPLY_DESIGN -> R.string.ai_trace_applying
        GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_trace_checking
        else -> R.string.ai_trace_running
    }
}

/** Underscores become spaces, because this lands in a sentence. */
internal fun ChatTrace.messageArg(): String? = when (this) {
    is ChatTrace.RunningTool -> when (name) {
        GlyphAiTools.GET_CURRENT_DESIGN,
        GlyphAiTools.APPLY_DESIGN,
        GlyphAiTools.VALIDATE_DESIGN,
        -> null

        else -> name.replace('_', ' ')
    }

    else -> null
}

/**
 * 0 for a tool this build does not know; the transcript's own stored label is used then,
 * so a conversation from a newer build still says something true.
 */
internal fun toolNoteRes(name: String): Int = when (name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_tool_read
    GlyphAiTools.APPLY_DESIGN -> R.string.ai_tool_applied
    GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_tool_checked
    else -> 0
}

/**
 * Separate from [toolNoteRes]: a step that stuck is named after the tool, one that did not
 * is named after what happens next, since a run of failed checks is the assistant working
 * rather than three identical errors. No 0 case, because an unknown tool still failed.
 */
internal fun stepFailureRes(name: String): Int = when (name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_step_read_failed
    GlyphAiTools.APPLY_DESIGN -> R.string.ai_step_apply_failed
    GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_step_check_failed
    else -> R.string.ai_tool_failed
}

/**
 * The check takes the same number its failure would, so the list reads as a sequence of
 * drafts. An apply is not numbered, and reading the design is not a draft.
 */
internal fun stepOkArg(note: ChatToolNote, attempt: Int): Any? = when (note.name) {
    GlyphAiTools.VALIDATE_DESIGN -> attempt
    else -> null
}

internal fun stepFailureArg(note: ChatToolNote, attempt: Int): Any? = when (note.name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> null
    GlyphAiTools.APPLY_DESIGN, GlyphAiTools.VALIDATE_DESIGN -> attempt
    else -> note.label
}

/**
 * The number is not stored: [ChatToolNote] records what happened, not how often. Derived
 * from a list already in order, so the live list and the scrollback agree.
 */
internal fun attemptOf(notes: List<ChatToolNote>, index: Int): Int =
    notes.take(index + 1).count { it.name == notes[index].name }

/**
 * The two stuck cases are worded apart: one left the canvas alone and the other just
 * changed it, and "kept working without answering" reads oddly over a fresh undo banner.
 */
internal fun GlyphAiOrchestrator.TurnResult.Reason.messageRes(): Int = when (this) {
    GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT -> R.string.ai_chat_error_transport
    GlyphAiOrchestrator.TurnResult.Reason.SERVER -> R.string.ai_chat_error_server
    GlyphAiOrchestrator.TurnResult.Reason.STUCK -> R.string.ai_chat_error_stuck
    GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED -> R.string.ai_chat_error_stuck_salvaged
    GlyphAiOrchestrator.TurnResult.Reason.EMPTY -> R.string.ai_chat_error_empty
}

/**
 * `animateScrollToItem(last)` aligns the item's top with the top of the viewport, leaving
 * the bottom of a tall live block under the composer, so the scroll asks for a position
 * past the end and lets the list clamp. The animated effect asks for one viewport rather
 * than [PAST_THE_END], which it would treat as a distance and cross in a single frame.
 *
 * `layoutInfo` is a measurement, not a plan: an effect keyed on the conversation runs
 * before the new row is measured, so `totalItemsCount - 1` would name the item before it.
 * Building this inside a `snapshotFlow` fixes that, because `layoutInfo` is a
 * `neverEqualPolicy` state assigned at the end of every measure.
 */
private data class ThreadEnd(
    val lastIndex: Int,
    /** The viewport, which is the smallest offset that is always past the end. */
    val viewportHeight: Int,
    val messages: Int,
    val steps: Int,
    val trace: ChatTrace?,
    val failure: ChatFailure?,
    val bottomBarPx: Int,
)

/**
 * The platform manager, because `LocalClipboardManager` is deprecated here and
 * `LocalClipboard` is `suspend`. Android 13 and up shows its own confirmation.
 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
}

private const val CLIP_LABEL = "Glyph assistant error"

/**
 * The pinned bottom stack has no height this file can know, so it reports its size through
 * [onSizeChanged] and the list adds it to this. Not a `Scaffold`: that is a
 * `SubcomposeLayout` which subcomposes inside its measure block, and it was traced to a
 * scrolling stutter and removed from every scroll-heavy screen here.
 */
private val LIST_HORIZONTAL_PADDING = 16.dp

private val LIST_VERTICAL_PADDING = 12.dp

/** A bubble never spans the full width; the alignment is what says who spoke. */
private val BUBBLE_MAX_WIDTH = 300.dp

private val THUMBNAIL_SIZE = 56.dp

/**
 * A scroll offset larger than any bubble, so a snap lands on the end of the item being
 * streamed into rather than its start. `LazyListState` clamps it to the end of the content.
 */
private const val PAST_THE_END = 100_000

/**
 * A bound rather than a bare `while (canScrollForward)`: this runs inside a `collectLatest`,
 * and a condition that could never be satisfied would spin for the whole turn instead of
 * failing visibly. The real case needs one pass.
 */
private const val SETTLE_PASSES = 4

private const val TICK_MS = 1_000L

/** Long enough that a quick answer never flashes a stopwatch. */
private const val ELAPSED_AFTER_SECONDS = 3L
