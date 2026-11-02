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
import android.widget.HorizontalScrollView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 장소 상세 화면. 사진을 창 전체에 깔고 그 위에 정보를 얹는다.
 * 좌우 스와이프(또는 오른쪽 위 화살표)로 같은 목록의 이전·다음 장소로 넘긴다.
 * 정차 중에는 사진이 5초마다 바뀌고, 주행 중에는 멈춘다. 사진 부분을 탭하면 전체 보기([PhotoViewer]).
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
        /** 현재 위치 기준 거리 문구(예: "2.3km", "출발지까지 4.1km"). */
        fun distanceLabel(place: Place): String
        /** 차 진행 방향 기준 상대 각도. 모르면 null. */
        fun relativeDirection(place: Place): Double?
    }

    private var places: List<Place> = emptyList()
    private var index = 0
    private val place: Place? get() = places.getOrNull(index)
    private var slideshowJob: Job? = null
    private val photoViewer = PhotoViewer(activity, scope)
    private var detail: PlaceDetail? = null
    private var selectedImage = 0
    private var restricted = false
    private var view: View? = null
    private var loadJob: Job? = null

    val isShowing get() = view != null

    /** [places] 중 [index]번째 장소를 연다. 스와이프는 이 목록 안에서 움직인다. */
    fun show(places: List<Place>, index: Int) {
        this.places = places
        this.index = index.coerceIn(0, places.lastIndex)
        openCurrent(slideFrom = 0)
    }

    /** 뒤로 키: 사진 전체 보기 → 상세 순서로 닫는다. 닫은 게 있으면 true. */
    fun onBack(): Boolean = when {
        photoViewer.isShowing -> { photoViewer.close(); true }
        isShowing -> { close(); true }
        else -> false
    }

    fun close() {
        if (photoViewer.isShowing) photoViewer.close()
        loadJob?.cancel()
        slideshowJob?.cancel()
        (view?.parent as? ViewGroup)?.removeView(view)
        view = null
        places = emptyList()
    }

    /** +1 다음, -1 이전 장소로 넘긴다. 새 화면은 넘긴 방향에서 밀려 들어온다. */
    private fun move(step: Int) {
        val target = index + step
        if (target !in places.indices) return
        index = target
        openCurrent(slideFrom = step)
    }

    private fun openCurrent(slideFrom: Int) {
        val place = place ?: return
        detail = null
        selectedImage = 0
        val old = view
        attach(keepOld = slideFrom != 0)
        if (slideFrom != 0 && old != null) {
            val w = (old.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels).toFloat()
            old.animate().translationX(-slideFrom * w).alpha(0.4f).setDuration(260)
                .withEndAction { (old.parent as? ViewGroup)?.removeView(old) }.start()
            view?.apply {
                translationX = slideFrom * w
                animate().translationX(0f).setDuration(260).start()
            }
        }
        load(place)
    }

    /** setContentView로 상세 뷰가 사라진 뒤(분할 화면 전환) 다시 붙인다. */
    fun reattach() {
        if (place != null) attach()
    }

    /** 위치가 바뀌면 거리·방향 문구를 갱신한다. */
    fun refreshLocation() {
        val v = view ?: return
        bindSubtitle(v, place ?: return)
    }

    private fun bindSubtitle(v: View, place: Place) {
        val rel = actions.relativeDirection(place)
        val distanceLabel = actions.distanceLabel(place)
        v.findViewById<ImageView>(R.id.direction).apply {
            visibility = if (rel != null) View.VISIBLE else View.GONE
            rotation = rel?.toFloat() ?: 0f
        }
        v.findViewById<TextView>(R.id.subtitle).text =
            listOfNotNull(distanceLabel, rel?.let(Direction::label), place.address.ifBlank { null }).joinToString(" · ")
    }

    /** 주행 중이면 긴 소개글을 숨기고 읽어주기만 남긴다. */
    fun setRestricted(restricted: Boolean) {
        this.restricted = restricted
        view?.let(::applyRestriction)
        restartSlideshow()
    }

    private fun attach(keepOld: Boolean = false) {
        val place = place ?: return
        if (!keepOld) (view?.parent as? ViewGroup)?.removeView(view)
        val v = LayoutInflater.from(activity).inflate(R.layout.view_place_detail, null)
        activity.addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
        bindPager(v)

        v.findViewById<TextView>(R.id.title).text = place.title
        bindSubtitle(v, place)
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
        restartSlideshow()

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
                    selectImage(v, images, index, fade = false)
                    restartSlideshow() // 직접 고른 사진은 다음 주기까지 보여준다.
                }
            }
            ImageLoader.load(scope, thumb, url, (160 * density).toInt()) {}
            container.addView(thumb)
        }
        // 슬라이드쇼로 넘어간 사진의 썸네일이 줄 밖에 있으면 보이도록 스크롤한다.
        val scroll = v.findViewById<HorizontalScrollView>(R.id.thumbScroll)
        scroll.post {
            val selected = container.getChildAt(selectedImage) ?: return@post
            if (selected.left < scroll.scrollX || selected.right > scroll.scrollX + scroll.width) {
                scroll.smoothScrollTo(selected.left, 0)
            }
        }
    }

    private fun selectImage(v: View, images: List<String>, index: Int, fade: Boolean) {
        selectedImage = index
        val image = v.findViewById<ImageView>(R.id.heroImage)
        if (fade) {
            image.animate().alpha(0.2f).setDuration(250).withEndAction {
                showHero(v, images[index])
                image.animate().alpha(1f).setDuration(450).start()
            }.start()
        } else {
            showHero(v, images[index])
        }
        bindThumbs(v, images)
    }

    /** 정차 중이고 사진이 여러 장이면 5초마다 다음 사진으로 넘긴다. 주행 중에는 움직이는 화면이 주의를 끌어 멈춘다. */
    private fun restartSlideshow() {
        slideshowJob?.cancel()
        val images = detail?.images ?: return
        if (restricted || images.size <= 1 || photoViewer.isShowing) return
        slideshowJob = scope.launch {
            while (true) {
                delay(SLIDESHOW_INTERVAL_MS)
                val v = view ?: return@launch
                selectImage(v, images, (selectedImage + 1) % images.size, fade = true)
            }
        }
    }

    /** 오른쪽 위 이전·다음 버튼, "3 / 10", 다음 장소 이름과 스와이프 연결. */
    private fun bindPager(v: View) {
        val swipe = v as SwipeFrameLayout
        swipe.onSwipe = ::move
        swipe.canSwipe = { step -> index + step in places.indices }
        swipe.excludedViews = listOf(v.findViewById(R.id.thumbScroll), v.findViewById(R.id.chipScroll))
        // 사진이 보이는 빈 곳을 탭하면 전체 보기. 정보 패널·버튼 위 탭은 제외한다.
        swipe.onTap = ::openPhotoViewer
        swipe.tapExcludedViews = listOfNotNull<View>(
            v.findViewById(R.id.sheet), v.findViewById(R.id.header), v.findViewById(R.id.bodyPanel),
            v.findViewById(R.id.pager), v.findViewById(R.id.backButton),
        )

        v.findViewById<View>(R.id.pager).visibility = if (places.size > 1) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.pagerText).text = "${index + 1} / ${places.size}"
        v.findViewById<View>(R.id.prevButton).apply {
            isEnabled = index > 0
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { move(-1) }
        }
        v.findViewById<View>(R.id.nextButton).apply {
            isEnabled = index < places.lastIndex
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { move(1) }
        }
        v.findViewById<TextView>(R.id.nextName).text = places.getOrNull(index + 1)?.let { "다음 · ${it.title}" } ?: ""
    }

    private fun openPhotoViewer() {
        val place = place ?: return
        val images = detail?.images?.takeIf { it.isNotEmpty() } ?: listOfNotNull(place.imageUrl)
        if (images.isEmpty()) return
        slideshowJob?.cancel()
        photoViewer.show(images, selectedImage.coerceIn(0, images.lastIndex), place.title) { last ->
            // 전체 보기에서 마지막으로 본 사진을 상세에도 이어서 보여준다.
            view?.let { v ->
                if (images.size > 1) selectImage(v, images, last, fade = false)
            }
            restartSlideshow()
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
                textSize = 26f
                maxLines = 1
                // 사진 위에 얹으므로 테마와 무관하게 흰 글씨·반투명 바탕을 쓴다.
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.photo_chip_bg)
                setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
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
        private const val SLIDESHOW_INTERVAL_MS = 5_000L
        private val PHONE = Regex("""\d{2,4}-\d{3,4}-\d{4}""")

        /** "한국농어촌공사 화성·수원지사 031-240-4800" → "031-240-4800" */
        fun phoneNumber(text: String?): String? = text?.let { PHONE.find(it)?.value }

        private fun firstLine(text: String) = text.lineSequence().first().trim()
    }
}
