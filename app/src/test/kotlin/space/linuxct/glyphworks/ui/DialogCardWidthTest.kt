package space.linuxct.glyphworks.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DialogCardWidthTest {
    private val phoneWindow = 411.dp

    @Test
    fun `platform width passes through on a phone`() {
        assertEquals(320.dp, dialogCardWidth(preferred = 320.dp, available = phoneWindow))
    }

    @Test
    fun `a large-screen platform width is held to MD3's maximum`() {
        assertEquals(DIALOG_MAX_WIDTH, dialogCardWidth(preferred = 580.dp, available = 800.dp))
    }

    @Test
    fun `a mean platform width is lifted to MD3's minimum`() {
        assertEquals(DIALOG_MIN_WIDTH, dialogCardWidth(preferred = 200.dp, available = phoneWindow))
    }

    @Test
    fun `the window wins over the minimum`() {
        assertEquals(272.dp, dialogCardWidth(preferred = 320.dp, available = 320.dp))
    }
}
