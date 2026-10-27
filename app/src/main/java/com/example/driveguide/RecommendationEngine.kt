package com.example.driveguide

import android.util.Log
import com.example.driveguide.navi.VehicleLocation
import com.example.driveguide.tour.CourseRepository
import com.example.driveguide.tour.Place
import com.example.driveguide.tour.PlaceType
import com.example.driveguide.tour.TourApiClient
import com.example.driveguide.tour.distanceMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class Recommendation(
    val region: String?,
    val regionChanged: Boolean,
    val attractions: List<Place>,
    val courses: List<Place>,
)

/**
 * 위치 업데이트를 받아 "새로 조회할 시점"을 판단하고 주변 관광지/여행코스를 가져온다.
 * - 시/군/구가 바뀌면 즉시 조회 (지역 진입 안내)
 * - 같은 지역 안에서도 마지막 조회 지점에서 [refetchDistanceMeters] 이상 이동하면 재조회
 */
class RecommendationEngine(
    private val tourApi: TourApiClient,
    private val courseRepository: CourseRepository,
    private val refetchDistanceMeters: Double = 5_000.0,
) {
    private var lastFetchLat: Double? = null
    private var lastFetchLng: Double? = null
    private var currentRegion: String? = null

    /** 재조회가 필요 없으면 null을 반환한다. */
    suspend fun onLocation(location: VehicleLocation): Recommendation? {
        val region = regionOf(location.address)
        val regionChanged = region != null && region != currentRegion
        val movedFar = lastFetchLat?.let { lat ->
            distanceMeters(lat, lastFetchLng!!, location.latitude, location.longitude) >= refetchDistanceMeters
        } ?: true

        if (!regionChanged && !movedFar) return null

        val (attractions, courses) = coroutineScope {
            val a = async { tourApi.nearby(location.latitude, location.longitude, PlaceType.ATTRACTION, 10_000) }
            // 코스 캐시를 받지 못해도 관광지 추천은 보여준다.
            val c = async {
                runCatching { courseRepository.nearby(location.latitude, location.longitude) }
                    .onFailure { Log.w(TAG, "Course lookup failed", it) }
                    .getOrDefault(emptyList())
            }
            a.await() to c.await()
        }

        // 조회에 성공한 뒤에만 기준점을 갱신해 실패 시 다음 위치에서 재시도한다.
        lastFetchLat = location.latitude
        lastFetchLng = location.longitude
        if (region != null) currentRegion = region

        return Recommendation(region ?: currentRegion, regionChanged, attractions, courses)
    }

    companion object {
        private const val TAG = "RecommendationEngine"

        /**
         * 주소에서 시/군/구 단위만 뽑는다. 내비 주소는 시/도 없이 오기도 한다.
         * "강원특별자치도 강릉시 교동 123" → "강릉시", "성남시 수정구 시흥동" → "성남시 수정구", "수정구 시흥동" → "수정구"
         */
        fun regionOf(address: String?): String? {
            val tokens = address?.trim()?.split(Regex("\\s+")) ?: return null
            val districts = tokens.filter { it.length >= 2 && it.last() in "시군구" && !it.endsWith("특별시") && !it.endsWith("광역시") }
            return districts.joinToString(" ").ifEmpty { null }
        }
    }
}
