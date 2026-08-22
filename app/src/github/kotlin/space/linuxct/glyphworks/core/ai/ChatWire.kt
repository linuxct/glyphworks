package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Responses API wire format and the SSE parse, over plain strings. Never throws. */
object ChatWire {

    const val MODEL = "gpt-5.6-sol"

    fun resolveModel(stored: String?): String {
        val trimmed = stored?.trim().orEmpty()
        return if (trimmed.isEmpty()) MODEL else trimmed
    }

    const val MODEL_MAX_LENGTH = 64

    const val DEFAULT_REASONING_EFFORT = "medium"

    /**
     * `encodeDefaults` sends `stream` and `store`; without `"stream": true` the reply is
     * one document and [parseSse] sees nothing. `explicitNulls` off keeps an absent
     * `reasoning` out of the body, which some backends reject as `null`.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encodeRequest(request: ChatRequest): String =
        json.encodeToString(ChatRequest.serializer(), request)

    /** [dropStrict]: the OAuth backend rejects `"strict"`, the standard API requires it. */
    fun toolSpec(specJson: String, dropStrict: Boolean = true): JsonElement? {
        val parsed = try {
            json.parseToJsonElement(specJson)
        } catch (e: Exception) {
            return null
        }
        val obj = parsed as? JsonObject ?: return null
        return if (dropStrict) JsonObject(obj - "strict") else obj
    }

    fun toolSpecs(tools: List<GlyphTool>, dropStrict: Boolean = true): List<JsonElement> =
        tools.mapNotNull { toolSpec(it.specJson, dropStrict) }

    fun imageDataUrl(base64: String, mimeType: String = "image/jpeg"): String =
        "data:$mimeType;base64,$base64"

