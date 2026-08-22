package space.linuxct.glyphworks.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTrace
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphworks.core.ai.GlyphAiTools

class GlyphAiChatCopyTest {
    private val knownTools = listOf(
        GlyphAiTools.GET_CURRENT_DESIGN,
        GlyphAiTools.APPLY_DESIGN,
        GlyphAiTools.VALIDATE_DESIGN,
    )

    private val everyTrace: List<ChatTrace> = buildList {
        add(ChatTrace.Thinking)
        add(ChatTrace.Processing)
        knownTools.forEach { add(ChatTrace.RunningTool(it)) }
        add(ChatTrace.RunningTool("some_future_tool"))
    }

    @Test
    fun `every trace has copy of its own`() {
        val ids = everyTrace.map { it.messageRes() }
        ids.forEach { assertNotEquals("a trace mapped to no string", 0, it) }
        assertEquals("two traces share a string", ids.size, ids.toSet().size)
    }

    @Test
    fun `every known tool has a past-tense label and unknown ones fall back`() {
        val ids = knownTools.map { toolNoteRes(it) }
        ids.forEach { assertNotEquals("a tool mapped to no label", 0, it) }
        assertEquals("two tools share a label", ids.size, ids.toSet().size)
        assertEquals(0, toolNoteRes("set_frames"))
    }

    @Test
    fun `a failed step has copy of its own, distinct from the success`() {
        knownTools.forEach { name ->
            val failed = stepFailureRes(name)
            assertNotEquals("$name has no failure copy", 0, failed)
            assertNotEquals("$name reads the same whether it worked or not", toolNoteRes(name), failed)
        }
        val ids = knownTools.map { stepFailureRes(it) }
        assertEquals("two tools share failure copy", ids.size, ids.toSet().size)
    }

    @Test
    fun `only the drawing tools are numbered by attempt`() {
        assertEquals(3, stepFailureArg(note(GlyphAiTools.VALIDATE_DESIGN), attempt = 3))
        assertEquals(2, stepFailureArg(note(GlyphAiTools.APPLY_DESIGN), attempt = 2))
        assertNull(stepFailureArg(note(GlyphAiTools.GET_CURRENT_DESIGN), attempt = 4))
    }

    private fun note(name: String) = ChatToolNote(name = name, label = ChatToolNote.labelFor(name))

    @Test
    fun `every failure reason has copy of its own`() {
        val ids = GlyphAiOrchestrator.TurnResult.Reason.entries.map { it.messageRes() }
        ids.forEach { assertNotEquals("a failure reason mapped to no string", 0, it) }
        assertEquals("two failure reasons share a string", ids.size, ids.toSet().size)
    }
}
