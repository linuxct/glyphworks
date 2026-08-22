package space.linuxct.glyphworks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateTourOfferTest {

    @Test
    fun `offered on the first arrival`() {
        assertTrue(shouldOfferCreateTour(visited = true, prompted = false, inDemo = false))
    }

    @Test
    fun `not offered before the tab is opened`() {
        assertFalse(shouldOfferCreateTour(visited = false, prompted = false, inDemo = false))
    }

    @Test
    fun `never offered twice`() {
        assertFalse(shouldOfferCreateTour(visited = true, prompted = true, inDemo = false))
    }

    @Test
    fun `never offered inside the guided demo`() {
        assertFalse(shouldOfferCreateTour(visited = true, prompted = false, inDemo = true))
        assertFalse(shouldOfferCreateTour(visited = true, prompted = true, inDemo = true))
    }
}
