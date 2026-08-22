package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.JsonElement
import space.linuxct.glyphworks.core.design.Design

/** Nothing else in `core/` knows HTTP exists. `ai/GlyphAiClient` implements this. */
interface GlyphChatClient {
    /** A refusal comes back as [ChatStreamResult.Failed]. A transport failure throws. */
    suspend fun respond(
        request: ChatRequest,
        onTextDelta: ((String) -> Unit)? = null,
    ): ChatStreamResult
}

/** What the assistant is doing. The UI's own copy lives in `strings.xml`. */
sealed interface ChatTrace {
    data object Thinking : ChatTrace

    data class RunningTool(val name: String) : ChatTrace

    data object Processing : ChatTrace

    /** English, for a UI with no string for this step. */
    fun defaultText(): String = when (this) {
        Thinking -> "Thinking…"
        Processing -> "Reading the results…"
        is RunningTool -> when (name) {
            GlyphAiTools.GET_CURRENT_DESIGN -> "Reading your design…"
            GlyphAiTools.APPLY_DESIGN -> "Applying changes…"
            GlyphAiTools.VALIDATE_DESIGN -> "Checking the design…"
            else -> "Running ${name.replace('_', ' ')}…"
        }
    }
}

/**
 * Runs one turn: ask, run the tools that come back, ask again, until the model answers in
 * words. Nothing here throws; a failure still carries what the turn managed first.
 */
