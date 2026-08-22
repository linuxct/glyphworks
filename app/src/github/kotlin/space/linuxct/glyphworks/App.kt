package space.linuxct.glyphworks

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration

/**
 * Configuration.Provider plus the WorkManagerInitializer removal in this flavour's
 * manifest defers WorkManager init to the first getInstance() call. This process also
 * starts in Direct Boot, where WorkManager's credential-encrypted store must not be
 * touched.
 */
class App : BaseApp(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun optionalChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Visible but silent: the notice is the way back to the design, and it appears on
        // every turn.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AI,
                getString(R.string.channel_ai),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_UPDATES = "app_updates"
        const val CHANNEL_AI = "ai_turn"
    }
}
