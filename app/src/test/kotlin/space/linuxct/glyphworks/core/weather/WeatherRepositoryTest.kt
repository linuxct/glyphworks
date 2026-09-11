package space.linuxct.glyphworks.core.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherRepositoryTest {
    private val now = 1_783_000_000_000L
    private val elapsed = 100_000L
    private val fixElapsedNanos = elapsed * 1_000_000
    private val location = WeatherLocation.create(40.41689, -3.70381, now)!!
    private val observation = WeatherObservation(22.5, WeatherCondition.CLEAR, true, now)

    private fun repository() = WeatherRepository().also {
        it.configure(enabled = true, permitted = true, active = true)
        it.setLocation(location, fixElapsedNanos)
    }

    @Test fun `initial states distinguish opt out permission and missing fix from loading`() {
        val repository = WeatherRepository()
        assertEquals(WeatherStatus.DISABLED, repository.snapshot(now).status)
        repository.configure(true, false, true)
        assertEquals(WeatherStatus.NO_PERMISSION, repository.snapshot(now).status)
        repository.configure(true, true, true)
        assertEquals(WeatherStatus.NO_LOCATION, repository.snapshot(now).status)
        repository.setLocation(location, fixElapsedNanos)
        repository.beginRequest(now, elapsed)
        assertEquals(WeatherStatus.LOADING, repository.snapshot(now).status)
    }

    @Test fun `concurrent refreshes deduplicate and normal refresh occurs at fifteen minutes`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        assertNull(repository.beginRequest(now, elapsed, force = true))
        repository.succeed(request, observation, now, elapsed)
        assertEquals(WeatherStatus.READY, repository.snapshot(now).status)
        assertNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS - 1,
            elapsed + WeatherPolicy.REFRESH_MS - 1))
        assertNotNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS,
            elapsed + WeatherPolicy.REFRESH_MS))
    }

    @Test fun `manual refresh respects request minimum and provider backoff`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.fail(request, elapsed, retryAfterMillis = 30 * 60_000)
        assertNull(repository.beginRequest(now + 60_000, elapsed + 60_000, force = true))
        assertNotNull(repository.beginRequest(now + 30 * 60_000, elapsed + 30 * 60_000, force = true))
    }

    @Test fun `timeout failures retry exponentially without losing valid observation`() {
        val repository = repository()
        val first = repository.beginRequest(now, elapsed)!!
        repository.succeed(first, observation, now, elapsed)
        val refreshAt = WeatherPolicy.REFRESH_MS
        val refresh = repository.beginRequest(now + refreshAt, elapsed + refreshAt)!!
        repository.fail(refresh, elapsed + refreshAt)
        assertEquals(WeatherStatus.STALE, repository.snapshot(now + refreshAt).status)
        assertNull(repository.beginRequest(now + refreshAt + 59_999, elapsed + refreshAt + 59_999))
        val retry = repository.beginRequest(now + refreshAt + 60_000, elapsed + refreshAt + 60_000)!!
        repository.fail(retry, elapsed + refreshAt + 60_000)
        assertNull(repository.beginRequest(now + refreshAt + 179_999, elapsed + refreshAt + 179_999))
        assertNotNull(repository.beginRequest(now + refreshAt + 180_000, elapsed + refreshAt + 180_000))
        assertEquals(WeatherPolicy.MAX_BACKOFF_MS, WeatherPolicy.backoffMillis(100))
    }

    @Test fun `stale cache disappears at two hours even when no request is made`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.succeed(request, observation, now, elapsed)
        assertEquals(WeatherStatus.STALE, repository.snapshot(now + WeatherPolicy.CACHE_EXPIRY_MS - 1).status)
        assertEquals(WeatherStatus.UNAVAILABLE, repository.snapshot(now + WeatherPolicy.CACHE_EXPIRY_MS).status)
    }

    @Test fun `revoking access rejects in flight result and cannot restore location or cache`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.configure(true, false, true)
        assertNull(repository.succeed(request, observation, now, elapsed))
        assertFalse(repository.restore(WeatherCacheEntry(location, observation, now), now, elapsed))
        assertEquals(WeatherStatus.NO_PERMISSION, repository.snapshot(now).status)
        repository.configure(true, true, true)
        assertEquals(WeatherStatus.NO_LOCATION, repository.snapshot(now).status)
    }

    @Test fun `deactivating cancels network but keeps existing observation and refresh schedule`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.succeed(request, observation, now, elapsed)
        val refresh = repository.beginRequest(now + 60_000, elapsed + 60_000, force = true)!!
        repository.configure(true, true, false)
        assertNull(repository.succeed(refresh, observation.copy(temperatureC = -20.0), now, elapsed))
        assertNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS, elapsed + WeatherPolicy.REFRESH_MS))
        assertEquals(22.5, repository.snapshot(now).temperatureC!!, 0.0)
        repository.configure(true, true, true)
        assertNull(repository.beginRequest(now + 60_000, elapsed + 60_000))
    }

    @Test fun `movement invalidates former location result and accumulated short moves refresh`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.setLocation(WeatherLocation.create(40.50, -3.70, now + 1), fixElapsedNanos + 1_000_000)
        assertNull(repository.succeed(request, observation, now, elapsed))
        assertNull(repository.beginRequest(now + 1, elapsed + 1)) // still rate limited
        val moved = repository.beginRequest(now + 60_000, elapsed + 60_000)!!
        repository.succeed(moved, observation, now + 60_000, elapsed + 60_000)
        assertFalse(repository.setLocation(WeatherLocation.create(40.52, -3.70, now + 60_001), fixElapsedNanos + 60_001_000_000))
        assertFalse(repository.setLocation(WeatherLocation.create(40.54, -3.70, now + 60_002), fixElapsedNanos + 60_002_000_000))
        assertEquals(WeatherStatus.READY, repository.snapshot(now + 60_002).status)
        assertTrue(repository.setLocation(WeatherLocation.create(40.56, -3.70, now + 60_003), fixElapsedNanos + 60_003_000_000))
        assertEquals(WeatherStatus.UNAVAILABLE, repository.snapshot(now + 60_003).status)
        assertNotNull(repository.beginRequest(now + 120_000, elapsed + 120_000))
    }

    @Test fun `disabled system location keeps stale cache but stops requests`() {
        val repository = repository()
        repository.succeed(repository.beginRequest(now, elapsed)!!, observation, now, elapsed)
        repository.setLocation(null, 0)
        assertEquals(WeatherStatus.STALE, repository.snapshot(now).status)
        assertNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS, elapsed + WeatherPolicy.REFRESH_MS))
    }

    @Test fun `cache restoration uses persisted fetch age and rejects expired or future observations`() {
        val repository = repository()
        val entry = WeatherCacheEntry(location, observation, now)
        assertTrue(repository.restore(entry, now + 10 * 60_000, elapsed))
        assertNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS - 1, elapsed + 5 * 60_000 - 1))
        assertNotNull(repository.beginRequest(now + WeatherPolicy.REFRESH_MS, elapsed + 5 * 60_000))
        assertFalse(repository().restore(entry, now + WeatherPolicy.CACHE_EXPIRY_MS, elapsed))
        assertFalse(repository().restore(entry, now - WeatherPolicy.FUTURE_TOLERANCE_MS - 1, elapsed))
    }

    @Test fun `wall clock jumps cannot bypass monotonic request limiting`() {
        val repository = repository()
        val request = repository.beginRequest(now, elapsed)!!
        repository.fail(request, elapsed)
        assertNull(repository.beginRequest(now + 10 * WeatherPolicy.CACHE_EXPIRY_MS, elapsed + 1, force = true))
    }

    @Test fun `new live fix after backward wall clock correction replaces previous location`() {
        val repository = repository()
        repository.succeed(repository.beginRequest(now, elapsed)!!, observation, now, elapsed)
        val correctedNow = now - 60 * 60_000
        val moved = WeatherLocation.create(35.0, 139.0, correctedNow)!!
        assertTrue(repository.setLocation(moved, fixElapsedNanos + 60_000_000_000))
        assertEquals(moved, repository.currentLocation)
        assertEquals(moved, repository.beginRequest(correctedNow, elapsed + 60_000)!!.location)
    }

    @Test fun `out of order live fix with a newer wall timestamp cannot undo current location`() {
        val repository = repository()
        val olderFix = WeatherLocation.create(35.0, 139.0, now + 60 * 60_000)!!
        assertFalse(repository.setLocation(olderFix, fixElapsedNanos - 1))
        assertEquals(location, repository.currentLocation)
    }

    @Test fun `restored cache does not impose live fix ordering across boots`() {
        val repository = WeatherRepository()
        repository.configure(true, true, true)
        assertTrue(repository.restore(WeatherCacheEntry(location, observation, now), now, elapsed))
        val moved = WeatherLocation.create(35.0, 139.0, now - 60_000)!!
        assertTrue(repository.setLocation(moved, 1))
        assertEquals(moved, repository.currentLocation)
    }

    @Test fun `coordinates are rounded and invalid coordinates rejected`() {
        assertEquals(40.42, location.latitude, 0.0)
        assertEquals(-3.70, location.longitude, 0.0)
        assertNull(WeatherLocation.create(Double.NaN, 0.0, now))
        assertNull(WeatherLocation.create(91.0, 0.0, now))
        assertNull(WeatherLocation.create(0.0, 181.0, now))
    }
}
