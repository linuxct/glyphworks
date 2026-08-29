package space.linuxct.glyphworks.core

// Nothing build numbers read <androidLetter><major>.<minor>-<date>-<time>, as in B4.1-260723-1820,
// and some carry a codename and a region around it: Metroid-C5.0-260819-1642-IND. The letter
// tracks Android, so only the major decides.
private val NOTHING_BUILD_NUMBER = Regex("(?:^|-)[A-Z](\\d+)\\.(\\d+)-")

private const val LUCENT_MAJOR = 5

fun nothingOsMajor(buildNumber: String): Int? =
    NOTHING_BUILD_NUMBER.find(buildNumber)?.groupValues?.get(1)?.toIntOrNull()

fun runsLucentOs(buildNumber: String): Boolean = (nothingOsMajor(buildNumber) ?: 0) >= LUCENT_MAJOR

/**
 * [PrefKeys.LUCENT_ADOPTED] is what makes the switch-on happen exactly once. Turning the look
 * back off leaves it set, so upgrading, downgrading and upgrading again never overrules the user.
 */
fun resolveLucent(prefs: Prefs, onLucentOs: Boolean): Boolean {
    if (onLucentOs && !prefs.getBoolean(PrefKeys.LUCENT_ADOPTED, PrefKeys.LUCENT_ADOPTED_DEF)) {
        prefs.putBoolean(PrefKeys.LUCENT_ADOPTED, true)
        prefs.putBoolean(PrefKeys.LUCENT_ENABLED, true)
        return true
    }
    return prefs.getBoolean(PrefKeys.LUCENT_ENABLED, PrefKeys.LUCENT_ENABLED_DEF)
}
