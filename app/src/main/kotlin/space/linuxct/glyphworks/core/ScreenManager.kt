package space.linuxct.glyphworks.core

/** The toy carousel. Call every method on the scheduler thread. */
class ScreenManager(
    private val allScreens: List<GlyphScreen>,
    private val prefs: Prefs,
    private val ports: Ports,
    private val scheduler: RenderScheduler,
    private val size: Int,
    private val output: (IntArray) -> Unit,
) {
    var sessionLive = false
        private set

    var inMenu = false
        private set

    // `@Volatile` because `TimerAlarmReceiver` reads this from a binder thread and pushes to
    // `GlyphLink` itself. Writes stay on the scheduler thread.
    @Volatile
    var livePreviewActive = false
        private set

    private var transientId: String? = null
    private var activeScreen: GlyphScreen? = null
    private var lastPushed: IntArray? = null

    private var blinkOn = true
    private var lastContentFrame: IntArray? = null

    // Kept unscaled: scaling an already scaled frame compounds the rounding and would slowly
    // dim the matrix.
    private var lastRawFrame: IntArray? = null
    private val blank = IntArray(size * size)
    private var blink: Cancelable? = null
    private var commitTimer: Cancelable? = null

    private val context: ScreenContext = ScreenContext(size, prefs, ports, scheduler) { frame ->
        if (livePreviewActive) return@ScreenContext
        val raw = lastRawFrame
        if (raw != null && raw.size == frame.size) frame.copyInto(raw) else lastRawFrame = frame.copyOf()
        val scaled = BrightnessScale.scale(frame, brightness())
        lastContentFrame = scaled
        val blinkedOff = inMenu && !blinkOn
        val toSend = if (blinkedOff) blank else scaled
        val last = lastPushed
        if (last != null && last.contentEquals(toSend)) return@ScreenContext
        lastPushed = toSend.copyOf()
        output(toSend)
    }

    fun enabledScreens(): List<GlyphScreen> {
        val byId = allScreens.associateBy { it.id }
        val ordered = prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
            .split(',')
            .mapNotNull { byId[it.trim()] }
        val known = ordered + allScreens.filter { s -> ordered.none { it.id == s.id } }
        val enabled = known.filter { prefs.getBoolean(PrefKeys.screenEnabled(it.id), true) }
        return enabled.ifEmpty { known }
    }

    fun currentScreen(): GlyphScreen {
        val screens = enabledScreens()
        transientId?.let { t -> allScreens.firstOrNull { it.id == t }?.let { return it } }
        val id = prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
        return screens.firstOrNull { it.id == id } ?: screens.first()
    }

    fun startSession() {
        if (sessionLive) return
        sessionLive = true
        lastPushed = null
        val screen = currentScreen()
        DebugLog.i(C, "session START on '${screen.id}'")
        activate(screen)
    }

    fun stopSession() {
        if (!sessionLive) return
        DebugLog.i(C, "session STOP (was '${activeScreen?.id}')")
        exitMenuState()
        deactivate()
        transientId = null
        sessionLive = false
        lastPushed = null
        lastContentFrame = null
        lastRawFrame = null
    }

    fun reapplyBrightness() {
        if (!sessionLive) return
        val raw = lastRawFrame ?: return
        // scale() hands back its input when nothing needs scaling, so copy: otherwise
        // lastContentFrame would alias the reused raw buffer.
        val scaled = BrightnessScale.scale(raw, brightness()).let { if (it === raw) raw.copyOf() else it }
        lastContentFrame = scaled
        val blinkedOff = inMenu && !blinkOn
        if (blinkedOff) return
        lastPushed = scaled.copyOf()
        output(scaled)
    }

    private fun brightness() = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)

    fun beginLivePreview() {
        if (livePreviewActive) return
        DebugLog.i(C, "live preview BEGIN (was '${activeScreen?.id}')")
        exitMenuState()
        livePreviewActive = true
        deactivate()
        // Drop the old frame, or an auto-brightness tick re-pushes the toy over the preview.
        lastRawFrame = null
        lastContentFrame = null
    }

    /** Takes ownership of [frame]. */
    fun pushLivePreview(frame: IntArray) {
        if (!livePreviewActive) return
        val raw = lastRawFrame
        if (raw != null && raw.size == frame.size) frame.copyInto(raw) else lastRawFrame = frame.copyOf()
        val scaled = BrightnessScale.scale(frame, brightness())
        lastContentFrame = scaled
        val last = lastPushed
        if (last != null && last.contentEquals(scaled)) return
        lastPushed = scaled.copyOf()
        output(scaled)
    }

    fun endLivePreview() {
        if (!livePreviewActive) return
        DebugLog.i(C, "live preview END")
        livePreviewActive = false
        lastPushed = null
        lastRawFrame = null
        lastContentFrame = null
        if (sessionLive) forceActivate(currentScreen())
    }

    fun refreshCurrentScreen() {
        if (!sessionLive || livePreviewActive) return
        DebugLog.i(C, "refresh '${currentScreen().id}'")
        forceActivate(currentScreen())
    }

    private var lastSelectedDesignId =
        prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)

    fun onSelectedDesignChanged(designScreenId: String) {
        val selected = prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
        if (selected == lastSelectedDesignId) return
        lastSelectedDesignId = selected
        // Record the selection first, so it survives even when the design toy is not showing.
        if (currentScreen().id != designScreenId) return
        refreshCurrentScreen()
    }

    fun next() = moveBy(1)

    fun home() {
        if (!sessionLive) return
        val wasInMenu = inMenu
        exitMenuState()
        val homeScreen = enabledScreens().first()
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, homeScreen.id)
        if (wasInMenu) {
            forceActivate(homeScreen)
        } else {
            if (activeScreen?.id == homeScreen.id) return
            switchTo(homeScreen)
        }
    }

    private fun moveBy(delta: Int) {
        if (!sessionLive) return
        val screens = enabledScreens()
        val current = activeScreen ?: currentScreen()
        val idx = screens.indexOfFirst { it.id == current.id }
        val nextScreen = screens[((if (idx < 0) 0 else idx) + delta + screens.size) % screens.size]
        DebugLog.i(C, "cycle '${current.id}' -> '${nextScreen.id}'")
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, nextScreen.id)
        switchTo(nextScreen)
    }

    fun dispatchGlyphEvent(event: String) {
        if (!sessionLive) {
            DebugLog.d(C, "event '$event' dropped (session not live)")
            return
        }
        DebugLog.i(C, "event '$event' -> '${activeScreen?.id}'")
        activeScreen?.onEvent(event)
    }

    fun selectScreen(id: String) {
        val screen = enabledScreens().firstOrNull { it.id == id }
            ?: allScreens.firstOrNull { it.id == id } ?: return
        DebugLog.i(C, "select '${screen.id}'")
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, screen.id)
        if (sessionLive && !livePreviewActive) switchTo(screen)
    }

    fun showTransient(id: String) {
        if (!sessionLive) {
            DebugLog.d(C, "transient '$id' dropped (session not live)")
            return
        }
        DebugLog.i(C, "transient preview '$id'")
        transientId = id
        val screen = allScreens.firstOrNull { it.id == id } ?: return
        switchTo(screen)
    }

    fun clearTransient() {
        if (transientId == null) return
        transientId = null
        if (sessionLive) switchTo(currentScreen())
    }

    fun enterMenu() {
        if (!sessionLive || inMenu) return
        DebugLog.i(C, "menu ENTER on '${currentScreen().id}'")
        inMenu = true
        blinkOn = true
        transientId = currentScreen().id
        startBlink()
        armCommit()
    }

    fun menuNext() {
        if (!inMenu) return
        val screens = enabledScreens()
        val cur = activeScreen ?: currentScreen()
        val idx = screens.indexOfFirst { it.id == cur.id }
        val nextScreen = screens[((if (idx < 0) 0 else idx) + 1) % screens.size]
        DebugLog.i(C, "menu NEXT '${cur.id}' -> '${nextScreen.id}'")
        transientId = nextScreen.id
        switchTo(nextScreen)
        armCommit()
    }

    fun commitMenu() {
        if (!inMenu) return
        val id = transientId ?: currentScreen().id
        DebugLog.i(C, "menu COMMIT '$id'")
        exitMenuState()
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, id)
        val screen = enabledScreens().firstOrNull { it.id == id }
            ?: allScreens.firstOrNull { it.id == id } ?: currentScreen()
        forceActivate(screen)
    }

    private fun startBlink() {
        blink?.cancel()
        scheduleBlink()
    }

    private fun scheduleBlink() {
        val delay = if (blinkOn) BLINK_ON_MS else BLINK_OFF_MS
        blink = scheduler.postDelayed(delay) {
            if (!inMenu) return@postDelayed
            blinkOn = !blinkOn
            val frame = if (blinkOn) lastContentFrame else blank
            if (frame != null) {
                lastPushed = frame.copyOf()
                output(frame)
            }
            scheduleBlink()
        }
    }

    private fun armCommit() {
        commitTimer?.cancel()
        commitTimer = scheduler.postDelayed(MENU_TIMEOUT_MS) { commitMenu() }
    }

    private fun exitMenuState() {
        blink?.cancel(); blink = null
        commitTimer?.cancel(); commitTimer = null
        inMenu = false
        blinkOn = true
    }

    private fun switchTo(screen: GlyphScreen) {
        if (activeScreen === screen) return
        deactivate()
        activate(screen)
    }

    private fun forceActivate(screen: GlyphScreen) {
        deactivate()
        activate(screen)
    }

    private fun activate(screen: GlyphScreen) {
        activeScreen = screen
        screen.onActivate(context)
    }

    private fun deactivate() {
        scheduler.clearTicker()
        activeScreen?.onDeactivate()
        activeScreen = null
    }

    private companion object {
        const val C = "Screens"
        const val MENU_TIMEOUT_MS = 5000L
        const val BLINK_ON_MS = 450L
        const val BLINK_OFF_MS = 300L
    }
}
