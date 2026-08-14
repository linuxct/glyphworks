package space.linuxct.glyphworks.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * **Every icon in this app is full-contrast: white on dark, black on light.**
 * There are no grey icons anywhere, and these helpers are how that is kept true.
 *
 * ## The problem they solve
 *
 * Material 3 paints icon *slots* with `onSurfaceVariant` — the same token it uses
 * for supporting text. That is a deliberate choice by the library and it is not
 * the choice this app makes: an icon rendered in subtitle grey reads as
 * decoration attached to the row rather than as the row's subject, and next to a
 * full-strength icon it looks like a bug. Hierarchy here is carried by text tint
 * and by size; icons are always ink.
 *
 * The token appears in a surprising number of component defaults, and they were
 * fixed one at a time and repeatedly missed — the Toys tab's play button stayed
 * grey through two passes. So the rule is now written once, here:
 *
 * | Component | Default that had to be overridden |
 * |---|---|
 * | `ListItem` | `leadingIconColor`, `trailingIconColor` |
 * | `IconButton` / `IconToggleButton` | unchecked `contentColor` |
 * | `FilledIconToggleButton` | UNCHECKED `contentColor` — the checked one keeps its own |
 * | `TopAppBar` (incl. the large variant) | `actionIconContentColor` |
 * | `DropdownMenuItem` | `leadingIconColor`, `trailingIconColor` |
 * | text fields | focused/unfocused leading and trailing icon colours |
 *
 * ## The two things that are NOT bugs
 *
 * **Checked filled toggles invert.** A checked `FilledIconToggleButton` sits on a
 * filled container — near-white in dark theme — so its glyph must be dark to be
 * seen at all. That is contrast, not muting, and [fullContrastToggleColors]
 * leaves `checkedContentColor` alone.
 *
 * **Disabled controls still look disabled.** M3 draws them at 38 % alpha. An
 * undo button with nothing to undo, painted at full ink, claims to work. State is
 * allowed to change an icon's appearance; style is not.
 */

/** Icons in list rows: ink, not the supporting-text grey. */
@Composable
internal fun fullContrastListItemColors() = ListItemDefaults.colors(
    leadingIconColor = MaterialTheme.colorScheme.onSurface,
    trailingIconColor = MaterialTheme.colorScheme.onSurface,
)

/**
 * A filled icon toggle whose UNCHECKED glyph is ink.
 *
 * ## `checkedContentColor` is passed on purpose — do not remove it
 *
 * `IconButtonDefaults.filledIconToggleButtonColors` declares:
 *
 * ```
 * checkedContentColor: Color = contentColorFor(checkedContainerColor),
 * ```
 *
 * and `checkedContainerColor` itself defaults to `Color.Unspecified`. Kotlin
 * evaluates that default expression the moment ANY argument is named, so
 * `contentColorFor(Unspecified)` runs, finds no match, and falls back to
 * **`LocalContentColor.current`** — the ink colour of whatever row the button
 * happens to sit in. `copy()` then treats that as a real override.
 *
 * The result: the checked button kept its `primary` container and had its glyph
 * repainted in the *same* near-black ink, so the Toys tab's active play button
 * and the auto-brightness toggle rendered as solid black slabs with nothing on
 * them. Naming this parameter restores the token value and is the only thing
 * standing between this helper and that bug.
 *
 * Everything else is left alone deliberately. Both container colours keep their
 * tokens — `SurfaceContainer` unchecked, `Primary` checked — so the pressed and
 * selected states look exactly as they always did. The one and only change here
 * is the unchecked glyph, `OnSurfaceVariant` to `onSurface`.
 */
@Composable
internal fun fullContrastToggleColors() = IconButtonDefaults.filledIconToggleButtonColors(
    // The unchecked glyph: ink instead of the muted token. This is the fix.
    contentColor = MaterialTheme.colorScheme.onSurface,
    // FilledIconButtonTokens.SelectedColor, restated. Not a new colour — the
    // token default, named so the broken derivation above never runs. It is also
    // exactly "the opposite of the selected button's background", since the
    // checked container is `primary`.
    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
)

/**
 * App-bar colours with the action slot at full contrast.
 *
 * `TopAppBarDefaults` gives navigation icons `onSurface` and *action* icons
 * `onSurfaceVariant`, so a bar's left icon and its right icons came out
 * different greys — visible in the design editor, where the back arrow was ink
 * and every tool beside it was not.
 *
 * Both app bars in the app paint themselves in `background` so the header is
 * seamless with the page, hence the container parameters rather than a bare
 * override.
 *
 * There is deliberately no helper for plain `IconButton`: its default content
 * colour is `LocalContentColor`, which is already ink on every surface this app
 * puts one on. A helper would imply otherwise and invite pointless call sites.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun fullContrastTopAppBarColors(
    containerColor: Color = Color.Unspecified,
    scrolledContainerColor: Color = containerColor,
) = TopAppBarDefaults.topAppBarColors(
    containerColor = containerColor,
    scrolledContainerColor = scrolledContainerColor,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
)

