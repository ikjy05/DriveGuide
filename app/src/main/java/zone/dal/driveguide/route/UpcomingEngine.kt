package zone.dal.driveguide.route

import android.util.Log
import zone.dal.driveguide.RecommendationEngine
import zone.dal.driveguide.navi.VehicleLocation
import zone.dal.driveguide.tour.CourseRepository
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.PlaceType
import zone.dal.driveguide.tour.TourApiClient
import zone.dal.driveguide.tour.distanceMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 경로 앞 한 구간(샘플 지점)의 추천. */
data class UpcomingSection(
    val aheadMeters: Double,
    val region: String?,
    val places: List<Place>,
)

/**
 * 경로 앞쪽 샘플 지점마다 주변 관광지/코스를 모아 "가는 길 추천"을 만든다.
 * 경로(목적지·경유지)가 바뀌거나 마지막 계산 지점에서 [refetchDistanceMeters] 이상 이동하면 다시 계산한다.
 */
class UpcomingEngine(
    private val tourApi: TourApiClient,
    private val courseRepository: CourseRepository,
    private val refetchDistanceMeters: Double = 5_000.0,
) {
    private var lastRouteKey: String? = null
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    /** 재계산이 필요 없으면 null, 경로가 없으면 빈 목록을 반환한다. */
    suspend fun update(location: VehicleLocation, route: RouteState?): List<UpcomingSection>? {
        if (route == null) {
            val hadRoute = lastRouteKey != null
            lastRouteKey = null
            return if (hadRoute) emptyList() else null
        }

        val routeKey = routeKeyOf(route)
        val movedFar = lastLat?.let { lat ->
            distanceMeters(lat, lastLng!!, location.latitude, location.longitude) >= refetchDistanceMeters
        } ?: true
        if (routeKey == lastRouteKey && !movedFar) return null

        val points = RouteAhead.samplePoints(location.latitude, location.longitude, route)
        Log.d(TAG, "Route ahead: waypoints=${route.waypoints.size} dest=${route.destination} samples=${points.map { it.aheadMeters.toInt() }}")
        val sections = coroutineScope {
            points.map { p -> async { sectionAt(p) } }.awaitAll()
        }

        // 이미 경유지/목적지인 장소는 빼고, 여러 구간에 같은 장소가 걸리면 가장 가까운 구간에만 남긴다.
        val onRoute = (route.waypoints + route.destination).map { it.name }.toSet()
        val seen = mutableSetOf<String>()
        val deduped = sections
            .map { s -> s.copy(places = s.places.filter { it.title !in onRoute && seen.add(it.id) }) }
            .filter { it.places.isNotEmpty() }

        lastRouteKey = routeKey
        lastLat = location.latitude
        lastLng = location.longitude
        return deduped
    }

    private suspend fun sectionAt(point: AheadPoint): UpcomingSection {
        val attractions = runCatching {
            tourApi.nearby(point.latitude, point.longitude, PlaceType.ATTRACTION, radiusMeters = 5_000, limit = 3)
        }.onFailure { Log.w(TAG, "Attraction lookup failed at ${point.aheadMeters}", it) }
            .getOrDefault(emptyList())
        val courses = runCatching {
            courseRepository.nearby(point.latitude, point.longitude, radiusMeters = 10_000.0, limit = 1)
        }.getOrDefault(emptyList())

        // distanceMeters는 샘플 지점(경로)에서 장소까지의 거리, 즉 경로에서 벗어나는 정도다.
        val places = attractions + courses
        val region = attractions.firstNotNullOfOrNull { RecommendationEngine.regionOf(it.address) }
        return UpcomingSection(point.aheadMeters, region, places)
    }

    private fun routeKeyOf(route: RouteState): String =
        (route.waypoints + route.destination).joinToString("|") { "%.4f,%.4f".format(it.latitude, it.longitude) }

    companion object {
        private const val TAG = "UpcomingEngine"
    }
}
