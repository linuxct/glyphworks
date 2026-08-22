package space.linuxct.glyphworks.key

import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.RenderScheduler
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionControl

enum class KeyAction {
    TOY_ACTION,
    NEXT_TOY,
    HOME,
    MENU_OPEN,
    MENU_PREVIEW_NEXT,
    MENU_COMMIT,
    SWALLOWED,
    IGNORED,
}

class KeyActionRouter(
    private val arbiter: SessionControl,
    private val screenManager: ScreenManager,
    private val scheduler: RenderScheduler,
    private val prefs: Prefs,
) {
    /** Runs on the render thread, or on the caller's thread for the early returns. */
    @Volatile
    var onAction: ((clicks: Int, action: KeyAction, screenId: String) -> Unit)? = null

    private fun report(clicks: Int, action: KeyAction) {
        val listener = onAction ?: return
        val id = runCatching { screenManager.currentScreen().id }.getOrDefault("")
        listener(clicks, action, id)
    }

    fun execute(clicks: Int) {
        DebugLog.i(C, "execute clicks=$clicks sessionShouldRun=${arbiter.sessionShouldRun}")
        if (clicks !in 1..3) {
            DebugLog.d(C, "ignored ($clicks clicks)")
            report(clicks, KeyAction.IGNORED)
            return
        }
        if (!arbiter.sessionShouldRun) {
            DebugLog.i(C, "no session owner -> revive and swallow")
            arbiter.revive()
            report(clicks, KeyAction.SWALLOWED)
            return
        }
        scheduler.run {
            if (!screenManager.sessionLive) {
                DebugLog.i(C, "session not live yet -> revive and swallow")
                arbiter.revive()
                report(clicks, KeyAction.SWALLOWED)
                return@run
            }
            val menuModeOn = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            when {
                menuModeOn && screenManager.inMenu -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "menu: 1 click -> cycle preview")
                        screenManager.menuNext()
                        report(clicks, KeyAction.MENU_PREVIEW_NEXT)
                    }
                    2 -> {
                        DebugLog.i(C, "menu: 2 clicks -> commit")
                        screenManager.commitMenu()
                        report(clicks, KeyAction.MENU_COMMIT)
                    }
                    3 -> {
                        DebugLog.i(C, "menu: 3 clicks -> home")
                        screenManager.home()
                        report(clicks, KeyAction.HOME)
                    }
                }
                menuModeOn -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "1 click -> EVENT_CHANGE to '${screenManager.currentScreen().id}'")
                        screenManager.dispatchGlyphEvent(Events.CHANGE)
                        report(clicks, KeyAction.TOY_ACTION)
                    }
                    2 -> {
                        DebugLog.i(C, "2 clicks -> open menu")
                        screenManager.enterMenu()
                        report(clicks, KeyAction.MENU_OPEN)
                    }
                    3 -> {
                        DebugLog.i(C, "3 clicks -> home")
                        screenManager.home()
                        report(clicks, KeyAction.HOME)
                    }
                }
                else -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "1 click -> EVENT_CHANGE to '${screenManager.currentScreen().id}'")
                        screenManager.dispatchGlyphEvent(Events.CHANGE)
                        report(clicks, KeyAction.TOY_ACTION)
                    }
                    2 -> {
                        DebugLog.i(C, "2 clicks -> next screen")
                        screenManager.next()
                        report(clicks, KeyAction.NEXT_TOY)
                    }
                    3 -> {
                        DebugLog.i(C, "3 clicks -> home")
                        screenManager.home()
                        report(clicks, KeyAction.HOME)
                    }
                }
            }
        }
    }

    fun glyphButtonChange() {
        scheduler.run {
            val menuModeOn = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            if (menuModeOn && screenManager.inMenu) {
                DebugLog.i(C, "glyph button -> menu cycle preview")
                screenManager.menuNext()
            } else {
                DebugLog.i(C, "glyph button CHANGE -> current screen")
                screenManager.dispatchGlyphEvent(Events.CHANGE)
            }
        }
    }

    private companion object {
        const val C = "Router"
    }
}
