package space.linuxct.glyphworks.toy

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nothing.ketchum.GlyphToy
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys

/** The system stores this component name, and AndroidManifest.xml names it. Never rename it. */
class AodToyService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                DebugLog.d(C, "message ignored (what=${msg.what})")
                super.handleMessage(msg)
                return
            }
            Core.prefs.putLong(PrefKeys.TOY_LAST_BOUND, System.currentTimeMillis())
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            DebugLog.i(C, "system toy message: '$event'")
            when (event) {
                GlyphToy.EVENT_CHANGE -> Core.router.glyphButtonChange()
                GlyphToy.EVENT_AOD -> Core.scheduler.run {
                    Core.screenManager.dispatchGlyphEvent(space.linuxct.glyphworks.core.Events.AOD)
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        DebugLog.i(C, "onBind (system selected us as the active toy)")
        Core.prefs.putLong(PrefKeys.TOY_LAST_BOUND, System.currentTimeMillis())
        Core.arbiter.setToyBound(true)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.i(C, "onUnbind")
        Core.prefs.putLong(PrefKeys.TOY_LAST_BOUND, System.currentTimeMillis())
        Core.arbiter.setToyBound(false)
        return false
    }

    override fun onDestroy() {
        DebugLog.i(C, "onDestroy")
        Core.arbiter.setToyBound(false)
        super.onDestroy()
    }

    private companion object {
        const val C = "Toy"
    }
}
