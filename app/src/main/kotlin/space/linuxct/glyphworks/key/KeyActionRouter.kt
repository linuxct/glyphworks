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
    private val customGestures: (Int) -> Boolean = { false },
    private val rawKey: (Boolean) -> Boolean = { false },
    private val resolvedSingle: () -> Boolean = { false },
    private val heldKey: (Long) -> Boolean = { false },
) {
    /** Runs on the render thread, or on the caller's thread for the early returns. */
    @Volatile
    var onAction: ((clicks: Int, action: KeyAction, screenId: String) -> Unit)? = null

    private fun report(clicks: Int, action: KeyAction) {
        val listener = onAction ?: return
        val id = runCatching { screenManager.currentScreen().id }.getOrDefault("")
        listener(clicks, action, id)
    }

    @Volatile
    private var firedOnFirstPress = false

    /** Only [GlyphScreen.instantAction] screens act here; the rest wait for the window to close. */
    private var rawConsumed = false
    private var awaitingResolution = false
    private var physicalDown = false
    private var hold: space.linuxct.glyphworks.core.Cancelable? = null
    fun keyDown() { scheduler.run {
        if (screenManager.livePreviewActive) return@run
        physicalDown = true; awaitingResolution = true
        rawConsumed = rawKey(true) || rawConsumed
        hold?.cancel()
        hold = scheduler.postDelayed(600) {
            if (physicalDown && !screenManager.livePreviewActive) {
                val consumed = heldKey(600)
                if (awaitingResolution) rawConsumed = consumed || rawConsumed
            }
        }
    } }
    fun keyUp() { scheduler.run {
        physicalDown = false; hold?.cancel(); hold = null
        if (!screenManager.livePreviewActive) {
            val consumed = rawKey(false)
            if (awaitingResolution) rawConsumed = consumed || rawConsumed
        }
    } }
    fun cancelPhysicalKey() { scheduler.run {
        physicalDown = false; awaitingResolution = false; rawConsumed = false; firedOnFirstPress = false
        hold?.cancel(); hold = null
    } }

    fun firstPress() {
        scheduler.run {
            if (!screenManager.sessionLive || screenManager.livePreviewActive || rawConsumed) return@run
            if (prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF) &&
                screenManager.inMenu
            ) {
                return@run
            }
            if (!screenManager.currentScreen().instantAction) return@run
            if (customGestures(1)) { firedOnFirstPress = true; report(1, KeyAction.TOY_ACTION); return@run }
            DebugLog.i(C, "instant press -> EVENT_CHANGE to '${screenManager.currentScreen().id}'")
            firedOnFirstPress = true
            screenManager.dispatchGlyphEvent(Events.CHANGE)
            report(1, KeyAction.TOY_ACTION)
        }
    }

    fun execute(clicks: Int) {
        val handledEarly = firedOnFirstPress
        firedOnFirstPress = false
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
            if (screenManager.livePreviewActive) { report(clicks, KeyAction.IGNORED); return@run }
            if (!screenManager.sessionLive) {
                DebugLog.i(C, "session not live yet -> revive and swallow")
                arbiter.revive()
                report(clicks, KeyAction.SWALLOWED)
                return@run
            }
            val consumedRaw = rawConsumed
            rawConsumed = false; awaitingResolution = false
            if (consumedRaw) { report(clicks, KeyAction.TOY_ACTION); return@run }
            if (clicks == 1) {
                val consumed = resolvedSingle()
                if (handledEarly || consumed) return@run
            }
            if (customGestures(clicks)) { report(clicks, KeyAction.TOY_ACTION); return@run }
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
            if (screenManager.livePreviewActive) return@run
            if (customGestures(1)) return@run
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
