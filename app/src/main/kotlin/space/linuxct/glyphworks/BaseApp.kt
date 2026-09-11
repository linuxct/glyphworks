package space.linuxct.glyphworks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.os.Bundle
import space.linuxct.glyphworks.notifications.NotificationSource

/** What both flavours' `Application` share. The manifest names `.App`, one per flavour. */
abstract class BaseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Core.init(this)
        registerWeatherVisibility()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        space.linuxct.glyphworks.core.DebugLog.i("App", "process started, version $version")
    }

    protected open fun optionalChannels(nm: NotificationManager) = Unit

    private fun registerWeatherVisibility() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) Core.weather.setForeground(true)
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) Core.weather.setForeground(false)
            }
            override fun onActivityResumed(activity: Activity) {
                Core.weather.onConfigurationChanged()
                NotificationSource.refreshAccess(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(LEGACY_CHANNEL_TEA_TIME)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TIMER,
                getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        optionalChannels(nm)
    }

    companion object {
        const val CHANNEL_TIMER = "timer"

        private const val LEGACY_CHANNEL_TEA_TIME = "tea_time"
    }
}
