package zone.dal.driveguide.route

import zone.dal.driveguide.tour.distanceMeters

data class RouteTarget(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    /** 내비가 알려주는 현재 위치부터의 남은 도로 거리. 모르면 0 이하. */
    val remainDistanceMeters: Int,
)

/** 남은 경유지(가까운 순)와 목적지. */
data class RouteState(
    val waypoints: List<RouteTarget>,
    val destination: RouteTarget,
)

/** 경로 앞쪽의 샘플 지점. [aheadMeters]는 현재 위치부터의 추정 도로 거리. */
data class AheadPoint(
    val latitude: Double,
    val longitude: Double,
    val aheadMeters: Double,
)

/**
 * NaviHelper는 경로 선형을 제공하지 않으므로 현재 위치 → 경유지 → 목적지를 직선으로 잇고,
 * 직선 거리와 내비의 남은 도로 거리 비율(우회 계수)로 보정해 경로 앞쪽 지점을 추정한다.
 */
object RouteAhead {

    fun samplePoints(
        currentLat: Double,
        currentLng: Double,
        route: RouteState,
        horizonMeters: Double = 60_000.0,
        maxSpacingMeters: Double = 15_000.0,
        minSpacingMeters: Double = 3_000.0,
    ): List<AheadPoint> {
        val nodes = buildList {
            add(currentLat to currentLng)
            route.waypoints.forEach { add(it.latitude to it.longitude) }
            add(route.destination.latitude to route.destination.longitude)
        }
        val segments = nodes.zipWithNext { a, b -> distanceMeters(a.first, a.second, b.first, b.second) }
        val straightTotal = segments.sum()
        if (straightTotal < minSpacingMeters) return emptyList()

        // 도로는 직선보다 길다. 내비 남은 거리를 알면 그 비율을 쓰고, 모르면 일반적인 값(1.3)을 쓴다.
        val roadTotal = route.destination.remainDistanceMeters.toDouble()
        val detour = if (roadTotal > 0) (roadTotal / straightTotal).coerceIn(1.0, 2.0) else 1.3
        val roadLength = straightTotal * detour

        // 짧은 경로는 간격을 좁혀 최소 3~4개 지점을 확보한다.
        val spacing = (roadLength / 4).coerceIn(minSpacingMeters, maxSpacingMeters)
        val limit = minOf(horizonMeters, roadLength)

        val points = mutableListOf<AheadPoint>()
        var ahead = spacing
        while (ahead <= limit + 1) {
            val (lat, lng) = pointAt(nodes, segments, ahead / detour)
            points += AheadPoint(lat, lng, ahead)
            ahead += spacing
        }
        return points
    }

    /** 폴리라인 시작점에서 직선 거리 [distance]만큼 떨어진 지점(구간 내 선형 보간). */
    private fun pointAt(
        nodes: List<Pair<Double, Double>>,
        segments: List<Double>,
        distance: Double,
    ): Pair<Double, Double> {
        var remaining = distance
        segments.forEachIndexed { i, length ->
            if (remaining <= length || i == segments.lastIndex) {
                val t = if (length == 0.0) 0.0 else (remaining / length).coerceIn(0.0, 1.0)
                val (aLat, aLng) = nodes[i]
                val (bLat, bLng) = nodes[i + 1]
                return (aLat + (bLat - aLat) * t) to (aLng + (bLng - aLng) * t)
            }
            remaining -= length
        }
        return nodes.last()
    }
}
