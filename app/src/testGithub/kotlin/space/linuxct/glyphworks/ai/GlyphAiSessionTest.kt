package space.linuxct.glyphworks.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatRole
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphworks.core.ai.GlyphToolContext
import space.linuxct.glyphworks.core.ai.PendingApply
import space.linuxct.glyphworks.core.ai.PendingApplyVerdict
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

class GlyphAiSessionTest {

    private class FakeTranscripts : TranscriptStore {
        val saved = LinkedHashMap<String, ChatTranscript>()
        val deleted = mutableListOf<String>()
        var writes = 0

        var holdNextLoad: CompletableDeferred<Unit>? = null

        override suspend fun load(designId: String): ChatTranscript? {
            holdNextLoad?.let {
                holdNextLoad = null
                it.await()
            }
            return saved[designId]
        }

        override suspend fun save(transcript: ChatTranscript) {
            writes++
            saved[transcript.designId] = transcript
        }

        override suspend fun delete(designId: String) {
            deleted += designId
            saved.remove(designId)
        }
    }

    private class FakePending : PendingApplyRecords {
        val records = LinkedHashMap<String, PendingApply>()

        override suspend fun take(designId: String): PendingApply? = records.remove(designId)

        override suspend fun put(record: PendingApply) {
            records[record.designId] = record
        }
    }

    private class FakeForeground : TurnForeground {
        var starts = 0
        var stops = 0
        var lastName = ""

        override fun turnStarted(designId: String, designName: String) {
            starts++
            lastName = designName
        }

        override fun turnEnded() {
            stops++
        }
    }

    private class FakeEditor(var design: Design) : GlyphEditorBridge {
        val applied = mutableListOf<Design>()

        override fun snapshot(): GlyphToolContext =
            GlyphToolContext(design = design, openVariant = PokemonCodename.BELLSPROUT)

        override fun apply(design: Design): GlyphApplyResult {
            val previous = this.design
            this.design = design
            applied += design
            return GlyphApplyResult.Applied(previous)
        }
    }

    private class ScriptedRunner : TurnRunner {
        val awaiting = CompletableDeferred<GlyphAiOrchestrator.TurnResult>()
        var request: TurnRequest? = null

        override suspend fun run(request: TurnRequest): GlyphAiOrchestrator.TurnResult {
            this.request = request
            return awaiting.await()
        }
    }

    private fun design(id: String = DESIGN_ID, modifiedAt: String = MODIFIED_AT) = Design(
        id = id,
        name = "Smiley",
        modifiedAt = modifiedAt,
        variants = mapOf(PokemonCodename.BELLSPROUT.codename to DesignVariant()),
    )

    private fun success(text: String, notes: List<ChatToolNote> = emptyList()) =
        GlyphAiOrchestrator.TurnResult.Success(
            text = text,
            rounds = 1,
            appliedDesign = null,
            toolNotes = notes,
            items = emptyList(),
        )

    private fun failure(
        detail: String,
        reason: GlyphAiOrchestrator.TurnResult.Reason =
            GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT,
        appliedDesign: Design? = null,
        notes: List<ChatToolNote> = emptyList(),
    ) = GlyphAiOrchestrator.TurnResult.Failure(
        reason = reason,
        detail = detail,
        rounds = 1,
        appliedDesign = appliedDesign,
        toolNotes = notes,
    )

    private fun salvaged() = failure(
        detail = "out of rounds",
        reason = GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED,
        appliedDesign = design(modifiedAt = "2026-04-04T00:00:00Z"),
        notes = listOf(ChatToolNote(name = "apply_design", label = "Applied a change", changedDesign = true)),
    )

    private class Fixture(
        val runner: ScriptedRunner = ScriptedRunner(),
        val transcripts: FakeTranscripts = FakeTranscripts(),
        val pending: FakePending = FakePending(),
        val foreground: FakeForeground = FakeForeground(),
        var storedModifiedAt: String? = MODIFIED_AT,
        var clock: Long = 1_000L,
    ) {
        val session = GlyphAiSession(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transcripts = transcripts,
            pendingApplies = pending,
            designs = StoredDesignFacts { storedModifiedAt },
            foreground = foreground,
            notices = object : TurnNotices {
                override fun changedTheDesign(
                    reason: GlyphAiOrchestrator.TurnResult.Reason,
                ): String = "notice:$reason"

                override fun deferredApplyDropped(verdict: PendingApplyVerdict): String =
                    "dropped:$verdict"
            },
            runner = runner,
            ioContext = Dispatchers.Unconfined,
            now = { clock },
        )
    }

    private fun claimed(fixture: Fixture) {
        fixture.transcripts.saved[DESIGN_ID] = ChatTranscript(
            designId = DESIGN_ID,
            messages = listOf(
                ChatMessage(role = ChatRole.USER, text = "draw a cat", atMs = 1L),
                ChatMessage(role = ChatRole.ASSISTANT, text = "Done — I drew you a cat.", atMs = 2L),
            ),
        )
        fixture.transcripts.writes = 0
    }

