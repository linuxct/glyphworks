package space.linuxct.glyphworks.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Notification-listener access is separate from permission to post our own notifications. */
object NotificationAccess {
    fun component(context: Context) = ComponentName(context, GlyphNotificationListenerService::class.java)

    fun isGranted(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(component(context)) == true
    } catch (_: RuntimeException) {
        // A missing system service or lost binder connection must not expose a stale count.
        false
    }

    fun settingsIntent(context: Context): Intent {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        return if (detail.resolveActivity(context.packageManager) != null) detail
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /** Safe before connection or after disconnection; never grants access on the user's behalf. */
    fun requestReconnect(context: Context) {
        try {
            NotificationListenerService.requestRebind(component(context))
        } catch (_: RuntimeException) {
            // Access may have been revoked between the check and this request.
        }
    }
}
