package space.linuxct.glyphworks.core.weather

/**
 * Deterministic repository state. The Android adapter confines mutation to its worker;
 * renderers only see an immutable snapshot published by that worker. Wall time ages saved
 * observations; monotonic time schedules requests, so timezone/clock edits cannot cause bursts.
 */
class WeatherRepository {
    data class Request(val generation: Long, val location: WeatherLocation)

    private var enabled = false
    private var permitted = false
    private var active = false
    private var location: WeatherLocation? = null
    // Live fix ordering is process-local and never restored from the wall-clock cache.
    private var locationElapsedNanos: Long? = null
    private var cached: WeatherCacheEntry? = null
    private var request: Request? = null
    private var generation = 0L
    private var nextRefreshElapsed = 0L
    private var retryAfterElapsed = 0L
    private var failures = 0

    val inFlight: Request? get() = request
    val currentLocation: WeatherLocation? get() = location

    fun configure(enabled: Boolean, permitted: Boolean, active: Boolean) {
        this.enabled = enabled
        this.permitted = permitted
        this.active = active
        if (!enabled || !permitted) {
            invalidateRequest()
            location = null
            locationElapsedNanos = null
            cached = null
            nextRefreshElapsed = 0
            retryAfterElapsed = 0
            failures = 0
        } else if (!active) {
            invalidateRequest()
        }
    }

    /** A rejected/expired cache never restores a location that could otherwise be refreshed. */
    fun restore(entry: WeatherCacheEntry, nowMillis: Long, elapsedMillis: Long): Boolean {
        if (!enabled || !permitted || !entry.validAt(nowMillis) || cached != null) return false
        if (location != null && location!!.distanceKm(entry.location) >= WeatherPolicy.MOVEMENT_KM) return false
        cached = entry
        if (location == null) location = entry.location
        nextRefreshElapsed = elapsedMillis + (WeatherPolicy.REFRESH_MS -
            (nowMillis - entry.fetchedAtMillis).coerceAtLeast(0)).coerceAtLeast(0)
        return true
    }

    /**
     * [fixElapsedNanos] orders live fixes independently of user wall-clock corrections;
     * pass zero only when clearing the location. It is never written to the cache.
     * Returns true when movement invalidated the saved observation, so disk can follow suit.
     */
    fun setLocation(fix: WeatherLocation?, fixElapsedNanos: Long): Boolean {
        if (!enabled || !permitted) return false
        if (fix == null) {
            location = null
            invalidateRequest()
            return false
        }
        if (fixElapsedNanos <= 0 || locationElapsedNanos?.let { fixElapsedNanos < it } == true) return false
        val moved = location?.distanceKm(fix)?.let { it >= WeatherPolicy.MOVEMENT_KM } ?: true
        location = fix
        locationElapsedNanos = fixElapsedNanos
        // Compare against the actual weather/request position, rather than only the most
        // recent fix: many short journeys must eventually cross the refresh threshold too.
        val cacheMoved = cached?.location?.distanceKm(fix)?.let { it >= WeatherPolicy.MOVEMENT_KM } ?: false
        val requestMoved = request?.location?.distanceKm(fix)?.let { it >= WeatherPolicy.MOVEMENT_KM } ?: false
        if (moved || cacheMoved || requestMoved) {
            if (cacheMoved) cached = null
            if (requestMoved) invalidateRequest()
            nextRefreshElapsed = 0
        }
        return cacheMoved
    }

    fun beginRequest(nowMillis: Long, elapsedMillis: Long, force: Boolean = false): Request? {
        if (!enabled || !permitted || !active || request != null) return null
        val fix = location ?: return null
        if (elapsedMillis < retryAfterElapsed) return null
        if (!force && elapsedMillis < nextRefreshElapsed && cached?.validAt(nowMillis) == true) return null
        return Request(++generation, fix).also {
            request = it
            retryAfterElapsed = elapsedMillis + WeatherPolicy.MIN_REQUEST_MS
        }
    }

    /** Returns an entry to persist only if this is still the authorized request. */
    fun succeed(
        completed: Request,
        observation: WeatherObservation,
        nowMillis: Long,
        elapsedMillis: Long,
    ): WeatherCacheEntry? {
        if (request != completed) return null
        if (!observation.validAt(nowMillis)) {
            fail(completed, elapsedMillis)
            return null
        }
        request = null
        failures = 0
        nextRefreshElapsed = elapsedMillis + WeatherPolicy.REFRESH_MS
        retryAfterElapsed = elapsedMillis + WeatherPolicy.MIN_REQUEST_MS
        return WeatherCacheEntry(completed.location, observation, nowMillis).also { cached = it }
    }

    fun fail(completed: Request, elapsedMillis: Long, retryAfterMillis: Long = 0) {
        if (request != completed) return
        request = null
        failures = (failures + 1).coerceAtMost(100)
        val delay = maxOf(WeatherPolicy.backoffMillis(failures),
            retryAfterMillis.coerceIn(0, WeatherPolicy.MAX_RETRY_AFTER_MS))
        nextRefreshElapsed = elapsedMillis + delay
        retryAfterElapsed = nextRefreshElapsed
    }

    fun snapshot(nowMillis: Long): WeatherSnapshot {
        if (!enabled) return WeatherSnapshot(WeatherStatus.DISABLED)
        if (!permitted) return WeatherSnapshot(WeatherStatus.NO_PERMISSION)
        val entry = cached?.takeIf { it.validAt(nowMillis) }
        if (entry != null) {
            val stale = failures > 0 || location == null ||
                nowMillis - entry.fetchedAtMillis >= WeatherPolicy.REFRESH_MS
            return WeatherSnapshot(if (stale) WeatherStatus.STALE else WeatherStatus.READY,
                entry.observation.temperatureC, entry.observation.condition,
                entry.observation.isDay, entry.observation.observedAtMillis)
        }
        return WeatherSnapshot(when {
            location == null -> WeatherStatus.NO_LOCATION
            request != null -> WeatherStatus.LOADING
            else -> WeatherStatus.UNAVAILABLE
        })
    }

    private fun invalidateRequest() {
        request = null
        generation++
    }
}
