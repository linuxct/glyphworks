package space.linuxct.glyphworks.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.annotation.SuppressLint
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.RippleConfiguration
import com.google.android.material.color.utilities.Hct
import space.linuxct.glyphworks.R
import kotlin.math.abs

internal val LocalLucent = staticCompositionLocalOf { false }

/** True when the Nothing OS 5 look is on. Off, every token below is unused. */
val MaterialTheme.lucent: Boolean
    @Composable @ReadOnlyComposable get() = LocalLucent.current

/** Cards let the background through; overlays sit over live content and stay denser. */
internal const val LUCENT_SURFACE_ALPHA = 0.82f

internal const val LUCENT_OVERLAY_ALPHA = 0.86f

private fun geist(weight: Int) = Font(
    R.font.geist_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

internal val Geist = FontFamily(geist(300), geist(400), geist(500), geist(600), geist(700))

internal val LucentShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

internal val PillShape = RoundedCornerShape(percent = 50)

/**
 * Cards are translucent; dialogs are not. Nothing's own dialogs sit opaque over a dimmed page,
 * because a frosted sheet with text behind it cannot be read.
 */
@Composable
internal fun dialogSurface(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)

/**
 * Frosts the page behind a dialog. This is a window-level blur, so it reaches the Activity
 * underneath — and it quietly does nothing for a Popup, which is what the design-card menu is.
 */
@Composable
internal fun DialogBackdropBlur(radius: Dp = NAV_BLUR) {
    val lucent = MaterialTheme.lucent
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, lucent, px) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val manager = window?.context?.getSystemService(WindowManager::class.java)
        if (lucent && window != null && manager?.isCrossWindowBlurEnabled == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = px }
        }
        onDispose { }
    }
}

/**
 * A press lands on exactly the colour a selected row wears, so the two read as one state.
 * Compose draws the ripple over the label rather than behind it, so the colour is diluted to
 * [RIPPLE_ALPHA] and brightened by the same factor: the row still lands on [pressed], and the
 * text keeps most of its contrast. The alpha-bearing constructor is deprecated in favour of
 * writing a whole ripple node; for one colour that is not a trade worth making.
 */
@Suppress("DEPRECATION")
internal fun lucentRipple(resting: Color, pressed: Color): RippleConfiguration {
    val alpha = rippleAlpha(resting, pressed)
    return RippleConfiguration(
        color = rippleColor(resting, pressed, alpha * RIPPLE_LOSS),
        rippleAlpha = RippleAlpha(
            draggedAlpha = alpha * RIPPLE_DRAGGED,
            focusedAlpha = alpha * RIPPLE_FOCUSED,
            hoveredAlpha = alpha * RIPPLE_HOVERED,
            pressedAlpha = alpha,
        ),
    )
}

private fun rippleAlpha(resting: Color, pressed: Color): Float = maxOf(
    RIPPLE_ALPHA,
    channelAlpha(resting.red, pressed.red),
    channelAlpha(resting.green, pressed.green),
    channelAlpha(resting.blue, pressed.blue),
).coerceAtMost(1f)

private fun channelAlpha(resting: Float, pressed: Float): Float {
    val lift = pressed - resting
    val room = if (lift >= 0f) 1f - resting else resting
    return if (room <= 0f) 1f else abs(lift) / room / RIPPLE_LOSS
}

private fun rippleColor(resting: Color, pressed: Color, alpha: Float): Color = Color(
    red = lift(resting.red, pressed.red, alpha),
    green = lift(resting.green, pressed.green, alpha),
    blue = lift(resting.blue, pressed.blue, alpha),
)

private fun lift(resting: Float, pressed: Float, alpha: Float): Float =
    (resting + (pressed - resting) / alpha).coerceIn(0f, 1f)

private const val RIPPLE_ALPHA = 0.3f

// The platform ripple is a soft-edged shader, not a flat disc, so a held press settles below the
// alpha it was handed. Measured on device: a plateau of 0.84.
private const val RIPPLE_LOSS = 0.84f

private const val RIPPLE_DRAGGED = 0.6f
private const val RIPPLE_FOCUSED = 0.5f
private const val RIPPLE_HOVERED = 0.35f

/** Corners that grow under Lucent. [classic] is what the app has always drawn. */
@Composable
internal fun glyphCorner(classic: Dp, lucent: Dp): RoundedCornerShape =
    RoundedCornerShape(if (MaterialTheme.lucent) lucent else classic)

