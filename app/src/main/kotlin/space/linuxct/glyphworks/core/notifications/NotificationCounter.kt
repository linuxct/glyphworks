package space.linuxct.glyphworks.core.notifications

/**
 * Only the metadata needed to count outstanding notifications. [key] is the platform's
 * opaque notification key; [groupKey] must distinguish packages and users, or be null for
 * an ungrouped notification. Notification contents, badge numbers, and intents never enter
 * this model.
 */
data class NotificationMetadata(
    val key: String,
    val groupKey: String? = null,
    val isGroupSummary: Boolean = false,
    val isClearable: Boolean = true,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isMedia: Boolean = false,
) {
    val isCountable: Boolean
        get() = isClearable && !isOngoing && !isForegroundService && !isMedia
}

/**
 * In-memory listener state shared by the standalone toy and Ambient. A null count means
 * the listener is unavailable, including before its initial snapshot arrives. A connected
 * listener with no countable notifications reports zero.
 *
 * Mutations can arrive on a different thread from rendering. Only mutations take a lock;
 * the compositor reads an already computed, volatile count without iterating notifications.
 */
class NotificationCounter {
    private val active = mutableMapOf<String, NotificationMetadata>()
    private var isConnected = false

    @Volatile private var currentCount: Int? = null

    fun count(): Int? = currentCount

    /** Every connection replaces the entire snapshot, discarding events missed offline. */
    @Synchronized
    fun connected(notifications: Iterable<NotificationMetadata>) {
        active.clear()
        notifications.forEach { active[it.key] = it }
        isConnected = true
        recompute()
    }

    /** A repeated key replaces its previous metadata, including grouping and eligibility. */
    @Synchronized
    fun posted(notification: NotificationMetadata) {
        if (!isConnected) return
        active[notification.key] = notification
        recompute()
    }

    @Synchronized
    fun removed(key: String) {
        if (!isConnected || active.remove(key) == null) return
        recompute()
    }

    /** Used for listener loss and revoked access; retain no notification metadata. */
    @Synchronized
    fun disconnected() {
        isConnected = false
        active.clear()
        currentCount = null
    }

    private fun recompute() {
        // An excluded child still represents its group. Otherwise the group summary of
        // ongoing/media children could turn their deliberately excluded group into a count.
        val groupsWithChildren = active.values.asSequence()
            .filter { !it.isGroupSummary }
            .mapNotNull { it.groupKey }
            .toHashSet()
        val countedSummaries = mutableSetOf<String>()
        currentCount = active.values.count { notification ->
            when {
                !notification.isCountable -> false
                !notification.isGroupSummary || notification.groupKey == null -> true
                notification.groupKey in groupsWithChildren -> false
                else -> countedSummaries.add(notification.groupKey)
            }
        }
    }
}
