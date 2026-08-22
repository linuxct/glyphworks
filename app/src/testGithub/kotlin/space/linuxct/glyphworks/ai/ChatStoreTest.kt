package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.ai.ChatMessage
import space.linuxct.glyphworks.core.ai.ChatRole
import space.linuxct.glyphworks.core.ai.ChatToolNote
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.ChatTranscriptCodec
import java.io.File

class ChatStoreTest {

    @Test
    fun `an ordinary design id names a json file beside the others`() {
        assertEquals("abc123.json", chatFileName("abc123"))
        assertEquals(
            "9f2c4b1e8a6d40f2b3c5d7e9f1a2b3c4.json",
            chatFileName("9f2c4b1e8a6d40f2b3c5d7e9f1a2b3c4"),
        )
        assertEquals("A_b-C.json", chatFileName("A_b-C"))
    }

    @Test
    fun `nothing that could escape the directory can name a file`() {
        val hostile = listOf(
            "..",
            "../secrets",
            "../../shared_prefs/openai_auth",
            "chats/../../x",
            "a/b",
            "a\\b",
            "with space",
            "dot.ted",
            "nul\u0000byte",
            "",
            "über",
        )

        hostile.forEach { assertNull(it, chatFileName(it)) }
    }

    @Test
    fun `a transcript written whole is read back whole`() {
        val file = write("good.json", ChatTranscriptCodec.encode(transcript))

        val read = readTranscript(file)

        assertEquals(transcript, read)
    }

    @Test
    fun `a file that does not exist is simply no history`() {
        assertNull(readTranscript(File(dir(), "never-written.json")))
    }

    @Test
    fun `a truncated file degrades to no history rather than throwing`() {
        val whole = ChatTranscriptCodec.encode(transcript)
        val file = write("truncated.json", whole.substring(0, whole.length - 40))

        assertNull(readTranscript(file))
    }

    @Test
    fun `an absurdly large file is refused without being read into memory`() {
        val file = File(dir(), "huge.json").apply {
            writeText("{")
            java.io.RandomAccessFile(this, "rw").use {
                it.setLength(ChatTranscriptCodec.MAX_BYTES + 1L)
            }
        }

        assertNull(readTranscript(file))
    }

    @Test
    fun `a conversation whose design is gone is an orphan`() {
        val orphans = orphanChats(
            listOf("alive.json", "deleted.json", "alsodeleted.json"),
            setOf("alive"),
        )

        assertEquals(listOf("deleted.json", "alsodeleted.json"), orphans)
    }

    @Test
    fun `an orphan's backup and temp go with it`() {
        val orphans = orphanChats(
            listOf("gone.json", "gone.json.bak", "gone.json.tmp"),
            emptySet(),
        )

        assertEquals(listOf("gone.json", "gone.json.bak", "gone.json.tmp"), orphans)
    }

    @Test
    fun `nothing this store did not write is ever swept up`() {
        val strangers = listOf(
            "notes.txt",
            "README",
            ".json",
            "with space.json",
            "über.json",
            "sub",
            "",
        )

        assertEquals(emptyList<String>(), orphanChats(strangers, emptySet()))
    }

    @Test
    fun `clearing a conversation takes its file, its backup and its temp`() {
        val directory = freshDir("glyphworks-chat-delete")
        File(directory, "abc123.json").writeText(ChatTranscriptCodec.encode(transcript))
        File(directory, "abc123.json.bak").writeText("{}")
        File(directory, "abc123.json.tmp").writeText("{}")

        assertTrue(deleteTranscript(directory, "abc123.json"))

        assertEquals(emptyList<String>(), directory.list()!!.sorted())
    }

    @Test
    fun `a conversation appended turn by turn comes back in the same order`() {
        var thread = ChatTranscript(designId = "abc123")
        thread = thread.plus(ChatMessage(role = ChatRole.USER, text = "draw a smiley", atMs = 10L))
        thread = thread.plus(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                text = "Here you go.",
                atMs = 11L,
                tools = listOf(
                    ChatToolNote(name = "get_current_design", label = "Read your design"),
                    ChatToolNote(name = "apply_design", label = "Applied a change", changedDesign = true),
                ),
            ),
        )
        thread = thread.plus(
            ChatMessage(role = ChatRole.USER, text = "now make it blink", atMs = 12L, imageCount = 2),
        )

        val restored = readTranscript(write("thread.json", ChatTranscriptCodec.encode(thread)))

        assertEquals(thread, restored)
        assertEquals(3, restored!!.messages.size)
        assertEquals(listOf(10L, 11L, 12L), restored.messages.map { it.atMs })
        assertTrue(restored.messages[1].tools.any { it.changedDesign })
        assertEquals(2, restored.messages[2].imageCount)
    }

    @Test
    fun `a restored conversation is the context the next turn is sent with`() {
        val thread = ChatTranscript(designId = "abc123")
            .plus(ChatMessage(role = ChatRole.USER, text = "draw a smiley", atMs = 1L))
            .plus(ChatMessage(role = ChatRole.ASSISTANT, text = "Here you go.", atMs = 2L))
            .plus(ChatMessage(role = ChatRole.ASSISTANT, text = "", atMs = 3L))

        val restored = readTranscript(write("resumed.json", ChatTranscriptCodec.encode(thread)))!!

        val input = restored.asInput()
        assertEquals("the blank turn is not replayed", 2, input.size)
    }

    private val transcript = ChatTranscript(
        designId = "abc123",
        messages = listOf(
            ChatMessage(role = ChatRole.USER, text = "make it rounder", atMs = 1L),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Done.", atMs = 2L),
        ),
    )

    private fun dir(): File =
        File(System.getProperty("java.io.tmpdir"), "glyphworks-chat-test").apply { mkdirs() }

    private fun freshDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), name).apply {
            deleteRecursively()
            mkdirs()
        }

    private fun write(name: String, text: String): File =
        File(dir(), name).apply { writeText(text) }
}
