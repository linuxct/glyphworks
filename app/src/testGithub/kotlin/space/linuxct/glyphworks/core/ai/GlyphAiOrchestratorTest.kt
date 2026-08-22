package space.linuxct.glyphworks.core.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.PokemonCodename
import java.io.IOException

class GlyphAiOrchestratorTest {
    private val design = TestDesigns.bellsproutOnly()
    private val context = GlyphToolContext(design = design, openVariant = PokemonCodename.BELLSPROUT)

    @Test
    fun `a turn with no tool calls is one request and the model's words`() {
        val client = FakeClient(text("The panel is a 13x13 disc."))
        val result = run(GlyphAiOrchestrator(client))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals("The panel is a 13x13 disc.", success.text)
        assertEquals(0, success.rounds)
        assertTrue(success.toolNotes.isEmpty())
        assertNull(success.appliedDesign)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `history and the new message are both sent, in order`() {
        val client = FakeClient(text("ok"))
        runBlocking {
            GlyphAiOrchestrator(client).runTurn(
                instructions = "system",
                history = listOf(
                    ChatMessageItem.user("hi"),
                    ChatMessageItem.assistant("hello"),
                ),
                message = ChatMessageItem.user("make it rounder"),
                context = context,
            )
        }

        val input = client.requests.single().input
        assertEquals(3, input.size)
        assertEquals("system", client.requests.single().instructions)
        assertEquals(
            listOf("hi", "hello", "make it rounder"),
            input.map { (it as ChatMessageItem).content.first().let(::textOf) },
        )
    }

    @Test
    fun `one tool round appends the call and its output, then re-sends everything`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
            text("You have two frames."),
        )
        val traces = mutableListOf<ChatTrace>()

        val result = run(GlyphAiOrchestrator(client, onTrace = { traces += it }))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(1, success.rounds)
        assertEquals("You have two frames.", success.text)

        val second = client.requests[1].input
        // user message, function_call, function_call_output, in that order. The
        // API rejects an output that comes before its call.
        assertEquals(3, second.size)
        val callItem = second[1] as ChatFunctionCallItem
        val outputItem = second[2] as ChatFunctionCallOutputItem
        assertEquals("call_1", callItem.callId)
        assertEquals("call_1", outputItem.callId)
        assertTrue(outputItem.output, outputItem.output.contains("\"allowed_variants\""))

