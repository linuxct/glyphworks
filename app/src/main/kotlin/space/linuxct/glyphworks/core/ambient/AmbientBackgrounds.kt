package space.linuxct.glyphworks.core.ambient

import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs

/** Stable preference IDs; this order also defines the settings list and carousel order. */
object AmbientBackgrounds {
    const val TEXT_CLOCK = "text_clock"
    const val ANALOG_CLOCK = "analog_clock"
    const val CONNECTION = "connection"
    const val BATTERY_TEXT = "battery_text"
    const val SPEED = "speed"
    const val TILT_BALL = "tilt_ball"
    const val PIXEL_CLOCK = "pixel_clock"
    const val BATTERY_GAUGE = "battery_gauge"
    const val SOLAR_PATH = "solar_path"
    const val MOON_PHASE = "moon_phase"
    const val NOTIFICATIONS = "notifications"
    const val WEATHER = "weather"

    private val legacyIds = listOf(
        TEXT_CLOCK, ANALOG_CLOCK, CONNECTION, BATTERY_TEXT, SPEED, TILT_BALL,
        PIXEL_CLOCK, BATTERY_GAUGE, SOLAR_PATH, MOON_PHASE,
    )

    val orderedIds: List<String> = legacyIds + listOf(NOTIFICATIONS, WEATHER)

    /** Match the old compositor's bounds handling, including out-of-range stored values. */
    fun legacyId(index: Int): String = legacyIds[index.coerceIn(0, legacyIds.lastIndex)]

    fun readSelection(prefs: Prefs): List<String> = decode(
        prefs.getString(PrefKeys.AMBIENT_BACKGROUNDS, PrefKeys.AMBIENT_BACKGROUNDS_DEF),
    )

    /** Unknown IDs are ignored; a deliberately empty selection remains empty. */
    fun decode(value: String): List<String> {
        val enabled = value.split(',').map { it.trim() }.toSet()
        return orderedIds.filter { it in enabled }
    }

    fun encode(ids: Collection<String>): String = orderedIds.filter { it in ids }.joinToString(",")
}
