package space.linuxct.glyphworks.core

interface Prefs {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getString(key: String, def: String): String
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun putBoolean(key: String, v: Boolean)
    fun putInt(key: String, v: Int)
    fun putLong(key: String, v: Long)
    fun putFloat(key: String, v: Float)
    fun putString(key: String, v: String)
    fun addChangeListener(listener: (String) -> Unit)
    fun removeChangeListener(listener: (String) -> Unit)
}

object PrefKeys {
    const val PREFS_VERSION = "prefs_version"
    const val PREFS_VERSION_DEF = 1

    // AndroidPrefs stamps PREFS_VERSION on a first launch, so the default 1 means "maybe legacy".
    const val PREFS_VERSION_CURRENT = 2

    const val MASTER_TOGGLE = "master_toggle"
    const val MASTER_TOGGLE_DEF = true

    const val KEY_ACTION_TOASTS = "keyActionToasts"
    const val KEY_ACTION_TOASTS_DEF = false

    const val MENU_MODE_ENABLED = "menuModeEnabled"
    const val MENU_MODE_ENABLED_DEF = false

    const val SCREEN_ORDER = "screen_order"

    // `rps` stays listed while Rock Paper Scissors is off. Readers drop ids they cannot
    // resolve, so the toy keeps its place for when it returns.
    const val SCREEN_ORDER_DEF =
        "ambient,clock,eyes,speed,battery,solar,moon,dice,coin,dino,bottle,rps,counter,breathing," +
            "timer,compass,level,visualizer,custom"

    fun screenEnabled(id: String) = "screen_enabled_$id"

    const val CURRENT_SCREEN = "current_screen"
    const val CURRENT_SCREEN_DEF = "ambient"

    const val BRIGHTNESS = "brightness"
    const val BRIGHTNESS_DEF = 1.0f

    const val AUTO_BRIGHTNESS = "autoBrightness"
    const val AUTO_BRIGHTNESS_DEF = false

    const val USE_12H = "use12hClock" // default seeded from the system 24h setting

    const val CLOCK_THEME = "clockTheme"
    const val CLOCK_THEME_DEF = 0

    const val SELECTED_DICE = "diceType"
    const val SELECTED_DICE_DEF = "D6"

    const val COIN_DESIGN = "coinDesign"
    const val COIN_DESIGN_DEF = 0

    const val BATTERY_SHOW_WATTS = "batteryShowWatts"
    const val BATTERY_SHOW_WATTS_DEF = false

    const val BREATHING_PACE = "breathingPace"
    const val BREATHING_PACE_DEF = "4"

    const val COUNTER = "counterValue"
    const val COUNTER_DEF = 0

    const val TIMER_START = "timerStartMillis"
    const val TIMER_START_DEF = 0L

    const val TIMER_DURATION = "timerDurationSec"
    const val TIMER_DURATION_DEF = 60

    val TIMER_DURATION_OPTIONS = listOf(60, 180, 300, 420, 600, 780)

    // Above 0 means paused, and it wins over TIMER_START. Pausing banks at least 1 ms.
    const val TIMER_PAUSED_ELAPSED = "timerPausedElapsedMillis"
    const val TIMER_PAUSED_ELAPSED_DEF = 0L

    const val TIMER_CHIMED_FOR = "timerChimedFor"
    const val TIMER_CHIMED_FOR_DEF = 0L

    const val CUSTOM_DESIGN_ID = "customDesignId"
    const val CUSTOM_DESIGN_ID_DEF = ""

    const val CREATOR_NAME = "creatorName"
    const val CREATOR_NAME_DEF = ""

    const val AMBIENT_BACKGROUND = "ambientBackground"
    const val AMBIENT_BACKGROUND_DEF = 0

    const val AMBIENT_USE_BACKGROUND = "ambientUseBackground"
    const val AMBIENT_USE_BACKGROUND_DEF = true

    const val AMBIENT_NIGHT_VISIBLE = "ambientVisibleAtNight"
    const val AMBIENT_NIGHT_VISIBLE_DEF = true

    const val AMBIENT_SHAKE_ACTIVATE = "ambientShakeToShow"
    const val AMBIENT_SHAKE_ACTIVATE_DEF = false

    const val AMBIENT_USE_CHARGING = "ambientShowCharging"
    const val AMBIENT_USE_CHARGING_DEF = true

    const val AMBIENT_CHARGING_STYLE = "ambientChargingStyle"
    const val AMBIENT_CHARGING_STYLE_DEF = 0

    const val VISUALIZER_THEME = "visualizerTheme"
    const val VISUALIZER_THEME_DEF = 0

    const val VISUALIZER_TUNING = "visualizerTuning"
    const val VISUALIZER_TUNING_DEF = 1

    const val VISUALIZER_AOD_HINT = "visualizerAodHint"
    const val VISUALIZER_AOD_HINT_DEF = false

    const val SERVICE_HEARTBEAT = "service_heartbeat"
    const val SERVICE_HEARTBEAT_DEF = 0L

    // Nothing OS exposes no way to read which always-on toy is selected, so a recorded bind
    // is the only proof. It is a latch: it never goes back down.
    const val TOY_LAST_BOUND = "toyLastBound"
    const val TOY_LAST_BOUND_DEF = 0L

    // After a fresh install Nothing OS binds no new toy until this process has restarted, so
    // TOY_LAST_BOUND cannot be believed when it says no until the app has been seen alive twice.
    const val TOY_PROBE_ARMED = "toyProbeArmed"
    const val TOY_PROBE_ARMED_DEF = false

    const val TOY_PROBE_SEEN_ONCE = "toyProbeSeenOnce"
    const val TOY_PROBE_SEEN_ONCE_DEF = false

    const val ONBOARDING_DONE = "onboardingDone"
    const val ONBOARDING_DONE_DEF = false

    const val UNTESTED_DEVICE_ACK = "untestedDeviceAck"
    const val UNTESTED_DEVICE_ACK_DEF = false

    // It records that the dialog was shown, not the answer.
    const val CREATE_TOUR_PROMPTED = "createTourPrompted"
    const val CREATE_TOUR_PROMPTED_DEF = false
}
