package space.linuxct.glyphworks.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.BuildConfig
import space.linuxct.glyphworks.FakePrefs

class ReviewPromptPolicyTest {
    private val prefs = FakePrefs()
    private val start = 20_000 * DAY
    private val policy = newPolicy(25, 0)

    @Test fun `installation and app launches alone never qualify`() {
        val saved = prefs.map.toMap()
        assertFalse(policy.eligible(start))
        assertFalse(policy.markShownIfEligible(start + 365 * DAY))
        assertEquals(saved, prefs.map)
    }

    @Test fun `one day of real use does not qualify even after months`() {
        use(policy, 0)
        assertFalse(policy.eligible(start + 365 * DAY))
    }

    @Test fun `thousands of frames on the same date count once without repeated writes`() {
        var writes = 0
        prefs.addChangeListener { writes++ }
        use(policy, 0)
        val initialWrites = writes
        repeat(10_000) { policy.recordUse(start + it * 1_000L, start / DAY) }
        assertEquals(initialWrites, writes)
        use(policy, 3)
        assertFalse(policy.eligible(start + 30 * DAY))
    }

    @Test fun `first invitation needs three use days and 72 full hours elapsed`() {
        mature()
        assertFalse(policy.eligible(start + 3 * DAY - 1))
        assertTrue(policy.eligible(start + 3 * DAY))
    }

    @Test fun `72 hours do not imply three days of actual use`() {
        use(policy, 0)
        use(policy, 3)
        assertFalse(policy.eligible(start + 3 * DAY))
        use(policy, 4)
        assertTrue(policy.eligible(start + 4 * DAY))
    }

    @Test fun `eligibility survives process recreation without installing a timer`() {
        mature()
        val restarted = newPolicy(25, 0)
        assertTrue(restarted.markShownIfEligible(start + 3 * DAY))
    }

    @Test fun `opening the prompt consumes the release regardless of how it is dismissed`() {
        showFirstPrompt()
        assertFalse(policy.markShownIfEligible(start + 3 * DAY))
        for (day in 4L..30L) use(policy, day)
        assertFalse(policy.eligible(start + 365 * DAY))
        assertFalse(newPolicy(25, 0).eligible(start + 365 * DAY))
    }

    @Test fun `checking eligibility without displaying does not consume the release`() {
        mature()
        repeat(10) { assertTrue(policy.eligible(start + 3 * DAY)) }
        assertTrue(policy.markShownIfEligible(start + 4 * DAY))
    }

    @Test fun `subsequent update needs exactly 48 hours after installation with no use required`() {
        showFirstPrompt()
        val updated = newPolicy(26, 20)
        assertFalse(updated.needsUsageEvidence())
        assertFalse(updated.eligible(start + 22 * DAY - 1))
        assertTrue(updated.markShownIfEligible(start + 22 * DAY))
        assertFalse(updated.eligible(start + 50 * DAY))
    }

    @Test fun `update installed days before first opening is already old enough`() {
        showFirstPrompt()
        val updated = newPolicy(26, 20)
        assertTrue(updated.eligible(start + 30 * DAY))
    }

    @Test fun `restarting the process does not restart the update waiting period`() {
        showFirstPrompt()
        assertFalse(newPolicy(26, 20).eligible(start + 21 * DAY))
        assertTrue(newPolicy(26, 20).markShownIfEligible(start + 22 * DAY))
    }

    @Test fun `next updates each receive at most one new invitation without usage recording`() {
        showFirstPrompt()
        for (version in 26..29) {
            val installDay = (version - 20) * 10L
            val updated = newPolicy(version, installDay)
            assertFalse(updated.needsUsageEvidence())
            assertFalse(updated.eligible(start + (installDay + 1) * DAY))
            assertTrue(updated.markShownIfEligible(start + (installDay + 2) * DAY))
            assertFalse(updated.markShownIfEligible(start + (installDay + 30) * DAY))
        }
    }

    @Test fun `skipping releases does not queue old invitations`() {
        showFirstPrompt()
        val updated = newPolicy(30, 20)
        assertTrue(updated.markShownIfEligible(start + 22 * DAY))
        repeat(10) { assertFalse(updated.markShownIfEligible(start + 100 * DAY)) }
    }

    @Test fun `rapid upgrades use the latest installation timestamp`() {
        showFirstPrompt()
        val intermediate = newPolicy(26, 10)
        assertFalse(intermediate.eligible(start + 11 * DAY))
        val latest = newPolicy(27, 11)
        assertFalse(latest.eligible(start + 13 * DAY - 1))
        assertTrue(latest.eligible(start + 13 * DAY))
        assertFalse(intermediate.eligible(start + 13 * DAY))
    }

