package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionCardTest {

    @Test
    fun `a group of one is all outer corners`() {
        assertEquals(SectionItemPosition.ONLY, sectionItemPosition(index = 0, count = 1))
    }

    @Test
    fun `a group of two is a first and a last, with no middle`() {
        assertEquals(SectionItemPosition.FIRST, sectionItemPosition(index = 0, count = 2))
        assertEquals(SectionItemPosition.LAST, sectionItemPosition(index = 1, count = 2))
    }

    @Test
    fun `only the ends of a long group are rounded`() {
        val count = 6
        val positions = (0 until count).map { sectionItemPosition(it, count) }
        assertEquals(
            listOf(
                SectionItemPosition.FIRST,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.LAST,
            ),
            positions,
        )
    }

    @Test
    fun `an empty group has no ends to get wrong`() {
        assertEquals(SectionItemPosition.ONLY, sectionItemPosition(index = 0, count = 0))
    }
}
