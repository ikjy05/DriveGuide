package zone.dal.driveguide.tour

/**
 * 주변 관광지 목록을 스크롤하는 만큼 이어 붙인다. 관광공사 API는 거리순으로 한 쪽씩 주고 반경은 20km까지만 받으므로,
 * 먼저 [NEAR_RADIUS] 안을 다음 쪽부터 이어 받고, 그 안을 다 보면 [FAR_RADIUS]로 넓혀 처음부터 받으며 이미 있는 곳은 뺀다.
 * 내릴수록 자연스럽게 더 먼 곳이 나온다.
 *
 * @param initial 처음 추천(반경 [NEAR_RADIUS], 첫 쪽)으로 이미 받은 관광지.
 * @param fetch 반경(m)과 쪽 번호(1부터)로 한 쪽([pageSize]곳)을 받는다.
 */
class NearbyPager(
    initial: List<Place>,
    private val pageSize: Int = 10,
    private val fetch: suspend (radiusMeters: Int, page: Int) -> List<Place>,
) : MorePager {
    private val _places = initial.distinctBy { it.id }.toMutableList()
    override val places: List<Place> get() = _places

    private var radius = NEAR_RADIUS
    /** 다음에 받을 쪽. 첫 쪽은 처음 추천으로 이미 받았다. */
    private var nextPage = 2

    /** [FAR_RADIUS] 안을 다 봤으면 true. */
    override var done = false
        private set

    val radiusMeters: Int get() = radius

    init {
        // 첫 쪽이 다 차지 않았으면 가까운 반경은 이미 다 본 것이다.
        if (initial.size < pageSize) widen()
    }

    /**
     * 다음 장소들을 받아 붙인다. 새로 붙은 곳 수를 돌려준다(0이면 [done]).
     * 받은 쪽이 모두 이미 있는 곳이면(반경을 넓힌 직후) 새 곳이 나올 때까지 다음 쪽을 이어 받는다.
     */
    override suspend fun loadMore(): Int {
        while (!done) {
            val page = fetch(radius, nextPage)
            nextPage++
            val known = _places.mapTo(HashSet()) { it.id }
            val fresh = page.filter { it.id !in known }.distinctBy { it.id }
            _places += fresh
            if (page.size < pageSize) widen()
            if (fresh.isNotEmpty()) return fresh.size
        }
        return 0
    }

    private fun widen() {
        if (radius < FAR_RADIUS) {
            radius = FAR_RADIUS
            nextPage = 1
        } else {
            done = true
        }
    }

    companion object {
        const val NEAR_RADIUS = 10_000
        const val FAR_RADIUS = 20_000
    }
}