    @Test fun `updates before the first invitation cannot bypass first-use requirements`() {
        use(policy, 0)
        use(policy, 1)
        val updated = newPolicy(26, 2)
        assertTrue(updated.needsUsageEvidence())
        assertFalse(updated.eligible(start + 30 * DAY))
        use(updated, 30)
        assertTrue(updated.markShownIfEligible(start + 30 * DAY))
    }

    @Test fun `never ask survives process recreation and all subsequent upgrades`() {
        showFirstPrompt()
        policy.neverAskAgain()
        val saved = prefs.map.toMap()
        for (version in 25..30) {
            val updated = newPolicy(version, 20)
            assertFalse(updated.needsUsageEvidence())
            for (day in 20L..40L) use(updated, day)
            assertFalse(updated.markShownIfEligible(start + 365 * DAY))
        }
        assertEquals(saved, prefs.map)
    }

    @Test fun `recording stops at three dates even while waiting for the first prompt`() {
        mature()
        assertFalse(policy.needsUsageEvidence())
        assertFalse(policy.eligible(start + 2 * DAY))
        val saved = prefs.map.toMap()
        for (day in 3L..100L) use(policy, day)
        assertEquals(saved, prefs.map)
        assertFalse(newPolicy(25, 0).needsUsageEvidence())
    }

    @Test fun `temporary usage data is deleted on first invitation and never returns`() {
        showFirstPrompt()
        assertEquals(setOf("review_seen_version", "review_prompted_version"), prefs.map.keys)
        val updated = newPolicy(26, 20)
        val saved = prefs.map.toMap()
        for (day in 20L..100L) use(updated, day)
        assertEquals(saved, prefs.map)
        assertTrue(updated.markShownIfEligible(start + 22 * DAY))
        assertEquals(saved.keys, prefs.map.keys)
    }

    @Test fun `downgrades and reinstalling a prompted version do not rearm it`() {
        showFirstPrompt()
        val downgraded = newPolicy(24, 10)
        assertFalse(downgraded.needsUsageEvidence())
        assertFalse(downgraded.eligible(start + 30 * DAY))
        val reinstalled = newPolicy(25, 31)
        assertFalse(reinstalled.needsUsageEvidence())
        assertFalse(reinstalled.eligible(start + 100 * DAY))
    }

    @Test fun `downgrade before the first invitation cannot qualify an older version`() {
        mature()
        val downgraded = newPolicy(24, 10)
        use(downgraded, 10)
        assertFalse(downgraded.eligible(start + 20 * DAY))
        assertTrue(newPolicy(25, 11).eligible(start + 20 * DAY))
    }

    @Test fun `moving the calendar backwards cannot double-count use dates`() {
        use(policy, 0)
        use(policy, 1)
        use(policy, 0)
        use(policy, 1)
        assertFalse(policy.eligible(start + 3 * DAY))
        use(policy, 3)
        assertTrue(policy.eligible(start + 3 * DAY))
    }

    @Test fun `clock rollback postpones eligibility without consuming it`() {
        mature()
        assertFalse(policy.markShownIfEligible(start - DAY))
        assertTrue(policy.markShownIfEligible(start + 3 * DAY))
        val updated = newPolicy(26, 10)
        assertFalse(updated.markShownIfEligible(start + 9 * DAY))
        assertTrue(updated.markShownIfEligible(start + 12 * DAY))
    }

    @Test fun `calendar boundaries do not substitute for elapsed time`() {
        policy.recordUse(start + DAY - 1, start / DAY)
        policy.recordUse(start + DAY, start / DAY + 1)
        policy.recordUse(start + 2 * DAY, start / DAY + 2)
        assertFalse(policy.eligible(start + 3 * DAY))
        assertTrue(policy.eligible(start + 4 * DAY - 1))
    }

    @Test fun `missing update timestamp cannot cause an immediate reminder`() {
        showFirstPrompt()
        val updated = ReviewPromptPolicy(prefs, 26, 0)
        assertFalse(updated.eligible(start + 365 * DAY))
    }

    @Test fun `store destination uses the generated app id`() {
        assertEquals("https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}",
            reviewListingUrl(BuildConfig.APPLICATION_ID))
    }

    private fun newPolicy(version: Int, installDay: Long) =
        ReviewPromptPolicy(prefs, version, start + installDay * DAY)

    private fun use(target: ReviewPromptPolicy, day: Long) =
        target.recordUse(start + day * DAY, start / DAY + day)

    private fun mature() {
        use(policy, 0)
        use(policy, 1)
        use(policy, 2)
    }

    private fun showFirstPrompt() {
        mature()
        assertTrue(policy.markShownIfEligible(start + 3 * DAY))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
