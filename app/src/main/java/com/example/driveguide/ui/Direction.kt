package com.example.driveguide.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 출발점에서 도착점을 바라보는 방위각(북쪽 0°, 시계방향, 0~360). */
fun bearingDegrees(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
    val phi1 = Math.toRadians(fromLat)
    val phi2 = Math.toRadians(toLat)
    val dLng = Math.toRadians(toLng - fromLng)
    val y = sin(dLng) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * 차 기준 방향. 도로를 따라가는 방향이 아니라 직선 방향이므로 대략적인 안내용이다.
 */
object Direction {

    /** 차 진행 방향 기준 상대 각도(-180~180, 오른쪽이 +). */
    fun relative(heading: Double, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val diff = bearingDegrees(fromLat, fromLng, toLat, toLng) - heading
        return ((diff % 360) + 540) % 360 - 180
    }

    /** 8방향 문구. */
    fun label(relative: Double): String = when (((relative + 360 + 22.5) % 360 / 45).toInt()) {
        0 -> "앞"
        1 -> "오른쪽 앞"
        2 -> "오른쪽"
        3 -> "오른쪽 뒤"
        4 -> "뒤"
        5 -> "왼쪽 뒤"
        6 -> "왼쪽"
        else -> "왼쪽 앞"
    }

    /** 이미 지나친 쪽(뒤쪽 90° 범위). */
    fun isBehind(relative: Double): Boolean = abs(relative) > 135
}
