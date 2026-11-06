package zone.dal.driveguide.saved

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import zone.dal.driveguide.tour.Place

/** 헤드업 알림의 "나중에 보기" 처리. 앱 화면이 내비 뒤에 있어도 저장되고, 저장 탭에는 바로 반영된다. */
class SavePlaceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val place = SavedPlaces.decodePlace(intent.getStringExtra(EXTRA_PLACE) ?: return)
        SavedPlaces(context).add(place)
        context.getSystemService(NotificationManager::class.java)
            .cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        Toast.makeText(context, "${place.title}을(를) 저장했습니다", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_PLACE = "place"
        private const val EXTRA_NOTIFICATION_ID = "notificationId"

        fun intent(context: Context, place: Place, notificationId: Int): Intent =
            Intent(context, SavePlaceReceiver::class.java)
                .putExtra(EXTRA_PLACE, SavedPlaces.encodePlace(place))
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}
