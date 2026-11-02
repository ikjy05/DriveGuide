package zone.dal.driveguide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionTest {

    // 성남 시흥동 근처
    private val lat = 37.4418
    private val lng = 127.1376

    @Test
    fun bearingToCardinalPoints() {
        assertEquals(0.0, bearingDegrees(lat, lng, lat + 0.01, lng), 0.5)
        assertEquals(90.0, bearingDegrees(lat, lng, lat, lng + 0.01), 0.5)
        assertEquals(180.0, bearingDegrees(lat, lng, lat - 0.01, lng), 0.5)
        assertEquals(270.0, bearingDegrees(lat, lng, lat, lng - 0.01), 0.5)
    }

    @Test
    fun relativeToHeading() {
        // 동쪽으로 달리는 중, 남동쪽 장소 → 오른쪽 앞
        val southEast = Direction.relative(90.0, lat, lng, lat - 0.01, lng + 0.0126)
        assertEquals(45.0, southEast, 2.0)
        assertEquals("오른쪽 앞", Direction.label(southEast))
        // 북쪽으로 달리는 중, 서쪽 장소 → 왼쪽
        assertEquals("왼쪽", Direction.label(Direction.relative(0.0, lat, lng, lat, lng - 0.01)))
    }

    @Test
    fun wrapsAroundNorth() {
        // 진행 350°, 장소 10° → 오른쪽으로 20°
        val north = Direction.relative(350.0, lat, lng, lat + 0.01, lng + 0.0022)
        assertEquals(20.0, north, 2.0)
        assertEquals("앞", Direction.label(north))
    }

    @Test
    fun behindDetection() {
        assertTrue(Direction.isBehind(170.0))
        assertTrue(Direction.isBehind(-150.0))
        assertTrue(Direction.isBehind(-125.0)) // 왼쪽 뒤
        assertFalse(Direction.isBehind(100.0)) // 오른쪽
        assertEquals("뒤", Direction.label(-179.0))
        assertEquals("왼쪽 뒤", Direction.label(-135.0))
    }
}
