package com.example.driveguide.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.driveguide.route.RouteState
import com.example.driveguide.route.RouteTarget
import com.example.driveguide.tour.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
)

/**
 * Pleos NaviHelper SDK 래퍼.
 * NaviHelper는 요청(get*Info)과 응답(on*Info 콜백)이 분리되어 있으므로
 * 현재 위치와 경로(목적지·남은 경유지)를 주기적으로 요청하고 콜백 결과를 StateFlow로 노출한다.
 */
class NaviBridge(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)
    private var pollJob: Job? = null

    private val _location = MutableStateFlow<VehicleLocation?>(null)
    val location: StateFlow<VehicleLocation?> = _location.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 안내 중인 경로. 경로가 없으면 null. */
    private val _route = MutableStateFlow<RouteState?>(null)
    val route: StateFlow<RouteState?> = _route.asStateFlow()

    // 목적지와 경유지는 서로 다른 콜백으로 오므로 따로 보관했다가 합친다.
    @Volatile private var destination: RouteTarget? = null
    @Volatile private var waypoints: List<RouteTarget> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _location.value = VehicleLocation(
                latitude = currentLocationInfo.latitude,
                longitude = currentLocationInfo.longitude,
                address = currentLocationInfo.address,
            )
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination = destinationInfo.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let {
                RouteTarget(it.latitude, it.longitude, it.name, it.remainDistance)
            }
            publishRoute()
        }

        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
            waypoints = waypointInfo
                .mapNotNull { w ->
                    val (lat, lng) = if (w.latitude != 0.0 || w.longitude != 0.0) {
                        w.latitude to w.longitude
                    } else {
                        addedWaypoints[w.name] ?: return@mapNotNull null
                    }
                    RouteTarget(lat, lng, w.name, w.remainDistance)
                }
                .sortedBy { it.remainDistanceMeters }
            publishRoute()
        }

        // 경로가 바뀌는 이벤트에서는 주기를 기다리지 않고 바로 다시 조회한다.
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = requestRoute()
        override fun onRoutingCompleted() = requestRoute()
        override fun onReRouteEnded() = requestRoute()
        override fun onDestinationChanged() = requestRoute()
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = requestRoute()

        override fun onRouteCancelled() = clearRoute()
        override fun onRouteEnded() = clearRoute()

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "Navi error: $errorCode")
            _error.value = "내비 오류: $errorCode"
        }
    }

    fun start(scope: CoroutineScope, intervalMillis: Long = 10_000) {
        naviHelper.initialize(
            onConnected = {
                naviHelper.addListener(listener)
                pollJob = scope.launch {
                    var tick = 0
                    while (isActive) {
                        naviHelper.getCurrentLocationInfo()
                        // 남은 거리 갱신용. 경로 변경은 이벤트로도 즉시 반영된다.
                        if (tick++ % ROUTE_POLL_EVERY == 0) requestRoute()
                        delay(intervalMillis)
                    }
                }
            },
            onFailed = { t ->
                Log.e(TAG, "NaviHelper connection failed", t)
                _error.value = "내비 서비스 연결 실패: ${t.message}"
            },
        )
    }

    fun stop() {
        pollJob?.cancel()
        naviHelper.removeListener(listener)
        naviHelper.release()
    }

    private fun requestRoute() {
        naviHelper.getDestinationInfo()
        naviHelper.getWaypointInfo()
    }

    private fun clearRoute() {
        destination = null
        waypoints = emptyList()
        _route.value = null
    }

    private fun publishRoute() {
        _route.value = destination?.let { RouteState(waypoints, it) }
    }

    /** 추천 장소를 첫 번째 경유지로 추가한다. 결과는 내비 화면/onWaypointChanged로 반영된다. */
    fun addAsWaypoint(place: Place) {
        naviHelper.addWaypoint(waypointRequest(place))
    }

    /** 장소를 목적지로 설정해 바로 안내를 시작한다. */
    fun setDestination(place: Place) {
        naviHelper.requestRoute(
            RouteInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                displayLatitude = null,
                displayLongitude = null,
                poiName = place.title,
                poiId = "",
                address = place.address,
                poiSubId = "0",
            )
        )
    }

    companion object {
        private const val TAG = "NaviBridge"
        private const val ROUTE_POLL_EVERY = 3

        // 내비가 경유지 좌표를 (0, 0)으로 돌려주는 경우가 있어, 앱에서 추가한 경유지의 좌표를 이름으로 기억해 둔다.
        // 알림에서 추가한 경유지도 같은 프로세스에서 처리되므로 프로세스 전역으로 둔다.
        private val addedWaypoints = ConcurrentHashMap<String, Pair<Double, Double>>()

        private fun waypointRequest(place: Place): RequestWaypointInfo {
            addedWaypoints[place.title] = place.latitude to place.longitude
            return RequestWaypointInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                poiName = place.title,
                waypointIndex = WaypointIndex.FIRST,
                poiId = "", // TourAPI contentid는 내비 POI ID가 아니므로 좌표 기반으로 요청
                address = place.address,
                poiSubId = "0",
            )
        }

        /** 화면 없이(알림 버튼 등) 경유지 하나를 추가한다. 별도 연결을 맺고 요청 후 해제한다. */
        fun addWaypointOnce(context: Context, place: Place, onDone: (Boolean) -> Unit) {
            val helper = NaviHelper(context.applicationContext)
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                // 요청이 전달될 시간을 두고 해제한다.
                handler.postDelayed({ helper.release(); onDone(success) }, if (success) 1_000 else 0)
            }
            helper.initialize(
                onConnected = {
                    helper.addWaypoint(waypointRequest(place))
                    finish(true)
                },
                onFailed = { t ->
                    Log.e(TAG, "NaviHelper connection failed", t)
                    finish(false)
                },
            )
            // BroadcastReceiver.goAsync()의 제한 시간 안에 끝낸다.
            handler.postDelayed({ finish(false) }, 8_000)
        }
    }
}
