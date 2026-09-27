package space.linuxct.glyphworks.review

import space.linuxct.glyphworks.core.Prefs

/** First ask requires real Glyph use. Later asks require a newer install at least 48 hours old. */
internal class ReviewPromptPolicy(
    private val prefs: Prefs,
    private val versionCode: Int,
    private val installedAtMillis: Long,
) {
    init {
        // Version observation never counts as use. Keep a high-water mark across downgrades.
        if (!prefs.getBoolean(NEVER, false) && versionCode > prefs.getInt(SEEN_VERSION, 0)) {
            prefs.putInt(SEEN_VERSION, versionCode)
        }
    }

    /** Once three dates are established, the hardware observer can be detached altogether. */
    @Synchronized
    fun needsUsageEvidence(): Boolean =
        !prefs.getBoolean(NEVER, false) && prefs.getInt(PROMPTED_VERSION, 0) == 0 &&
            prefs.getInt(DAYS, 0) < 3 && versionCode == prefs.getInt(SEEN_VERSION, 0)

    /** Called for actual display use; repeated frames on the same date do not add days or writes. */
    @Synchronized
    fun recordUse(nowMillis: Long, epochDay: Long) {
        if (!needsUsageEvidence() || nowMillis <= 0) return

        if (!prefs.contains(FIRST_USE)) prefs.putLong(FIRST_USE, nowMillis)
        // Only advancing dates count. Clock/time-zone rollback cannot count the same dates twice.
        if (!prefs.contains(LAST_DAY) || epochDay > prefs.getLong(LAST_DAY, epochDay)) {
            prefs.putLong(LAST_DAY, epochDay)
            prefs.putInt(DAYS, prefs.getInt(DAYS, 0) + 1)
        }
    }

    @Synchronized
    fun eligible(nowMillis: Long): Boolean {
        if (prefs.getBoolean(NEVER, false)) return false
        val promptedVersion = prefs.getInt(PROMPTED_VERSION, 0)
        if (versionCode <= promptedVersion || versionCode != prefs.getInt(SEEN_VERSION, 0)) return false
        return if (promptedVersion == 0) {
            prefs.getInt(DAYS, 0) >= 3 && oldEnough(nowMillis, prefs.getLong(FIRST_USE, 0), 3 * DAY_MS)
        } else {
            oldEnough(nowMillis, installedAtMillis, 2 * DAY_MS)
        }
    }

    /** Claim before opening the dialog, including when it is dismissed without choosing a button. */
    @Synchronized
    fun markShownIfEligible(nowMillis: Long): Boolean {
        if (!eligible(nowMillis)) return false
        prefs.putInt(PROMPTED_VERSION, versionCode)
        // Only release/opt-out bookkeeping survives the first invitation. No lasting usage history.
        prefs.remove(FIRST_USE)
        prefs.remove(LAST_DAY)
        prefs.remove(DAYS)
        return true
    }

    @Synchronized
    fun neverAskAgain() {
        prefs.putBoolean(NEVER, true)
    }

    private fun oldEnough(nowMillis: Long, first: Long, delay: Long): Boolean {
        return first > 0 && nowMillis >= first && nowMillis - first >= delay
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NEVER = "review_never"
        const val PROMPTED_VERSION = "review_prompted_version"
        const val SEEN_VERSION = "review_seen_version"
        const val FIRST_USE = "review_first_use"
        const val LAST_DAY = "review_last_day"
        const val DAYS = "review_days"
    }
}
