package zone.dal.driveguide.saved

import org.junit.Assert.assertEquals
import org.junit.Test
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.PlaceType

class SavedPlacesTest {

    private fun place(id: String, type: PlaceType = PlaceType.ATTRACTION, imageUrl: String? = "https://a/$id.jpg") =
        Place(id, type, "장소 $id", "주소 $id", 37.5, 127.1, 0.0, imageUrl)

    @Test
    fun `저장한 장소를 그대로 복원한다`() {
        val places = listOf(place("1", PlaceType.COURSE), place("2", imageUrl = null))
        assertEquals(places, SavedPlaces.decode(SavedPlaces.encode(places)))
    }

    @Test
    fun `저장할 때의 거리는 남기지 않는다`() {
        val decoded = SavedPlaces.decode(SavedPlaces.encode(listOf(place("1").copy(distanceMeters = 1234.0))))
        assertEquals(0.0, decoded.single().distanceMeters, 0.0)
    }

    @Test
    fun `알림으로 보낸 장소도 그대로 복원한다`() {
        val course = place("1", PlaceType.COURSE, imageUrl = null)
        assertEquals(course, SavedPlaces.decodePlace(SavedPlaces.encodePlace(course)))
    }

    @Test
    fun `최근에 저장한 곳이 앞에 오고 중복되지 않는다`() {
        val result = SavedPlaces.added(listOf(place("2"), place("1")), place("1"))
        assertEquals(listOf("1", "2"), result.map { it.id })
    }

    @Test
    fun `저장값이 없거나 깨졌으면 빈 목록이다`() {
        assertEquals(emptyList<Place>(), SavedPlaces.decode(null))
        assertEquals(emptyList<Place>(), SavedPlaces.decode("{깨짐"))
    }
}
