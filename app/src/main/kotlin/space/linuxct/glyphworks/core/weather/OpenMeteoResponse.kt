package space.linuxct.glyphworks.core.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Only current conditions are requested; no location name or notification data is sent. */
object OpenMeteoResponse {
    fun requestUrl(location: WeatherLocation): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
            "&longitude=${location.longitude}&current=temperature_2m,weather_code,is_day" +
            "&temperature_unit=celsius&timeformat=unixtime&timezone=GMT&forecast_days=1"

    /** Provider errors, missing/null fields and unknown codes fail closed to unavailable. */
    fun parse(body: String, nowMillis: Long): WeatherObservation? = try {
        if (body.length > WeatherPolicy.MAX_RESPONSE_BYTES) null else parseCurrent(body, nowMillis)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseCurrent(body: String, nowMillis: Long): WeatherObservation? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val units = root["current_units"] as? JsonObject ?: return null
        if ((units["temperature_2m"] as? JsonPrimitive)?.content != "°C" ||
            (units["time"] as? JsonPrimitive)?.content != "unixtime") return null
        val temperature = number(current, "temperature_2m")?.doubleOrNull ?: return null
        val code = number(current, "weather_code")?.intOrNull ?: return null
        val day = number(current, "is_day")?.intOrNull?.takeIf { it == 0 || it == 1 } ?: return null
        val seconds = number(current, "time")?.longOrNull?.takeIf {
            it in 1..Long.MAX_VALUE / 1000
        } ?: return null
        return WeatherObservation(temperature, condition(code) ?: return null, day == 1,
            seconds * 1000).takeIf { it.validAt(nowMillis) }
    }

    private fun number(current: JsonObject, key: String): JsonPrimitive? =
        (current[key] as? JsonPrimitive)?.takeUnless { it.isString }

    /** WMO interpretation codes documented at https://open-meteo.com/en/docs. */
    fun condition(code: Int): WeatherCondition? = when (code) {
        0, 1 -> WeatherCondition.CLEAR
        2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57 -> WeatherCondition.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDERSTORM
        else -> null
    }
}
