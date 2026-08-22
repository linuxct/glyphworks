package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiMaxRounds
import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator

class AiMaxRoundsTest {
    @Test
    fun anUnsetBudgetIsTheOrchestratorsOwnDefault() {
        assertEquals(
            GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS,
            FakePrefs().aiMaxRounds(),
        )
    }

    @Test
    fun aStoredBudgetIsUsedAsWritten() {
        val prefs = FakePrefs()
        prefs.putInt(AiPrefKeys.MAX_ROUNDS, 24)
        assertEquals(24, prefs.aiMaxRounds())
    }

    @Test
    fun aBudgetBelowTheFloorIsRaisedToIt() {
        val prefs = FakePrefs()
        for (stored in listOf(Int.MIN_VALUE, -1, 0, 1, AiPrefKeys.MAX_ROUNDS_MIN - 1)) {
            prefs.putInt(AiPrefKeys.MAX_ROUNDS, stored)
            assertEquals(
                "stored $stored should clamp up to the floor",
                AiPrefKeys.MAX_ROUNDS_MIN,
                prefs.aiMaxRounds(),
            )
        }
    }
}
