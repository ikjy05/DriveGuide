package zone.dal.driveguide.tour

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePagerTest {

    private fun course(n: Int) = Place("c$n", PlaceType.COURSE, "코스$n", "", 37.0, 127.0, n * 1000.0, null)

    /** 가까운 순 코스 [total]개가 반경 안에 있는 가짜 저장소. */
    private class FakeRepo(val total: Int) {
        val calls = mutableListOf<Pair<Int, Int>>()
        suspend fun page(offset: Int, limit: Int): List<Place> {
            calls += offset to limit
            return (offset until minOf(offset + limit, total)).map {
                Place("c$it", PlaceType.COURSE, "코스$it", "", 37.0, 127.0, it * 1000.0, null)
            }
        }
    }

    @Test
    fun appendsNextPagesUntilRadiusIsExhausted() = runBlocking {
        val repo = FakeRepo(total = 7)
        val pager = CoursePager((0 until 3).map(::course), pageSize = 3, fetch = repo::page)
        assertFalse(pager.done)
        assertEquals(3, pager.loadMore())
        assertEquals(1, pager.loadMore()) // 마지막 쪽은 1개뿐
        assertTrue(pager.done)
        assertEquals((0 until 7).map { "c$it" }, pager.places.map { it.id })
        assertEquals(listOf(3 to 3, 6 to 3), repo.calls)
        assertEquals(0, pager.loadMore())
    }

    @Test
    fun shortFirstPageMeansNothingMore() = runBlocking {
        val repo = FakeRepo(total = 2)
        val pager = CoursePager((0 until 2).map(::course), pageSize = 3, fetch = repo::page)
        assertTrue(pager.done)
        assertEquals(0, pager.loadMore())
        assertTrue(repo.calls.isEmpty())
    }
}
