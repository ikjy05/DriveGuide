package com.example.driveguide

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.driveguide.navi.CourseLeg
import com.example.driveguide.navi.NaviBridge
import com.example.driveguide.navi.VehicleLocation
import com.example.driveguide.notify.PlaceNotifier
import com.example.driveguide.route.UpcomingEngine
import com.example.driveguide.route.UpcomingSection
import com.example.driveguide.tour.CourseRepository
import com.example.driveguide.tour.Place
import com.example.driveguide.tour.PlaceType
import com.example.driveguide.tour.TourApiClient
import com.example.driveguide.tour.distanceMeters
import com.example.driveguide.ui.Direction
import com.example.driveguide.ui.DrivingRestriction
import com.example.driveguide.ui.ImageLoader
import com.example.driveguide.ui.PlaceDetailScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {

    private enum class Tab { NEARBY, COURSES, UPCOMING }

    private val scope = MainScope()
    private lateinit var navi: NaviBridge
    private lateinit var engine: RecommendationEngine
    private lateinit var upcomingEngine: UpcomingEngine
    private lateinit var notifier: PlaceNotifier
    private lateinit var detailScreen: PlaceDetailScreen
    private lateinit var drivingRestriction: DrivingRestriction
    private var tts: TextToSpeech? = null

    private lateinit var regionText: TextView
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tabButtons: Map<Tab, Button>

    private var latest: Recommendation? = null
    private var upcoming: List<UpcomingSection>? = null
    private var selectedTab = Tab.NEARBY

    /** 화면에 그려진 카드. 위치가 바뀔 때 카드를 다시 만들지 않고 거리·방향만 갱신한다. */
    private class CardBinding(
        val place: Place,
        val direction: ImageView,
        val subtitle: TextView,
        /** 거리 문구 앞말(예: "출발지까지", "경로에서"). */
        val prefix: String,
        /** true면 현재 위치에서 다시 잰 거리, false면 추천 당시 거리(경로 기준)를 쓴다. */
        val liveDistance: Boolean,
    )
    private val cards = mutableListOf<CardBinding>()
    private var currentLocation: VehicleLocation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindViews()

        ImageLoader.installDiskCache(this)
        notifier = PlaceNotifier(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN
        }

        val tourApi = TourApiClient(BuildConfig.TOUR_API_KEY)
        val courseRepository = CourseRepository(this, tourApi)
        engine = RecommendationEngine(tourApi, courseRepository)
        upcomingEngine = UpcomingEngine(tourApi, courseRepository)
        navi = NaviBridge(this)
        navi.onCourseLeg = { leg -> runOnUiThread { announceCourseLeg(leg) } }
        navi.start(scope)

        detailScreen = PlaceDetailScreen(this, scope, tourApi, object : PlaceDetailScreen.Actions {
            override fun addWaypoint(place: Place) = this@MainActivity.addWaypoint(place)
            override fun setDestination(place: Place) {
                navi.setDestination(place)
                Toast.makeText(this@MainActivity, "${place.title}(으)로 안내를 시작합니다", Toast.LENGTH_SHORT).show()
            }
            override fun startCourse(course: Place, stops: List<Place>) {
                navi.startCourse(stops)
            }
            override fun speak(text: String) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "detail")
            }
            override fun distanceLabel(place: Place): String =
                cards.firstOrNull { it.place == place }?.let(::distanceText) ?: formatDistance(place.distanceMeters)
            override fun relativeDirection(place: Place): Double? = relativeTo(place)
        })
        drivingRestriction = DrivingRestriction(this) { detailScreen.setRestricted(it) }
        drivingRestriction.start()

        scope.launch {
            navi.location.filterNotNull().collect { location ->
                statusText.text = location.address ?: "%.5f, %.5f".format(location.latitude, location.longitude)
                currentLocation = location
                updateCards()
                try {
                    engine.onLocation(location)?.let(::onRecommendation)
                } catch (e: Exception) {
                    Log.e(TAG, "Recommendation failed", e)
                    statusText.text = "추천 정보를 불러오지 못했습니다: ${e.message}"
                }
            }
        }
        scope.launch {
            combine(navi.location.filterNotNull(), navi.route) { location, route -> location to route }
                .collect { (location, route) ->
                    upcomingEngine.update(location, route)?.let {
                        upcoming = it
                        if (selectedTab == Tab.UPCOMING) render()
                        notifyUpcoming(it)
                    }
                }
        }
        scope.launch {
            navi.error.filterNotNull().collect { statusText.text = it }
        }
    }

    /**
     * 분할 화면 전환 시 창 폭에 맞는 레이아웃(layout / layout-w1200dp)으로,
     * 시스템 라이트/다크 전환 시 새 색(values / values-night)으로 다시 그린다.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val status = statusText.text
        bindViews()
        statusText.text = status
        latest?.region?.let { regionText.text = it }
        // setContentView가 덮어 띄운 상세 화면까지 지우므로 다시 붙인다.
        detailScreen.reattach()
    }

    private fun bindViews() {
        // 재시작 없이 라이트/다크가 바뀌면 테마의 창 배경은 그대로라 직접 갱신한다.
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.bg)))
        setContentView(R.layout.activity_main)
        regionText = findViewById(R.id.regionText)
        statusText = findViewById(R.id.statusText)
        listContainer = findViewById(R.id.listContainer)
        tabButtons = mapOf(
            Tab.NEARBY to findViewById(R.id.tabAttractions),
            Tab.COURSES to findViewById(R.id.tabCourses),
            Tab.UPCOMING to findViewById(R.id.tabUpcoming),
        )
        tabButtons.forEach { (tab, button) -> button.setOnClickListener { selectTab(tab) } }
        selectTab(selectedTab)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!detailScreen.onBack()) super.onBackPressed()
    }

    override fun onDestroy() {
        drivingRestriction.stop()
        navi.stop()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun onRecommendation(rec: Recommendation) {
        latest = rec
        regionText.text = rec.region ?: "현재 위치 주변"
        render()

        if (rec.regionChanged && rec.region != null) {
            val name = rec.region.substringAfterLast(' ')
            val message = StringBuilder().apply {
                append("${name}에 진입했습니다.")
                if (rec.attractions.isNotEmpty()) append(" 주변 추천 관광지 ${rec.attractions.size}곳이 있습니다.")
                if (rec.courses.isNotEmpty()) append(" ${rec.courses.first().title} 코스도 가까워요.")
            }
            // 방향을 알면 지나친 쪽이 아닌 장소를 먼저 안내한다.
            val top = sortAhead(rec.attractions).firstOrNull()
            val topDirection = top?.let { relativeTo(it) }
            if (top != null && topDirection != null) {
                message.append(" ${Direction.label(topDirection)} ${spokenDistance(top.distanceMeters)}에 ${top.title}이 있어요.")
            }
            tts?.speak(message.toString(), TextToSpeech.QUEUE_FLUSH, null, "region")
            top?.let {
                val where = listOfNotNull(formatDistance(it.distanceMeters), topDirection?.let(Direction::label)).joinToString(" ")
                notifier.notify("${name} 진입", it, "$where · 추천 관광지 ${rec.attractions.size}곳", force = true)
            }
        }
    }

    /** 가는 길 추천 중 가장 가까운 구간의 첫 장소를 알린다(같은 장소는 한 번만). */
    private fun notifyUpcoming(sections: List<UpcomingSection>) {
        val section = sections.firstOrNull() ?: return
        val place = section.places.firstOrNull() ?: return
        val where = "약 ${formatDistance(section.aheadMeters)} 앞" + (section.region?.let { " · $it" } ?: "")
        val side = relativeTo(place)?.let { " · 차 기준 ${Direction.label(it)}" } ?: ""
        notifier.notify(where, place, "경로에서 ${formatDistance(place.distanceMeters)}$side")
    }

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        // 밑줄(tab_bg)과 글자색(tab_text)은 selected 상태에 따라 바뀐다. 굵기만 직접 바꾼다.
        tabButtons.forEach { (t, button) ->
            button.isSelected = t == tab
            button.setTypeface(null, if (t == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        cards.clear()
        when (selectedTab) {
            Tab.NEARBY -> renderPlaces(latest?.attractions ?: return) {
                addEmptyState(R.drawable.ic_place_large, "주변에 추천할 관광지가 없어요", "이동하면 새 지역의 관광지를 다시 찾아볼게요.")
            }
            Tab.COURSES -> renderPlaces(latest?.courses ?: return) {
                addEmptyState(
                    R.drawable.ic_route_empty, "가까운 여행코스가 없어요", "반경 60km 안에서 출발하는 코스를 찾지 못했어요.",
                    "주변 관광지 보기",
                ) { selectTab(Tab.NEARBY) }
            }
            Tab.UPCOMING -> renderUpcoming()
        }
    }

    private fun renderPlaces(places: List<Place>, onEmpty: () -> Unit) {
        if (places.isEmpty()) {
            onEmpty()
            return
        }
        // 진행 방향을 알면 이미 지나친 쪽 장소를 뒤로 보낸다(그 안에서는 거리순 유지).
        addCardGrid(sortAhead(places), liveDistance = true) { place ->
            if (place.type == PlaceType.COURSE) "출발지까지" else ""
        }
    }

    private fun renderUpcoming() {
        val sections = upcoming
        when {
            navi.route.value == null -> addEmptyState(
                R.drawable.ic_route_empty, "목적지를 설정해 주세요",
                "내비에서 목적지를 정하면 가는 길에 들를 만한\n관광지와 코스를 미리 알려드려요.",
                "주변 관광지 보기",
            ) { selectTab(Tab.NEARBY) }
            sections == null -> addEmptyState(null, "가는 길을 살펴보는 중", "경로 앞 60km까지 들를 만한 곳을 찾고 있어요.")
            sections.isEmpty() -> addEmptyState(
                R.drawable.ic_route_empty, "가는 길엔 추천할 곳이 없어요",
                "남은 경로 주변에서 들를 만한 곳을 찾지 못했어요.\n달리면서 계속 찾아볼게요.",
                "주변 관광지 보기",
            ) { selectTab(Tab.NEARBY) }
            else -> sections.forEach { section ->
                addSectionHeader(
                    "약 ${formatDistance(section.aheadMeters)} 앞" + (section.region?.let { " · $it" } ?: "")
                )
                addCardGrid(section.places, liveDistance = false) { place ->
                    if (place.type == PlaceType.COURSE) "코스 출발지" else "경로에서"
                }
            }
        }
    }

    /** 장소들을 카드 격자로 추가한다. 열 수는 창 폭에 따라 2(분할) 또는 3(전체). */
    private fun addCardGrid(places: List<Place>, liveDistance: Boolean, prefix: (Place) -> String) {
        val columns = resources.getInteger(R.integer.card_columns)
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        // 이미지는 카드 폭 정도로만 디코딩한다.
        val cardWidth = (listContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / columns
        places.chunked(columns).forEach { rowPlaces ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = gap }
            }
            for (i in 0 until columns) {
                val place = rowPlaces.getOrNull(i)
                val card = if (place != null) {
                    createCard(row, place, prefix(place), liveDistance, cardWidth)
                } else {
                    // 마지막 줄이 덜 차도 카드 폭이 같도록 빈 칸을 채운다.
                    View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                }
                if (i > 0) (card.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                row.addView(card)
            }
            listContainer.addView(row)
        }
    }

    private fun createCard(parent: LinearLayout, place: Place, prefix: String, liveDistance: Boolean, widthPx: Int): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_place_card, parent, false)
        card.clipToOutline = true // 이미지 모서리를 카드 라운드에 맞춘다.
        card.findViewById<TextView>(R.id.title).text = place.title
        val binding = CardBinding(place, card.findViewById(R.id.direction), card.findViewById(R.id.subtitle), prefix, liveDistance)
        cards += binding
        updateCard(binding)
        // 종류가 섞이는 가는 길 탭에서만 배지로 구분한다(다른 탭은 탭 이름이 종류다).
        card.findViewById<TextView>(R.id.badge).apply {
            text = if (place.type == PlaceType.COURSE) "코스" else "관광지"
            visibility = if (selectedTab == Tab.UPCOMING) View.VISIBLE else View.GONE
        }
        card.findViewById<Button>(R.id.addWaypoint).setOnClickListener { addWaypoint(place) }
        // 상세에서 좌우로 넘길 목록은 지금 화면에 보이는 카드 순서 그대로다(가는 길 탭은 구간을 이어서).
        card.setOnClickListener { detailScreen.show(cards.map { it.place }, cards.indexOf(binding)) }

        val image = card.findViewById<ImageView>(R.id.image)
        ImageLoader.load(scope, image, place.imageUrl, widthPx) {
            // 이미지가 없는 장소는 위치 아이콘으로 대신한다.
            image.scaleType = ImageView.ScaleType.CENTER
            image.setImageResource(R.drawable.ic_place_large)
        }
        return card
    }

    /** 카드의 거리 문구. 사진 위에 겹치므로 거리·방향·주소를 한 줄로 묶는다. */
    private fun updateCard(card: CardBinding) {
        val rel = relativeTo(card.place)
        card.direction.visibility = if (rel != null) View.VISIBLE else View.GONE
        card.direction.rotation = rel?.toFloat() ?: 0f
        card.subtitle.text = listOfNotNull(distanceText(card), rel?.let(Direction::label), card.place.address.ifBlank { null })
            .joinToString(" · ")
    }

    private fun updateCards() {
        cards.forEach(::updateCard)
        if (detailScreen.isShowing) detailScreen.refreshLocation()
    }

    private fun distanceText(card: CardBinding): String {
        val loc = currentLocation
        val meters = if (card.liveDistance && loc != null) {
            distanceMeters(loc.latitude, loc.longitude, card.place.latitude, card.place.longitude)
        } else {
            card.place.distanceMeters
        }
        return listOf(card.prefix, formatDistance(meters)).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 차 진행 방향 기준 상대 각도. 위치나 방향을 모르면 null. */
    private fun relativeTo(place: Place): Double? {
        val loc = currentLocation ?: return null
        val heading = loc.heading ?: return null
        return Direction.relative(heading, loc.latitude, loc.longitude, place.latitude, place.longitude)
    }

    private fun sortAhead(places: List<Place>): List<Place> =
        places.sortedBy { place -> relativeTo(place)?.let(Direction::isBehind) ?: false }

    /** TTS용 거리("2.3킬로미터", "800미터"). */
    private fun spokenDistance(meters: Double): String =
        if (meters >= 1000) "%.1f킬로미터".format(meters / 1000) else "${meters.toInt()}미터"

    private fun addSectionHeader(text: String) {
        listContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 32f
            setTextColor(getColor(R.color.accent_text))
            setPadding(0, 16, 0, 12)
        })
    }

    /** 목록 대신 가운데에 그림·제목·설명(·버튼)을 보여준다. [icon]이 null이면 불러오는 중 표시. */
    private fun addEmptyState(
        icon: Int?,
        title: String,
        body: String,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_empty_state, listContainer, false)
        val iconView = view.findViewById<ImageView>(R.id.emptyIcon)
        if (icon != null) iconView.setImageResource(icon) else iconView.visibility = View.GONE
        view.findViewById<View>(R.id.emptyProgress).visibility = if (icon == null) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.emptyTitle).text = title
        view.findViewById<TextView>(R.id.emptyBody).text = body
        if (actionText != null && action != null) {
            view.findViewById<Button>(R.id.emptyAction).apply {
                text = actionText
                visibility = View.VISIBLE
                setOnClickListener { action() }
            }
        }
        // 스크롤 영역 높이만큼 늘려 세로 가운데에 둔다.
        val scroll = listContainer.parent as View
        view.minimumHeight = scroll.height
        if (scroll.height == 0) scroll.post { view.minimumHeight = scroll.height }
        listContainer.addView(view)
    }

    /** 코스 구간 안내가 시작될 때 들르는 순서를 알려준다. */
    private fun announceCourseLeg(leg: CourseLeg) {
        val names = leg.stops.joinToString(" → ") { it.title }
        val rest = if (leg.remaining > 0) " 도착하면 남은 ${leg.remaining}곳을 이어서 안내합니다." else ""
        Toast.makeText(this, "코스 안내: $names", Toast.LENGTH_LONG).show()
        tts?.speak("코스 안내를 시작합니다. ${leg.stops.joinToString(", ") { it.title }} 순서로 들릅니다.$rest", TextToSpeech.QUEUE_FLUSH, null, "course")
    }

    private fun addWaypoint(place: Place) {
        navi.addAsWaypoint(place)
        Toast.makeText(this, "${place.title}을(를) 경유지로 추가했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000) else "${meters.toInt()}m"

    companion object {
        private const val TAG = "DriveGuide"
    }
}
