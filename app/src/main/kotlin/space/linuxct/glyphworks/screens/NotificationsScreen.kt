package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.NotificationPrefs
import space.linuxct.glyphworks.core.ScreenContext

class NotificationsScreen : GlyphScreen {
    override val id = "notifications"
    override val interactive = false
    private val presentation = NotificationsPresentation()
    private var context: ScreenContext? = null
    private var interval = 0L

    override fun behaviorState(): Map<String, Any> {
        val c = context ?: return emptyMap()
        val elapsed = presentation.elapsed(c)
        val phase = elapsed % NotificationsRenderer.LOOP_MS
        return mapOf("elapsed" to elapsed, "phase" to when {
            c.ports.notifications.count() == null -> "unavailable"
            NotificationPrefs.style(c.prefs) != NotificationPrefs.BELL -> "frame"
            phase < NotificationsRenderer.HOLD_MS -> "icon"
            phase < NotificationsRenderer.HOLD_MS + NotificationsRenderer.SLIDE_MS -> "slide_to_count"
            phase < 2 * NotificationsRenderer.HOLD_MS + NotificationsRenderer.SLIDE_MS -> "count"
            else -> "slide_to_icon"
        })
    }

    override fun onActivate(ctx: ScreenContext) {
        context = ctx
        presentation.reset()
        schedule(ctx, intervalFor(ctx))
    }

    override fun onDeactivate() {
        context = null
        presentation.reset()
    }

    private fun intervalFor(c: ScreenContext) = if (NotificationPrefs.style(c.prefs) == NotificationPrefs.BELL) 50L else 500L

    private fun schedule(c: ScreenContext, nextInterval: Long) {
        interval = nextInterval
        c.scheduler.setTicker(interval) {
            if (context === c) {
                val next = intervalFor(c)
                if (next != interval) schedule(c, next)
                else c.pushFrame(presentation.render(c))
            }
        }
    }

    companion object {
        fun renderFrame(size: Int, count: Int?, style: String = NotificationPrefs.DEFAULT_STYLE, elapsedMs: Long = 0): IntArray =
            NotificationsRenderer.renderFrame(size, count, style, elapsedMs)
    }
}
