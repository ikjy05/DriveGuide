package zone.dal.driveguide.ui

import android.widget.ImageView
import zone.dal.driveguide.R
import zone.dal.driveguide.tour.PlaceType

/**
 * 사진이 없거나 받지 못한 장소에 종류별 일러스트를 보여 준다(앱 아이콘과 같은 화풍, 사진처럼 보이지 않게).
 * 그림은 tools/icon/gen_placeholder.py로 만든다.
 */
object Placeholder {
    fun show(view: ImageView, type: PlaceType) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageResource(
            if (type == PlaceType.COURSE) R.drawable.placeholder_course else R.drawable.placeholder_attraction
        )
    }
}
