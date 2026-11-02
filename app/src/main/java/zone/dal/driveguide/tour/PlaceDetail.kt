package zone.dal.driveguide.tour

/** 상세 화면용 정보. 값이 없는 항목은 null(또는 빈 목록)이다. */
data class PlaceDetail(
    val overview: String?,
    val tel: String?,
    val useTime: String?,
    val restDate: String?,
    val parking: String?,
    val images: List<String>,
    // 여행코스 전용
    val courseDistance: String?,
    val courseTakeTime: String?,
    val courseStops: List<CourseStop>,
)

/** 여행코스에 포함된 장소. 좌표는 detailInfo2에 없어서 경유지로 넣을 때 따로 조회한다. */
data class CourseStop(
    val contentId: String,
    val name: String,
    val overview: String?,
    val imageUrl: String?,
)
