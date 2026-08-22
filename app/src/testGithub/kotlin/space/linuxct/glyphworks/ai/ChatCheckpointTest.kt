package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatRole
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.ChatTranscriptCodec
import java.io.File

class ChatCheckpointTest {
    private val user = ChatMessage(role = ChatRole.USER, text = "draw a cat", atMs = 1)

    private fun checkpoint(text: String, tools: List<ChatToolNote> = emptyList()) = ChatMessage(
        role = ChatRole.ASSISTANT,
        text = text,
        atMs = 2,
        tools = tools,
        partial = true,
    )

    @Test
    fun `the first checkpoint is appended`() {
        val transcript = ChatTranscript(designId = "abc").plus(user)

        val checkpointed = transcript.withPartial(checkpoint("Draw"))

        assertEquals(2, checkpointed.messages.size)
        assertTrue(checkpointed.messages[1].partial)
    }

    @Test
    fun `every checkpoint after it replaces the one before`() {
        var transcript = ChatTranscript(designId = "abc").plus(user)

        repeat(40) { index -> transcript = transcript.withPartial(checkpoint("word ".repeat(index))) }

        assertEquals(2, transcript.messages.size)
        assertEquals("word ".repeat(39), transcript.messages[1].text)
    }

    @Test
    fun `dropping the checkpoint leaves the conversation as it was`() {
        val before = ChatTranscript(designId = "abc").plus(user)

        assertEquals(before, before.withPartial(checkpoint("Draw")).withoutPartial())
    }

    @Test
    fun `a checkpoint survives a write and a read`() {
        val transcript = ChatTranscript(designId = "abc")
            .plus(user)
            .withPartial(
                checkpoint(
                    "Half a sen",
                    tools = listOf(ChatToolNote(name = "validate_design", label = "Checked")),
                ),
            )
        val file = File.createTempFile("chat", ".json")
        file.deleteOnExit()
        file.writeText(ChatTranscriptCodec.encode(transcript))

        val read = readTranscript(file)

        assertNotNull(read)
        assertEquals(2, read!!.messages.size)
        assertEquals("Half a sen", read.messages[1].text)
        assertTrue(read.messages[1].partial)
        assertEquals(1, read.messages[1].tools.size)
        assertFalse(read.messages[0].partial)
    }
}
