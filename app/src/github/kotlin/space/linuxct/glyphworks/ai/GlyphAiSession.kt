package space.linuxct.glyphworks.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiMaxRounds
import space.linuxct.glyphworks.core.ai.aiReasoningEffort
import space.linuxct.glyphworks.core.ai.ChatInputItem
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatMessageItem
import space.linuxct.glyphworks.core.ai.ChatRole
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTrace
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphworks.core.ai.GlyphAiPrompt
import space.linuxct.glyphworks.core.ai.GlyphChatClient
import space.linuxct.glyphworks.core.ai.GlyphToolContext
import space.linuxct.glyphworks.core.ai.PendingApply
import space.linuxct.glyphworks.core.ai.PendingApplyVerdict
import space.linuxct.glyphworks.core.ai.SourceImage
import space.linuxct.glyphworks.core.ai.pendingApplyVerdict
import space.linuxct.glyphworks.core.design.Design
import kotlin.coroutines.CoroutineContext

/** [ChatStore] implements this. */
interface TranscriptStore {
    suspend fun load(designId: String): ChatTranscript?
    suspend fun save(transcript: ChatTranscript)
    suspend fun delete(designId: String)
}

/** [PendingApplyStore] implements this. */
interface PendingApplyRecords {
    /** Reads **and removes** the record for [designId]. */
    suspend fun take(designId: String): PendingApply?
    suspend fun put(record: PendingApply)
}

fun interface StoredDesignFacts {
    suspend fun modifiedAt(designId: String): String?
}

/**
 * Holds the process up while a turn runs; without it the low-memory killer takes a
 * backgrounded turn. Both called on the main thread.
 */
interface TurnForeground {
    fun turnStarted(designId: String, designName: String)

    fun turnEnded()
}

/** What the app writes as the assistant when the assistant itself did not get to. */
interface TurnNotices {
    fun changedTheDesign(reason: GlyphAiOrchestrator.TurnResult.Reason): String

    /** Never called for [PendingApplyVerdict.APPLY]. */
    fun deferredApplyDropped(verdict: PendingApplyVerdict): String
}

class TurnRequest(
    val context: GlyphToolContext,
    val history: List<ChatInputItem>,
    val message: ChatMessageItem,
    val applyDesign: (Design) -> String?,
    val onTrace: (ChatTrace) -> Unit,
    val onToolNote: (ChatToolNote) -> Unit,
    val onTextDelta: (String) -> Unit,
)

/** Runs one turn against the model. The real one builds a [GlyphAiOrchestrator]. */
fun interface TurnRunner {
    suspend fun run(request: TurnRequest): GlyphAiOrchestrator.TurnResult
}

/**
 * Owned by the process, not by a screen, so closing the editor does not kill a turn.
 * [stopTurn] is the only thing that ends one early, and a turn that changed the design has
 * to be explainable afterwards; see [noticeFor] and [applyDeferred].
 *
 * [openChat] points this at one design. A turn on another design keeps running and writes
 * to its own transcript, but stops touching the visible state. See [viewEpoch].
 */
