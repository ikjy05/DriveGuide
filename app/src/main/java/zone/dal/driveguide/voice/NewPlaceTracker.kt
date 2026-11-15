package zone.dal.driveguide.voice

import zone.dal.driveguide.tour.Place

/**
 * 이미 안내한 장소를 기억해, 추천이 새로 올 때 처음 보는 곳만 골라 준다.
 * 지역 경계를 오가거나 같은 곳 근처를 다시 지나도 같은 장소를 되풀이해 말하지 않게 한다.
 */
class NewPlaceTracker {
    private val announced = HashSet<String>()

    /** [places] 중 처음 보는 곳을 순서대로 돌려주고 본 것으로 표시한다. */
    fun takeNew(places: List<Place>): List<Place> = places.filter { announced.add(it.id) }
}
