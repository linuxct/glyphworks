package space.linuxct.glyphworks.notifications

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import space.linuxct.glyphworks.core.NotificationPort
import space.linuxct.glyphworks.core.notifications.NotificationCounter
import space.linuxct.glyphworks.core.notifications.NotificationMetadata
import java.util.concurrent.atomic.AtomicLong

/** One process-wide metadata source, with no disk storage and no notification payloads. */
object NotificationSource : NotificationPort {
    private const val ACCESS_CHECK_INTERVAL_MS = 5_000L
    private const val RECONNECT_INTERVAL_MS = 30_000L
    private val counter = NotificationCounter()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<(space.linuxct.pipeline.PipelineEvent) -> Unit>()
    private val metadata = mutableMapOf<String, NotificationMetadata>()
    fun addEventListener(listener: (space.linuxct.pipeline.PipelineEvent) -> Unit) { observers += listener }
    fun removeEventListener(listener: (space.linuxct.pipeline.PipelineEvent) -> Unit) { observers -= listener }
    private fun notifyEvent(name: String, notification: NotificationMetadata) {
        if (!notification.isCountable) return
        val values = mapOf("package" to space.linuxct.pipeline.text(notification.packageName), "count" to (counter.count()?.let { space.linuxct.pipeline.number(it) } ?: space.linuxct.pipeline.Value.Unavailable()))
        val event = space.linuxct.pipeline.PipelineEvent(name, values, SystemClock.elapsedRealtime())
        observers.forEach { listener -> runCatching { listener(event) } }
    }
    private val nextAccessCheckAt = AtomicLong(0L)
    private val nextReconnectAt = AtomicLong(0L)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // Retain only the process Application, never an Activity or Service context.
    @Volatile private var app: Application? = null
    @Volatile private var accessGranted = false

    /** Safe to call repeatedly from Core or the listener, including before first unlock. */
    fun initialize(context: Context): NotificationSource {
        app = context.applicationContext as Application
        scheduleAccessCheck()
        return this
    }

    override fun count(): Int? {
        // Rendering only reads the snapshot. The infrequent permission/binder check runs
        // on the main looper, never the compositor. No recurring work runs while unused.
        scheduleAccessCheck()
        return counter.count()
    }

    /** Call on returning from Android Settings so revoked access clears immediately. */
    fun refreshAccess(context: Context): Boolean {
        val application = context.applicationContext as Application
        app = application
        val granted = application.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            NotificationAccess.isGranted(application)
        synchronized(this) {
            accessGranted = granted
            if (!granted) {
                disconnected()
                nextReconnectAt.set(0L)
            }
        }
        val now = SystemClock.elapsedRealtime()
        nextAccessCheckAt.set(now + ACCESS_CHECK_INTERVAL_MS)
        if (granted && counter.count() == null) {
            val next = nextReconnectAt.get()
            if (now >= next && nextReconnectAt.compareAndSet(next, now + RECONNECT_INTERVAL_MS)) {
                NotificationAccess.requestReconnect(application)
            }
        }
        return granted
    }

    @Synchronized
    internal fun connected(notifications: Iterable<NotificationMetadata>) {
        if (accessGranted) {
            val list = notifications.toList()
            counter.connected(list)
            metadata.clear(); list.forEach { metadata[it.key] = it }
        }
    }

    @Synchronized
    internal fun posted(notification: NotificationMetadata) {
        if (accessGranted && hasSnapshot()) {
            val existed = metadata.put(notification.key, notification) != null
            counter.posted(notification)
            notifyEvent(if (existed) "notification.updated" else "notification.posted", notification)
        }
    }

    @Synchronized internal fun removed(key: String) {
        val old = metadata.remove(key)
        counter.removed(key)
        if (old != null) notifyEvent("notification.removed", old)
    }

    @Synchronized internal fun disconnected() { metadata.clear(); counter.disconnected() }

    /** Read without scheduling work; used to recover from a failed initial snapshot. */
    internal fun hasSnapshot() = counter.count() != null

    private fun scheduleAccessCheck() {
        val application = app ?: return
        val now = SystemClock.elapsedRealtime()
        val next = nextAccessCheckAt.get()
        if (now < next || !nextAccessCheckAt.compareAndSet(next, now + ACCESS_CHECK_INTERVAL_MS)) return
        handler.post { refreshAccess(application) }
    }
}
