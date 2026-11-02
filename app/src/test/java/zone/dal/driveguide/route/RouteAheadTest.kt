package zone.dal.driveguide.route

import zone.dal.driveguide.tour.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAheadTest {

    // 서울시청 → 강릉역, 직선 약 160km
    private val seoul = 37.5663 to 126.9779
    private val gangneung = RouteTarget(37.7643, 128.8996, "강릉역", 0)

    @Test
    fun longRouteSamplesEvery15kmUpToHorizon() {
        val points = RouteAhead.samplePoints(seoul.first, seoul.second, RouteState(emptyList(), gangneung))
        assertEquals(listOf(15_000.0, 30_000.0, 45_000.0, 60_000.0), points.map { it.aheadMeters })
        // 우회 계수 1.3 → 15km 앞 지점은 직선 약 11.5km
        val first = points.first()
        assertEquals(15_000.0 / 1.3, distanceMeters(seoul.first, seoul.second, first.latitude, first.longitude), 200.0)
    }

    @Test
    fun usesNaviRemainDistanceForDetourFactor() {
        val straight = distanceMeters(seoul.first, seoul.second, gangneung.latitude, gangneung.longitude)
        val dest = gangneung.copy(remainDistanceMeters = (straight * 1.5).toInt())
        val first = RouteAhead.samplePoints(seoul.first, seoul.second, RouteState(emptyList(), dest)).first()
        assertEquals(10_000.0, distanceMeters(seoul.first, seoul.second, first.latitude, first.longitude), 200.0)
    }

    @Test
    fun followsWaypointBeforeDestination() {
        // 남쪽(수원) 경유 후 강릉: 첫 지점은 시청보다 남쪽이어야 한다.
        val suwon = RouteTarget(37.2636, 127.0286, "수원", 0)
        val first = RouteAhead.samplePoints(seoul.first, seoul.second, RouteState(listOf(suwon), gangneung)).first()
        assertTrue(first.latitude < seoul.first)
    }

    @Test
    fun shortRouteUsesTighterSpacing() {
        val near = RouteTarget(37.4418, 127.1376, "성남", 0) // 직선 약 20km
        val points = RouteAhead.samplePoints(seoul.first, seoul.second, RouteState(emptyList(), near))
        assertTrue(points.size >= 3)
        assertTrue(points.last().aheadMeters <= distanceMeters(seoul.first, seoul.second, near.latitude, near.longitude) * 1.3 + 1)
    }

    @Test
    fun destinationTooCloseGivesNoPoints() {
        val tooClose = RouteTarget(37.57, 126.98, "근처", 0)
        assertTrue(RouteAhead.samplePoints(seoul.first, seoul.second, RouteState(emptyList(), tooClose)).isEmpty())
    }
}
