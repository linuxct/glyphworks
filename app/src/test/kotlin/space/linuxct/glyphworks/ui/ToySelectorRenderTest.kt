package space.linuxct.glyphworks.ui

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.GlyphLink
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.theme.fullContrastTopAppBarColors
import space.linuxct.glyphworks.ui.theme.recordBackdrop
import space.linuxct.glyphworks.ui.theme.rememberBackdrop
import java.io.File

/** Render the production selector, header and navigation locally, without starting Core/services. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h933dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToySelectorRenderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun froggerPro() = render(13, "froggerpro")

    @Test
    @Config(qualifiers = "w480dp-h1067dp-notnight-420dpi")
    fun froggerProMoreSpace() = render(13, "froggerpro-wide")

    @Test fun metroid() = render(25, "metroid")

    @Test
    @Config(qualifiers = "w420dp-h933dp-night-xxhdpi")
    fun froggerProDark() = render(13, "froggerpro-dark")

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(panelSize: Int, name: String) {
        val fixture = TestHarness(panelSize)
        fixture.prefs.putBoolean(PrefKeys.LUCENT_ENABLED, true)
        fixture.prefs.putString(PrefKeys.CURRENT_SCREEN, "ambient")
        fun coreField(name: String, value: Any?) {
            Core::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
        }
        coreField("prefs", fixture.prefs)
        coreField("ports", fixture.ports)
        val link = GlyphLink(RuntimeEnvironment.getApplication())
        GlyphLink::class.java.getDeclaredField("size").apply { isAccessible = true }.setInt(link, panelSize)
        coreField("glyphLink", link)
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 0f)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        try {
            activity.setup()
            activity.get().setContent {
                RenderTheme {
                    var pillHeight by remember { mutableStateOf(0.dp) }
                    val backdrop = rememberBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize().recordBackdrop(backdrop),
                            containerColor = MaterialTheme.colorScheme.background,
                            topBar = {
                                LargeTopAppBar(
                                    title = { Text(stringResource(R.string.screens_title)) },
                                    colors = fullContrastTopAppBarColors(MaterialTheme.colorScheme.background),
                                )
                            },
                        ) { padding ->
                            ToysTab(
                                innerPadding = PaddingValues(
                                    top = padding.calculateTopPadding(),
                                    bottom = padding.calculateBottomPadding() + pillHeight + 44.dp,
                                ),
                                listState = rememberLazyListState(),
                                visible = true,
                                onDeckGesture = {},
                            )
                        }
                        FloatingNavBar(
                            selected = 0, position = { 0f }, fabVisible = false,
                            onFabClick = {}, onSelect = {}, onPillHeight = { pillHeight = it },
                            backdrop = backdrop, modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
            compose.waitForIdle()
            // Thumbnail work runs on Dispatchers.Default, outside Compose's test clock.
            // Wait for actual lit pixels inside the centered card's disc, not a fixed delay.
            val selectedCard = compose.onNode(isSelected() and hasText("Ambient (background)"))
            compose.waitUntil(timeoutMillis = 10_000) {
                val card = selectedCard.captureToImage().asAndroidBitmap()
                var lit = 0
                for (y in (card.height * 0.30f).toInt() until (card.height * 0.60f).toInt()) {
                    for (x in (card.width * 0.36f).toInt() until (card.width * 0.64f).toInt()) {
                        val pixel = card.getPixel(x, y)
                        if ((pixel shr 16 and 255) > 230 && (pixel shr 8 and 255) > 230 && (pixel and 255) > 230) lit++
                    }
                }
                lit > 10
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val output = File("build/reports/selector/$name.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            activity.close()
            (GlyphLink::class.java.getDeclaredField("ioThread").apply { isAccessible = true }.get(link) as HandlerThread)
                .quitSafely()
            coreField("glyphLink", null)
            coreField("ports", null)
            coreField("prefs", null)
        }
    }

    @Composable
    private fun RenderTheme(content: @Composable () -> Unit) {
        GlyphWorksTheme {
            val deviceFont = remember {
                System.getProperty("selectorHeadlineFont")?.let { FontFamily(Font(File(it))) }
            }
            val base = MaterialTheme.typography
            val typography = if (deviceFont == null) base else base.copy(
                displaySmall = base.displaySmall.copy(fontFamily = deviceFont),
                headlineLarge = base.headlineLarge.copy(fontFamily = deviceFont),
                headlineMedium = base.headlineMedium.copy(fontFamily = deviceFont),
                headlineSmall = base.headlineSmall.copy(fontFamily = deviceFont),
                titleLarge = base.titleLarge.copy(fontFamily = deviceFont),
            )
            MaterialTheme(typography = typography, content = content)
        }
    }
}
