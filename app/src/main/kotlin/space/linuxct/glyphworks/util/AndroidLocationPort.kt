package space.linuxct.glyphworks.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import space.linuxct.glyphworks.core.LocationPort

class AndroidLocationPort(private val app: Context) : LocationPort {

    @Volatile private var cached: Pair<Double, Double>? = null
    @Volatile private var cachedAt = 0L

    override fun latLon(): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached
        cachedAt = now

        val hasCoarseLocation =
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarseLocation) {
            cached = null
            return null
        }
        cached = try {
            val locationManager = app.getSystemService(LocationManager::class.java)
            locationManager?.allProviders?.firstNotNullOfOrNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
        return cached
    }

    private companion object {
        const val CACHE_MS = 10 * 60_000L
    }
}
