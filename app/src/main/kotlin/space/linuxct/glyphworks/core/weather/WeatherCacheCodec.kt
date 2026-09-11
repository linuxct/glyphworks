package space.linuxct.glyphworks.core.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Versioned, bounded on-disk data; stores only rounded coordinates and current conditions. */
object WeatherCacheCodec {
    const val MAX_BYTES = 4096

    fun encode(entry: WeatherCacheEntry): String = buildJsonObject {
        put("version", 1)
        put("latitude", entry.location.latitude)
        put("longitude", entry.location.longitude)
        put("locatedAtMillis", entry.location.locatedAtMillis)
        put("temperatureC", entry.observation.temperatureC)
        put("condition", entry.observation.condition.name)
        put("isDay", entry.observation.isDay)
        put("observedAtMillis", entry.observation.observedAtMillis)
        put("fetchedAtMillis", entry.fetchedAtMillis)
    }.toString()

    fun decode(body: String, nowMillis: Long): WeatherCacheEntry? = try {
        if (body.length > MAX_BYTES) null else decodeEntry(body, nowMillis)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeEntry(body: String, nowMillis: Long): WeatherCacheEntry? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        fun field(key: String) = root[key] as? JsonPrimitive
        fun number(key: String) = field(key)?.takeUnless { it.isString }
        if (number("version")?.intOrNull != 1) return null
        val location = WeatherLocation.create(
            number("latitude")?.doubleOrNull ?: return null,
            number("longitude")?.doubleOrNull ?: return null,
            number("locatedAtMillis")?.longOrNull ?: return null,
        ) ?: return null
        if (location.locatedAtMillis - nowMillis > WeatherPolicy.FUTURE_TOLERANCE_MS) return null
        val condition = field("condition")?.takeIf { it.isString }?.content?.let { name ->
            WeatherCondition.entries.firstOrNull { it.name == name }
        } ?: return null
        val observation = WeatherObservation(
            number("temperatureC")?.doubleOrNull ?: return null,
            condition,
            number("isDay")?.booleanOrNull ?: return null,
            number("observedAtMillis")?.longOrNull ?: return null,
        )
        return WeatherCacheEntry(location, observation,
            number("fetchedAtMillis")?.longOrNull ?: return null).takeIf { it.validAt(nowMillis) }
    }
}
