package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.ambient.AmbientBackgrounds
import kotlin.math.abs

object PrefsMigration {

    private const val OLD_SCREEN_ID = "tea"
    private const val NEW_SCREEN_ID = "timer"
    private const val OLD_START = "teaStartMillis"
    private const val OLD_DURATION = "teaDurationSec"
    private const val OLD_CHIMED_FOR = "teaChimedFor"
    private const val OLD_AMBIENT_CHARGING_STYLE = "ambientChargingStyle"

    fun run(prefs: Prefs): Boolean {
        val from = prefs.getInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_DEF)
        if (from >= PrefKeys.PREFS_VERSION_CURRENT) return false
        if (from < 2) {
            renameTimerKeys(prefs)
            renameScreenId(prefs)
            snapTimerDuration(prefs)
        }
        if (from < 3) migrateAmbientBackground(prefs)
        // The standalone Battery preference is authoritative. Discard the old override
        // without translating it into or otherwise changing BATTERY_SHOW_WATTS.
        if (from < 4) prefs.remove(OLD_AMBIENT_CHARGING_STYLE)
        prefs.putInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_CURRENT)
        return true
    }

    private fun migrateAmbientBackground(prefs: Prefs) {
        if (!prefs.contains(PrefKeys.AMBIENT_BACKGROUND)) return
        if (!prefs.contains(PrefKeys.AMBIENT_BACKGROUNDS)) {
            val oldIndex = prefs.getInt(PrefKeys.AMBIENT_BACKGROUND, PrefKeys.AMBIENT_BACKGROUND_DEF)
            prefs.putString(PrefKeys.AMBIENT_BACKGROUNDS, AmbientBackgrounds.legacyId(oldIndex))
        }
        prefs.remove(PrefKeys.AMBIENT_BACKGROUND)
    }

    private fun renameTimerKeys(prefs: Prefs) {
        moveLong(prefs, OLD_START, PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
        moveLong(prefs, OLD_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF)
        moveInt(prefs, OLD_DURATION, PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF)
    }

    private fun renameScreenId(prefs: Prefs) {
        val oldEnabled = PrefKeys.screenEnabled(OLD_SCREEN_ID)
        moveBoolean(prefs, oldEnabled, PrefKeys.screenEnabled(NEW_SCREEN_ID), true)

        if (prefs.contains(PrefKeys.SCREEN_ORDER)) {
            val order = prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
            val renamed = order.split(',')
                .joinToString(",") { if (it.trim() == OLD_SCREEN_ID) NEW_SCREEN_ID else it }
            if (renamed != order) prefs.putString(PrefKeys.SCREEN_ORDER, renamed)
        }

        if (prefs.contains(PrefKeys.CURRENT_SCREEN) &&
            prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF) == OLD_SCREEN_ID
        ) {
            prefs.putString(PrefKeys.CURRENT_SCREEN, NEW_SCREEN_ID)
        }
    }

    private fun snapTimerDuration(prefs: Prefs) {
        if (!prefs.contains(PrefKeys.TIMER_DURATION)) return
        val stored = prefs.getInt(PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF)
        if (stored in PrefKeys.TIMER_DURATION_OPTIONS) return
        val nearestLongerOnTie = compareBy<Int>({ abs(it - stored) }, { -it })
        val nearest = PrefKeys.TIMER_DURATION_OPTIONS.minWithOrNull(nearestLongerOnTie)
            ?: PrefKeys.TIMER_DURATION_DEF
        prefs.putInt(PrefKeys.TIMER_DURATION, nearest)
    }

    private inline fun move(prefs: Prefs, old: String, new: String, copy: () -> Unit) {
        if (!prefs.contains(old)) return
        if (!prefs.contains(new)) copy()
        prefs.remove(old)
    }

    private fun moveLong(prefs: Prefs, old: String, new: String, def: Long) =
        move(prefs, old, new) { prefs.putLong(new, prefs.getLong(old, def)) }

    private fun moveInt(prefs: Prefs, old: String, new: String, def: Int) =
        move(prefs, old, new) { prefs.putInt(new, prefs.getInt(old, def)) }

    private fun moveBoolean(prefs: Prefs, old: String, new: String, def: Boolean) =
        move(prefs, old, new) { prefs.putBoolean(new, prefs.getBoolean(old, def)) }
}
