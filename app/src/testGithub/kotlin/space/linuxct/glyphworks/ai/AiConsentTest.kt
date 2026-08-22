package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConsentTest {
    private class FakeConsent(private var value: Boolean = false) : AiConsentStorage {
        var writes = 0
            private set

        override val accepted: Boolean get() = value

        override fun accept() {
            value = true
            writes++
        }
    }

    @Test
    fun `nothing is disclosed by default, so the first door is the disclosure`() {
        val consent = FakeConsent()
        assertFalse(consent.accepted)
        assertEquals(AiGate.CONSENT, aiGate(consented = consent.accepted, signedIn = false))
    }

    @Test
    fun `a token from an older build does not skip the disclosure`() {
        assertEquals(AiGate.CONSENT, aiGate(consented = false, signedIn = true))
    }

    @Test
    fun `accepting moves on to the sign-in, and signing in to the chat`() {
        val consent = FakeConsent()
        consent.accept()
        assertEquals(AiGate.SIGN_IN, aiGate(consented = consent.accepted, signedIn = false))
        assertEquals(AiGate.CHAT, aiGate(consented = consent.accepted, signedIn = true))
    }

    @Test
    fun `acceptance persists across readers`() {
        val consent = FakeConsent()
        consent.accept()
        repeat(3) { assertTrue(consent.accepted) }
        assertEquals(AiGate.SIGN_IN, aiGate(consent.accepted, signedIn = false))
    }

    @Test
    fun `declining leaves the feature inert and stores nothing`() {
        val consent = FakeConsent()
        assertEquals(0, consent.writes)
        assertFalse(consent.accepted)
        assertEquals(AiGate.CONSENT, aiGate(consent.accepted, signedIn = true))
        assertEquals(AiGate.CONSENT, aiGate(consent.accepted, signedIn = false))
    }
}
