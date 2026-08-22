package space.linuxct.glyphworks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.linuxct.glyphworks.core.DebugLog
import java.io.File

private val LightScheme = lightColorScheme(
    primary = Color(0xFF17171C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4EA),
    onPrimaryContainer = Color(0xFF17171C),
    secondary = Color(0xFF5A5A62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7EC),
    onSecondaryContainer = Color(0xFF17171C),
    tertiary = Color(0xFF5A5A62),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E4EA),
    onTertiaryContainer = Color(0xFF17171C),
    background = Color(0xFFF2F2FA),
    onBackground = Color(0xFF17171C),
    surface = Color.White,
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFEBEBEF),
    onSurfaceVariant = Color(0xFF6C6C74),
    surfaceTint = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFF2F2FA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    inverseSurface = Color(0xFF2E2E33),
    inverseOnSurface = Color(0xFFF2F2FA),
    outline = Color(0xFF9A9AA2),
    outlineVariant = Color(0xFFE4E4EC),
    error = Color(0xFF17171C),
    onError = Color.White,
    errorContainer = Color(0xFFDDDDE2),
    onErrorContainer = Color(0xFF17171C),
)

private val DarkCard = Color(0xFF191C20)

private val DarkPill = Color(0xFF26292E)

private val DarkContainer = Color(0xFF2E3138)

private val DarkHairline = Color(0xFF0D0F12)

private val DarkInk = Color(0xFFEFF0F7)

private val DarkInkMuted = Color(0xFF84868C)

private val DarkInkDim = Color(0xFFC5C6CC)

private val DarkScheme = darkColorScheme(
    primary = DarkInk,
    onPrimary = DarkCard,
    primaryContainer = DarkContainer,
    onPrimaryContainer = DarkInk,
    secondary = DarkInkDim,
    onSecondary = DarkCard,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = DarkInk,
    tertiary = DarkInkDim,
    onTertiary = DarkCard,
    tertiaryContainer = DarkContainer,
    onTertiaryContainer = DarkInk,
    background = Color.Black,
    onBackground = DarkInk,
    surface = DarkCard,
    onSurface = DarkInk,
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = DarkInkMuted,
    surfaceTint = DarkCard,
    surfaceBright = DarkCard,
    surfaceDim = Color.Black,
    surfaceContainerLowest = DarkCard,
    surfaceContainerLow = DarkCard,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCard,
    surfaceContainerHighest = DarkCard,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkCard,
    outline = Color(0xFF5A5D63),
    outlineVariant = DarkHairline,
    error = DarkInk,
    onError = DarkCard,
    errorContainer = DarkContainer,
    onErrorContainer = DarkInk,
)

@Immutable
data class NavPillColors(
    val container: Color,
    val content: Color,
    val selectedContainer: Color,
    val selectedContent: Color,
    val fabContainer: Color,
    val fabContent: Color,
    val badgeContainer: Color,
    val badgeContent: Color,
)

private val LightNavPill = NavPillColors(
    container = Color(0xFF2E2E33),
    content = Color(0xFFF2F2FA),
    selectedContainer = Color(0xFFF2F2FA),
    selectedContent = Color(0xFF2E2E33),
    fabContainer = NothingLiquidBlue,
    fabContent = Color(0xFFF2F2FA),
    badgeContainer = NothingRed,
    badgeContent = Color.White,
)

private val DarkNavPill = NavPillColors(
    container = DarkPill,
    content = DarkInk,
    selectedContainer = Color(0xFFDDDEE4),
    selectedContent = DarkCard,
    fabContainer = NothingLiquidBlue,
    fabContent = DarkInk,
    badgeContainer = NothingRed,
    badgeContent = Color.White,
)

private val LocalNavPillColors = staticCompositionLocalOf { LightNavPill }

val MaterialTheme.navPill: NavPillColors
    @Composable @ReadOnlyComposable get() = LocalNavPillColors.current

private val FONT_DIRS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")

private val HEADLINE_FONT_NAMES = listOf("NType82-Regular")

private fun deviceHeadlineFont(): FontFamily? {
    val files = FONT_DIRS.flatMap { File(it).listFiles()?.toList() ?: emptyList() }
    val ordered = HEADLINE_FONT_NAMES.mapNotNull { base ->
        files.firstOrNull { it.name.equals("$base.otf", true) || it.name.equals("$base.ttf", true) }
    }
    for (file in ordered) {
        try {
            return FontFamily(Font(file)).also {
                DebugLog.i("Theme", "headline font loaded from ${file.path}")
            }
        } catch (e: Exception) {
            DebugLog.w("Theme", "failed to load ${file.path}: ${e.message}")
        }
    }
    return null
}

private fun buildTypography(headline: FontFamily): Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = headline),
        headlineLarge = base.headlineLarge.copy(fontFamily = headline),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = headline,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Normal,
            fontSynthesis = FontSynthesis.None,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = headline, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = headline, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
        ),
    )
}

@Composable
fun GlyphWorksTheme(content: @Composable () -> Unit) {
    val typography = remember {
        val headline = deviceHeadlineFont() ?: run {
            DebugLog.i("Theme", "no Nothing headline font found; using system serif")
            FontFamily.Serif
        }
        buildTypography(headline)
    }
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalNavPillColors provides if (dark) DarkNavPill else LightNavPill) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            // Keep plain MaterialTheme: MaterialExpressiveTheme would also swap the shape and
            // colour defaults, which breaks the monochrome palette.
            motionScheme = MotionScheme.expressive(),
            typography = typography,
            content = content,
        )
    }
}
