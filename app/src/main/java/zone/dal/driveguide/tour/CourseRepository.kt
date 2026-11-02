package zone.dal.driveguide.tour

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 여행코스(contentTypeId=25)는 전국 약 1,000건뿐이라 위치기반 조회(최대 반경 20km)로는
 * 결과가 비는 경우가 많다. 전체 목록을 받아 파일에 캐시하고, 앱에서 더 넓은 반경으로 거리 계산한다.
 * 좌표는 코스의 출발 지점 기준이다.
 */
class CourseRepository(
    context: Context,
    private val tourApi: TourApiClient,
    private val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    private val cacheFile = File(context.filesDir, "courses.json")
    private val mutex = Mutex()
    @Volatile private var courses: List<Place>? = null

    // 포함 장소 이미지를 이미 조회한 코스(실패·없음 포함). 앱 실행 중에는 다시 조회하지 않는다.
    private val imageChecked = ConcurrentHashMap.newKeySet<String>()

    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 60_000.0,
        limit: Int = 5,
    ): List<Place> = withImages(
        load()
            .map { it.copy(distanceMeters = distanceMeters(latitude, longitude, it.latitude, it.longitude)) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
    )

    /** 대표 이미지가 없는 코스는 포함 장소 이미지로 채우고, 찾은 이미지는 캐시에 저장한다. */
    private suspend fun withImages(places: List<Place>): List<Place> {
        val found = coroutineScope {
            places.filter { it.imageUrl == null && imageChecked.add(it.id) }
                .map { p -> async { p.id to runCatching { tourApi.courseSubImage(p.id) }.getOrNull() } }
                .awaitAll()
                .mapNotNull { (id, url) -> url?.let { id to it } }
                .toMap()
        }
        if (found.isNotEmpty()) {
            mutex.withLock {
                courses = courses?.map { c -> found[c.id]?.let { c.copy(imageUrl = it) } ?: c }
                courses?.let { writeCache(it) }
            }
        }
        // 동시에 다른 호출이 찾은 이미지도 반영되도록 메모리 목록에서 다시 읽는다.
        val byId = courses.orEmpty().associateBy { it.id }
        return places.map { p -> p.imageUrl?.let { p } ?: p.copy(imageUrl = found[p.id] ?: byId[p.id]?.imageUrl) }
    }

    private suspend fun load(): List<Place> = mutex.withLock {
        courses?.let { return it }
        val cached = readCache()
        val fresh = cached != null && System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMillis
        val loaded = if (fresh) {
            cached!!
        } else {
            try {
                tourApi.all(PlaceType.COURSE).also { writeCache(it) }
            } catch (e: Exception) {
                // 네트워크 실패 시 오래된 캐시라도 사용한다.
                Log.w(TAG, "Course download failed", e)
                cached ?: throw e
            }
        }
        Log.i(TAG, "Loaded ${loaded.size} courses (fromCache=$fresh)")
        courses = loaded
        loaded
    }

    private suspend fun readCache(): List<Place>? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        try {
            val array = JSONArray(cacheFile.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Place(
                    id = o.getString("id"),
                    type = PlaceType.COURSE,
                    title = o.getString("title"),
                    address = o.optString("address"),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lng"),
                    distanceMeters = 0.0,
                    // 이전 버전 캐시에는 http URL이 남아 있을 수 있다.
                    imageUrl = o.optString("image").ifBlank { null }?.replaceFirst("http://", "https://"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt course cache, ignoring", e)
            null
        }
    }

    private suspend fun writeCache(places: List<Place>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        places.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("address", p.address)
                    .put("lat", p.latitude)
                    .put("lng", p.longitude)
                    .put("image", p.imageUrl ?: "")
            )
        }
        val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(cacheFile)
    }

    companion object {
        private const val TAG = "CourseRepository"
    }
}

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
