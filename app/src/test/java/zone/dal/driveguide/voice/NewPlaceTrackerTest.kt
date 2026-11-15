package zone.dal.driveguide.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.PlaceType

class NewPlaceTrackerTest {
    private fun place(id: String) = Place(id, PlaceType.ATTRACTION, id, "", 37.0, 127.0, 0.0, null)

    @Test
    fun returnsOnlyUnseenPlaces() {
        val tracker = NewPlaceTracker()
        assertEquals(listOf("a", "b"), tracker.takeNew(listOf(place("a"), place("b"))).map { it.id })
        assertEquals(listOf("c"), tracker.takeNew(listOf(place("b"), place("c"), place("a"))).map { it.id })
        assertEquals(emptyList<String>(), tracker.takeNew(listOf(place("a"), place("c"))).map { it.id })
    }
}
