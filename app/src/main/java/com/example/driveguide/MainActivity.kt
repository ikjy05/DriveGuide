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
import com.example.driveguide.navi.NaviBridge
import com.example.driveguide.notify.PlaceNotifier
import com.example.driveguide.route.UpcomingEngine
import com.example.driveguide.route.UpcomingSection
import com.example.driveguide.tour.CourseRepository
import com.example.driveguide.tour.Place
import com.example.driveguide.tour.PlaceType
import com.example.driveguide.tour.TourApiClient
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
        navi.start(scope)

        detailScreen = PlaceDetailScreen(this, scope, tourApi, object : PlaceDetailScreen.Actions {
            override fun addWaypoint(place: Place) = this@MainActivity.addWaypoint(place)
            override fun setDestination(place: Place) {
                navi.setDestination(place)
                Toast.makeText(this@MainActivity, "${place.title}(으)로 안내를 시작합니다", Toast.LENGTH_SHORT).show()
            }
            override fun speak(text: String) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "detail")
            }
        })
        drivingRestriction = DrivingRestriction(this) { detailScreen.setRestricted(it) }
        drivingRestriction.start()

        scope.launch {
            navi.location.filterNotNull().collect { location ->
                statusText.text = location.address ?: "%.5f, %.5f".format(location.latitude, location.longitude)
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
        if (detailScreen.isShowing) detailScreen.close() else super.onBackPressed()
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
            val message = buildString {
                append("${name}에 진입했습니다.")
                if (rec.attractions.isNotEmpty()) append(" 주변 추천 관광지 ${rec.attractions.size}곳이 있습니다.")
                if (rec.courses.isNotEmpty()) append(" ${rec.courses.first().title} 코스도 가까워요.")
            }
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "region")
            rec.attractions.firstOrNull()?.let { top ->
                notifier.notify("${name} 진입", top, "${formatDistance(top.distanceMeters)} · 추천 관광지 ${rec.attractions.size}곳", force = true)
            }
        }
    }

    /** 가는 길 추천 중 가장 가까운 구간의 첫 장소를 알린다(같은 장소는 한 번만). */
    private fun notifyUpcoming(sections: List<UpcomingSection>) {
        val section = sections.firstOrNull() ?: return
        val place = section.places.firstOrNull() ?: return
        val where = "약 ${formatDistance(section.aheadMeters)} 앞" + (section.region?.let { " · $it" } ?: "")
        notifier.notify(where, place, "경로에서 ${formatDistance(place.distanceMeters)}")
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
        when (selectedTab) {
            Tab.NEARBY -> renderPlaces(latest?.attractions ?: return, "주변에 표시할 관광지 정보가 없습니다.")
            Tab.COURSES -> renderPlaces(latest?.courses ?: return, "주변에 표시할 여행코스 정보가 없습니다.")
            Tab.UPCOMING -> renderUpcoming()
        }
    }

    private fun renderPlaces(places: List<Place>, emptyMessage: String) {
        if (places.isEmpty()) {
            addMessage(emptyMessage)
            return
        }
        addCardGrid(places) { place ->
            val distance = formatDistance(place.distanceMeters)
            if (place.type == PlaceType.COURSE) "출발지까지 $distance" else distance
        }
    }

    private fun renderUpcoming() {
        val sections = upcoming
        when {
            navi.route.value == null -> addMessage("내비에서 목적지를 설정하면 가는 길의 관광지를 미리 알려드려요.")
            sections == null -> addMessage("경로를 분석하는 중…")
            sections.isEmpty() -> addMessage("경로 앞쪽에 추천할 장소가 없습니다.")
            else -> sections.forEach { section ->
                addSectionHeader(
                    "약 ${formatDistance(section.aheadMeters)} 앞" + (section.region?.let { " · $it" } ?: "")
                )
                addCardGrid(section.places) { place ->
                    val kind = if (place.type == PlaceType.COURSE) "코스 출발지" else "경로에서"
                    "$kind ${formatDistance(place.distanceMeters)}"
                }
            }
        }
    }

    /** 장소들을 카드 격자로 추가한다. 열 수는 창 폭에 따라 2(분할) 또는 3(전체). */
    private fun addCardGrid(places: List<Place>, distanceLabel: (Place) -> String) {
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
                    createCard(row, place, distanceLabel(place), cardWidth)
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

    private fun createCard(parent: LinearLayout, place: Place, distance: String, widthPx: Int): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_place_card, parent, false)
        card.clipToOutline = true // 이미지 모서리를 카드 라운드에 맞춘다.
        card.findViewById<TextView>(R.id.title).text = place.title
        // 사진 위에 겹치므로 거리와 주소를 한 줄로 묶는다.
        card.findViewById<TextView>(R.id.subtitle).text =
            if (place.address.isBlank()) distance else "$distance · ${place.address}"
        card.findViewById<TextView>(R.id.badge).text = if (place.type == PlaceType.COURSE) "코스" else "관광지"
        card.findViewById<Button>(R.id.addWaypoint).setOnClickListener { addWaypoint(place) }
        card.setOnClickListener { detailScreen.show(place, distance) }

        val image = card.findViewById<ImageView>(R.id.image)
        ImageLoader.load(scope, image, place.imageUrl, widthPx) {
            // 이미지가 없는 장소는 위치 아이콘으로 대신한다.
            image.scaleType = ImageView.ScaleType.CENTER
            image.setImageResource(R.drawable.ic_place_large)
        }
        return card
    }

    private fun addSectionHeader(text: String) {
        listContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 32f
            setTextColor(getColor(R.color.accent_text))
            setPadding(0, 16, 0, 12)
        })
    }

    private fun addMessage(text: String) {
        listContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 28f
            setTextColor(getColor(R.color.text_secondary))
        })
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
