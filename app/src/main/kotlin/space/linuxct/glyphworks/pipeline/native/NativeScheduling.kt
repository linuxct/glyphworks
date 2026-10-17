package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.Cancelable
import space.linuxct.glyphworks.core.RenderScheduler
import space.linuxct.pipeline.Cancellation
import space.linuxct.pipeline.NativeContext

/** Per-instance virtual animation time. Wall time intentionally keeps advancing while hidden. */
internal class NativeTime(private val context: NativeContext) {
    private var pausedAt: Long? = null
    private var hiddenMillis = 0L
    fun elapsed() = (pausedAt ?: context.clock.elapsedMillis()) - hiddenMillis
    fun pause() { if (pausedAt == null) pausedAt = context.clock.elapsedMillis() }
    fun resume() { pausedAt?.let { hiddenMillis += context.clock.elapsedMillis() - it }; pausedAt = null }
}

/** Every ticker and delayed task belongs to this instance; suspension never reactivates a toy. */
internal class NativeScheduling(private val context: NativeContext, private val time: NativeTime) : RenderScheduler {
    private val pending = LinkedHashSet<Task>()
    private var ticker: Task? = null
    private var paused = false
    private var closed = false
    private inner class Task(var due: Long, val action: () -> Unit, val interval: Long? = null) : Cancelable {
        var handle: Cancellation? = null
        var cancelled = false
        fun arm() {
            if (paused || closed || cancelled) return
            handle = context.scheduler.post((due - time.elapsed()).coerceAtLeast(0)) {
                handle = null
                if (paused || closed || cancelled) return@post
                if (interval == null) pending.remove(this)
                action()
                if (interval != null && !cancelled && !closed) {
                    due = time.elapsed() + interval
                    arm()
                }
            }
        }
        override fun cancel() {
            cancelled = true
            handle?.cancel()
            handle = null
            pending.remove(this)
        }
    }
    override fun setTicker(intervalMs: Long, tick: () -> Unit) {
        clearTicker()
        if (closed) return
        val interval = intervalMs.coerceAtLeast(1)
        val task = Task(time.elapsed() + if (paused) 0 else interval, tick, interval)
        ticker = task; pending += task
        // RenderScheduler fires its first tick synchronously. In particular, a nested VM's
        // scheduler deliberately rounds queued zero-delay work up to 1 ms; postponing this
        // tick would otherwise replace the initial artwork with a blank presentation.
        if (!paused) tick()
        task.arm()
    }
    override fun clearTicker() { ticker?.cancel(); ticker = null }
    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable {
        val task = Task(time.elapsed() + delayMs.coerceAtLeast(0), action)
        if (closed) task.cancel() else { pending += task; task.arm() }
        return task
    }
    override fun run(action: () -> Unit) { postDelayed(0, action) }
    fun suspend() {
        if (paused || closed) return
        paused = true
        time.pause()
        pending.forEach { it.handle?.cancel(); it.handle = null }
    }
    fun resume() {
        if (!paused || closed) return
        time.resume()
        paused = false
        pending.toList().forEach { it.arm() }
    }
    fun close() { closed = true; pending.toList().forEach { it.cancel() }; ticker = null }
}
