package zone.dal.driveguide.tour

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import zone.dal.driveguide.R

/** [colorRes]는 종류 배지의 색 점. */
enum class PlaceType(val contentTypeId: Int, val label: String, val colorRes: Int) {
    ATTRACTION(12, "관광지", R.color.type_attraction),
    COURSE(25, "여행코스", R.color.type_course),
}

data class Place(
    val id: String,
    val type: PlaceType,
    val title: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val imageUrl: String?,
)

/**
 * 한국관광공사 국문 관광정보 서비스(KorService2) 위치기반 관광정보 조회 클라이언트.
 * https://www.data.go.kr/data/15101578/openapi.do
 */
class TourApiClient(private val serviceKey: String) {

    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        type: PlaceType,
        radiusMeters: Int = 10_000,
        limit: Int = 10,
    ): List<Place> = request(
        "locationBasedList2",
        mapOf(
            "arrange" to "E", // 거리순
            "mapX" to longitude.toString(),
            "mapY" to latitude.toString(),
            "radius" to radiusMeters.coerceAtMost(20_000).toString(),
            "contentTypeId" to type.contentTypeId.toString(),
            "numOfRows" to limit.toString(),
            "pageNo" to "1",
        ),
        type,
    ).places

    /** 지역 조건 없이 해당 타입 전체를 페이지 단위로 모두 받는다. 좌표가 없는 항목은 제외한다. */
    suspend fun all(type: PlaceType, pageSize: Int = 500): List<Place> {
        val result = mutableListOf<Place>()
        var page = 1
        while (true) {
            val (places, totalCount) = request(
                "areaBasedList2",
                mapOf(
                    "contentTypeId" to type.contentTypeId.toString(),
                    "numOfRows" to pageSize.toString(),
                    "pageNo" to page.toString(),
                ),
                type,
            )
            result += places
            if (places.isEmpty() || page * pageSize >= totalCount) break
            page++
        }
        return result.filter { it.latitude != 0.0 && it.longitude != 0.0 }
    }

    /**
     * 여행코스는 대표 이미지가 거의 없어(약 4%) 코스에 포함된 장소(detailInfo2)의 첫 이미지를 대신 쓴다.
     * 없으면 null.
     */
    suspend fun courseSubImage(contentId: String): String? =
        items("detailInfo2", mapOf("contentId" to contentId, "contentTypeId" to PlaceType.COURSE.contentTypeId.toString()))
            .map { it.optString("subdetailimg") }
            .firstOrNull { it.isNotBlank() }
            ?.let(::https)

    /** 상세 화면 정보. 공통정보·소개정보와 (관광지는 사진 목록, 코스는 포함 장소)를 함께 조회한다. */
    suspend fun detail(place: Place): PlaceDetail = coroutineScope {
        val typeParam = "contentTypeId" to place.type.contentTypeId.toString()
        val common = async { items("detailCommon2", mapOf("contentId" to place.id)).firstOrNull() }
        val intro = async { items("detailIntro2", mapOf("contentId" to place.id, typeParam)).firstOrNull() }
        val extra = async {
            if (place.type == PlaceType.COURSE) {
                items("detailInfo2", mapOf("contentId" to place.id, typeParam))
            } else {
                items("detailImage2", mapOf("contentId" to place.id, "imageYN" to "Y"))
            }
        }
        val c = common.await()
        val i = intro.await()
        val e = extra.await()

        val images = buildList {
            place.imageUrl?.let(::add)
            if (place.type == PlaceType.ATTRACTION) {
                e.mapNotNull { it.optString("originimgurl").ifBlank { null }?.let(::https) }.forEach(::add)
            }
        }.distinct()
        val stops = if (place.type == PlaceType.COURSE) {
            e.map {
                CourseStop(
                    contentId = it.optString("subcontentid"),
                    name = it.optString("subname"),
                    overview = cleanText(it.optString("subdetailoverview")),
                    imageUrl = it.optString("subdetailimg").ifBlank { null }?.let(::https),
                )
            }
        } else emptyList()

        PlaceDetail(
            overview = cleanText(c?.optString("overview")),
            // 공통정보의 tel은 비어 있는 경우가 많아 소개정보의 문의처로 보완한다.
            tel = cleanText(c?.optString("tel")) ?: cleanText(i?.optString("infocenter")),
            useTime = cleanText(i?.optString("usetime")),
            restDate = cleanText(i?.optString("restdate")),
            parking = cleanText(i?.optString("parking")),
            images = images,
            courseDistance = cleanText(i?.optString("distance")),
            courseTakeTime = cleanText(i?.optString("taketime")),
            courseStops = stops,
        )
    }

    /** contentId로 장소 하나를 조회한다(코스 포함 장소를 경유지로 넣을 때 좌표가 필요하다). */
    suspend fun placeById(contentId: String): Place? {
        val o = items("detailCommon2", mapOf("contentId" to contentId)).firstOrNull() ?: return null
        val lat = o.optString("mapy").toDoubleOrNull() ?: return null
        val lng = o.optString("mapx").toDoubleOrNull() ?: return null
        return Place(
            id = contentId,
            type = PlaceType.ATTRACTION,
            title = o.optString("title"),
            address = o.optString("addr1"),
            latitude = lat,
            longitude = lng,
            distanceMeters = 0.0,
            imageUrl = o.optString("firstimage").ifBlank { null }?.let(::https),
        )
    }

    /** 단건/다건/빈 응답을 모두 JSONObject 목록으로 돌려준다. */
    private suspend fun items(operation: String, extra: Map<String, String>): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val body = JSONObject(get(operation, extra)).getJSONObject("response").getJSONObject("body")
            val items = body.optJSONObject("items") ?: return@withContext emptyList()
            when (val raw = items.opt("item")) {
                is JSONArray -> (0 until raw.length()).map { raw.getJSONObject(it) }
                is JSONObject -> listOf(raw)
                else -> emptyList()
            }
        }

    private fun get(operation: String, extra: Map<String, String>): String {
        require(serviceKey.isNotBlank()) { "TOUR_API_KEY가 local.properties에 설정되지 않았습니다." }
        val params = linkedMapOf(
            "serviceKey" to serviceKey,
            "MobileOS" to "AND",
            "MobileApp" to "DriveGuide",
            "_type" to "json",
        ) + extra
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("$BASE_URL/$operation?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private data class Page(val places: List<Place>, val totalCount: Int)

    private suspend fun request(operation: String, extra: Map<String, String>, type: PlaceType): Page =
        withContext(Dispatchers.IO) { parse(get(operation, extra), type) }

    private fun parse(body: String, type: PlaceType): Page {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            // 인증키 오류 등은 XML로 응답된다.
            throw IllegalStateException("TourAPI 오류 응답: ${body.take(200)}")
        }
        val response = json.getJSONObject("response")
        val code = response.getJSONObject("header").optString("resultCode")
        if (code != "0000") {
            throw IllegalStateException("TourAPI resultCode=$code ${response.getJSONObject("header").optString("resultMsg")}")
        }
        val responseBody = response.getJSONObject("body")
        val totalCount = responseBody.optInt("totalCount")
        // 결과가 없으면 items가 빈 문자열, 1건이면 item이 객체로 올 수 있다.
        val items = responseBody.optJSONObject("items") ?: return Page(emptyList(), totalCount)
        val array = when (val raw = items.opt("item")) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            else -> return Page(emptyList(), totalCount)
        }
        val places = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Place(
                id = o.optString("contentid"),
                type = type,
                title = o.optString("title"),
                address = o.optString("addr1"),
                latitude = o.optString("mapy").toDoubleOrNull() ?: 0.0,
                longitude = o.optString("mapx").toDoubleOrNull() ?: 0.0,
                distanceMeters = o.optString("dist").toDoubleOrNull() ?: 0.0,
                // 원본(약 940px)을 카드 폭에 맞게 줄여 쓴다. 이미지 서버는 HTTPS를 지원하므로 cleartext를 피한다.
                imageUrl = o.optString("firstimage").ifBlank { o.optString("firstimage2") }
                    .ifBlank { null }?.let(::https),
            )
        }
        return Page(places, totalCount)
    }

    companion object {
        private const val BASE_URL = "https://apis.data.go.kr/B551011/KorService2"

        /** 이미지 서버는 HTTPS를 지원하므로 cleartext 요청을 피한다. */
        private fun https(url: String) = url.replaceFirst("http://", "https://")

        /** API 값에 섞인 <br>, <a> 등 HTML을 일반 텍스트로 바꾼다. 빈 값은 null. */
        private fun cleanText(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return Html.fromHtml(raw.replace("\n", ""), Html.FROM_HTML_MODE_COMPACT).toString().trim().ifBlank { null }
        }
    }
}