class GlyphAiOrchestrator(
    private val client: GlyphChatClient,
    private val tools: List<GlyphTool> = GlyphAiTools.build(),
    private val model: String = ChatWire.MODEL,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    // Null omits the `reasoning` field entirely, for a backend that rejects it.
    private val reasoningEffort: String? = ChatWire.DEFAULT_REASONING_EFFORT,
    // Returns null on success, or the reason, which is then what the model is told.
    private val applyDesign: (Design) -> String? = { null },
    private val onTrace: (ChatTrace) -> Unit = {},
    private val onToolNote: (ChatToolNote) -> Unit = {},
) {

    sealed interface TurnResult {
        data class Success(
            val text: String,
            val rounds: Int,
            val appliedDesign: Design?,
            val toolNotes: List<ChatToolNote>,
            val items: List<ChatInputItem>,
        ) : TurnResult

        /** [appliedDesign] can be set here too: round two's apply outlives round three. */
        data class Failure(
            val reason: Reason,
            val detail: String,
            val rounds: Int,
            val appliedDesign: Design?,
            val toolNotes: List<ChatToolNote>,
        ) : TurnResult

        enum class Reason {
            TRANSPORT,
            SERVER,
            STUCK,
            STUCK_SALVAGED,
            EMPTY,
        }
    }

    suspend fun runTurn(
        instructions: String,
        history: List<ChatInputItem>,
        message: ChatMessageItem,
        context: GlyphToolContext,
        onTextDelta: ((String) -> Unit)? = null,
    ): TurnResult {
        val byName = tools.associateBy { it.name }
        val toolSpecs = ChatWire.toolSpecs(tools)
        val input = mutableListOf<ChatInputItem>()
        input += history
        input += message

        val notes = mutableListOf<ChatToolNote>()
        var ctx = context
        var applied: Design? = null
        var lastValidatedDraft: Design? = null
        var rounds = 0

        onTrace(ChatTrace.Thinking)
        var response = when (val first = send(instructions, input, toolSpecs, onTextDelta)) {
            is Sent.Ok -> first.response
            is Sent.Bad -> return fail(first.reason, first.detail, rounds, applied, notes)
        }

        while (response.functionCalls.isNotEmpty()) {
            if (rounds >= maxRounds) return salvage(rounds, applied, lastValidatedDraft, notes)
            rounds++

            // Every call is echoed back before any of them is answered. The API rejects an
            // interleaved `function_call` and `function_call_output` on a parallel call.
            for (call in response.functionCalls) {
                input += ChatFunctionCallItem(
                    callId = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                )
            }

            for (call in response.functionCalls) {
                onTrace(ChatTrace.RunningTool(call.name))
                var result = runTool(byName[call.name], call, ctx)

                result.validated?.let { lastValidatedDraft = it }

                val produced = result.design
                if (produced != null) {
                    // The tool's JSON already claims the canvas changed, so apply first and
                    // rewrite the result if it did not.
                    val problem = try {
                        applyDesign(produced)
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    if (problem == null) {
                        applied = produced
                        ctx = ctx.copy(design = produced)
                    } else {
                        result = GlyphToolResult(
                            json = APPLY_FAILED_JSON.format(problem.replace('"', '\'')),
                            isError = true,
                        )
                    }
                }

                val note = ChatToolNote(
                    name = call.name,
                    label = ChatToolNote.labelFor(call.name),
                    ok = !result.isError,
                    changedDesign = produced != null && !result.isError,
                )
                notes += note
                onToolNote(note)
                input += ChatFunctionCallOutputItem(callId = call.callId, output = result.json)
            }

            onTrace(ChatTrace.Processing)
            response = when (val next = send(instructions, input, toolSpecs, onTextDelta)) {
                is Sent.Ok -> next.response
                is Sent.Bad -> return fail(next.reason, next.detail, rounds, applied, notes)
            }
        }

        val text = response.outputText?.takeIf { it.isNotBlank() }
            ?: return fail(
                TurnResult.Reason.EMPTY,
                "The assistant finished without saying anything.",
                rounds,
                applied,
                notes,
            )

        return TurnResult.Success(
            text = text,
            rounds = rounds,
            appliedDesign = applied,
            toolNotes = notes,
            items = input.toList(),
        )
    }

    private fun runTool(
        tool: GlyphTool?,
        call: ChatFunctionCall,
        ctx: GlyphToolContext,
    ): GlyphToolResult {
        if (tool == null) {
            return GlyphToolResult(
                json = UNKNOWN_TOOL_JSON.format(
                    call.name.replace('"', '\''),
                    tools.joinToString(", ") { "\"${it.name}\"" },
                ),
                isError = true,
            )
        }
        return try {
            tool.run(call.arguments, ctx)
        } catch (e: Exception) {
            GlyphToolResult(
                json = TOOL_THREW_JSON.format(
                    call.name.replace('"', '\''),
                    (e.message ?: e.javaClass.simpleName).replace('"', '\''),
                ),
                isError = true,
            )
        }
    }

    /**
     * Out of tool rounds: land the last draft that passed `validate_design`, but only if
     * nothing was applied this turn. An older draft would undo work the user watched
     * happen.
     */
    private fun salvage(
        rounds: Int,
        applied: Design?,
        validated: Design?,
        notes: MutableList<ChatToolNote>,
    ): TurnResult.Failure {
        val outOfRounds = "The assistant used its $maxRounds tool rounds without answering. " +
            "A complex design may need more — raise the assistant's tool rounds in Settings."
        val draft = validated?.takeIf { applied == null }
            ?: return fail(TurnResult.Reason.STUCK, outOfRounds, rounds, applied, notes)

        val problem = try {
            applyDesign(draft)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (problem != null) {
            return fail(TurnResult.Reason.STUCK, outOfRounds, rounds, applied, notes)
        }

        val note = ChatToolNote(
            name = GlyphAiTools.APPLY_DESIGN,
            label = ChatToolNote.labelFor(GlyphAiTools.APPLY_DESIGN),
            ok = true,
            changedDesign = true,
        )
        notes += note
        onToolNote(note)
        return fail(
            TurnResult.Reason.STUCK_SALVAGED,
            "$outOfRounds The last draft that passed its checks was applied.",
            rounds,
            draft,
            notes,
        )
    }

    private sealed interface Sent {
        data class Ok(val response: ChatResponse) : Sent
        data class Bad(val reason: TurnResult.Reason, val detail: String) : Sent
    }

    private suspend fun send(
        instructions: String,
        input: List<ChatInputItem>,
        toolSpecs: List<JsonElement>,
        onTextDelta: ((String) -> Unit)?,
    ): Sent {
        val request = ChatRequest(
            model = model,
            instructions = instructions,
            input = input.toList(),
            tools = toolSpecs,
            reasoning = reasoningEffort?.let { ChatReasoning(it) },
        )
        return try {
            when (val result = client.respond(request, onTextDelta)) {
                is ChatStreamResult.Ok -> Sent.Ok(result.response)
                is ChatStreamResult.Failed -> Sent.Bad(TurnResult.Reason.SERVER, result.message)
            }
        } catch (e: Exception) {
            Sent.Bad(TurnResult.Reason.TRANSPORT, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(
        reason: TurnResult.Reason,
        detail: String,
        rounds: Int,
        applied: Design?,
        notes: List<ChatToolNote>,
    ): TurnResult.Failure = TurnResult.Failure(
        reason = reason,
        detail = detail,
        rounds = rounds,
        appliedDesign = applied,
        toolNotes = notes.toList(),
    )

    companion object {
        const val DEFAULT_MAX_ROUNDS = 8

        // Substituted text has its double quotes replaced first, so it cannot break out.
        private const val UNKNOWN_TOOL_JSON =
            """{"ok":false,"error":"There is no tool called \"%s\" in this conversation.","expected":"One of: %s."}"""

        private const val TOOL_THREW_JSON =
            """{"ok":false,"error":"The tool \"%s\" failed: %s","expected":"Try a different approach, or tell the user what went wrong."}"""

        private const val APPLY_FAILED_JSON =
            """{"ok":false,"error":"The document was valid, but the editor could not apply it: %s","expected":"Nothing was changed. Tell the user, or try again."}"""
    }
}
