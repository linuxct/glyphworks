package space.linuxct.glyphworks.core.weather

import org.junit.Assert.*
import org.junit.Test

class OpenMeteoResponseTest {
    private val now = 1_783_000_000_000L
    private fun response(temp: String = "22.5", code: String = "2", day: String = "1", time: String = "${now / 1000}") =
        """{"current_units":{"time":"unixtime","temperature_2m":"°C"},"current":{"time":$time,"temperature_2m":$temp,"weather_code":$code,"is_day":$day}}"""

    @Test fun `current response parses daytime nighttime freezing and high Fahrenheit range in Celsius`() {
        val current = OpenMeteoResponse.parse(response(), now)!!
        assertEquals(22.5, current.temperatureC, 0.0)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, current.condition)
        assertTrue(current.isDay)
        assertEquals(now, current.observedAtMillis)
        assertEquals(-17.5, OpenMeteoResponse.parse(response(temp = "-17.5", day = "0"), now)!!.temperatureC, 0.0)
        assertFalse(OpenMeteoResponse.parse(response(day = "0"), now)!!.isDay)
        assertNotNull(OpenMeteoResponse.parse(response(temp = "50.0"), now))
    }

    @Test fun `all documented WMO codes map to appropriate artwork`() {
        val expected = mapOf(
            WeatherCondition.CLEAR to listOf(0, 1),
            WeatherCondition.PARTLY_CLOUDY to listOf(2),
            WeatherCondition.CLOUDY to listOf(3),
            WeatherCondition.FOG to listOf(45, 48),
            WeatherCondition.DRIZZLE to listOf(51, 53, 55, 56, 57),
            WeatherCondition.RAIN to listOf(61, 63, 65, 66, 67, 80, 81, 82),
            WeatherCondition.SNOW to listOf(71, 73, 75, 77, 85, 86),
            WeatherCondition.THUNDERSTORM to listOf(95, 96, 99),
        )
        expected.forEach { (condition, codes) -> codes.forEach { code ->
            assertEquals("code $code", condition, OpenMeteoResponse.parse(response(code = "$code"), now)!!.condition)
        } }
        assertNull(OpenMeteoResponse.condition(100))
    }

    @Test fun `malformed incomplete unknown and incorrectly typed responses are rejected`() {
        listOf("", "{", "[]", "{}", "null", """{"error":true,"reason":"limit"}""",
            response(temp = "null"), response(temp = "\"22.5\""), response(temp = "true"),
            response(temp = "999"), response(temp = "NaN"), response(code = "4"),
            response(code = "2.5"), response(day = "2"), response(day = "null"),
            response(time = "9223372036854775807"), response(time = "0"),
            response().replace("°C", "°F"), response().replace("unixtime", "iso8601"),
            response().replace("\"temperature_2m\":22.5,", ""),
            " ".repeat(WeatherPolicy.MAX_RESPONSE_BYTES + 1),
        ).forEach { assertNull("Accepted invalid response: ${it.take(200)}", OpenMeteoResponse.parse(it, now)) }
    }

    @Test fun `provider timestamps beyond expiry and clock tolerance cannot become fresh cache`() {
        assertNull(OpenMeteoResponse.parse(response(time = "${(now - WeatherPolicy.CACHE_EXPIRY_MS) / 1000}"), now))
        assertNull(OpenMeteoResponse.parse(response(time = "${(now + WeatherPolicy.FUTURE_TOLERANCE_MS + 1000) / 1000}"), now))
    }

    @Test fun `URL sends only rounded coordinates and requested current fields over HTTPS`() {
        val url = OpenMeteoResponse.requestUrl(WeatherLocation.create(40.41689, -3.70381, now)!!)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=40.42&longitude=-3.7"))
        assertTrue(url.contains("current=temperature_2m,weather_code,is_day"))
        assertTrue(url.contains("timeformat=unixtime"))
        assertFalse(url.contains("apikey"))
    }
}
