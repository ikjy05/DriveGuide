package zone.dal.driveguide.tour

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPagerTest {

    private fun place(n: Int) = Place("p$n", PlaceType.ATTRACTION, "장소$n", "", 37.0, 127.0, n * 100.0, null)

    /** 거리순 장소 [near]곳이 10km 안, [far]곳까지가 20km 안에 있는 가짜 API. 호출을 기록한다. */
    private class FakeApi(val near: Int, val far: Int, val pageSize: Int = 3) {
        val calls = mutableListOf<Pair<Int, Int>>()
        suspend fun fetch(radius: Int, page: Int): List<Place> {
            calls += radius to page
            val total = if (radius <= NearbyPager.NEAR_RADIUS) near else far
            val from = (page - 1) * pageSize
            return (from until minOf(from + pageSize, total)).map { Place("p$it", PlaceType.ATTRACTION, "장소$it", "", 37.0, 127.0, it * 100.0, null) }
        }
    }

    @Test
    fun continuesNearRadiusThenWidensWithoutDuplicates() = runBlocking {
        val api = FakeApi(near = 5, far = 8)
        val pager = NearbyPager((0 until 3).map(::place), pageSize = 3, fetch = api::fetch)

        assertEquals(2, pager.loadMore()) // 10km 2쪽: p3, p4 (덜 찼으니 20km로 넓힘)
        assertEquals(NearbyPager.FAR_RADIUS, pager.radiusMeters)
        assertEquals(1, pager.loadMore()) // 20km 1쪽은 이미 본 곳뿐이라 건너뛰고, 2쪽(p3..p5)에서 새 곳 p5
        assertEquals(2, pager.loadMore()) // 20km 3쪽: p6, p7 (덜 찼으니 끝)
        assertEquals((0 until 8).map { "p$it" }, pager.places.map { it.id })
        assertTrue(pager.done)
        assertEquals(0, pager.loadMore())
        assertEquals(listOf(10_000 to 2, 20_000 to 1, 20_000 to 2, 20_000 to 3), api.calls)
    }

    @Test
    fun shortFirstPageStartsAtWideRadius() = runBlocking {
        val api = FakeApi(near = 2, far = 4)
        val pager = NearbyPager((0 until 2).map(::place), pageSize = 3, fetch = api::fetch)
        assertEquals(NearbyPager.FAR_RADIUS, pager.radiusMeters)
        assertEquals(1, pager.loadMore()) // 20km 1쪽(p0..p2)에서 새 곳 p2
        assertEquals(1, pager.loadMore()) // 20km 2쪽: p3 (덜 찼으니 끝)
        assertEquals(listOf(20_000 to 1, 20_000 to 2), api.calls)
        assertTrue(pager.done)
    }

    @Test
    fun nothingMoreWithinWideRadiusEndsCleanly() = runBlocking {
        val api = FakeApi(near = 3, far = 3)
        val pager = NearbyPager((0 until 3).map(::place), pageSize = 3, fetch = api::fetch)
        assertFalse(pager.done)
        assertEquals(0, pager.loadMore())
        assertTrue(pager.done)
        assertEquals(3, pager.places.size)
    }
}
