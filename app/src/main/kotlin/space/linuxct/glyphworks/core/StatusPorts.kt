package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus

/** Outstanding notifications, or null while access/the listener is unavailable. */
fun interface NotificationPort {
    fun count(): Int?
}

object NotificationPrefs {
    const val STYLE = "notificationStyle"
    const val ENVELOPE = "envelope"
    const val TEXT = "text"
    const val DOT = "dot"
    const val BELL = "bell"
    const val DEFAULT_STYLE = TEXT
    val styles = listOf(ENVELOPE, TEXT, DOT, BELL)

    fun normalize(style: String) = style.takeIf { it in styles } ?: DEFAULT_STYLE
    fun style(prefs: Prefs) = normalize(prefs.getString(STYLE, DEFAULT_STYLE))
}

/** Snapshot reads never block the compositor. Demand changes schedule work elsewhere. */
interface WeatherPort {
    fun snapshot(): WeatherSnapshot
    fun setActive(active: Boolean)
}

object NoWeatherPort : WeatherPort {
    override fun snapshot() = WeatherSnapshot(WeatherStatus.DISABLED)
    override fun setActive(active: Boolean) = Unit
}

object WeatherPrefs {
    const val ENABLED = "weatherEnabled"
    const val UNIT = "weatherUnit"
    const val CELSIUS = "celsius"
    const val FAHRENHEIT = "fahrenheit"
    fun fahrenheit(prefs: Prefs) = prefs.getString(UNIT, CELSIUS) == FAHRENHEIT
}
