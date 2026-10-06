package space.linuxct.glyphworks.core

interface Cancelable {
    fun cancel()
}

/** All render work runs on one background thread. Callers from elsewhere hop in with [run]. */
interface RenderScheduler {
    fun setTicker(intervalMs: Long, tick: () -> Unit)
    fun clearTicker()
    fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable
    fun run(action: () -> Unit)
}

class ScreenContext(
    val size: Int,
    val prefs: Prefs,
    val ports: Ports,
    val scheduler: RenderScheduler,
    private val sink: (IntArray) -> Unit,
) {
    fun pushFrame(frame: IntArray) = sink(frame)
}

interface GlyphScreen {
    val id: String
    val interactive: Boolean

    /** Take [Events.CHANGE] on the first press, before the multi-press window can tell it apart. */
    val instantAction: Boolean get() = false

    fun onActivate(ctx: ScreenContext)
    /** Optional scoped preview suspension; true means the screen retains its running state. */
    fun suspendForPreview(): Boolean = false
    fun resumeFromPreview() {}

    fun onDeactivate()
    fun onEvent(event: String) {}

    /** Primitive, hardware-free state exposed to programmable native behavior blocks. */
    fun behaviorState(): Map<String, Any> = emptyMap()

    /** Returns true only when the behavior recognized the command. */
    fun behaviorCommand(name: String, arguments: Map<String, Any>): Boolean = false
}
