package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    private fun badgeShown(status: SetupStatus) = status.needsAttention

    private fun sectionStartsExpanded(status: SetupStatus) = status.needsAttention

    private fun assertNeedsAttention(status: SetupStatus) {
        assertTrue("an outstanding item must badge the nav bar: $status", badgeShown(status))
        assertTrue(
            "an outstanding item must open the section on arrival: $status",
            sectionStartsExpanded(status),
        )
    }

    @Test
    fun `everything done means no badge and a collapsed section`() {
        val status = SetupStatus.COMPLETE
        assertFalse("a finished checklist must not badge the nav bar", badgeShown(status))
        assertFalse(
            "a finished checklist is six rows of noise; it stays collapsed",
            sectionStartsExpanded(status),
        )
    }

    @Test
    fun `the accessibility service missing badges and expands`() {
        assertNeedsAttention(SetupStatus.COMPLETE.copy(accessibility = false))
    }

    @Test
    fun `nothing done at all badges and expands`() {
        assertNeedsAttention(
            SetupStatus(
                accessibility = false,
                alwaysOnToy = false,
                toyProbeArmed = true,
                notifications = false,
                microphone = false,
                location = false,
                exactAlarms = false,
            ),
        )
    }

    @Test
    fun `an unset toy stays silent until the probe can be believed`() {
        val cannotTellYet = SetupStatus.COMPLETE.copy(alwaysOnToy = false, toyProbeArmed = false)
        assertFalse(
            "before the probe is armed a missing toy is unknown, not missing",
            badgeShown(cannotTellYet),
        )
        assertNeedsAttention(cannotTellYet.copy(toyProbeArmed = true))
    }

    @Test
    fun `only the complete status clears the badge`() {
        val oneMissing = listOf(
            SetupStatus.COMPLETE.copy(accessibility = false),
            SetupStatus.COMPLETE.copy(alwaysOnToy = false),
            SetupStatus.COMPLETE.copy(notifications = false),
            SetupStatus.COMPLETE.copy(microphone = false),
            SetupStatus.COMPLETE.copy(location = false),
            SetupStatus.COMPLETE.copy(exactAlarms = false),
        )
        assertEquals("one case per item", 6, oneMissing.size)
        assertEquals("each case must differ from COMPLETE", 6, oneMissing.toSet().size)
        oneMissing.forEach { assertTrue(it.toString(), badgeShown(it)) }
    }
}
