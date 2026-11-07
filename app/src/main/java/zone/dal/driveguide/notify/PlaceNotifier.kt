package zone.dal.driveguide.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import zone.dal.driveguide.MainActivity
import zone.dal.driveguide.R
import zone.dal.driveguide.saved.SavePlaceReceiver
import zone.dal.driveguide.tour.Place

/**
 * 내비가 전체 화면일 때도 보이도록 추천 장소를 헤드업 알림으로 띄운다.
 * 알림의 "경유지 추가" 버튼은 [AddWaypointReceiver], "즐겨찾기" 버튼은 [SavePlaceReceiver]가 처리한다.
 */
class PlaceNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifiedIds = mutableSetOf<String>()
    private var lastNotifiedAt = 0L

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "주변 추천 장소", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "지역 진입과 가는 길 추천을 헤드업 알림으로 표시합니다."
            }
        )
    }

    /**
     * 같은 장소는 한 번만, 알림 사이에는 최소 [minIntervalMillis] 간격을 둔다.
     * [force]는 지역 진입처럼 놓치면 안 되는 안내에 사용한다.
     */
    fun notify(title: String, place: Place, detail: String, force: Boolean = false, minIntervalMillis: Long = 3 * 60_000L) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (place.id in notifiedIds) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifiedAt < minIntervalMillis) return
        notifiedIds += place.id
        lastNotifiedAt = now

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val addWaypoint = PendingIntent.getBroadcast(
            context, place.id.hashCode(),
            AddWaypointReceiver.intent(context, place, NOTIFICATION_ID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val saveForLater = PendingIntent.getBroadcast(
            context, place.id.hashCode(),
            SavePlaceReceiver.intent(context, place, NOTIFICATION_ID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_place)
            .setContentTitle(title)
            .setContentText("${place.title} · $detail")
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_place), "경유지 추가", addWaypoint).build()
            )
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_star), "즐겨찾기", saveForLater).build()
            )
            .build()
        // 최신 추천 하나만 유지한다.
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "place_recommendation"
        const val NOTIFICATION_ID = 1
    }
}
