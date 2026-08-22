package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val CHAT_FORMAT = "glyph.chat"

const val CHAT_FORMAT_VERSION = 1

@Serializable
enum class ChatRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,
}

@Serializable
data class ChatToolNote(
    val name: String = "",
    val label: String = "",
    val ok: Boolean = true,
    val changedDesign: Boolean = false,
) {
    companion object {
        fun labelFor(name: String): String = when (name) {
            GlyphAiTools.GET_CURRENT_DESIGN -> "Read your design"
            GlyphAiTools.APPLY_DESIGN -> "Applied a change"
            GlyphAiTools.VALIDATE_DESIGN -> "Checked a design"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

@Serializable
data class ChatMessage(
    val role: ChatRole = ChatRole.USER,
    val text: String = "",
    val atMs: Long = 0L,
    val tools: List<ChatToolNote> = emptyList(),
    val imageCount: Int = 0,
    val error: Boolean = false,
    val partial: Boolean = false,
)

@Serializable
data class ChatTranscript(
    val format: String = CHAT_FORMAT,
    val formatVersion: Int = CHAT_FORMAT_VERSION,
    val designId: String = "",
    val messages: List<ChatMessage> = emptyList(),
) {
    fun plus(message: ChatMessage): ChatTranscript =
        copy(messages = (messages + message).takeLast(ChatTranscriptCodec.MAX_MESSAGES))

    /** Idempotent: a long turn can checkpoint as often as it likes. */
    fun withPartial(message: ChatMessage): ChatTranscript =
        withoutPartial().plus(message)

    /** Null on an empty thread: writing would resurrect a deleted design's file. */
    fun withCorrection(message: ChatMessage): ChatTranscript? =
        if (messages.isEmpty()) null else plus(message)

    fun withoutPartial(): ChatTranscript =
        if (messages.lastOrNull()?.partial == true) copy(messages = messages.dropLast(1)) else this

    /**
     * The context the model is given. Tool notes are dropped: a `function_call` with no
     * `function_call_output` is a protocol error. Blank turns are dropped too, because the
     * API rejects empty content.
     */
    fun asInput(count: Int = ChatTranscriptCodec.HISTORY_TURNS): List<ChatInputItem> =
        messages.asSequence()
            .filter { it.text.isNotBlank() && !it.error }
            .toList()
            .takeLast(count)
            .map {
                when (it.role) {
                    ChatRole.USER -> ChatMessageItem.user(it.text)
                    ChatRole.ASSISTANT -> ChatMessageItem.assistant(it.text)
                }
            }
}

/** Never throws. A broken transcript opens as an empty thread. */
object ChatTranscriptCodec {

    const val MAX_MESSAGES = 400

    const val HISTORY_TURNS = 6

    const val MAX_BYTES = 4 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An unknown `role` decodes to the default instead of voiding the thread.
        coerceInputValues = true
    }

    fun encode(transcript: ChatTranscript): String =
        json.encodeToString(
            ChatTranscript.serializer(),
            transcript.copy(format = CHAT_FORMAT, formatVersion = CHAT_FORMAT_VERSION),
        )

    fun decode(text: String): ChatTranscript? {
        if (text.length > MAX_BYTES) return null
        val parsed = try {
            json.decodeFromString(ChatTranscript.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (parsed.format != CHAT_FORMAT) return null
        if (parsed.formatVersion > CHAT_FORMAT_VERSION) return null
        return parsed.copy(messages = parsed.messages.takeLast(MAX_MESSAGES))
    }
}
