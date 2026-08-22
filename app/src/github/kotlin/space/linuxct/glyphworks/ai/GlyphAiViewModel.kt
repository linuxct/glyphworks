package space.linuxct.glyphworks.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTrace
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphworks.core.ai.GlyphToolContext
import space.linuxct.glyphworks.core.design.Design
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

enum class SignInFailure {
    TIMED_OUT,
    PORT_BUSY,
    NO_BROWSER,
    FAILED,
}

/** One object, so "signed in" and "waiting for the browser" cannot show together. */
data class GlyphAiAuthState(
    val signedIn: Boolean,
    val busy: Boolean = false,
    val failure: SignInFailure? = null,
    val detail: String? = null,
    /** Here so [aiGate] reads it and [signedIn] together, never a stale half of the pair. */
    val consented: Boolean = false,
)

/**
 * How the assistant reaches the canvas. Main thread only: [GlyphToolContext] is built from
 * the live frame buffers the pointer handler writes from that same thread.
 */
interface GlyphEditorBridge {
    /** The design as shown, unsaved edits included. */
    fun snapshot(): GlyphToolContext

    fun apply(design: Design): GlyphApplyResult
}

sealed interface GlyphApplyResult {
    /** [previous] is what "Undo AI change" restores. */
    data class Applied(val previous: Design) : GlyphApplyResult

    /** [reason] goes to the model, not the user, as the tool's result. */
    data class Refused(val reason: String) : GlyphApplyResult
}

data class ChatFailure(
    val reason: GlyphAiOrchestrator.TurnResult.Reason,
    /**
     * Never replaced with a friendlier sentence: a wrong model id, a rejected header and an
     * expired token all look like "it didn't work", and only the status and body tell them
     * apart.
     */
    val detail: String,
)

/**
 * [streaming] is the reply arriving now and only becomes a message when the turn finishes.
 * It is checkpointed to disk meanwhile as a crash record; see `ChatTranscript.withPartial`.
 */
data class GlyphChatState(
    val designId: String = "",
    /** True once the transcript has been read, or found not to exist. */
    val restored: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val trace: ChatTrace? = null,
    /**
     * Lives in [GlyphAiSession] so it survives a rotation. Clearing it at the end loses
     * nothing: the same calls reappear as [ChatMessage.tools].
     */
    val steps: List<ChatToolNote> = emptyList(),
    /** Elapsed time is derived from this, so a rotation does not restart the clock. */
    val startedAtMs: Long = 0L,
    val sending: Boolean = false,
    val attachments: List<AttachedImage> = emptyList(),
    val attachFailed: Boolean = false,
    val failure: ChatFailure? = null,
    val canRevert: Boolean = false,
)

/**
 * No turn in flight: a running turn ends by appending its reply, which would write a thread
 * straight back into one just emptied. [restored], because the transcript is read
 * asynchronously and a clear before it arrives would land the file on the empty screen.
 */
internal fun GlyphChatState.canReset(): Boolean =
    restored && !sending && messages.isNotEmpty()

/**
 * [canRevert] survives: the banner is a way back from a change to the artwork, and a reset
 * never touches the artwork.
 */
internal fun GlyphChatState.cleared(): GlyphChatState =
    GlyphChatState(designId = designId, restored = restored, canRevert = canRevert)

/**
 * Owns the sign-in and forwards everything else to [GlyphAiSession]. The sign-in is
 * activity-scoped because it waits on a `ServerSocket` for up to ten minutes while the user
 * types a password in a browser.
 *
 * Cancelling has to close the socket, not just the job: `ServerSocket.accept()` is not
 * interruptible by coroutine cancellation, so port 1455 would stay bound and the next
 * attempt would fail to bind it. [releaseCallbackPort] pokes the port with a throwaway
 * loopback connection to unblock `accept()`, on its own thread, because [onCleared] cancels
 * [viewModelScope] first and that is exactly when the port must go.
 */
class GlyphAiViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Credential-protected; see [TokenStore]. Built from the Application, the ordinary
     * context. Nothing here may use the device-protected one.
     */
    private val tokens = TokenStore(app)

    /** Also credential-protected, also from the ordinary context. */
    private val consent: AiConsentStorage = AiConsentStore(app)

    /**
     * Nothing may force this during construction: chat storage is only touched once the
     * user opens the chat. See [GlyphAiSession.of].
     */
    private val session: GlyphAiSession by lazy { GlyphAiSession.of(app) }

    private val _state = MutableStateFlow(
        GlyphAiAuthState(signedIn = tokens.isSignedIn, consented = consent.accepted),
    )
    val state: StateFlow<GlyphAiAuthState> = _state.asStateFlow()

    val chat: StateFlow<GlyphChatState> get() = session.chat

    private var job: Job? = null

    private var nextAttachmentId = 0L

    /**
     * [openBrowser] is called synchronously and then forgotten: it closes over an Activity,
     * and the coroutine below can live for ten minutes.
     */
    fun signIn(openBrowser: (String) -> Unit) {
        if (job?.isActive == true) return

        val flow = try {
            createOAuthFlow()
        } catch (e: Exception) {
            fail(SignInFailure.FAILED, e)
            return
        }

        _state.value = authState(signedIn = false, busy = true)

        try {
            openBrowser(flow.url)
        } catch (e: Exception) {
            // ActivityNotFoundException and friends. Either way there is no browser.
            fail(SignInFailure.NO_BROWSER, e)
            return
        }

        job = viewModelScope.launch {
            try {
                val code = waitForOAuthCode(flow.state)
                val issued = exchangeAuthorizationCode(code, flow.verifier)
                withContext(Dispatchers.IO) { tokens.save(issued) }
                _state.value = authState(signedIn = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // [releaseCallbackPort] unblocks `accept()` by connecting to it, which lands
                // here on an already-cancelled coroutine. The user closed the dialog.
                if (!isActive) return@launch
                DebugLog.w("GlyphAi", "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = authState(
                    signedIn = tokens.isSignedIn,
                    failure = classify(e),
                    detail = e.message,
                )
            }
        }
    }

    fun cancelSignIn() {
        val running = job ?: return
        job = null
        running.cancel()
        releaseCallbackPort()
        _state.value = authState(signedIn = tokens.isSignedIn)
    }

    fun signOut() {
        cancelSignIn()
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) { tokens.clear() }
            _state.value = authState(signedIn = false)
        }
    }

    /**
     * `NonCancellable`, because the sign-in starts next and an acceptance that lost that
     * race would ask again. Declining has no counterpart; see [AiConsentStore].
     */
    fun acceptConsent() {
        if (_state.value.consented) return
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) { consent.accept() }
            _state.value = _state.value.copy(consented = true)
        }
    }

    fun setEditor(bridge: GlyphEditorBridge) = session.setEditor(bridge)

    fun clearEditor(bridge: GlyphEditorBridge) = session.clearEditor(bridge)

    /**
     * Call this from the chat modal, never from anything `Core.init` touches: it forces
     * chat storage, which needs the first unlock. See [ChatStore].
     */
    fun openChat(designId: String) = session.openChat(designId)

    fun resetChat(): Boolean = session.resetChat()

    /**
     * The decode and JPEG re-encode happen now, not at send time, so an unreadable image is
     * reported while the user is still composing.
     */
    fun attach(uri: Uri) {
        if (session.attachmentsFull()) return
        val id = nextAttachmentId++
        val context = getApplication<Application>()
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { readAttachment(context, uri, id) }
            if (image == null) session.attachFailed() else session.attached(image)
        }
    }

    fun removeAttachment(id: Long) = session.removeAttachment(id)

    fun clearAttachError() = session.clearAttachError()

    fun dismissFailure() = session.dismissFailure()

    /** False means nothing was sent, and the caller must keep what the user typed. */
    fun send(text: String): Boolean = session.send(text)

    /** The user's message is already in the transcript, so it is not appended twice. */
    fun retry() = session.retry()

    fun stopTurn() = session.stopTurn()

    /**
     * One step, not a stack: a whole-document swap does not fit the editor's per-frame
     * undo, and keeping every snapshot would cost a megabyte of frames per turn.
     */
    fun revertLastChange() = session.revertLastChange()

    /**
     * The turn is not cancelled here: it lives in [GlyphAiSession] on a scope this cannot
     * reach, and [stopTurn] is the way to abandon one. The sign-in is cancelled, or port
     * 1455 would stay bound with nobody listening.
     */
    override fun onCleared() {
        job?.cancel()
        job = null
        releaseCallbackPort()
        super.onCleared()
    }

    private fun fail(failure: SignInFailure, e: Exception) {
        DebugLog.w("GlyphAi", "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
        _state.value = authState(
            signedIn = tokens.isSignedIn,
            failure = failure,
            detail = e.message,
        )
    }

    /**
     * Transitions rebuild the whole object rather than copying, so an impossible pair
     * cannot happen; consent is orthogonal and has to survive all of them.
     */
    private fun authState(
        signedIn: Boolean,
        busy: Boolean = false,
        failure: SignInFailure? = null,
        detail: String? = null,
    ): GlyphAiAuthState = GlyphAiAuthState(
        signedIn = signedIn,
        busy = busy,
        failure = failure,
        detail = detail,
        consented = _state.value.consented,
    )

    private fun classify(e: Exception): SignInFailure = when (e) {
        is SocketTimeoutException -> SignInFailure.TIMED_OUT
        is BindException -> SignInFailure.PORT_BUSY
        else -> SignInFailure.FAILED
    }

    /** See this class's KDoc for why this is a thread and not a coroutine. */
    private fun releaseCallbackPort() {
        val port = callbackPort ?: return
        Thread({
            try {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), POKE_TIMEOUT_MS) }
            } catch (_: IOException) {
                // Nothing was listening, which is the outcome this wants anyway.
            }
        }, "oauth-callback-release").start()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val POKE_TIMEOUT_MS = 1_000

        /** Read out of [OAUTH_REDIRECT_URI]: the bound port and the redirect's are one fact. */
        val callbackPort: Int? = runCatching { Uri.parse(OAUTH_REDIRECT_URI).port.takeIf { it > 0 } }
            .getOrNull()
    }
}