class GlyphAiSession internal constructor(
    /**
     * Application-scoped, dispatched on Main: a turn reads and writes the editor's live
     * frame buffers, which is main-thread work. The network hops to IO on its own.
     */
    private val scope: CoroutineScope,
    private val transcripts: TranscriptStore,
    private val pendingApplies: PendingApplyRecords,
    private val designs: StoredDesignFacts,
    private val foreground: TurnForeground,
    private val notices: TurnNotices,
    private val runner: TurnRunner,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _chat = MutableStateFlow(GlyphChatState())
    val chat: StateFlow<GlyphChatState> = _chat.asStateFlow()

    /** The conversation of record: what is written to disk and replayed to the model. */
    private var transcript = ChatTranscript()

    private var turn: Job? = null

    private var editor: GlyphEditorBridge? = null

    private var revertSnapshot: Design? = null

    private var revertOf: String = ""

    private var lastTurn: PendingTurn? = null

    /**
     * A turn captures this at the start and stops writing to [chat] once it no longer
     * matches, so one conversation's deltas never appear in another. Reopening the same
     * design mid-turn does not bump it, so a live turn is still on screen when you return.
     */
    private var viewEpoch = 0

    /** [correctDeferred] joins this rather than racing the read. */
    private var openJob: Job? = null

    /**
     * FIFO on one consumer, unbounded so a send never blocks the main thread. Writes are
     * ordered so a checkpoint cannot land after the reply that replaced it. Reads share the
     * queue because [correctDeferred] is a load-modify-save, and a load slipping between its
     * two halves would give the in-memory copy one message too few.
     */
    private val persistQueue = Channel<PersistOp>(Channel.UNLIMITED)

    init {
        scope.launch(ioContext) {
            for (op in persistQueue) {
                try {
                    when (op) {
                        is PersistOp.Save -> transcripts.save(op.transcript)
                        is PersistOp.Delete -> transcripts.delete(op.designId)
                        is PersistOp.Load -> op.answer(transcripts.load(op.designId))
                        is PersistOp.Correct -> op.answer(correctOnDisk(op))
                    }
                } catch (e: Exception) {
                    // One throw here would end the loop and stop all persisting.
                    DebugLog.w(TAG, "could not persist: ${e.message}")
                } finally {
                    // An op that threw before it answered would strand its caller forever.
                    // Completing a settled deferred is a no-op.
                    op.answer(null)
                }
            }
        }
    }

    /**
     * On the queue's own thread, so nothing else can read or write this transcript between
     * the load and the save. A missing file says nothing; see [ChatTranscript.withCorrection].
     */
    private suspend fun correctOnDisk(op: PersistOp.Correct): ChatTranscript? {
        val stored = transcripts.load(op.designId)?.copy(designId = op.designId)
        val corrected = stored?.withCorrection(op.message) ?: return null
        transcripts.save(corrected)
        return corrected
    }

    /** Also hands [bridge] anything the assistant finished while there was no editor. */
    fun setEditor(bridge: GlyphEditorBridge) {
        editor = bridge
        scope.launch {
            try {
                applyDeferred(bridge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This scope's exceptions reach the thread's uncaught handler, and a
                // drawing that could not be handed over is not worth the process.
                DebugLog.w(TAG, "could not hand over a waiting change: ${e.message}")
            }
        }
    }

    /**
     * Only if [bridge] is still the registered one: a configuration change disposes the
     * outgoing composition after the incoming one registers, so an unconditional clear
     * would remove the live editor. Never cancels the turn.
     */
    fun clearEditor(bridge: GlyphEditorBridge) {
        if (editor === bridge) editor = null
    }

    /**
     * Call this from the chat modal, never from anything `Core.init` touches: it forces
     * chat storage, which creates a credential-protected directory and so needs the first
     * unlock. See [ChatStore]. The early return keeps a live turn on screen when the same
     * design is reopened.
     */
    fun openChat(designId: String) {
        val current = _chat.value
        if (current.designId == designId && current.restored) return
        viewEpoch++
        _chat.value = GlyphChatState(
            designId = designId,
            // The banner is about the canvas, not the conversation. A deferred apply lands
            // before anybody opens the chat, and it still needs a way back.
            canRevert = revertSnapshot != null && revertOf == designId,
        )
        openJob = scope.launch {
            // Through the queue, since a correction may be mid-flight on the same file.
            val loaded = if (designId.isBlank()) null else awaitOp { PersistOp.Load(designId, it) }
            transcript = loaded?.copy(designId = designId) ?: ChatTranscript(designId = designId)
            _chat.update {
                if (it.designId != designId) it
                else it.copy(restored = true, messages = transcript.messages)
            }
        }
    }

    private suspend fun awaitOp(
        build: (CompletableDeferred<ChatTranscript?>) -> PersistOp,
    ): ChatTranscript? {
        val answer = CompletableDeferred<ChatTranscript?>()
        if (persistQueue.trySend(build(answer)).isFailure) return null
        return answer.await()
    }

    /** Forgets what was said; it does not undo what was drawn. See [cleared]. */
    fun resetChat(): Boolean {
        val state = _chat.value
        if (!state.canReset()) return false
        val designId = state.designId
        transcript = ChatTranscript(designId = designId)
        lastTurn = null
        _chat.value = state.cleared()
        if (designId.isNotBlank()) persistQueue.trySend(PersistOp.Delete(designId))
        return true
    }

    fun attached(image: AttachedImage) {
        _chat.update {
            if (it.attachments.size >= MAX_ATTACHMENTS) it
            else it.copy(attachments = it.attachments + image)
        }
    }

    fun attachFailed() {
        _chat.update { it.copy(attachFailed = true) }
    }

    fun removeAttachment(id: Long) {
        _chat.update { it.copy(attachments = it.attachments.filterNot { image -> image.id == id }) }
    }

    fun clearAttachError() {
        _chat.update { it.copy(attachFailed = false) }
    }

    fun dismissFailure() {
        _chat.update { it.copy(failure = null) }
    }

    fun attachmentsFull(): Boolean = _chat.value.attachments.size >= MAX_ATTACHMENTS

    /** False means nothing was sent, and the caller must keep what the user typed. */
    fun send(text: String): Boolean {
        val state = _chat.value
        val trimmed = text.trim()
        if (trimmed.isEmpty() && state.attachments.isEmpty()) return false
        if (state.sending) return false
        return startTurn(
            PendingTurn(
                text = trimmed,
                imageDataUrls = state.attachments.map { it.dataUrl },
                // The same photos, as pixels, for `image_to_grid`. Held on the pending turn
                // so a retry converts what was sent, not what is attached now.
                images = state.attachments.mapNotNull { it.source },
            ),
            record = true,
        )
    }

    fun retry() {
        val pending = lastTurn ?: return
        if (_chat.value.sending) return
        startTurn(pending, record = false)
    }

    /**
     * The socket read cannot be interrupted, so this frees the user, not the connection.
     * Anything already applied stays applied and stays revertible.
     */
    fun stopTurn() {
        turn?.cancel()
        turn = null
        _chat.update { it.turnEnded() }
    }

    private fun GlyphChatState.turnEnded(): GlyphChatState =
        copy(sending = false, streaming = "", trace = null, steps = emptyList(), startedAtMs = 0L)

    /**
     * One step, not a stack: a whole-document swap does not fit the editor's per-frame
     * undo, and keeping every snapshot would cost a megabyte of frames per turn.
     */
    fun revertLastChange() {
        val snapshot = revertSnapshot ?: return
        if (revertOf != _chat.value.designId) return
        val bridge = editor ?: return
        if (bridge.apply(snapshot) is GlyphApplyResult.Applied) {
            revertSnapshot = null
            revertOf = ""
            _chat.update { it.copy(canRevert = false) }
        }
    }

    private fun startTurn(pending: PendingTurn, record: Boolean): Boolean {
        if (turn?.isActive == true) return false
        val bridge = editor ?: run {
            // A turn needs an editor to start, because there has to be a drawing to read.
            // It may finish with none; that is what the deferred apply is for.
            DebugLog.w(TAG, "no editor is registered; nothing was sent")
            return false
        }
        val context = bridge.snapshot().copy(images = pending.images)
        val designId = _chat.value.designId
        // Captured before the new message is appended. The orchestrator takes the new turn
        // separately, so history holding it too would send it twice.
        val history = (if (record) transcript else transcript.withoutTrailingUser()).asInput()
        if (record) {
            appendMessage(
                ChatMessage(
                    role = ChatRole.USER,
                    text = pending.text,
                    atMs = now(),
                    imageCount = pending.imageDataUrls.size,
                ),
            )
        }
        lastTurn = pending
        _chat.update {
            it.copy(
                sending = true,
                streaming = "",
                trace = ChatTrace.Thinking,
                steps = emptyList(),
                startedAtMs = now(),
                failure = null,
                attachments = emptyList(),
            )
        }

        val epoch = viewEpoch
        val base = transcript
        foreground.turnStarted(designId, context.design.name)

        turn = scope.launch {
            val streamed = StringBuilder()
            val notes = mutableListOf<ChatToolNote>()
            var checkpointedAt = 0L
            var checkpointOnDisk = false
            var appended = false

            /** Writes what has arrived so far, so a killed process leaves a record. */
            fun checkpoint() {
                if (base.designId.isBlank()) return
                if (streamed.isEmpty() && notes.isEmpty()) return
                checkpointedAt = now()
                checkpointOnDisk = true
                persistQueue.trySend(
                    PersistOp.Save(
                        base.withPartial(
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                text = streamed.toString(),
                                atMs = checkpointedAt,
                                tools = notes.toList(),
                                partial = true,
                            ),
                        ),
                    ),
                )
            }

            try {
                val result = runner.run(
                    TurnRequest(
                        context = context,
                        history = history,
                        message = ChatMessageItem.user(pending.text, pending.imageDataUrls),
                        applyDesign = { design -> applyFromModel(design, designId, epoch) },
                        onTrace = { trace ->
                            // The screen drops a preamble when a tool starts, so the
                            // checkpoint drops it too. See [onTrace].
                            if (trace is ChatTrace.RunningTool) streamed.setLength(0)
                            onTrace(trace, epoch)
                        },
                        onToolNote = { note ->
                            notes += note
                            updateFor(epoch) { it.copy(steps = it.steps + note) }
                            // Unthrottled: tool notes are rare, and are the visible record
                            // of a slow turn.
                            checkpoint()
                        },
                        onTextDelta = { delta ->
                            streamed.append(delta)
                            updateFor(epoch) { it.copy(streaming = it.streaming + delta) }
                            // The first fragment writes at once, so a turn killed three
                            // seconds in does not look like one that never began.
                            if (checkpointedAt == 0L || now() - checkpointedAt >= CHECKPOINT_INTERVAL_MS) {
                                checkpoint()
                            }
                        },
                    ),
                )
                when (result) {
                    is GlyphAiOrchestrator.TurnResult.Success -> {
                        commit(
                            base,
                            epoch,
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                text = result.text,
                                atMs = now(),
                                tools = result.toolNotes,
                            ),
                        )
                        appended = true
                        updateFor(epoch) { it.turnEnded() }
                    }

                    is GlyphAiOrchestrator.TurnResult.Failure -> {
                        DebugLog.w(
                            TAG,
                            "turn failed (${result.reason}) after ${result.rounds} round(s): ${result.detail}",
                        )
                        val notice = noticeFor(result)
                        if (notice != null) {
                            commit(base, epoch, notice)
                            appended = true
                        }
                        updateFor(epoch) {
                            it.turnEnded().copy(failure = ChatFailure(result.reason, result.detail))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `GlyphAiOrchestrator` never throws, so this is a bug. An exception
                // escaping this application-scoped launch would take the process down.
                DebugLog.w(TAG, "turn threw: ${e.javaClass.simpleName}: ${e.message}")
                updateFor(epoch) {
                    it.turnEnded().copy(
                        failure = ChatFailure(
                            GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT,
                            "${e.javaClass.simpleName}: ${e.message}",
                        ),
                    )
                }
            } finally {
                foreground.turnEnded()
                // The turn is over, so its checkpoint describes something no longer
                // happening. Nothing replaced it above, so drop it.
                if (checkpointOnDisk && !appended) {
                    persistQueue.trySend(PersistOp.Save(base.withoutPartial()))
                }
            }
        }
        return true
    }

    /**
     * A failed turn that changed nothing stores nothing, or the thread would fill up with
     * "couldn't reach the service"; one that changed the design has to be explainable
     * later. The note carries [ChatMessage.error], which [ChatTranscript.asInput] drops, so
     * the model never sees its own failures replayed.
     */
    private fun noticeFor(result: GlyphAiOrchestrator.TurnResult.Failure): ChatMessage? {
        if (result.appliedDesign == null) return null
        return ChatMessage(
            role = ChatRole.ASSISTANT,
            text = notices.changedTheDesign(result.reason),
            atMs = now(),
            tools = result.toolNotes,
            error = true,
        )
    }

    /**
     * Reads [editor] afresh, so a design produced after a rotation lands on the editor that
     * is on screen now. A returned string is a failed tool call the model sees, not
     * user-facing copy. With no editor open the change is recorded and applied on the next
     * open, and the model is told it succeeded. See [applyDeferred].
     */
    private fun applyFromModel(design: Design, designId: String, epoch: Int): String? {
        val bridge = editor
        if (bridge == null) {
            if (designId.isBlank()) {
                return "There is no design open to change."
            }
            defer(design, designId)
            return null
        }
        return when (val outcome = bridge.apply(design)) {
            is GlyphApplyResult.Applied -> {
                revertSnapshot = outcome.previous
                revertOf = designId
                updateFor(epoch) { it.copy(canRevert = true) }
                null
            }

            is GlyphApplyResult.Refused -> outcome.reason
        }
    }

    /**
     * The baseline is read from disk here, not at the start of the turn: the editor writes
     * on its way out, so this has to compare against what close left behind. See
     * [PendingApply] for the conflict rule.
     */
    private fun defer(design: Design, designId: String) {
        scope.launch {
            try {
                val base = withContext(ioContext) { designs.modifiedAt(designId) }.orEmpty()
                val record = PendingApply(
                    designId = designId,
                    baseModifiedAt = base,
                    atMs = now(),
                    design = design,
                )
                withContext(ioContext) { pendingApplies.put(record) }
                DebugLog.i(TAG, "no editor open; $designId will take the change on next open")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "could not record a change for $designId: ${e.message}")
            }
        }
    }

    /**
     * The user's own strokes always win over the model's draft, because a drawing can be
     * asked for again and strokes cannot. The record is consumed either way, so a draft
     * that cannot land does not re-offer itself; one that does not land is said out loud in
     * the thread, since the reply already claimed the change. See [correctDeferred].
     */
    private suspend fun applyDeferred(bridge: GlyphEditorBridge) {
        val designId = bridge.snapshot().design.id
        if (designId.isBlank()) return
        val record = withContext(ioContext) { pendingApplies.take(designId) } ?: return
        val current = withContext(ioContext) { designs.modifiedAt(designId) }
        val verdict = pendingApplyVerdict(record, current, now())
        if (verdict != PendingApplyVerdict.APPLY) {
            DebugLog.i(TAG, "a change waiting for $designId was dropped: $verdict")
            correctDeferred(designId, verdict)
            return
        }
        // The editor may have gone again while the two reads above were running.
        if (editor !== bridge) return
        when (val outcome = bridge.apply(record.design)) {
            is GlyphApplyResult.Applied -> {
                revertSnapshot = outcome.previous
                revertOf = designId
                _chat.update { if (it.designId == designId) it.copy(canRevert = true) else it }
                DebugLog.i(TAG, "applied the change that was waiting for $designId")
            }

            is GlyphApplyResult.Refused -> DebugLog.w(TAG, "the editor refused: ${outcome.reason}")
        }
    }

    /**
     * Runs from [setEditor], usually before [openChat] has read that transcript, so it
     * races a reader. Join [openJob] first, because "is this thread in memory?" has no
     * answer while a read is arriving; then either append in memory or go through
     * [persistQueue] as a [PersistOp.Correct], where no load can slip between its read and
     * its write. The reconciliation at the end covers an [openChat] that started during the
     * join and queued its load first.
     */
    private suspend fun correctDeferred(designId: String, verdict: PendingApplyVerdict) {
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = notices.deferredApplyDropped(verdict),
            atMs = now(),
            // A notice, not something the assistant said, so `asInput` drops it.
            error = true,
        )
        openJob?.join()
        val onScreen = _chat.value
        if (onScreen.designId == designId && onScreen.restored) {
            // An empty thread is left empty. See [ChatTranscript.withCorrection].
            if (transcript.messages.isEmpty()) return
            appendMessage(message)
            return
        }
        val corrected = awaitOp { PersistOp.Correct(designId, message, it) } ?: return
        if (_chat.value.designId != designId) return
        transcript = corrected
        _chat.update {
            if (it.designId == designId && it.restored) it.copy(messages = corrected.messages) else it
        }
    }

    /** Only while the conversation on screen is still this turn's. See [viewEpoch]. */
    private fun updateFor(epoch: Int, block: (GlyphChatState) -> GlyphChatState) {
        if (viewEpoch != epoch) return
        _chat.update(block)
    }

    private fun onTrace(trace: ChatTrace, epoch: Int) {
        updateFor(epoch) {
            when (trace) {
                // Text before a tool call is thinking out loud, not the answer. Drop it, or
                // the reply arrives stuck to a preamble.
                is ChatTrace.RunningTool -> it.copy(trace = trace, streaming = "")
                else -> it.copy(trace = trace)
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        transcript = transcript.plus(message)
        _chat.update { it.copy(messages = transcript.messages) }
        if (transcript.designId.isNotBlank()) {
            persistQueue.trySend(PersistOp.Save(transcript))
        }
    }

    /**
     * Uses [base], not [transcript], because the user may have opened another design
     * meanwhile. The reply still belongs in the thread that asked for it.
     */
    private fun commit(base: ChatTranscript, epoch: Int, message: ChatMessage) {
        val next = base.withoutPartial().plus(message)
        if (viewEpoch == epoch) {
            transcript = next
            _chat.update { it.copy(messages = next.messages) }
        }
        if (next.designId.isNotBlank()) persistQueue.trySend(PersistOp.Save(next))
    }

    /** See [retry]: the user's message is already stored, so it is not appended twice. */
    private fun ChatTranscript.withoutTrailingUser(): ChatTranscript =
        if (messages.lastOrNull()?.role == ChatRole.USER) copy(messages = messages.dropLast(1)) else this

    /**
     * [imageDataUrls] is what the model sees; [images] is the same pictures as brightness
     * grids, which is what `image_to_grid` converts. Neither is stored afterwards.
     */
    private data class PendingTurn(
        val text: String,
        val imageDataUrls: List<String>,
        val images: List<SourceImage> = emptyList(),
    )

    /**
     * One unit of work on conversation storage. See [persistQueue].
     *
     * [Load] and [Correct] carry a [CompletableDeferred] to answer with. Waiting on it is
     * also what orders them against the writes.
     */
    private sealed interface PersistOp {
        data class Save(val transcript: ChatTranscript) : PersistOp
        data class Delete(val designId: String) : PersistOp
        data class Load(
            val designId: String,
            val answer: CompletableDeferred<ChatTranscript?>,
        ) : PersistOp

        data class Correct(
            val designId: String,
            val message: ChatMessage,
            val answer: CompletableDeferred<ChatTranscript?>,
        ) : PersistOp
    }

    /** Settling twice is a no-op, so the consumer's `finally` can call this always. */
    private fun PersistOp.answer(value: ChatTranscript?) {
        when (this) {
            is PersistOp.Load -> answer.complete(value)
            is PersistOp.Correct -> answer.complete(value)
            is PersistOp.Save, is PersistOp.Delete -> Unit
        }
    }

    companion object {
        private const val TAG = "GlyphAi"

        /** Each is up to 1024 px of JPEG as base64, and the target is a 13x13 drawing. */
        const val MAX_ATTACHMENTS = 4

        /**
         * Deltas land about thirty times a second, and writing on each would be thirty file
         * writes a second. Two seconds bounds what a process death loses to about a
         * sentence.
         */
        const val CHECKPOINT_INTERVAL_MS = 2_000L

        /**
         * The scope belongs to the process, so an escaping exception would reach the
         * thread's uncaught handler and take the app down.
         */
        private val crashGuard = CoroutineExceptionHandler { _, e ->
            DebugLog.w(TAG, "uncaught in the assistant's scope: ${e.javaClass.simpleName}: ${e.message}")
        }

        @Volatile
        private var instance: GlyphAiSession? = null

        /**
         * Nothing reachable from `Core.init` may call this: it builds [ChatStore],
         * [PendingApplyStore] and [TokenStore], which all need credential-protected
         * storage, and `Core.init` can run before the first unlock.
         */
        fun of(context: Context): GlyphAiSession {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(app: Context): GlyphAiSession {
            val chats = ChatStore(app) { Core.designStore.storedIds() }
            val pending = PendingApplyStore(app)
            val client: GlyphChatClient by lazy { GlyphAiClient(TokenStore(app)) }
            return GlyphAiSession(
                scope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Main.immediate + crashGuard,
                ),
                transcripts = object : TranscriptStore {
                    override suspend fun load(designId: String) = chats.load(designId)
                    override suspend fun save(transcript: ChatTranscript) {
                        chats.save(transcript)
                    }

                    override suspend fun delete(designId: String) {
                        chats.delete(designId)
                    }
                },
                pendingApplies = object : PendingApplyRecords {
                    override suspend fun take(designId: String) = pending.take(designId)
                    override suspend fun put(record: PendingApply) {
                        pending.put(record)
                    }
                },
                designs = StoredDesignFacts { Core.designStore.load(it)?.modifiedAt },
                foreground = GlyphAiTurnNotifier(app),
                notices = object : TurnNotices {
                    override fun changedTheDesign(
                        reason: GlyphAiOrchestrator.TurnResult.Reason,
                    ): String = app.getString(
                        when (reason) {
                            GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED ->
                                R.string.ai_chat_notice_salvaged

                            else -> R.string.ai_chat_notice_changed
                        },
                    )

                    override fun deferredApplyDropped(verdict: PendingApplyVerdict): String =
                        app.getString(
                            when (verdict) {
                                PendingApplyVerdict.CONFLICT ->
                                    R.string.ai_chat_notice_deferred_conflict

                                PendingApplyVerdict.EXPIRED ->
                                    R.string.ai_chat_notice_deferred_expired

                                // APPLY never reaches here. If it did, "couldn't find it"
                                // is the safe thing to say.
                                else -> R.string.ai_chat_notice_deferred_missing
                            },
                        )
                },
                runner = TurnRunner { request ->
                    GlyphAiOrchestrator(
                        client = client,
                        // All three are read per turn, not once at build: each setting
                        // exists to rescue a turn you just watched go wrong, so it has to
                        // take effect on the very next message.
                        model = ChatWire.resolveModel(
                            Core.prefs.getString(AiPrefKeys.MODEL, AiPrefKeys.MODEL_DEF),
                        ),
                        maxRounds = Core.prefs.aiMaxRounds(),
                        reasoningEffort = Core.prefs.aiReasoningEffort().wire,
                        applyDesign = request.applyDesign,
                        onTrace = request.onTrace,
                        onToolNote = request.onToolNote,
                    ).runTurn(
                        instructions = GlyphAiPrompt.build(request.context.design),
                        history = request.history,
                        message = request.message,
                        context = request.context,
                        onTextDelta = request.onTextDelta,
                    )
                },
            )
        }
    }
}