    /**
     * The SSE rules. A line that is not `data:` is skipped, which covers blank lines,
     * `event:` lines and `:` keep-alives. `data: [DONE]` ends the stream. A payload that is
     * not a JSON object, and an event `type` this build does not know, are both skipped.
     * Lazy, so deltas reach the UI as they arrive off the socket.
     */
    fun parseSse(lines: Sequence<String>): Sequence<SseEvent> = sequence {
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith(DATA_PREFIX)) continue
            val payload = line.removePrefix(DATA_PREFIX).trim()
            if (payload.isEmpty()) continue
            if (payload == DONE) {
                yield(SseEvent.Done)
                return@sequence
            }
            val event = parseEvent(payload) ?: continue
            yield(event)
        }
    }

    private fun parseEvent(payload: String): SseEvent? {
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        return when (obj.str("type")) {
            "response.created" ->
                SseEvent.Created(obj.obj("response")?.str("id").orEmpty())

            "response.output_item.added" -> {
                val item = obj.obj("item") ?: return null
                if (item.str("type") != "function_call") return null
                SseEvent.FunctionCallAdded(
                    itemId = item.str("id").orEmpty(),
                    callId = item.str("call_id").orEmpty(),
                    name = item.str("name").orEmpty(),
                )
            }

            "response.output_text.delta" -> {
                val delta = obj.str("delta") ?: return null
                if (delta.isEmpty()) return null
                SseEvent.TextDelta(itemId = obj.str("item_id").orEmpty(), delta = delta)
            }

            "response.function_call_arguments.delta" -> {
                val delta = obj.str("delta") ?: return null
                if (delta.isEmpty()) return null
                SseEvent.FunctionArgumentsDelta(
                    itemId = obj.str("item_id").orEmpty(),
                    callId = obj.str("call_id").orEmpty(),
                    name = obj.str("name").orEmpty(),
                    delta = delta,
                )
            }

            "response.completed" -> {
                val response = obj.obj("response") ?: return null
                SseEvent.Completed(
                    responseId = response.str("id").orEmpty(),
                    output = parseOutput(response["output"] as? JsonArray),
                )
            }

            // The backend reports a refusal or a mid-stream abort here, not as `error`.
            "response.failed" -> SseEvent.Failed(
                obj.obj("response")?.obj("error")?.str("message")
                    ?: obj.str("message")
                    ?: "The model stopped before answering.",
            )

            "error" -> SseEvent.Failed(
                obj.str("message") ?: obj.obj("error")?.str("message") ?: payload,
            )

            else -> null
        }
    }

    /** Read by hand: kotlinx's polymorphic decoder throws on an unknown discriminator. */
    private fun parseOutput(output: JsonArray?): ChatOutput {
        if (output == null) return ChatOutput(null, emptyList())
        var text: String? = null
        val calls = mutableListOf<ChatFunctionCall>()
        for (element in output) {
            val item = element as? JsonObject ?: continue
            when (item.str("type")) {
                "message" -> {
                    val content = item["content"] as? JsonArray ?: continue
                    for (part in content) {
                        val partObj = part as? JsonObject ?: continue
                        val partType = partObj.str("type")
                        if (partType != "output_text" && partType != "text") continue
                        // Last non-blank wins: the final message item is the user's reply.
                        text = partObj.str("text")?.takeIf { it.isNotBlank() } ?: continue
                    }
                }

                "function_call" -> calls += ChatFunctionCall(
                    callId = item.str("call_id").orEmpty(),
                    name = item.str("name").orEmpty(),
                    arguments = item.str("arguments")?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
        }
        return ChatOutput(text, calls)
    }

    /**
     * The API sends the same content twice: as deltas tagged with an output item id, and
     * whole inside `response.completed`. Deltas feed [onTextDelta] and are accumulated per
     * item id; `response.completed` wins, because only it is guaranteed well-formed. A
     * stream that is cut before it falls back to the accumulated deltas.
     */
    fun assemble(
        events: Sequence<SseEvent>,
        onTextDelta: ((String) -> Unit)? = null,
    ): ChatStreamResult {
        var responseId = ""
        var completed: ChatOutput? = null
        val textByItem = LinkedHashMap<String, StringBuilder>()
        val callsByItem = LinkedHashMap<String, PartialCall>()

        for (event in events) {
            when (event) {
                is SseEvent.Created -> if (event.responseId.isNotEmpty()) responseId = event.responseId

                is SseEvent.FunctionCallAdded ->
                    callsByItem[event.itemId] = PartialCall(event.callId, event.name)

                is SseEvent.TextDelta -> {
                    textByItem.getOrPut(event.itemId) { StringBuilder() }.append(event.delta)
                    onTextDelta?.invoke(event.delta)
                }

                is SseEvent.FunctionArgumentsDelta ->
                    // getOrPut, not get: `response.output_item.added` can be missing.
                    callsByItem.getOrPut(event.itemId) { PartialCall(event.callId, event.name) }
                        .arguments.append(event.delta)

                is SseEvent.Completed -> {
                    if (event.responseId.isNotEmpty()) responseId = event.responseId
                    completed = event.output
                }

                is SseEvent.Failed -> return ChatStreamResult.Failed(event.message)

                SseEvent.Done -> Unit
            }
        }

        val output = completed?.takeIf { it.text != null || it.functionCalls.isNotEmpty() }
            ?: ChatOutput(
                text = textByItem.values
                    .map { it.toString() }
                    .lastOrNull { it.isNotBlank() },
                functionCalls = callsByItem.values
                    .filter { it.name.isNotEmpty() }
                    .map {
                        ChatFunctionCall(
                            callId = it.callId,
                            name = it.name,
                            arguments = it.arguments.toString().takeIf { a -> a.isNotBlank() } ?: "{}",
                        )
                    },
            )

        return ChatStreamResult.Ok(
            ChatResponse(
                id = responseId,
                outputText = output.text,
                functionCalls = output.functionCalls,
            ),
        )
    }

    private class PartialCall(
        val callId: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder(),
    )

    private const val DATA_PREFIX = "data:"
    private const val DONE = "[DONE]"

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}

/** With `store` false there is no server-side thread, so `input` replays the transcript. */
@Serializable
data class ChatRequest(
    val model: String = ChatWire.MODEL,
    val instructions: String = "",
    val input: List<ChatInputItem> = emptyList(),
    val tools: List<JsonElement> = emptyList(),
    val stream: Boolean = true,
    val store: Boolean = false,
    val reasoning: ChatReasoning? = null,
)

@Serializable
data class ChatReasoning(val effort: String = ChatWire.DEFAULT_REASONING_EFFORT)

enum class ReasoningEffort(
    val wire: String,
    val unverified: Boolean = false,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max", unverified = true),
    ULTRA("ultra", unverified = true),
    ;

    companion object {
        // firstOrNull, not first: a class initialiser that throws takes the process down.
        val DEFAULT: ReasoningEffort =
            entries.firstOrNull { it.wire == ChatWire.DEFAULT_REASONING_EFFORT } ?: MEDIUM

        fun fromWire(stored: String?): ReasoningEffort {
            val token = stored?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == token } ?: DEFAULT
        }
    }
}

