package space.linuxct.glyphworks.ui

import android.content.Context
import space.linuxct.glyphworks.R

/** Adding or removing an entry here must match `screens/ScreenRegistry.kt`. */
internal val SCREEN_DISPLAY_NAMES = mapOf(
    "ambient" to R.string.screen_ambient,
    "clock" to R.string.screen_clock,
    "eyes" to R.string.screen_eyes,
    "speed" to R.string.screen_speed,
    "battery" to R.string.screen_battery,
    "solar" to R.string.screen_solar,
    "moon" to R.string.screen_moon,
    "dice" to R.string.screen_dice,
    "coin" to R.string.screen_coin,
    "dino" to R.string.screen_dino,
    "bottle" to R.string.screen_bottle,
    // Disabled: // "rps" to R.string.screen_rps, — restore with its ScreenRegistry.kt line.
    "counter" to R.string.screen_counter,
    "breathing" to R.string.screen_breathing,
    "timer" to R.string.screen_timer,
    "compass" to R.string.screen_compass,
    "level" to R.string.screen_level,
    "visualizer" to R.string.screen_visualizer,
    "custom" to R.string.screen_custom,
)

internal fun screenDisplayName(context: Context, id: String): String =
    SCREEN_DISPLAY_NAMES[id]?.let(context::getString) ?: id
