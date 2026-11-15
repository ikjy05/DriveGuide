package zone.dal.driveguide.tour

/** 목록 끝까지 스크롤하면 다음 장소들을 이어 붙이는 목록. 관광지 탭([NearbyPager])과 코스 탭([CoursePager])이 쓴다. */
interface MorePager {
    /** 지금까지 받은 장소(가까운 순). */
    val places: List<Place>

    /** 더 받을 것이 없으면 true. */
    val done: Boolean

    /** 다음 장소들을 받아 붙인다. 새로 붙은 곳 수를 돌려준다(0이면 [done]). */
    suspend fun loadMore(): Int
}
