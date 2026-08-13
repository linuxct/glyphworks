package space.linuxct.glyphworks.key

import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.RenderScheduler
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionControl

/**
 * Click-count -> action mapping.
 *
 * Classic mode (menu mode OFF, the default):
 *   1 = Glyph Touch (EVENT_CHANGE) to the current screen (no-op on passive screens)
 *   2 = next screen
 *   3 = jump home (the ambient background screen)
 *
 * Menu mode ON, not in the menu:
 *   1 = Glyph Touch (interactive toys still work)
 *   2 = open the blinking selector, 3 = home.
 * Menu mode ON, in the menu:
 *   1 = cycle the blinking preview, 2 = commit (set + exit), 3 = home (exits).
 *
 *   4+ = ignored.
 * If no session is live when a burst lands, the press only revives the
 * session and the action is swallowed (no accidental dice roll on a dark
 * matrix).
 */
/**
 * What a resolved key gesture actually did.
 *
 * Exists so the Android side can *say* what happened without re-deriving the
 * mapping above — a second copy of "2 clicks means next toy, unless menu mode,
 * unless the menu is open" would be wrong the first time this table changed.
 * See `KeyActionRouter.onAction` and `EssentialKeyService`.
 *
 * An enum rather than a message: this class is pure Kotlin with no Android and
 * no user-visible English in it, and it stays that way. The words live in
 * string resources.
 */
enum class KeyAction {
    /** Glyph Touch dispatched to the toy on screen (single press). */
    TOY_ACTION,

    /** Moved to the next enabled toy in the cycle. */
    NEXT_TOY,

    /** Jumped back to the ambient background toy. */
    HOME,

    /** Menu mode: opened the on-matrix selector. */
    MENU_OPEN,

    /** Menu mode, selector open: advanced the blinking preview. */
    MENU_PREVIEW_NEXT,

    /** Menu mode, selector open: committed the previewed toy and closed it. */
    MENU_COMMIT,

    /**
     * Recognised, but nothing to act on: no session owner, or the session had
     * not come up yet. The press revives the session and is otherwise dropped,
     * so a dark matrix cannot silently roll dice.
     */
    SWALLOWED,

    /** More than three presses in one burst — outside the mapping. */
    IGNORED,
}

class KeyActionRouter(
    private val arbiter: SessionControl,
    private val screenManager: ScreenManager,
    private val scheduler: RenderScheduler,
    private val prefs: Prefs,
) {
    /**
     * Called once per resolved gesture with what it did, after it has been done —
     * so the toy id is the one the user ends up on, not the one they left.
     *
     * Null by default and only ever set by `EssentialKeyService`, which uses it
     * for the optional on-screen announcement. **Not** on the render thread for
     * every outcome: the two early returns fire on the caller's thread and the
     * rest inside `scheduler.run`, so anything touching UI must marshal.
     */
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
            // Master toggle off with a live toy binding gone etc. — just try to
            // bring the session back; swallow the action.
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
            val menu = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            when {
                menu && screenManager.inMenu -> when (clicks) {
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
                menu -> when (clicks) {
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

    /**
     * A real Glyph Button long-press (Phone 3). Cycles the preview while the
     * menu is open (menu mode); otherwise behaves like a single Glyph Touch.
     */
    fun glyphButtonChange() {
        scheduler.run {
            val menu = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            if (menu && screenManager.inMenu) {
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
