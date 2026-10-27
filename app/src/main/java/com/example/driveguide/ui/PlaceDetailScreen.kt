package com.example.driveguide.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.driveguide.R
import com.example.driveguide.tour.CourseStop
import com.example.driveguide.tour.Place
import com.example.driveguide.tour.PlaceDetail
import com.example.driveguide.tour.PlaceType
import com.example.driveguide.tour.TourApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 장소 상세 화면. 사진을 창 전체에 깔고 그 위에 정보를 얹는다.
 * Activity 콘텐츠 위에 덮어 띄우고(addContentView), 분할 화면 전환 시
 * [reattach]로 새 창 폭에 맞는 레이아웃(layout / layout-w1200dp)으로 다시 그린다.
 */
class PlaceDetailScreen(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val tourApi: TourApiClient,
    private val actions: Actions,
) {
    interface Actions {
        fun addWaypoint(place: Place)
        fun setDestination(place: Place)
        fun speak(text: String)
    }

    private var place: Place? = null
    private var distanceLabel = ""
    private var detail: PlaceDetail? = null
    private var selectedImage = 0
    private var restricted = false
    private var view: View? = null
    private var loadJob: Job? = null

    val isShowing get() = view != null

    fun show(place: Place, distanceLabel: String) {
        this.place = place
        this.distanceLabel = distanceLabel
        detail = null
        selectedImage = 0
        attach()
        load(place)
    }

    fun close() {
        loadJob?.cancel()
        (view?.parent as? ViewGroup)?.removeView(view)
        view = null
        place = null
    }

    /** setContentView로 상세 뷰가 사라진 뒤(분할 화면 전환) 다시 붙인다. */
    fun reattach() {
        if (place != null) attach()
    }

    /** 주행 중이면 긴 소개글을 숨기고 읽어주기만 남긴다. */
    fun setRestricted(restricted: Boolean) {
        this.restricted = restricted
        view?.let(::applyRestriction)
    }

    private fun attach() {
        val place = place ?: return
        (view?.parent as? ViewGroup)?.removeView(view)
        val v = LayoutInflater.from(activity).inflate(R.layout.view_place_detail, null)
        activity.addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v

        v.findViewById<TextView>(R.id.title).text = place.title
        v.findViewById<TextView>(R.id.subtitle).text =
            if (place.address.isBlank()) distanceLabel else "$distanceLabel · ${place.address}"
        v.findViewById<TextView>(R.id.badge).text = place.type.label
        v.findViewById<View>(R.id.backButton).setOnClickListener { close() }
        v.findViewById<Button>(R.id.btnWaypoint).setOnClickListener { actions.addWaypoint(place) }
        v.findViewById<Button>(R.id.btnDestination).setOnClickListener { actions.setDestination(place) }
        v.findViewById<View>(R.id.btnCall).visibility = View.GONE

        val detail = detail
        if (detail != null) bindDetail(v, place, detail) else showHero(v, place.imageUrl)
        applyRestriction(v)
    }

    private fun load(place: Place) {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val d = tourApi.detail(place)
                if (this@PlaceDetailScreen.place != place) return@launch
                detail = d
                view?.let { bindDetail(it, place, d) }
            } catch (e: Exception) {
                Log.e(TAG, "Detail load failed", e)
                view?.let {
                    it.findViewById<View>(R.id.loading).visibility = View.GONE
                    it.findViewById<TextView>(R.id.errorText).apply {
                        text = "상세 정보를 불러오지 못했습니다."
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun bindDetail(v: View, place: Place, d: PlaceDetail) {
        v.findViewById<View>(R.id.loading).visibility = View.GONE

        showHero(v, d.images.getOrNull(selectedImage) ?: place.imageUrl)
        bindThumbs(v, d.images)
        bindChips(v, d)

        phoneNumber(d.tel)?.let { number ->
            v.findViewById<View>(R.id.btnCall).apply {
                visibility = View.VISIBLE
                setOnClickListener { dial(number) }
            }
        }

        d.overview?.let { overview ->
            v.findViewById<View>(R.id.overviewSection).visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.overviewText).text = overview
            v.findViewById<View>(R.id.btnRead).setOnClickListener { actions.speak("${place.title}. $overview") }
        }

        if (d.courseStops.isNotEmpty()) {
            v.findViewById<View>(R.id.courseSection).visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.courseTitle).text = listOfNotNull(
                "코스 구성 ${d.courseStops.size}곳",
                d.courseDistance?.let { "총 $it" },
                d.courseTakeTime?.let { "소요 $it" },
            ).joinToString(" · ")
            val container = v.findViewById<LinearLayout>(R.id.stopsContainer)
            container.removeAllViews()
            d.courseStops.forEachIndexed { index, stop -> container.addView(stopRow(container, index, stop)) }
        }
        applyRestriction(v)
    }

    private fun showHero(v: View, url: String?) {
        val image = v.findViewById<ImageView>(R.id.heroImage)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        // 사진이 창 전체를 채우므로 화면 폭 기준으로 디코딩한다.
        ImageLoader.load(scope, image, url, activity.resources.displayMetrics.widthPixels) {
            image.scaleType = ImageView.ScaleType.CENTER
            image.setImageResource(R.drawable.ic_place_large)
        }
    }

    private fun bindThumbs(v: View, images: List<String>) {
        v.findViewById<View>(R.id.thumbScroll).visibility = if (images.size > 1) View.VISIBLE else View.GONE
        val container = v.findViewById<LinearLayout>(R.id.thumbContainer)
        container.removeAllViews()
        if (images.size <= 1) return
        val density = activity.resources.displayMetrics.density
        images.forEachIndexed { index, url ->
            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((160 * density).toInt(), (90 * density).toInt())
                    .apply { marginEnd = (10 * density).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.card_bg)
                clipToOutline = true
                foreground = if (index == selectedImage) activity.getDrawable(R.drawable.thumb_selected_bg) else null
                contentDescription = "사진 ${index + 1}"
                setOnClickListener {
                    selectedImage = index
                    showHero(v, url)
                    bindThumbs(v, images)
                }
            }
            ImageLoader.load(scope, thumb, url, (160 * density).toInt()) {}
            container.addView(thumb)
        }
    }

    /** 이용 시간·휴무·주차·코스 거리 같은 핵심 정보를 한 줄짜리 칩으로 보여준다. */
    private fun bindChips(v: View, d: PlaceDetail) {
        val chips = listOfNotNull(
            d.useTime?.let { "이용 ${firstLine(it)}" },
            d.restDate?.let { "휴무 ${firstLine(it)}" },
            d.parking?.let { "주차 ${firstLine(it)}" },
            d.courseDistance?.let { "총 $it" },
            d.courseTakeTime?.let { "소요 $it" },
        )
        val container = v.findViewById<LinearLayout>(R.id.chipContainer)
        container.removeAllViews()
        val density = activity.resources.displayMetrics.density
        chips.forEach { text ->
            container.addView(TextView(activity).apply {
                this.text = text
                textSize = 22f
                maxLines = 1
                // 사진 위에 얹으므로 테마와 무관하게 흰 글씨·반투명 바탕을 쓴다.
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.photo_chip_bg)
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (10 * density).toInt() }
            })
        }
    }

    private fun stopRow(parent: ViewGroup, index: Int, stop: CourseStop): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_course_stop, parent, false)
        row.findViewById<View>(R.id.stopThumbFrame).clipToOutline = true
        row.findViewById<TextView>(R.id.stopNumber).text = "${index + 1}"
        row.findViewById<TextView>(R.id.stopName).text = stop.name
        val image = row.findViewById<ImageView>(R.id.stopImage)
        ImageLoader.load(scope, image, stop.imageUrl, (128 * activity.resources.displayMetrics.density).toInt()) {
            image.scaleType = ImageView.ScaleType.CENTER
            image.setImageResource(R.drawable.ic_place)
        }
        row.findViewById<Button>(R.id.stopWaypoint).setOnClickListener {
            // detailInfo2에는 좌표가 없어서 누를 때 조회한다.
            scope.launch {
                val stopPlace = runCatching { tourApi.placeById(stop.contentId) }.getOrNull()
                if (stopPlace != null) {
                    actions.addWaypoint(stopPlace)
                } else {
                    Toast.makeText(activity, "${stop.name}의 위치를 찾지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return row
    }

    private fun applyRestriction(v: View) {
        val hasOverview = detail?.overview != null
        v.findViewById<View>(R.id.overviewText).visibility = if (hasOverview && !restricted) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.drivingNotice).visibility = if (hasOverview && restricted) View.VISIBLE else View.GONE
    }

    private fun dial(number: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "전화를 걸 수 없습니다: $number", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "PlaceDetailScreen"
        private val PHONE = Regex("""\d{2,4}-\d{3,4}-\d{4}""")

        /** "한국농어촌공사 화성·수원지사 031-240-4800" → "031-240-4800" */
        fun phoneNumber(text: String?): String? = text?.let { PHONE.find(it)?.value }

        private fun firstLine(text: String) = text.lineSequence().first().trim()
    }
}