/**
 * Built in HCT, the space Material itself uses, because HSL cannot express these colours:
 * holding an HSL hue while raising saturation walks off Material's hue line.
 *
 * Every number below is a regional median taken off a Nothing OS 5 phone carrying the identical
 * Monet seed, so the page is the real page and not merely the commonest colour on screen — the
 * cards cover most of a settings page, and reading the histogram once had me paint the page in
 * the card's tone, leaving no contrast at all. Theirs runs tone 8.9 to 18.6, a gap of ten;
 * Material's own neutral sits near chroma 6 where theirs is 29, and 23 degrees round from it.
 */
private const val NOTHING_HUE_SHIFT = -23.0

/**
 * Below this the system is on its monochrome theme style and must stay grey.
 *
 * A grey is not colourless in CAM16: sRGB's own grey sits off the adapting white, so the whole
 * ramp reads hue 209.5 at a small chroma, and the monochrome accent this was measured against
 * (#C6C6C6) comes out at 2.46. Left under the threshold it painted the app teal, because a
 * meaningless hue is still a hue once the surfaces are built at chroma 31. The dimmest palette
 * that really carries colour is the neutral theme style at about 12, so this sits between them.
 */
private const val MONOCHROME_CHROMA = 6.0

private const val PAGE_CHROMA_DARK = 16.5
private const val CARD_CHROMA_DARK = 31.0
private const val INK_CHROMA_DARK = 17.0
private const val MUTED_CHROMA_DARK = 19.0

private const val PAGE_CHROMA_LIGHT = 8.0
private const val CARD_CHROMA_LIGHT = 5.0
private const val INK_CHROMA_LIGHT = 10.0
private const val MUTED_CHROMA_LIGHT = 12.0

// Their pressed state is a pure tone lift: across a ripple the hue holds at 322 and the chroma
// at 28, while the tone climbs 17.8 to 34.6. So one surface serves the selected row and the
// ripple both, and it is the card's own colour raised about seventeen tones.
private const val PRESSED_TONE_DARK = 35.0
private const val PRESSED_TONE_LIGHT = 92.0

// A grey page and a grey selected row have nothing but tone to tell them apart, and 92 against
// the page's 94 is not a difference: measured on a monochrome phone the row came out #E8E8E8 on
// a #EEEEEE page, half the separation an ordinary card gets. This drops it clear of both. Only
// the colourless case needs it — everywhere else the chroma carries the difference too.
private const val PRESSED_TONE_LIGHT_MONO = 86.0

private const val PAGE_TONE_DARK = 8.9
private const val CARD_TONE_DARK = 20.6
private const val BRIGHT_TONE_DARK = 30.0
private const val INK_TONE_DARK = 86.0
private const val MUTED_TONE_DARK = 74.0

// Nothing's extra dark mode drops the page to black and leaves the cards where they are: their
// settings page measures #1B1B1B on a #000000 ground with it on, against #1B1B1B for the ground
// itself with it off, which is what PAGE_TONE_DARK already draws.
private const val PAGE_TONE_DARK_EXTRA = 0.0

private const val PAGE_TONE_LIGHT = 94.0
private const val CARD_TONE_LIGHT = 99.0
private const val BRIGHT_TONE_LIGHT = 100.0
private const val INK_TONE_LIGHT = 12.0
private const val MUTED_TONE_LIGHT = 40.0

/**
 * Nothing's "Extra dark mode", read straight from where the system keeps it. It also lives in
 * `persist.sys.extradark`, but a system property needs reflection to reach and this does not.
 * Keyed on the configuration because switching it swaps a set of overlays, which is a
 * configuration change: on a phone that does not have the setting at all this stays false.
 */
@Composable
private fun extraDark(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        Settings.Secure.getInt(resolver, EXTRA_DARK_SETTING, 0) != 0
    }
}

private const val EXTRA_DARK_SETTING = "theme_extra_darkmode"

