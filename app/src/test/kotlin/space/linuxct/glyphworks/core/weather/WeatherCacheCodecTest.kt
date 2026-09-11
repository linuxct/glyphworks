package space.linuxct.glyphworks.core.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherCacheCodecTest {
    private val now = 1_783_000_000_000L
    private val entry = WeatherCacheEntry(
        WeatherLocation.create(40.41689, -3.70381, now)!!,
        WeatherObservation(-12.5, WeatherCondition.SNOW, false, now), now,
    )

    @Test fun `cache round trips only rounded location and minimal observation`() {
        val encoded = WeatherCacheCodec.encode(entry)
        assertEquals(entry, WeatherCacheCodec.decode(encoded, now))
        assertFalse(encoded.contains("40.41689"))
        assertTrue(encoded.length < WeatherCacheCodec.MAX_BYTES)
    }

    @Test fun `saved fetch and observation times both bound expiry across process restarts`() {
        assertNotNull(WeatherCacheCodec.decode(WeatherCacheCodec.encode(entry), now + WeatherPolicy.CACHE_EXPIRY_MS - 1))
        assertNull(WeatherCacheCodec.decode(WeatherCacheCodec.encode(entry), now + WeatherPolicy.CACHE_EXPIRY_MS))
        val oldObservation = entry.copy(observation = entry.observation.copy(observedAtMillis = now - WeatherPolicy.CACHE_EXPIRY_MS))
        assertNull(WeatherCacheCodec.decode(WeatherCacheCodec.encode(oldObservation), now))
        val future = entry.copy(fetchedAtMillis = now + WeatherPolicy.FUTURE_TOLERANCE_MS + 1)
        assertNull(WeatherCacheCodec.decode(WeatherCacheCodec.encode(future), now))
    }

    @Test fun `corrupt versioned missing and oversized cache data is ignored`() {
        val encoded = WeatherCacheCodec.encode(entry)
        listOf("", "null", "{", "[]", "{}", encoded.replace("\"version\":1", "\"version\":2"),
            encoded.replace("SNOW", "FUTURE"), encoded.replace("40.42", "190.0"),
            encoded.replace("-12.5", "999"), encoded.replace("\"isDay\":false", "\"isDay\":null"),
            " ".repeat(WeatherCacheCodec.MAX_BYTES + 1),
        ).forEach { assertNull(WeatherCacheCodec.decode(it, now)) }
    }
}
