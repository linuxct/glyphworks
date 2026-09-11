package space.linuxct.glyphworks.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import space.linuxct.glyphworks.core.weather.WeatherLocation
import space.linuxct.glyphworks.core.weather.WeatherPolicy
import java.util.concurrent.Executor

/** Low-power approximate updates. The owner checks coarse/background access before starting. */
internal class AndroidWeatherLocation(
    app: Context,
    private val executor: Executor,
    private val onFix: (WeatherLocation, Long) -> Unit,
    private val onProvidersChanged: () -> Unit,
) {
    private val manager = app.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var cancellation: CancellationSignal? = null
    private var generation = 0L

    fun isEnabled(): Boolean = try { manager?.isLocationEnabled == true } catch (_: RuntimeException) { false }

    @SuppressLint("MissingPermission") // validated on the owner's worker; races caught below
    fun start() {
        if (listener != null || !isEnabled()) return
        val manager = manager ?: return
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER).filter { provider ->
            try { manager.hasProvider(provider) && manager.isProviderEnabled(provider) }
            catch (_: RuntimeException) { false }
        }
        if (providers.isEmpty()) return
        val requestGeneration = ++generation
        providers.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) }
            catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
        }.filter { recent(it) }.maxByOrNull { it.elapsedRealtimeNanos }?.let { deliver(it) }
        val callbacks = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (generation == requestGeneration) deliver(location)
            }
            override fun onProviderDisabled(provider: String) {
                if (generation == requestGeneration) onProvidersChanged()
            }
            override fun onProviderEnabled(provider: String) {
                if (generation == requestGeneration) onProvidersChanged()
            }
        }
        // Fused/network use the platform's existing low-power sources; never request GPS
        // or ACCESS_FINE_LOCATION. An OEM's fused implementation may be unavailable to
        // coarse-only callers despite reporting a provider, so try each compatible source.
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider,
                    LocationRequest.Builder(WeatherPolicy.REFRESH_MS)
                        .setQuality(LocationRequest.QUALITY_LOW_POWER)
                        .setMinUpdateIntervalMillis(5 * 60_000L)
                        .setMinUpdateDistanceMeters((WeatherPolicy.MOVEMENT_KM * 1000).toFloat())
                        .build(), executor, callbacks)
                listener = callbacks
            } catch (_: SecurityException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (provider != LocationManager.PASSIVE_PROVIDER) {
                try {
                    cancellation = CancellationSignal().also { signal ->
                        manager.getCurrentLocation(provider,
                            LocationRequest.Builder(0).setQuality(LocationRequest.QUALITY_LOW_POWER)
                                .setDurationMillis(20_000).build(), signal, executor) { fix ->
                            if (generation == requestGeneration && fix != null) deliver(fix)
                        }
                    }
                } catch (_: SecurityException) {
                    // Continuous updates can still succeed; the next access check handles
                    // an actual revocation. Never lose an already registered listener.
                } catch (_: IllegalArgumentException) {
                }
            }
            return
        }
    }

    fun stop() {
        generation++
        cancellation?.cancel()
        cancellation = null
        listener?.let { try { manager?.removeUpdates(it) } catch (_: RuntimeException) { } }
        listener = null
    }

    private fun deliver(location: Location) {
        if (!recent(location)) return
        // Wall time is for persistence only. Pass the original monotonic timestamp
        // separately so a clock correction cannot reverse the order of live fixes.
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        WeatherLocation.create(location.latitude, location.longitude, System.currentTimeMillis() - age)?.let {
            onFix(it, location.elapsedRealtimeNanos)
        }
    }

    private fun recent(location: Location): Boolean {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return location.elapsedRealtimeNanos > 0 && age in 0 until WeatherPolicy.CACHE_EXPIRY_MS
    }
}