@SuppressLint("RestrictedApi")
@Composable
internal fun lucentColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val accent = Hct.fromInt(base.primary.toArgb())
    val hue = (accent.hue + NOTHING_HUE_SHIFT + 360.0) % 360.0
    // Chroma is a target, not a ceiling. Capping it against the accent looked prudent and was
    // wrong: this phone's accent carries only chroma 12, so every surface came out grey no
    // matter what was asked for. Nothing states its chroma outright, and so do we — except
    // under the system's monochrome theme style, where the accent really is colourless and
    // inventing a colour would be the bug.
    val monochrome = accent.chroma < MONOCHROME_CHROMA
    fun surface(chroma: Double, tone: Double): Color =
        Color(Hct.from(hue, if (monochrome) 0.0 else chroma, tone).toInt())

    val page = surface(
        if (dark) PAGE_CHROMA_DARK else PAGE_CHROMA_LIGHT,
        when {
            !dark -> PAGE_TONE_LIGHT
            extraDark() -> PAGE_TONE_DARK_EXTRA
            else -> PAGE_TONE_DARK
        },
    )
    val card = surface(
        if (dark) CARD_CHROMA_DARK else CARD_CHROMA_LIGHT,
        if (dark) CARD_TONE_DARK else CARD_TONE_LIGHT,
    )
    val bright = surface(
        if (dark) CARD_CHROMA_DARK else CARD_CHROMA_LIGHT,
        if (dark) BRIGHT_TONE_DARK else BRIGHT_TONE_LIGHT,
    )
    val ink = surface(
        if (dark) INK_CHROMA_DARK else INK_CHROMA_LIGHT,
        if (dark) INK_TONE_DARK else INK_TONE_LIGHT,
    )
    val muted = surface(
        if (dark) MUTED_CHROMA_DARK else MUTED_CHROMA_LIGHT,
        if (dark) MUTED_TONE_DARK else MUTED_TONE_LIGHT,
    )
    val pressed = surface(
        if (dark) CARD_CHROMA_DARK else CARD_CHROMA_LIGHT,
        when {
            dark -> PRESSED_TONE_DARK
            monochrome -> PRESSED_TONE_LIGHT_MONO
            else -> PRESSED_TONE_LIGHT
        },
    )
    return base.copy(
        background = page,
        surfaceDim = page,
        surfaceBright = bright,
        surface = card.copy(alpha = LUCENT_SURFACE_ALPHA),
        surfaceVariant = card.copy(alpha = LUCENT_SURFACE_ALPHA),
        surfaceContainerLowest = card.copy(alpha = LUCENT_SURFACE_ALPHA),
        surfaceContainerLow = card.copy(alpha = LUCENT_SURFACE_ALPHA),
        surfaceContainer = card.copy(alpha = LUCENT_SURFACE_ALPHA),
        // Material's filled Card reads surfaceContainerHighest, so these have to carry the card
        // tone too or every Card stays the page colour.
        surfaceContainerHigh = card.copy(alpha = LUCENT_OVERLAY_ALPHA),
        surfaceContainerHighest = card.copy(alpha = LUCENT_OVERLAY_ALPHA),
        // The switch track, radio ring and body text all read these, which is how the controls
        // pick up the tint rather than staying black and white. Their switch track measures
        // chroma 19 against text at 16, close enough that one tone serves both.
        onSurface = ink,
        onBackground = ink,
        onSurfaceVariant = muted,
        outline = muted,
        outlineVariant = muted,
        // Section headers read this. Material rotates tertiary 60 degrees off the seed, which
        // turns them orange against a magenta wallpaper; Nothing keeps headers in the surface
        // family. The accents themselves are left alone — theirs stay the wallpaper's own hue.
        tertiary = muted,
        // The selected toy row reads this. Untouched it came from the secondary palette, which
        // is a different hue entirely — the row rendered grey-brown at chroma 14 beside cards at 28.
        secondaryContainer = pressed,
        onSecondaryContainer = ink,
    )
}

/** Titles keep NType 82; everything else moves to Geist, the Nothing OS 5 system face. */
internal fun lucentTypography(base: Typography): Typography = base.copy(
    titleMedium = base.titleMedium.copy(fontFamily = Geist),
    titleSmall = base.titleSmall.copy(fontFamily = Geist),
    bodyLarge = base.bodyLarge.copy(fontFamily = Geist),
    bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
    bodySmall = base.bodySmall.copy(fontFamily = Geist),
    labelLarge = base.labelLarge.copy(fontFamily = Geist),
    labelMedium = base.labelMedium.copy(fontFamily = Geist),
    labelSmall = base.labelSmall.copy(fontFamily = Geist),
)

/** Blur radius under the floating bar. Nothing frosts hard enough to lose all detail. */
internal val NAV_BLUR = 28.dp

// A thin scrim over the frost, not a solid bar: measured at roughly half alpha on their own.
private const val NAV_SCRIM_ALPHA = 0.55f

internal fun lucentNavPill(scheme: ColorScheme, fallback: NavPillColors) = NavPillColors(
    // Not `surface`: in light that is now the card white, which the selected chip would vanish into.
    container = scheme.background.copy(alpha = NAV_SCRIM_ALPHA),
    content = scheme.onSurface,
    // The selected pill is opaque and a step brighter than the bar, in both themes.
    selectedContainer = scheme.surfaceBright.copy(alpha = 1f),
    selectedContent = scheme.onSurface,
    // The + FAB keeps Nothing's red and blue in both looks.
    fabContainer = fallback.fabContainer,
    fabContent = fallback.fabContent,
    badgeContainer = scheme.primary,
    badgeContent = scheme.onPrimary,
)
