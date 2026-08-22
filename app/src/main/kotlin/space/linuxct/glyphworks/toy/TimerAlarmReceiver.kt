package space.linuxct.glyphworks.toy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.BrightnessScale
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.screens.TimerScreen

/** AndroidManifest.xml names this class, so never rename it. */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        val start = Core.prefs.getLong(PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
        val tickerAlreadyFinishedIt = start == 0L
        if (tickerAlreadyFinishedIt) return
        if (Core.prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF) == start) return

        Core.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, start)
        Core.ports.timer.chime()

        // This push skips ScreenManager and takes its own lease, so nothing else stops
        // it painting over a live session or the design editor's preview.
        val matrixIsFree = !Core.screenManager.sessionLive && !Core.screenManager.livePreviewActive
        if (matrixIsFree) {
            val pendingResult = goAsync()
            val lease = Core.glyphLink.acquire("timer-alarm")
            val frame = BrightnessScale.scale(
                TimerScreen.renderDone(Core.glyphLink.size),
                Core.prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF),
            )
            Core.glyphLink.pushFrame(frame)
            Handler(Looper.getMainLooper()).postDelayed({
                lease.release()
                pendingResult.finish()
            }, RENDER_SETTLE_MS)
        }
    }

    private companion object {
        // The bind and render are async. This stays well under the 10 s broadcast budget.
        const val RENDER_SETTLE_MS = 4_000L
    }
}