@Serializable
sealed interface ChatInputItem

@Serializable
@SerialName("message")
data class ChatMessageItem(
    val role: String,
    val content: List<ChatContentPart>,
) : ChatInputItem {
    companion object {
        fun user(text: String): ChatMessageItem =
            ChatMessageItem(ROLE_USER, listOf(ChatInputText(text)))

        /** Text first: an image with no instruction before it gets described, not acted on. */
        fun user(text: String, imageDataUrls: List<String>): ChatMessageItem =
            ChatMessageItem(
                ROLE_USER,
                buildList {
                    if (text.isNotBlank()) add(ChatInputText(text))
                    imageDataUrls.forEach { add(ChatInputImage(it)) }
                },
            )

        /** [ChatOutputText], not [ChatInputText]: assistant `input_text` is rejected. */
        fun assistant(text: String): ChatMessageItem =
            ChatMessageItem(ROLE_ASSISTANT, listOf(ChatOutputText(text)))

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

@Serializable
sealed interface ChatContentPart

@Serializable
@SerialName("input_text")
data class ChatInputText(val text: String) : ChatContentPart

@Serializable
@SerialName("output_text")
data class ChatOutputText(val text: String) : ChatContentPart

@Serializable
@SerialName("input_image")
data class ChatInputImage(
    @SerialName("image_url") val imageUrl: String,
    val detail: String = "auto",
) : ChatContentPart

@Serializable
@SerialName("function_call")
data class ChatFunctionCallItem(
    @SerialName("call_id") val callId: String,
    val name: String,
    val arguments: String,
) : ChatInputItem

@Serializable
@SerialName("function_call_output")
data class ChatFunctionCallOutputItem(
    @SerialName("call_id") val callId: String,
    val output: String,
) : ChatInputItem

sealed interface SseEvent {
    data class Created(val responseId: String) : SseEvent

    data class TextDelta(val itemId: String, val delta: String) : SseEvent

    data class FunctionCallAdded(
        val itemId: String,
        val callId: String,
        val name: String,
    ) : SseEvent

    data class FunctionArgumentsDelta(
        val itemId: String,
        val callId: String,
        val name: String,
        val delta: String,
    ) : SseEvent

    data class Completed(val responseId: String, val output: ChatOutput) : SseEvent

    data class Failed(val message: String) : SseEvent

    data object Done : SseEvent
}

data class ChatOutput(
    val text: String?,
    val functionCalls: List<ChatFunctionCall>,
)

/** [arguments] is JSON text, and can be malformed. */
data class ChatFunctionCall(
    val callId: String,
    val name: String,
    val arguments: String,
)

data class ChatResponse(
    val id: String,
    val outputText: String?,
    val functionCalls: List<ChatFunctionCall>,
)

/** A declared server error is a result here. The transport still throws. */
sealed interface ChatStreamResult {
    data class Ok(val response: ChatResponse) : ChatStreamResult
    data class Failed(val message: String) : ChatStreamResult
}
