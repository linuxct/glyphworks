package space.linuxct.glyphworks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** What both flavours' `Application` share. The manifest names `.App`, one per flavour. */
abstract class BaseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Core.init(this)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        space.linuxct.glyphworks.core.DebugLog.i("App", "process started, version $version")
    }

    protected open fun optionalChannels(nm: NotificationManager) = Unit

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
