package space.linuxct.glyphworks.weather

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.os.UserManager
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.WeatherPort
import space.linuxct.glyphworks.core.weather.WeatherPolicy
import space.linuxct.glyphworks.core.weather.WeatherRepository
import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Process-wide weather source. Render callbacks only publish desired state or read memory;
 * all platform queries and disk work run on weather-state, with HTTP on weather-network.
 * No foreground service, wake lock, high-accuracy request, or pre-unlock storage access.
 */
class AndroidWeatherPort(app: Context, private val prefs: Prefs) : WeatherPort {
    private val app = app.applicationContext
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "weather-state").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val network = Executors.newSingleThreadExecutor { task ->
        Thread(task, "weather-network").apply { isDaemon = true }
    }
    private val repository = WeatherRepository()
    private val cache = AndroidWeatherCache(this.app)
    private val client = OpenMeteoClient()
    private val location = AndroidWeatherLocation(this.app, worker,
        onFix = { fix, fixElapsedNanos ->
            // A permission/settings callback may race an already queued location callback.
            if (configured() && permitted() && unlocked() && needed() &&
                (foreground || backgroundPermitted())) {
                if (repository.setLocation(fix, fixElapsedNanos)) cache.clear()
                cancelObsoleteCall()
                requestWeather()
                publish()
            }
        },
        onProvidersChanged = ::onLocationProvidersChanged,
    )

    @Volatile private var value = WeatherSnapshot(WeatherStatus.DISABLED)
    @Volatile private var wantedActive = false
    @Volatile private var foreground = false
    private var manualUntilElapsed = 0L
    private var forceRefresh = false
    private var cacheLoaded = false
    private var cacheCleared = false
    private var tick: ScheduledFuture<*>? = null
    private var call: OpenMeteoClient.Call? = null
    private var callRequest: WeatherRepository.Request? = null
    private var callFuture: Future<*>? = null
    private var callTimeout: ScheduledFuture<*>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            worker.execute { onLocationProvidersChanged() }
        }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.app.packageName) onConfigurationChanged()
    }

    init {
        worker.execute {
            this.app.registerReceiver(receiver, IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, Context.RECEIVER_NOT_EXPORTED)
            this.app.getSystemService(AppOpsManager::class.java)?.startWatchingMode(
                AppOpsManager.OPSTR_COARSE_LOCATION, this.app.packageName, permissionListener)
            reconcile()
        }
    }

    override fun snapshot(): WeatherSnapshot {
        val current = value
        if (current.status == WeatherStatus.READY || current.status == WeatherStatus.STALE) {
            // A deep-idle suspension can defer the worker. Never leave expired data on the
            // panel simply because its next scheduled background wake was delayed.
            val age = System.currentTimeMillis() - current.observedAtMillis
            if (age !in -WeatherPolicy.FUTURE_TOLERANCE_MS until WeatherPolicy.CACHE_EXPIRY_MS)
                return WeatherSnapshot(WeatherStatus.UNAVAILABLE)
        }
        return current
    }

    override fun setActive(active: Boolean) {
        if (wantedActive == active) return
        wantedActive = active
        worker.execute { reconcile() }
    }

    /** Activity visibility enables foreground-only location access; AOD is not foreground. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        worker.execute { reconcile() }
    }

    /** User-requested refresh also works from settings while the Weather toy is inactive. */
    fun refresh() {
        worker.execute {
            if (foreground) manualUntilElapsed = SystemClock.elapsedRealtime() + 30_000
            forceRefresh = true
            reconcile()
        }
    }

    /** Call after weather settings change or returning from Android permission/settings UI. */
    fun onConfigurationChanged() {
        worker.execute { reconcile() }
    }

    private fun onLocationProvidersChanged() {
        location.stop()
        reconcile()
    }

    private fun reconcile(startRequest: Boolean = true) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val enabled = configured()
        val permission = permitted()
        val userUnlocked = unlocked()
        val needed = needed()
        repository.configure(enabled, permission, needed && userUnlocked)
        cancelObsoleteCall()
        if (!enabled || !permission) {
            location.stop()
            if (userUnlocked && !cacheCleared) {
                cache.clear()
                cacheCleared = true
            }
            cacheLoaded = false
            forceRefresh = false
        } else if (userUnlocked) {
            cacheCleared = false
            // Never touch credential storage merely because Core was built during boot.
            if (needed && !cacheLoaded) {
                cacheLoaded = true
                cache.load(now)?.let { repository.restore(it, now, elapsed) }
            }
            if (!location.isEnabled()) {
                location.stop()
                repository.setLocation(null, 0)
                cancelObsoleteCall()
            } else if (needed && (foreground || backgroundPermitted())) {
                location.start()
            } else {
                location.stop()
            }
        } else {
            location.stop()
        }
        if (startRequest && userUnlocked && enabled && permission) requestWeather()
        publish()
        tick?.cancel(false)
        tick = if (needed && enabled && userUnlocked) worker.schedule({ reconcile() },
            30, TimeUnit.SECONDS) else null
    }

    private fun requestWeather() {
        val request = repository.beginRequest(System.currentTimeMillis(), SystemClock.elapsedRealtime(), forceRefresh)
            ?: return
        forceRefresh = false
        val pending = client.newCall(request.location)
        call = pending
        callRequest = request
        callFuture = network.submit {
            val result = pending.execute()
            worker.execute {
                // Revalidate consent, permissions and activity before accepting/persisting;
                // results from a canceled request cannot resurrect cleared coordinates.
                reconcile(startRequest = false)
                if (callRequest == request) {
                    callTimeout?.cancel(false)
                    callTimeout = null
                    call = null
                    callRequest = null
                    callFuture = null
                    when (result) {
                        is WeatherFetchResult.Success -> repository.succeed(request, result.observation,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime())?.let { cache.save(it) }
                        is WeatherFetchResult.Failure -> repository.fail(request,
                            SystemClock.elapsedRealtime(), result.retryAfterMillis)
                    }
                    manualUntilElapsed = 0
                    reconcile()
                }
            }
        }
        // A total deadline also bounds slow trickle responses that evade a socket read timeout.
        callTimeout = worker.schedule({
            if (callRequest == request) {
                repository.fail(request, SystemClock.elapsedRealtime())
                cancelObsoleteCall()
                manualUntilElapsed = 0
                reconcile()
            }
        }, 25, TimeUnit.SECONDS)
        publish()
    }

    private fun cancelObsoleteCall() {
        if (callRequest != null && callRequest != repository.inFlight) {
            callTimeout?.cancel(false)
            callTimeout = null
            callFuture?.cancel(true)
            callFuture = null
            call?.cancel()
            call = null
            callRequest = null
        }
    }

    private fun publish() { value = repository.snapshot(System.currentTimeMillis()) }
    private fun needed() = wantedActive || (foreground && SystemClock.elapsedRealtime() < manualUntilElapsed)
    private fun configured() = prefs.getBoolean("weatherEnabled", false)
    private fun permitted() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun backgroundPermitted() = app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun unlocked() = app.getSystemService(UserManager::class.java)?.isUserUnlocked == true
}
