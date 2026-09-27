package space.linuxct.glyphworks.review

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.PrefKeys

class ReviewUsageRecorderTest {
    private val prefs = FakePrefs().apply { putBoolean(PrefKeys.ONBOARDING_DONE, true) }
    private val clock = TestClock()
    private val start = clock.now.toEpochMilli()
    private val policy = ReviewPromptPolicy(prefs, 25, start)
    private var elapsed = 0L
    private var elapsedReads = 0
    private val recorder = ReviewUsageRecorder(prefs, policy, clock) { elapsedReads++; elapsed }
    private val lit = IntArray(169).apply { this[84] = 100 }
    private val dark = IntArray(169)

    @Test fun `time and dark frames never count as use`() {
        for (day in 0L..10L) {
            advanceTo(day)
            assertTrue(recorder.onFrameDisplayed(dark))
        }
        assertEquals(0, clock.reads)
        assertTrue(policy.needsUsageEvidence())
        assertFalse(policy.eligible(start + 100 * DAY))
    }

    @Test fun `only lit hardware frames on three separate dates qualify`() {
        assertTrue(recorder.onFrameDisplayed(lit))
        advanceTo(1)
        assertTrue(recorder.onFrameDisplayed(lit))
        advanceTo(3)
        assertFalse(policy.eligible(start + 3 * DAY))
        assertFalse(recorder.onFrameDisplayed(lit))
        assertTrue(policy.eligible(start + 3 * DAY))
    }

    @Test fun `frames before setup is complete do not start the clock`() {
        prefs.putBoolean(PrefKeys.ONBOARDING_DONE, false)
        for (day in 0L..10L) {
            advanceTo(day)
            assertTrue(recorder.onFrameDisplayed(lit))
        }
        assertEquals(0, clock.reads)
        prefs.putBoolean(PrefKeys.ONBOARDING_DONE, true)
        assertTrue(recorder.onFrameDisplayed(lit))
        advanceTo(11)
        assertTrue(recorder.onFrameDisplayed(lit))
        advanceTo(12)
        assertFalse(recorder.onFrameDisplayed(lit))
        assertFalse(policy.eligible(start + 13 * DAY - 1))
        assertTrue(policy.eligible(start + 13 * DAY))
    }

    @Test fun `animated frames sample the date at most once per minute`() {
        recorder.onFrameDisplayed(lit)
        for (time in 1L..59_999L step 16L) {
            elapsed = time
            recorder.onFrameDisplayed(lit)
        }
        assertEquals(1, clock.reads)
        elapsed = 60_000L
        recorder.onFrameDisplayed(lit)
        assertEquals(2, clock.reads)
        assertFalse(policy.eligible(start + 100 * DAY))
    }

    @Test fun `third qualifying day detaches observer and stops all clock reads and writes`() {
        for (day in 0L..2L) {
            advanceTo(day)
            recorder.onFrameDisplayed(lit)
        }
        val saved = prefs.map.toMap()
        val wallReads = clock.reads
        val monotonicReads = elapsedReads
        for (day in 3L..100L) {
            advanceTo(day)
            assertFalse(recorder.onFrameDisplayed(lit))
        }
        assertEquals(saved, prefs.map)
        assertEquals(wallReads, clock.reads)
        assertEquals(monotonicReads, elapsedReads)
    }

    @Test fun `after the first invitation even a newly constructed recorder does no work`() {
        for (day in 0L..2L) {
            advanceTo(day)
            recorder.onFrameDisplayed(lit)
        }
        assertTrue(policy.markShownIfEligible(start + 3 * DAY))
        val updated = ReviewPromptPolicy(prefs, 26, start + 10 * DAY)
        val nextRecorder = ReviewUsageRecorder(prefs, updated, clock) { error("No usage clock after first ask") }
        val saved = prefs.map.toMap()
        val reads = clock.reads
        assertFalse(nextRecorder.onFrameDisplayed(lit))
        assertEquals(saved, prefs.map)
        assertEquals(reads, clock.reads)
        assertTrue(updated.eligible(start + 12 * DAY))
    }

    @Test fun `dates use the device calendar rather than UTC midnight`() {
        clock.zoneId = ZoneId.of("Europe/Madrid")
        clock.now = Instant.parse("2026-09-27T21:59:59Z")
        recorder.onFrameDisplayed(lit)
        clock.now = Instant.parse("2026-09-27T22:01:00Z")
        elapsed += 61_000L
        recorder.onFrameDisplayed(lit)
        clock.now = Instant.parse("2026-09-28T22:01:00Z")
        elapsed += DAY
        assertFalse(recorder.onFrameDisplayed(lit))
        assertTrue(policy.eligible(Instant.parse("2026-09-30T21:59:59Z").toEpochMilli()))
    }

    private fun advanceTo(day: Long) {
        elapsed = day * DAY
        clock.now = Instant.ofEpochMilli(start + day * DAY)
    }

    private class TestClock : Clock() {
        var now: Instant = Instant.parse("2026-09-27T12:00:00Z")
        var reads = 0
        var zoneId: ZoneId = ZoneOffset.UTC
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant { reads++; return now }
    }

    private companion object { const val DAY = 86_400_000L }
}
