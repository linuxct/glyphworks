package space.linuxct.glyphworks.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import space.linuxct.glyphworks.core.notifications.NotificationMetadata

/** Counts outstanding notifications without reading text, persisting, or modifying them. */
class GlyphNotificationListenerService : NotificationListenerService() {
    private var listenerConnected = false

    override fun onCreate() {
        super.onCreate()
        NotificationSource.initialize(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        rebuildSnapshot()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!listenerConnected || sbn == null) return
        if (!NotificationSource.hasSnapshot()) {
            rebuildSnapshot()
            return
        }
        NotificationSource.posted(sbn.countMetadata())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!listenerConnected || sbn == null) return
        NotificationSource.removed(sbn.key)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        // System automatic grouping can change group keys without a post/removal callback.
        if (listenerConnected) rebuildSnapshot()
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        NotificationSource.disconnected()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        listenerConnected = false
        NotificationSource.disconnected()
        super.onDestroy()
    }

    private fun rebuildSnapshot() {
        if (!NotificationSource.refreshAccess(this)) return
        val notifications = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null
        }
        if (notifications == null) {
            // Failure to read is unavailable, never a misleading zero.
            NotificationSource.disconnected()
            return
        }
        NotificationSource.connected(notifications.map { it.countMetadata() })
    }

    private fun StatusBarNotification.countMetadata(): NotificationMetadata {
        val posted = notification
        return NotificationMetadata(
            packageName = packageName,
            key = key,
            groupKey = if (isGroup) groupKey else null,
            isGroupSummary = posted.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isClearable = isClearable,
            isOngoing = isOngoing,
            isForegroundService = posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            isMedia = posted.isMediaEntry(),
        )
    }

    private fun Notification.isMediaEntry(): Boolean {
        if (category == Notification.CATEGORY_TRANSPORT) return true
        return try {
            // Inspect only style metadata/session-key presence, never titles, messages,
            // RemoteViews, intents, or the session token itself.
            val metadata = extras ?: return false
            if (metadata.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
            val template = metadata.getString(Notification.EXTRA_TEMPLATE)
            template == Notification.MediaStyle::class.java.name ||
                template == Notification.DecoratedMediaCustomViewStyle::class.java.name
        } catch (_: RuntimeException) {
            // An unreadable extras bundle is conservatively excluded. Do not log payloads.
            true
        }
    }
}
