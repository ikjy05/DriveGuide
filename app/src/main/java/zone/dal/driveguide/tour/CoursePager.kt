package zone.dal.driveguide.tour

/**
 * 코스 탭 목록을 스크롤하는 만큼 [pageSize]개씩 이어 붙인다. 코스는 기기에 저장한 전국 목록에서
 * 반경 60km 안을 가까운 순으로 자르므로 바로 나오지만, 사진을 받는 코스가 많아 한 번에 다 보이지 않는다.
 *
 * @param initial 처음 추천으로 받은 코스(가까운 순 첫 [pageSize]개).
 * @param fetch 가까운 순으로 [offset]번째부터 [limit]개를 받는다.
 */
class CoursePager(
    initial: List<Place>,
    private val pageSize: Int = PAGE_SIZE,
    private val fetch: suspend (offset: Int, limit: Int) -> List<Place>,
) : MorePager {
    private val _places = initial.distinctBy { it.id }.toMutableList()
    override val places: List<Place> get() = _places

    // 첫 쪽이 다 차지 않았으면 반경 안을 이미 다 본 것이다.
    override var done = initial.size < pageSize
        private set

    override suspend fun loadMore(): Int {
        if (done) return 0
        val page = fetch(_places.size, pageSize)
        val known = _places.mapTo(HashSet()) { it.id }
        val fresh = page.filter { it.id !in known }.distinctBy { it.id }
        _places += fresh
        if (page.size < pageSize || fresh.isEmpty()) done = true
        return fresh.size
    }

    companion object {
        const val PAGE_SIZE = 10
        const val RADIUS_KM = 60
    }
}
