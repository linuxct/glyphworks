package space.linuxct.glyphworks.ui

import android.content.Context
import space.linuxct.glyphworks.R

/**
 * Screen id -> the name the user sees, in canonical cycle order.
 *
 * ## Two jobs, and that is deliberate
 *
 * This is the Toys tab's **roster** as well as a lookup: `loadOrder` keeps only
 * stored ids that are keys here and appends any key the store has not seen, so
 * adding or removing an entry adds or removes the row. Taking a toy out of the
 * app therefore means editing this map *and* `screens/ScreenRegistry.kt`, which
 * is what the Essential Key actually cycles — one without the other leaves the
 * toy half-gone. Both files say so.
 *
 * It lives here rather than inside `MainActivity` because
 * `key/EssentialKeyService` needs it too: the optional gesture announcement says
 * which toy an action landed on, and it must not carry a second copy of these
 * names that could drift from the ones on screen.
 */
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
    // TEMPORARILY DISABLED — Rock Paper Scissors. This map is the Toys tab's
    // roster: dropping the entry removes the row and with it every way to enable
    // the toy. Restore alongside the matching line in screens/ScreenRegistry.kt,
    // which is what the Essential Key cycles.
    // "rps" to R.string.screen_rps,
    "counter" to R.string.screen_counter,
    "breathing" to R.string.screen_breathing,
    "timer" to R.string.screen_timer,
    "compass" to R.string.screen_compass,
    "level" to R.string.screen_level,
    "visualizer" to R.string.screen_visualizer,
    "custom" to R.string.screen_custom,
)

/**
 * The user-facing name of a screen, for text built outside Compose.
 *
 * Falls back to the raw id rather than to something like "unknown": if this ever
 * misses, the id is the one thing that makes the miss diagnosable, and it is
 * still a truthful answer to "which toy".
 */
internal fun screenDisplayName(context: Context, id: String): String =
    SCREEN_DISPLAY_NAMES[id]?.let(context::getString) ?: id
