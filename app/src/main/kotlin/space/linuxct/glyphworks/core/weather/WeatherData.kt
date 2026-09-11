package space.linuxct.glyphworks.core.weather

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinates are deliberately rounded before either persistence or transmission. */
@ConsistentCopyVisibility
data class WeatherLocation private constructor(
    val latitude: Double,
    val longitude: Double,
    val locatedAtMillis: Long,
) {
    fun distanceKm(other: WeatherLocation): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val halfLat = sin((lat2 - lat1) / 2)
        val halfLon = sin(Math.toRadians(other.longitude - longitude) / 2)
        val a = halfLat * halfLat + cos(lat1) * cos(lat2) * halfLon * halfLon
        return 12_742.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        fun create(latitude: Double, longitude: Double, locatedAtMillis: Long): WeatherLocation? {
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 || locatedAtMillis <= 0
            ) return null
            return WeatherLocation(round(latitude * 100) / 100, round(longitude * 100) / 100,
                locatedAtMillis)
        }
    }
}

data class WeatherObservation(
    val temperatureC: Double,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val observedAtMillis: Long,
) {
    fun validAt(nowMillis: Long): Boolean = temperatureC.isFinite() && temperatureC in -100.0..70.0 &&
        observedAtMillis > 0 && nowMillis - observedAtMillis in
        -WeatherPolicy.FUTURE_TOLERANCE_MS until WeatherPolicy.CACHE_EXPIRY_MS
}

data class WeatherCacheEntry(
    val location: WeatherLocation,
    val observation: WeatherObservation,
    val fetchedAtMillis: Long,
) {
    fun validAt(nowMillis: Long): Boolean = observation.validAt(nowMillis) && fetchedAtMillis > 0 &&
        nowMillis - fetchedAtMillis in -WeatherPolicy.FUTURE_TOLERANCE_MS until WeatherPolicy.CACHE_EXPIRY_MS
}

object WeatherPolicy {
    const val REFRESH_MS = 15 * 60_000L
    const val CACHE_EXPIRY_MS = 2 * 60 * 60_000L
    const val FUTURE_TOLERANCE_MS = 5 * 60_000L
    const val MIN_REQUEST_MS = 60_000L
    const val MAX_BACKOFF_MS = 60 * 60_000L
    const val MAX_RETRY_AFTER_MS = 24 * 60 * 60_000L
    const val MOVEMENT_KM = 5.0
    const val MAX_RESPONSE_BYTES = 64 * 1024

    fun backoffMillis(failures: Int): Long =
        (MIN_REQUEST_MS * (1L shl (failures - 1).coerceIn(0, 6))).coerceAtMost(MAX_BACKOFF_MS)
}
