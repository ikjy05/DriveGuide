package com.example.driveguide.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
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
import kotlin.math.abs

/**
 * 장소 상세 화면. 사진을 창 전체에 깔고 그 위에 정보를 얹는다.
 * 이전·현재·다음 장소 화면을 빈틈 없이 나란히 두고, 좌우로 끌면 손가락을 1:1로 따라가다
 * 놓으면 남은 거리를 감속하며 넘긴다(왼쪽 위 화살표도 같은 동작). 이웃 장소의 상세 정보는 미리 불러 둔다.
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

    /** 상세 화면들을 담는 바탕. 검은 배경이라 넘기는 동안 뒤의 목록이 비치지 않는다. */
    private var host: FrameLayout? = null
    /** 0: 이전, 1: 현재, 2: 다음 장소 화면. 넘긴 뒤에는 돌려 써서 이미 그린 화면을 다시 만들지 않는다. */
    private val slots = arrayOfNulls<View>(3)
    private var offset = 0f
    private var animator: ValueAnimator? = null

    private val details = HashMap<String, PlaceDetail>()
    private val loading = HashMap<String, Job>()
    private var selectedImage = 0
    private var restricted = false
    private var slideshowJob: Job? = null
    private val photoViewer = PhotoViewer(activity, scope)

    val isShowing get() = host != null

    /** [places] 중 [index]번째 장소를 연다. 스와이프는 이 목록 안에서 움직인다. */
    fun show(places: List<Place>, index: Int) {
        this.places = places
        this.index = index.coerceIn(0, places.lastIndex)
        selectedImage = 0
        attach()
    }

    /** 뒤로 키: 사진 전체 보기 → 상세 순서로 닫는다. 닫은 게 있으면 true. */
    fun onBack(): Boolean = when {
        photoViewer.isShowing -> { photoViewer.close(); true }
        isShowing -> { close(); true }
        else -> false
    }

    fun close() {
        if (photoViewer.isShowing) photoViewer.close()
        animator?.cancel()
        slideshowJob?.cancel()
        loading.values.forEach { it.cancel() }
        loading.clear()
        (host?.parent as? ViewGroup)?.removeView(host)
        host = null
        slots.fill(null)
        places = emptyList()
    }

    /** setContentView로 상세 뷰가 사라진 뒤(분할 화면 전환) 다시 붙인다. */
    fun reattach() {
        if (places.isNotEmpty()) attach()
    }

    /** 위치가 바뀌면 거리·방향 문구를 갱신한다. */
    fun refreshLocation() {
        forEachSlot { v, p, _ -> bindSubtitle(v, p) }
    }

    /** 주행 중이면 긴 소개글을 숨기고 읽어주기만 남긴다. */
    fun setRestricted(restricted: Boolean) {
        this.restricted = restricted
        forEachSlot { v, p, _ -> applyRestriction(v, p) }
        restartSlideshow()
    }

    // ---- 화면 배치·넘김 ----

    private fun attach() {
        (host?.parent as? ViewGroup)?.removeView(host)
        val h = FrameLayout(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            // 창 폭이 정해지면(분할 전환 포함) 이웃 화면 위치를 다시 잡는다.
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) applyOffset() }
        }
        activity.addContentView(h, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host = h
        slots.fill(null)
        offset = 0f
        for (s in 0..2) buildSlot(s)
        applyOffset()
        becomeCurrent()
    }

    private fun buildSlot(s: Int) {
        val h = host ?: return
        slots[s]?.let { h.removeView(it) }
        slots[s] = null
        val p = places.getOrNull(index + s - 1) ?: return
        val v = LayoutInflater.from(activity).inflate(R.layout.view_place_detail, h, false)
        h.addView(v)
        slots[s] = v
        bindBasic(v, p)
        val d = details[p.id]
        if (d != null) bindDetail(v, p, d, selected = 0) else showHero(v, p.imageUrl)
        applyRestriction(v, p)
        ensureLoaded(p)
    }

    private fun applyOffset() {
        val w = (host?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels).toFloat()
        slots.forEachIndexed { s, v -> v?.translationX = (s - 1) * w + offset }
    }

    /** 가운데 화면에만 스와이프·탭·화살표를 연결하고 슬라이드쇼를 시작한다. */
    private fun becomeCurrent() {
        slots.forEachIndexed { s, v ->
            val swipe = v as? SwipeFrameLayout ?: return@forEachIndexed
            if (s == 1) bindPager(swipe) else {
                swipe.onSwipe = null
                swipe.onDrag = null
                swipe.onSwipeCancel = null
                swipe.onTap = null
            }
        }
        // 가운데 화면이 위에 오도록(이웃 화면 그림자·터치가 가리지 않게).
        slots[1]?.bringToFront()
        place?.let { p -> details[p.id]?.let { d -> slots[1]?.let { bindDetail(it, p, d, selectedImage) } } }
        restartSlideshow()
    }

    private fun bindPager(swipe: SwipeFrameLayout) {
        swipe.canSwipe = { step -> index + step in places.indices }
        swipe.onDrag = { dx ->
            animator?.cancel()
            offset = dx
            applyOffset()
        }
        swipe.onSwipe = ::settle
        swipe.onSwipeCancel = { settle(0) }
        swipe.excludedViews = listOf(swipe.findViewById(R.id.thumbScroll), swipe.findViewById(R.id.chipScroll))
        // 사진이 보이는 빈 곳을 탭하면 전체 보기. 정보 패널·버튼 위 탭은 제외한다.
        swipe.onTap = ::openPhotoViewer
        swipe.tapExcludedViews = listOfNotNull<View>(
            swipe.findViewById(R.id.sheet), swipe.findViewById(R.id.header), swipe.findViewById(R.id.bodyPanel),
            swipe.findViewById(R.id.pager), swipe.findViewById(R.id.backButton),
        )

        swipe.findViewById<View>(R.id.pager).visibility = if (places.size > 1) View.VISIBLE else View.GONE
        swipe.findViewById<TextView>(R.id.pagerText).text = "${index + 1} / ${places.size}"
        swipe.findViewById<View>(R.id.prevButton).apply {
            isEnabled = index > 0
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { settle(-1) }
        }
        swipe.findViewById<View>(R.id.nextButton).apply {
            isEnabled = index < places.lastIndex
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { settle(1) }
        }
    }

    /** 남은 거리를 감속하며 마저 이동한다. step=±1이면 이웃 장소가 가운데로 온다. */
    private fun settle(step: Int) {
        if (step != 0 && index + step !in places.indices) return
        val w = (host?.width ?: return).toFloat()
        val target = -step * w
        animator?.cancel()
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = (200 + 180 * abs(target - offset) / w.coerceAtLeast(1f)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                offset = it.animatedValue as Float
                applyOffset()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && step != 0) shift(step)
                }
            })
            start()
        }
    }

    private fun shift(step: Int) {
        val h = host ?: return
        slideshowJob?.cancel()
        index += step
        selectedImage = 0
        if (step == 1) {
            slots[0]?.let { h.removeView(it) }
            slots[0] = slots[1]; slots[1] = slots[2]; slots[2] = null
            buildSlot(2)
        } else {
            slots[2]?.let { h.removeView(it) }
            slots[2] = slots[1]; slots[1] = slots[0]; slots[0] = null
            buildSlot(0)
        }
        offset = 0f
        applyOffset()
        becomeCurrent()
    }

    private inline fun forEachSlot(block: (View, Place, Int) -> Unit) {
        slots.forEachIndexed { s, v ->
            val p = places.getOrNull(index + s - 1)
            if (v != null && p != null) block(v, p, s)
        }
    }

    // ---- 데이터 ----

    /** 상세 정보를 한 번만 불러 캐시하고, 그 장소를 보여주는 화면이 있으면 채운다. */
    private fun ensureLoaded(p: Place) {
        if (details.containsKey(p.id) || loading.containsKey(p.id)) return
        loading[p.id] = scope.launch {
            try {
                val d = tourApi.detail(p)
                details[p.id] = d
                forEachSlot { v, sp, s -> if (sp.id == p.id) bindDetail(v, sp, d, if (s == 1) selectedImage else 0) }
                if (place?.id == p.id) restartSlideshow()
            } catch (e: Exception) {
                Log.e(TAG, "Detail load failed: ${p.title}", e)
                forEachSlot { v, sp, _ ->
                    if (sp.id == p.id) {
                        v.findViewById<View>(R.id.loading).visibility = View.GONE
                        v.findViewById<TextView>(R.id.errorText).apply {
                            text = "상세 정보를 불러오지 못했습니다."
                            visibility = View.VISIBLE
                        }
                    }
                }
            } finally {
                loading.remove(p.id)
            }
        }
    }

    // ---- 화면 채우기 ----

    private fun bindBasic(v: View, p: Place) {
        v.findViewById<TextView>(R.id.title).text = p.title
        bindSubtitle(v, p)
        v.findViewById<TextView>(R.id.badge).text = p.type.label
        v.findViewById<View>(R.id.backButton).setOnClickListener { close() }
        v.findViewById<Button>(R.id.btnWaypoint).setOnClickListener { actions.addWaypoint(p) }
        v.findViewById<Button>(R.id.btnDestination).setOnClickListener { actions.setDestination(p) }
        v.findViewById<View>(R.id.btnCall).visibility = View.GONE
        // 이웃 화면은 끄는 동안 보이기만 하므로 화살표 묶음은 숨긴다(가운데 화면에서 다시 보인다).
        v.findViewById<View>(R.id.pager).visibility = View.GONE
    }

    private fun bindSubtitle(v: View, p: Place) {
        val rel = actions.relativeDirection(p)
        v.findViewById<ImageView>(R.id.direction).apply {
            visibility = if (rel != null) View.VISIBLE else View.GONE
            rotation = rel?.toFloat() ?: 0f
        }
        v.findViewById<TextView>(R.id.subtitle).text =
            listOfNotNull(actions.distanceLabel(p), rel?.let(Direction::label), p.address.ifBlank { null }).joinToString(" · ")
    }

    private fun bindDetail(v: View, p: Place, d: PlaceDetail, selected: Int) {
        v.findViewById<View>(R.id.loading).visibility = View.GONE

        showHero(v, d.images.getOrNull(selected) ?: p.imageUrl)
        bindThumbs(v, d.images, selected)
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
            v.findViewById<View>(R.id.btnRead).setOnClickListener { actions.speak("${p.title}. $overview") }
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
            d.courseStops.forEachIndexed { i, stop -> container.addView(stopRow(container, i, stop)) }
        }
        applyRestriction(v, p)
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

    private fun bindThumbs(v: View, images: List<String>, selected: Int) {
        v.findViewById<View>(R.id.thumbScroll).visibility = if (images.size > 1) View.VISIBLE else View.GONE
        val container = v.findViewById<LinearLayout>(R.id.thumbContainer)
        container.removeAllViews()
        if (images.size <= 1) return
        val density = activity.resources.displayMetrics.density
        images.forEachIndexed { i, url ->
            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((160 * density).toInt(), (90 * density).toInt())
                    .apply { marginEnd = (10 * density).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.card_bg)
                clipToOutline = true
                foreground = if (i == selected) activity.getDrawable(R.drawable.thumb_selected_bg) else null
                contentDescription = "사진 ${i + 1}"
                setOnClickListener {
                    selectImage(v, images, i, fade = false)
                    restartSlideshow() // 직접 고른 사진은 다음 주기까지 보여준다.
                }
            }
            ImageLoader.load(scope, thumb, url, (160 * density).toInt()) {}
            container.addView(thumb)
        }
        // 슬라이드쇼로 넘어간 사진의 썸네일이 줄 밖에 있으면 보이도록 스크롤한다.
        val scroll = v.findViewById<HorizontalScrollView>(R.id.thumbScroll)
        scroll.post {
            val sel = container.getChildAt(selected) ?: return@post
            if (sel.left < scroll.scrollX || sel.right > scroll.scrollX + scroll.width) {
                scroll.smoothScrollTo(sel.left, 0)
            }
        }
    }

    private fun selectImage(v: View, images: List<String>, i: Int, fade: Boolean) {
        selectedImage = i
        val image = v.findViewById<ImageView>(R.id.heroImage)
        if (fade) {
            image.animate().alpha(0.2f).setDuration(250).withEndAction {
                showHero(v, images[i])
                image.animate().alpha(1f).setDuration(450).start()
            }.start()
        } else {
            showHero(v, images[i])
        }
        bindThumbs(v, images, i)
    }

    /** 정차 중이고 사진이 여러 장이면 5초마다 다음 사진으로 넘긴다. 주행 중에는 움직이는 화면이 주의를 끌어 멈춘다. */
    private fun restartSlideshow() {
        slideshowJob?.cancel()
        val images = place?.let { details[it.id] }?.images ?: return
        if (restricted || images.size <= 1 || photoViewer.isShowing) return
        slideshowJob = scope.launch {
            while (true) {
                delay(SLIDESHOW_INTERVAL_MS)
                // 끄는 중이거나 넘기는 중에는 건너뛴다.
                if (offset != 0f) continue
                val v = slots[1] ?: return@launch
                selectImage(v, images, (selectedImage + 1) % images.size, fade = true)
            }
        }
    }

    private fun openPhotoViewer() {
        val p = place ?: return
        val images = details[p.id]?.images?.takeIf { it.isNotEmpty() } ?: listOfNotNull(p.imageUrl)
        if (images.isEmpty()) return
        slideshowJob?.cancel()
        photoViewer.show(images, selectedImage.coerceIn(0, images.lastIndex), p.title) { last ->
            // 전체 보기에서 마지막으로 본 사진을 상세에도 이어서 보여준다.
            slots[1]?.let { v -> if (images.size > 1) selectImage(v, images, last, fade = false) }
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

    private fun stopRow(parent: ViewGroup, i: Int, stop: CourseStop): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_course_stop, parent, false)
        row.findViewById<View>(R.id.stopThumbFrame).clipToOutline = true
        row.findViewById<TextView>(R.id.stopNumber).text = "${i + 1}"
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

    private fun applyRestriction(v: View, p: Place) {
        val hasOverview = details[p.id]?.overview != null
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
