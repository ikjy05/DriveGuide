package zone.dal.driveguide.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import zone.dal.driveguide.route.RouteState
import zone.dal.driveguide.route.RouteTarget
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.distanceMeters
import zone.dal.driveguide.ui.bearingDegrees
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
    /** 진행 방향(북쪽 0°, 시계방향). 정차 등으로 알 수 없으면 null. */
    val heading: Double? = null,
    /** 내비에서 위치를 받지 못해 기본 위치([NaviBridge.DEFAULT_LOCATION])를 쓰는 중이면 true. */
    val isFallback: Boolean = false,
)

/** 코스 안내 한 구간. [remaining]은 이 구간 뒤에 남은 장소 수. */
data class CourseLeg(val stops: List<Place>, val remaining: Int)

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

    // 진행 방향 보정용: 마지막으로 의미 있게 이동한 위치와 그때의 방향.
    private var lastMoveLat: Double? = null
    private var lastMoveLng: Double? = null
    private var lastHeading: Double? = null

    /** 코스 안내에서 아직 보내지 않은 장소들. 지금 구간의 목적지에 도착하면 다음 구간으로 이어서 보낸다. */
    @Volatile private var pendingCourseStops: List<Place> = emptyList()
    /** 지금 안내 중인 코스 구간의 목적지. 사용자가 내비에서 다른 목적지로 바꾸면 코스 안내를 멈추는 데 쓴다. */
    @Volatile private var courseLegEnd: Place? = null

    /** 코스 구간 안내를 시작할 때 부른다(남은 구간이 이어질 때 포함). 메인 스레드가 아닐 수 있다. */
    var onCourseLeg: ((CourseLeg) -> Unit)? = null

    // 목적지와 경유지는 서로 다른 콜백으로 오므로 따로 보관했다가 합친다.
    @Volatile private var destination: RouteTarget? = null
    @Volatile private var waypoints: List<RouteTarget> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val lat = currentLocationInfo.latitude
            val lng = currentLocationInfo.longitude
            if (lat == 0.0 && lng == 0.0) return // 아직 측위 전
            _location.value = VehicleLocation(lat, lng, currentLocationInfo.address, headingFor(lat, lng, currentLocationInfo.heading))
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

        override fun onRouteCancelled() {
            stopCourse()
            clearRoute()
        }

        override fun onRouteEnded() {
            clearRoute()
            // 코스 구간 목적지에 도착했으면 남은 장소로 이어서 안내한다.
            if (pendingCourseStops.isNotEmpty()) sendCourseLeg(pendingCourseStops)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "Navi error: $errorCode")
            _error.value = "내비 오류: $errorCode"
        }
    }

    fun start(scope: CoroutineScope, intervalMillis: Long = 10_000) {
        // 내비가 위치를 주지 않으면(내비 미실행·연결 실패 등) 기본 위치로 추천을 보여준다.
        // 실제 위치가 오면 그 값으로 바로 바뀐다.
        scope.launch {
            delay(FALLBACK_DELAY_MILLIS)
            if (_location.value == null) {
                Log.w(TAG, "No location from navi; using default location")
                _location.value = DEFAULT_LOCATION
            }
        }
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

    /**
     * 내비 heading은 정차 중 0으로 오기도 한다. 0이 아니면 그대로 쓰고,
     * 0이면 이전 위치에서 30m 이상 움직였을 때 이동 방향을 계산한다. 정차 중에는 마지막 방향을 유지한다.
     */
    private fun headingFor(lat: Double, lng: Double, reported: Double): Double? {
        val prevLat = lastMoveLat
        val prevLng = lastMoveLng
        val moved = prevLat != null && prevLng != null && distanceMeters(prevLat, prevLng, lat, lng) >= 30
        if (prevLat == null || moved) {
            if (moved && reported == 0.0) lastHeading = bearingDegrees(prevLat!!, prevLng!!, lat, lng)
            lastMoveLat = lat
            lastMoveLng = lng
        }
        if (reported != 0.0) lastHeading = reported
        return lastHeading
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
        val dest = destination
        // 내비에서 다른 목적지로 바꿨으면 코스 이어가기를 멈춘다.
        courseLegEnd?.let { end ->
            if (dest != null && distanceMeters(end.latitude, end.longitude, dest.latitude, dest.longitude) > COURSE_MATCH_METERS) stopCourse()
        }
        _route.value = dest?.let { RouteState(waypoints, it) }
    }

    /**
     * 코스 장소들을 순서대로 안내한다. 내비는 경유지를 2곳까지만 받으므로 3곳씩(경유지 2 + 목적지 1) 끊어 보내고,
     * 구간 목적지에 도착하면 다음 3곳을 이어서 보낸다.
     */
    fun startCourse(stops: List<Place>) {
        if (stops.isEmpty()) return
        sendCourseLeg(stops)
    }

    private fun sendCourseLeg(stops: List<Place>) {
        val leg = stops.take(COURSE_STOPS_PER_LEG)
        val end = leg.last()
        pendingCourseStops = stops.drop(leg.size)
        courseLegEnd = end
        naviHelper.requestRouteWithWaypoints(
            RouteWithWaypointsRequest(
                destination = routeInfo(end),
                waypoints = leg.dropLast(1).mapIndexed { i, p ->
                    waypointRequest(p, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND)
                },
            )
        )
        onCourseLeg?.invoke(CourseLeg(leg, pendingCourseStops.size))
    }

    private fun stopCourse() {
        pendingCourseStops = emptyList()
        courseLegEnd = null
    }

    /** 추천 장소를 첫 번째 경유지로 추가한다. 결과는 내비 화면/onWaypointChanged로 반영된다. */
    fun addAsWaypoint(place: Place) {
        naviHelper.addWaypoint(waypointRequest(place))
    }

    /** 장소를 목적지로 설정해 바로 안내를 시작한다. */
    fun setDestination(place: Place) {
        stopCourse()
        naviHelper.requestRoute(routeInfo(place))
    }

    private fun routeInfo(place: Place) = RouteInfo(
        latitude = place.latitude,
        longitude = place.longitude,
        displayLatitude = null,
        displayLongitude = null,
        poiName = place.title,
        poiId = "",
        address = place.address,
        poiSubId = "0",
    )

    companion object {
        /** 내비에서 위치를 받지 못할 때 쓰는 기본 위치: 현대자동차 본사(서울 서초구 헌릉로 12, 양재동). */
        val DEFAULT_LOCATION = VehicleLocation(37.4632, 127.0427, "서울특별시 서초구 양재동", isFallback = true)
        private const val FALLBACK_DELAY_MILLIS = 15_000L
        private const val TAG = "NaviBridge"
        private const val ROUTE_POLL_EVERY = 3
        /** 경유지 2곳 + 목적지 1곳. NaviHelper의 WaypointIndex가 FIRST·SECOND뿐이다. */
        const val COURSE_STOPS_PER_LEG = 3
        private const val COURSE_MATCH_METERS = 300.0

        // 내비가 경유지 좌표를 (0, 0)으로 돌려주는 경우가 있어, 앱에서 추가한 경유지의 좌표를 이름으로 기억해 둔다.
        // 알림에서 추가한 경유지도 같은 프로세스에서 처리되므로 프로세스 전역으로 둔다.
        private val addedWaypoints = ConcurrentHashMap<String, Pair<Double, Double>>()

        private fun waypointRequest(place: Place, index: WaypointIndex = WaypointIndex.FIRST): RequestWaypointInfo {
            addedWaypoints[place.title] = place.latitude to place.longitude
            return RequestWaypointInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                poiName = place.title,
                waypointIndex = index,
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
