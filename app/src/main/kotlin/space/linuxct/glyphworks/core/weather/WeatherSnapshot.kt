package space.linuxct.glyphworks.core.weather

enum class WeatherStatus {
    DISABLED, NO_PERMISSION, NO_LOCATION, LOADING, UNAVAILABLE, READY, STALE,
}

enum class WeatherCondition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDERSTORM,
}

/** Immutable, in-memory view consumed by both weather renderers; never performs I/O. */
data class WeatherSnapshot(
    val status: WeatherStatus,
    val temperatureC: Double? = null,
    val condition: WeatherCondition? = null,
    val isDay: Boolean = true,
    val observedAtMillis: Long = 0,
)
