package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignCodec

class DesignRenameTest {
    @Test
    fun `a new name is trimmed and returned`() {
        assertEquals("Quiet Comet", renamedName(current = "Slow Ember", typed = "  Quiet Comet  "))
    }

    @Test
    fun `an empty field saves nothing`() {
        assertNull(renamedName(current = "Slow Ember", typed = ""))
    }

    @Test
    fun `an over-length name is capped at the format's limit`() {
        val typed = "x".repeat(DesignCodec.MAX_NAME_LENGTH * 2)
        val renamed = renamedName(current = "Slow Ember", typed = typed)
        assertEquals(DesignCodec.MAX_NAME_LENGTH, renamed?.length)
        assertEquals("x".repeat(DesignCodec.MAX_NAME_LENGTH), renamed)
    }

    @Test
    fun `newlines become spaces rather than breaking the name`() {
        assertEquals("Slow Ember", renamedName(current = "Quiet Comet", typed = "Slow\nEmber"))
    }
}