    private fun defer(fixture: Fixture, atMs: Long = fixture.clock) {
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = atMs,
            design = design(modifiedAt = "2026-02-02T00:00:00Z"),
        )
    }

    private fun opened(fixture: Fixture = Fixture()): Pair<Fixture, FakeEditor> {
        val editor = FakeEditor(design())
        fixture.session.openChat(DESIGN_ID)
        fixture.session.setEditor(editor)
        return fixture to editor
    }

    @Test
    fun `sending records the user's message, goes foreground and reports as sending`() {
        val (fixture, _) = opened()

        assertTrue(fixture.session.send("draw a cat"))

        val state = fixture.session.chat.value
        assertTrue(state.sending)
        assertEquals(1, state.messages.size)
        assertEquals(ChatRole.USER, state.messages[0].role)
        assertEquals("draw a cat", state.messages[0].text)
        assertEquals(1, fixture.foreground.starts)
        assertEquals(0, fixture.foreground.stops)
        assertEquals(1, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
    }

    @Test
    fun `only one turn runs at a time`() {
        val (fixture, _) = opened()
        fixture.session.send("first")

        assertFalse(fixture.session.send("second"))
        assertEquals(1, fixture.foreground.starts)
    }

    @Test
    fun `nothing is sent with no editor registered`() {
        val fixture = Fixture()
        fixture.session.openChat(DESIGN_ID)

        assertFalse(fixture.session.send("draw a cat"))
        assertEquals(0, fixture.foreground.starts)
    }

    @Test
    fun `a finished turn appends the reply, releases the service and clears the trace`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(success("Here you go."))

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("", state.streaming)
        assertNull(state.trace)
        assertEquals(2, state.messages.size)
        assertEquals(ChatRole.ASSISTANT, state.messages[1].role)
        assertEquals("Here you go.", state.messages[1].text)
        assertEquals(1, fixture.foreground.stops)
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
    }

    @Test
    fun `a failed turn is not written to the transcript but does release the service`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(failure("HTTP 400"))

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("HTTP 400", state.failure?.detail)
        assertEquals(1, state.messages.size)
        assertEquals(1, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
        assertEquals(1, fixture.foreground.stops)
    }

    @Test
    fun `a salvaged turn leaves a note in the transcript explaining the change`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(salvaged())

        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, stored.messages.size)
        val note = stored.messages[1]
        assertEquals(ChatRole.ASSISTANT, note.role)
        assertEquals(
            "notice:${GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED}",
            note.text,
        )
        assertEquals(1, note.tools.size)
        assertEquals(2, fixture.session.chat.value.messages.size)
        assertEquals(
            GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED,
            fixture.session.chat.value.failure?.reason,
        )
    }

    @Test
    fun `closing the editor does not end the turn`() {
        val (fixture, editor) = opened()
        fixture.session.send("draw a cat")

        fixture.session.clearEditor(editor)

        assertTrue(fixture.session.chat.value.sending)
        assertEquals(0, fixture.foreground.stops)
        assertFalse(fixture.runner.awaiting.isCompleted)

        fixture.runner.awaiting.complete(success("Done while you were away."))
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
        assertEquals(1, fixture.foreground.stops)
    }

    @Test
    fun `stopping is the users cancel and leaves the transcript as it was`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        fixture.runner.request!!.onTextDelta("Drawing a c")
        fixture.clock += GlyphAiSession.CHECKPOINT_INTERVAL_MS
        fixture.runner.request!!.onTextDelta("at…")
        assertTrue(fixture.transcripts.saved[DESIGN_ID]!!.messages.last().partial)

        fixture.session.stopTurn()

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("", state.streaming)
        assertEquals(1, fixture.foreground.stops)
        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(1, stored.messages.size)
        assertEquals(ChatRole.USER, stored.messages[0].role)
    }

    @Test
    fun `a reply still arriving is checkpointed, and replaced by the real one`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.request!!.onTextDelta("Here")
        val checkpoint = fixture.transcripts.saved[DESIGN_ID]!!.messages.last()
        assertTrue(checkpoint.partial)
        assertEquals("Here", checkpoint.text)

        fixture.runner.awaiting.complete(success("Here you go."))

        val finished = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, finished.messages.size)
        assertFalse(finished.messages.last().partial)
        assertEquals("Here you go.", finished.messages.last().text)
    }

    @Test
    fun `a finished tool call is checkpointed the moment it lands`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.request!!.onToolNote(ChatToolNote(name = "apply_design", label = "Applied"))

        val checkpoint = fixture.transcripts.saved[DESIGN_ID]!!.messages.last()
        assertTrue(checkpoint.partial)
        assertEquals(1, checkpoint.tools.size)
        assertEquals(1, fixture.session.chat.value.steps.size)
    }

    @Test
    fun `a checkpoint read back on the next open is the reply that arrived`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        fixture.runner.request!!.onTextDelta("Half a sen")

        val reopened = Fixture(transcripts = fixture.transcripts)
        reopened.session.openChat(DESIGN_ID)

        val messages = reopened.session.chat.value.messages
        assertEquals(2, messages.size)
        assertEquals("Half a sen", messages[1].text)
        assertTrue(messages[1].partial)
    }

    @Test
    fun `an apply with no editor open is recorded rather than refused`() {
        val (fixture, editor) = opened()
        fixture.session.send("draw a cat")
        fixture.session.clearEditor(editor)

        val refusal = fixture.runner.request!!.applyDesign(design())

        assertNull("the model is told it worked, because it did", refusal)
        val record = fixture.pending.records[DESIGN_ID]
        assertNotNull(record)
        assertEquals(MODIFIED_AT, record!!.baseModifiedAt)
        assertEquals(fixture.clock, record.atMs)
    }

    @Test
    fun `the recorded design lands when that design is next opened`() {
        val fixture = Fixture()
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = fixture.clock,
            design = design(modifiedAt = "2026-02-02T00:00:00Z"),
        )
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(1, editor.applied.size)
        assertTrue(fixture.pending.records.isEmpty())
        fixture.session.openChat(DESIGN_ID)
        assertTrue(fixture.session.chat.value.canRevert)
    }

    @Test
    fun `a design the user has edited since is not overwritten`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = fixture.clock,
            design = design(),
        )
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(0, editor.applied.size)
        assertTrue(fixture.pending.records.isEmpty())
    }

    @Test
    fun `a draft dropped for the user's own edits corrects the conversation`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        claimed(fixture)
        defer(fixture)

        fixture.session.setEditor(FakeEditor(design()))

        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(3, stored.messages.size)
        val correction = stored.messages.last()
        assertEquals(ChatRole.ASSISTANT, correction.role)
        assertEquals("dropped:${PendingApplyVerdict.CONFLICT}", correction.text)
        assertEquals(fixture.clock, correction.atMs)
    }

    @Test
    fun `a draft that lands adds nothing to the conversation`() {
        val fixture = Fixture()
        claimed(fixture)
        defer(fixture)
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(1, editor.applied.size)
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]!!.messages.size)
        assertEquals(0, fixture.transcripts.writes)
    }

    @Test
    fun `a correction and an open that races it lose no messages`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        claimed(fixture)
        defer(fixture)
        val gate = CompletableDeferred<Unit>()
        fixture.transcripts.holdNextLoad = gate

        fixture.session.openChat(DESIGN_ID)
        assertFalse("the read is still in flight", fixture.session.chat.value.restored)
        fixture.session.setEditor(FakeEditor(design()))
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]!!.messages.size)

        gate.complete(Unit)

        val onScreen = fixture.session.chat.value.messages
        assertEquals(3, onScreen.size)
        assertEquals("draw a cat", onScreen[0].text)
        assertEquals("dropped:${PendingApplyVerdict.CONFLICT}", onScreen[2].text)
        assertEquals(onScreen, fixture.transcripts.saved[DESIGN_ID]!!.messages)
    }

    @Test
    fun `opening another design leaves the turn running and stops it writing to the screen`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        val other = FakeEditor(design(id = OTHER_ID))
        fixture.session.openChat(OTHER_ID)
        fixture.session.setEditor(other)
        fixture.runner.request!!.onTextDelta("a cat, arriving")

        assertEquals("", fixture.session.chat.value.streaming)
        assertFalse(fixture.session.chat.value.sending)
        fixture.runner.awaiting.complete(success("Here is your cat."))
        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, stored.messages.size)
        assertEquals("Here is your cat.", stored.messages[1].text)
    }

    @Test
    fun `resetting removes the transcript and leaves the revert banner`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        fixture.runner.request!!.applyDesign(design())
        fixture.runner.awaiting.complete(success("Done."))
        assertTrue(fixture.session.chat.value.canRevert)

        assertTrue(fixture.session.resetChat())

        assertEquals(listOf(DESIGN_ID), fixture.transcripts.deleted)
        assertTrue(fixture.session.chat.value.messages.isEmpty())
        assertTrue("the artwork is not what a reset touches", fixture.session.chat.value.canRevert)
    }

    @Test
    fun `a turn in flight may not be reset`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        assertFalse(fixture.session.resetChat())
        assertTrue(fixture.transcripts.deleted.isEmpty())
    }

    private companion object {
        const val DESIGN_ID = "abc123"
        const val OTHER_ID = "def456"
        const val MODIFIED_AT = "2026-01-01T00:00:00Z"
    }
}
