package space.linuxct.glyphworks.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeFontTest {
    private val required: List<Char> =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + ' ' +
            "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toList()

    @Test
    fun `every glyph is nine rows of one width, drawn only in hash and dot`() {
        for (c in MarqueeFont.COVERAGE) {
            val glyph = MarqueeFont.glyph(c)!!
            assertEquals("'$c' is ${glyph.size} rows", MarqueeFont.HEIGHT, glyph.size)
            val width = glyph.first().length
            assertTrue("'$c' is $width columns wide", width in 1..MarqueeFont.MAX_WIDTH)
            for ((r, row) in glyph.withIndex()) {
                assertEquals("'$c' row $r is ragged", width, row.length)
                assertTrue("'$c' row $r has a stray character: $row", row.all { it == '#' || it == '.' })
            }
        }
    }

    @Test
    fun `the coverage is the printable ASCII the brief named`() {
        for (c in required) assertTrue("'$c' is missing", MarqueeFont.supports(c))
        assertEquals(required.sorted(), MarqueeFont.COVERAGE)
    }

    @Test
    fun `accented latin letters fall back to their base letter`() {
        assertEquals(MarqueeFont.glyph('e'), MarqueeFont.glyph('é'))
        assertEquals(MarqueeFont.glyph('N'), MarqueeFont.glyph('Ñ'))
        assertEquals(MarqueeFont.picture("cafe"), MarqueeFont.picture("café"))
        assertEquals(MarqueeFont.picture("ano"), MarqueeFont.picture("año"))
        assertEquals("cafe", MarqueeFont.drawnAs("café"))
    }

    @Test
    fun `what it cannot draw it reports, in order and without repeats`() {
        assertEquals(emptyList<Char>(), MarqueeFont.unsupported("HELLO, WORLD! 42"))
        assertEquals(emptyList<Char>(), MarqueeFont.unsupported("café"))
        assertEquals(listOf('♥', '中'), MarqueeFont.unsupported("A♥B中C♥"))
        assertFalse(MarqueeFont.supports('♥'))
        assertNull(MarqueeFont.glyph('♥'))
        assertEquals(0, MarqueeFont.width('♥'))
    }

    @Test
    fun `a strip is the glyph widths plus one gap between each pair`() {
        assertEquals(MarqueeFont.width('A'), MarqueeFont.stripWidth("A"))
        assertEquals(
            MarqueeFont.width('H') + MarqueeFont.GAP + MarqueeFont.width('I'),
            MarqueeFont.stripWidth("HI"),
        )
        val hello = "HELLO".sumOf { MarqueeFont.width(it) } + 4 * MarqueeFont.GAP
        assertEquals(hello, MarqueeFont.stripWidth("HELLO"))
        assertEquals(0, MarqueeFont.stripWidth(""))
    }

    @Test
    fun `text it cannot draw has no width and no picture`() {
        assertEquals(0, MarqueeFont.stripWidth("A♥B"))
        assertEquals(emptyList<String>(), MarqueeFont.picture("A♥B"))
        assertEquals(0, MarqueeFont.strip("A♥B").size)
    }

    @Test
    fun `the strip and the picture are the same bitmap`() {
        val text = "GLYPH 42!"
        val strip = MarqueeFont.strip(text)
        val picture = MarqueeFont.picture(text)

        assertEquals(MarqueeFont.HEIGHT, picture.size)
        assertEquals(MarqueeFont.stripWidth(text), strip.size)
        for (r in 0 until MarqueeFont.HEIGHT) {
            assertEquals(strip.size, picture[r].length)
            for (x in strip.indices) {
                assertEquals(
                    "row $r column $x",
                    strip[x] and (1 shl r) != 0,
                    picture[r][x] == '#',
                )
            }
        }
    }
}
