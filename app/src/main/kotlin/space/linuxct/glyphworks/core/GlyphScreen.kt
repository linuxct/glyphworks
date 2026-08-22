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
    fun onActivate(ctx: ScreenContext)
    fun onDeactivate()
    fun onEvent(event: String) {}
}
