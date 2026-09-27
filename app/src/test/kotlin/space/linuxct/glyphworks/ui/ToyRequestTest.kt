package space.linuxct.glyphworks.ui

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.preview.ToyPreview
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.screens.ScreenRegistry
import java.net.URI
import java.net.URLDecoder

class ToyRequestTest {
    @Test fun `mail draft preserves the exact recipient subject and form including line breaks`() {
        val uri = URI(ToyRequest.mailtoUri)
        assertEquals("mailto", uri.scheme)
        assertEquals("glyphworks@linuxct.space", uri.rawSchemeSpecificPart.substringBefore('?'))
        val fields = uri.rawSchemeSpecificPart.substringAfter('?').split('&').associate {
            val (key, value) = it.split('=', limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals(setOf("subject", "body"), fields.keys)
        assertEquals("Design request", fields["subject"])
        assertEquals(listOf(
            "-- PLEASE WRITE YOUR INTERACTIVE TOY REQUEST BY FILLING THE FORM IN THE MAIL BODY BELOW --",
            "",
            "Contact person (name and email address): ",
            "Design title:",
            "Design description:",
            "How does the design work? How does it interact with the device or the button?:",
        ).joinToString("\n"), fields["body"])
        assertFalse("Mail URI spaces must be escaped", ToyRequest.mailtoUri.contains(' '))
        assertFalse("Mail URI line breaks must be escaped", ToyRequest.mailtoUri.contains('\n'))
        assertFalse("Mail clients must not interpret form-encoded spaces as literal plus signs", ToyRequest.mailtoUri.contains('+'))
    }

    @Test fun `request card is not a playable toy and cannot switch the hardware session`() {
        val screens = ScreenRegistry.create()
        assertFalse(screens.any { it.id == ToyRequest.ID })
        assertFalse(SCREEN_DISPLAY_NAMES.containsKey(ToyRequest.ID))
        val h = TestHarness()
        h.prefs.putString(PrefKeys.CURRENT_SCREEN, "clock")
        val manager = h.manager(screens)
        manager.startSession()
        val frameCount = h.output.size
        val before = h.prefs.map.toMap()
        manager.selectScreen(ToyRequest.ID)
        assertEquals("clock", manager.currentScreen().id)
        assertEquals(before, h.prefs.map)
        assertEquals(frameCount, h.output.size)
        manager.stopSession()
        ToyPreview(13, FakePrefs()).use { preview ->
            assertThrows(IllegalArgumentException::class.java) { preview.select(ToyRequest.ID) }
        }
    }

    @Test fun `static app preview is a centered plus inside both panel masks`() {
        for (size in listOf(13, 25)) {
            val frame = ToyRequest.previewFrame(size)
            assertTrue(frame.any { it > 0 })
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = frame[y * size + x]
                if (pixel > 0) assertTrue(PanelMask.contains(x, y, size))
                assertEquals(pixel, frame[y * size + size - 1 - x])
                assertEquals(pixel, frame[(size - 1 - y) * size + x])
            }
        }
    }
}
