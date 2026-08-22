package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.ai.ChatReasoning
import space.linuxct.glyphworks.core.ai.ChatRequest
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.ReasoningEffort

class AiReasoningEffortTest {
    @Test
    fun anUnsetEffortIsTheWireDefault() {
        assertEquals(ChatWire.DEFAULT_REASONING_EFFORT, FakePrefs().aiReasoningEffort().wire)
    }

    @Test
    fun everyLevelSurvivesAStoreAndAReadBack() {
        val prefs = FakePrefs()
        for (level in ReasoningEffort.entries) {
            prefs.putString(AiPrefKeys.REASONING_EFFORT, level.wire)
            assertEquals("$level should round trip", level, prefs.aiReasoningEffort())
        }
    }

    @Test
    fun anUnknownStoredTokenDegradesToTheDefault() {
        val prefs = FakePrefs()
        for (stored in listOf("", "   ", "insane", "MEDIUM-ish", "0", "null", "extra-high")) {
            prefs.putString(AiPrefKeys.REASONING_EFFORT, stored)
            assertEquals(
                "stored \"$stored\" should degrade to the default",
                ReasoningEffort.DEFAULT,
                prefs.aiReasoningEffort(),
            )
        }
    }

    @Test
    fun theChosenLevelIsWhatTheRequestCarries() {
        val body = ChatWire.encodeRequest(
            ChatRequest(reasoning = ChatReasoning(ReasoningEffort.ULTRA.wire)),
        )
        assertTrue(body, body.contains("\"reasoning\""))
        assertTrue(body, body.contains("\"effort\":\"ultra\""))

        val without = ChatWire.encodeRequest(ChatRequest(reasoning = null))
        assertFalse(without, without.contains("reasoning"))
    }
}
