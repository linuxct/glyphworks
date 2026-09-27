package space.linuxct.glyphworks.review

import java.time.Clock
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs

/** GlyphLink calls this on its I/O thread only after a frame reaches the hardware successfully. */
internal class ReviewUsageRecorder(
    private val prefs: Prefs,
    private val policy: ReviewPromptPolicy,
    private val clock: Clock,
    private val elapsedMillis: () -> Long,
) {
    private var nextSample = 0L
    private var collecting = policy.needsUsageEvidence()

    /** False asks GlyphLink to detach this observer, including all its clock and frame checks. */
    fun onFrameDisplayed(frame: IntArray): Boolean {
        if (!collecting) return false
        // Sample at most once a minute, rather than inspecting preferences and dates per frame.
        val elapsed = elapsedMillis()
        if (elapsed < nextSample) return true
        if (frame.none { it > 0 } || !prefs.getBoolean(PrefKeys.ONBOARDING_DONE, false)) return true
        nextSample = elapsed + 60_000L
        val now = clock.instant()
        policy.recordUse(now.toEpochMilli(), now.atZone(clock.zone).toLocalDate().toEpochDay())
        collecting = policy.needsUsageEvidence()
        return collecting
    }
}
