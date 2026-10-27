package com.example.driveguide.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.driveguide.navi.NaviBridge
import com.example.driveguide.tour.Place
import com.example.driveguide.tour.PlaceType

/**
 * 헤드업 알림의 "경유지 추가" 처리.
 * 화면이 없어도 동작하도록 NaviHelper에 따로 연결해 경유지를 넣고 바로 해제한다.
 */
class AddWaypointReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val place = Place(
            id = intent.getStringExtra(EXTRA_ID) ?: return,
            type = PlaceType.ATTRACTION,
            title = intent.getStringExtra(EXTRA_TITLE) ?: return,
            address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
            latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0),
            longitude = intent.getDoubleExtra(EXTRA_LNG, 0.0),
            distanceMeters = 0.0,
            imageUrl = null,
        )
        context.getSystemService(NotificationManager::class.java)
            .cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))

        val pending = goAsync()
        NaviBridge.addWaypointOnce(context, place) { success ->
            Log.i(TAG, "Waypoint from notification: ${place.title} success=$success")
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "AddWaypointReceiver"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_NOTIFICATION_ID = "notificationId"

        fun intent(context: Context, place: Place, notificationId: Int): Intent =
            Intent(context, AddWaypointReceiver::class.java)
                .putExtra(EXTRA_ID, place.id)
                .putExtra(EXTRA_TITLE, place.title)
                .putExtra(EXTRA_ADDRESS, place.address)
                .putExtra(EXTRA_LAT, place.latitude)
                .putExtra(EXTRA_LNG, place.longitude)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}