        assertEquals(
            listOf(
                ChatTrace.Thinking,
                ChatTrace.RunningTool(GlyphAiTools.GET_CURRENT_DESIGN),
                ChatTrace.Processing,
            ),
            traces,
        )
        assertEquals("Read your design", success.toolNotes.single().label)
        assertTrue(success.toolNotes.single().ok)
        assertFalse(success.toolNotes.single().changedDesign)
    }

    @Test
    fun `an applied design is handed to the caller and never applied here`() {
        val renamed = design.copy(name = "Rounder")
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(renamed)),
            text("Done."),
        )

        val result = run(
            GlyphAiOrchestrator(client, applyDesign = { applied += it; null }),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals("Rounder", applied.single().name)
        assertEquals("Rounder", success.appliedDesign?.name)
        assertEquals(design.id, success.appliedDesign?.id)
        assertTrue(success.toolNotes.single().changedDesign)
    }

    @Test
    fun `a tool error comes back as a result the model then corrects from`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Fixed"))),
            text("Fixed it."),
        )

        val result = run(GlyphAiOrchestrator(client))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(2, success.rounds)
        assertEquals("Fixed", success.appliedDesign?.name)

        val firstOutput = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(firstOutput, firstOutput.contains("\"ok\":false"))
        assertTrue(firstOutput, firstOutput.contains("expected"))
        assertTrue(firstOutput, firstOutput.contains("625"))

        assertEquals(2, success.toolNotes.size)
        assertFalse(success.toolNotes[0].ok)
        assertTrue(success.toolNotes[1].ok)
    }

    @Test
    fun `each finished tool call is reported as it happens, not only at the end`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design)),
            call("call_3", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Note"))),
            text("Drew you a music note."),
        )
        val live = mutableListOf<Pair<ChatToolNote, Int>>()

        val result = run(
            GlyphAiOrchestrator(
                client,
                onToolNote = { live += it to client.requests.size },
            ),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(
            listOf(
                GlyphAiTools.VALIDATE_DESIGN,
                GlyphAiTools.VALIDATE_DESIGN,
                GlyphAiTools.APPLY_DESIGN,
            ),
            live.map { it.first.name },
        )
        assertEquals(listOf(false, true, true), live.map { it.first.ok })
        assertEquals(listOf(1, 2, 3), live.map { it.second })
        assertEquals(success.toolNotes, live.map { it.first })
    }

    @Test
    fun `a tool that throws costs the call, not the turn`() {
        val exploding = GlyphTool("boom", """{"type":"function","name":"boom"}""") { _, _ ->
            throw IllegalStateException("kaboom")
        }
        val client = FakeClient(call("call_1", "boom", "{}"), text("Recovered."))

        val result = run(GlyphAiOrchestrator(client, tools = listOf(exploding)))

        val output = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(output, output.contains("kaboom"))
        assertEquals("Recovered.", (result as GlyphAiOrchestrator.TurnResult.Success).text)
    }

    @Test
    fun `an apply the editor refuses is reported as a failure, not as a success`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Nope"))),
            text("I could not change it."),
        )

        val result = run(
            GlyphAiOrchestrator(client, applyDesign = { "the editor is closed" }),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertNull(success.appliedDesign)
        assertFalse(success.toolNotes.single().changedDesign)
        val output = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(output, output.contains("the editor is closed"))
        assertTrue(output, output.contains("\"ok\":false"))
    }

    @Test
    fun `a model that never stops calling tools is cut off with a reason`() {
        val client = FakeClient(
            *Array(10) { call("call_$it", GlyphAiTools.GET_CURRENT_DESIGN, "{}") },
        )

        val result = run(GlyphAiOrchestrator(client, maxRounds = 3))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
        assertEquals(3, failure.rounds)
        assertTrue(failure.detail, failure.detail.contains("3"))
        assertEquals(4, client.requests.size)
    }

    @Test
    fun `a design applied before the round budget ran out is still reported`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Landed"))),
            call("call_2", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
            call("call_3", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
        )

        val result = run(GlyphAiOrchestrator(client, maxRounds = 2))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals("Landed", failure.appliedDesign?.name)
        assertEquals(2, failure.toolNotes.size)
    }

    @Test
    fun `a draft that validated is applied when the rounds run out`() {
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "First draft"))),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Second draft"))),
            call("call_3", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Third draft"))),
        )

        val result = run(
            GlyphAiOrchestrator(client, maxRounds = 2, applyDesign = { applied += it; null }),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals("Second draft", applied.single().name)
        assertEquals("Second draft", failure.appliedDesign?.name)
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED, failure.reason)
        assertTrue(failure.detail, failure.detail.contains("2"))
        assertTrue(failure.detail, failure.detail.contains("draft"))
        val last = failure.toolNotes.last()
        assertEquals(GlyphAiTools.APPLY_DESIGN, last.name)
        assertTrue(last.ok)
        assertTrue(last.changedDesign)
    }

    @Test
    fun `a turn with no validated draft still fails empty-handed`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
        )

        val result = run(
            GlyphAiOrchestrator(client, maxRounds = 1, applyDesign = { applied += it; null }),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
        assertNull(failure.appliedDesign)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a transport failure is a failure, not an exception`() {
        val client = FakeClient(throwing = IOException("no route to host"))

        val result = run(GlyphAiOrchestrator(client))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT, failure.reason)
        assertEquals("no route to host", failure.detail)
    }

    @Test
    fun `a server error mid-stream is surfaced with the server's wording`() {
        val client = FakeClient(ChatStreamResult.Failed("model_not_found"))

        val result = run(GlyphAiOrchestrator(client))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.SERVER, failure.reason)
        assertEquals("model_not_found", failure.detail)
    }

    @Test
    fun `a turn that produces neither words nor a tool call is a failure`() {
        val client = FakeClient(
            ChatStreamResult.Ok(ChatResponse(id = "r", outputText = "   ", functionCalls = emptyList())),
        )

        val result = run(GlyphAiOrchestrator(client))

        assertEquals(
            GlyphAiOrchestrator.TurnResult.Reason.EMPTY,
            (result as GlyphAiOrchestrator.TurnResult.Failure).reason,
        )
    }

    @Test
    fun `text deltas are passed straight through to the caller`() {
        val client = FakeClient(text("done"), deltas = listOf("do", "ne"))
        val seen = mutableListOf<String>()

        runBlocking {
            GlyphAiOrchestrator(client).runTurn(
                instructions = "system",
                history = emptyList(),
                message = ChatMessageItem.user("go"),
                context = context,
                onTextDelta = { seen += it },
            )
        }

        assertEquals(listOf("do", "ne"), seen)
    }

    @Test
    fun `every request advertises the tools and the configured model`() {
        val client = FakeClient(text("ok"))
        run(GlyphAiOrchestrator(client, model = "some-other-model", reasoningEffort = null))

        val request = client.requests.single()
        assertEquals("some-other-model", request.model)
        assertNull(request.reasoning)
        assertEquals(GlyphAiTools.build().size, request.tools.size)
        assertTrue(request.stream)
        assertFalse(request.store)
    }

    private fun run(orchestrator: GlyphAiOrchestrator): GlyphAiOrchestrator.TurnResult =
        runBlocking {
            orchestrator.runTurn(
                instructions = "system",
                history = emptyList(),
                message = ChatMessageItem.user("make it rounder"),
                context = context,
            )
        }

    private fun applyArgs(design: Design): String =
        buildJsonObject { put(GlyphAiTools.ARG_DESIGN, DesignCodec.encode(design)) }.toString()

    private fun text(text: String) =
        ChatStreamResult.Ok(ChatResponse(id = "r", outputText = text, functionCalls = emptyList()))

    private fun call(callId: String, name: String, arguments: String) =
        ChatStreamResult.Ok(
            ChatResponse(
                id = "r",
                outputText = null,
                functionCalls = listOf(ChatFunctionCall(callId, name, arguments)),
            ),
        )

    private fun textOf(part: ChatContentPart): String = when (part) {
        is ChatInputText -> part.text
        is ChatOutputText -> part.text
        is ChatInputImage -> part.imageUrl
    }

    private class FakeClient(
        private val script: List<ChatStreamResult>,
        private val throwing: Throwable? = null,
        private val deltas: List<String> = emptyList(),
    ) : GlyphChatClient {
        constructor(
            vararg script: ChatStreamResult,
            deltas: List<String> = emptyList(),
        ) : this(script.toList(), null, deltas)

        constructor(throwing: Throwable) : this(emptyList(), throwing)

        val requests = mutableListOf<ChatRequest>()

        override suspend fun respond(
            request: ChatRequest,
            onTextDelta: ((String) -> Unit)?,
        ): ChatStreamResult {
            requests += request
            val index = requests.size - 1
            if (index >= script.size) {
                throwing?.let { throw it }
                error("the fake model ran out of scripted answers after ${script.size}")
            }
            deltas.forEach { onTextDelta?.invoke(it) }
            return script[index]
        }
    }
}
