package zone.dal.driveguide

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import com.google.android.material.button.MaterialButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import zone.dal.driveguide.navi.CourseLeg
import zone.dal.driveguide.navi.NaviBridge
import zone.dal.driveguide.navi.VehicleLocation
import zone.dal.driveguide.notify.PlaceNotifier
import zone.dal.driveguide.route.UpcomingEngine
import zone.dal.driveguide.route.UpcomingSection
import zone.dal.driveguide.saved.SavedPlaces
import zone.dal.driveguide.tour.CourseRepository
import zone.dal.driveguide.tour.CoursePager
import zone.dal.driveguide.tour.MorePager
import zone.dal.driveguide.tour.NearbyPager
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.PlaceType
import zone.dal.driveguide.tour.TourApiClient
import zone.dal.driveguide.tour.distanceMeters
import zone.dal.driveguide.ui.Direction
import zone.dal.driveguide.ui.DrivingRestriction
import zone.dal.driveguide.ui.ImageLoader
import zone.dal.driveguide.ui.PlaceDetailScreen
import zone.dal.driveguide.ui.Placeholder
import zone.dal.driveguide.voice.NewPlaceTracker
import zone.dal.driveguide.voice.Speaker
import zone.dal.driveguide.voice.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private enum class Tab { NEARBY, COURSES, UPCOMING, SAVED }

    private val scope = MainScope()
    private lateinit var navi: NaviBridge
    private lateinit var engine: RecommendationEngine
    private lateinit var upcomingEngine: UpcomingEngine
    private lateinit var notifier: PlaceNotifier
    private lateinit var detailScreen: PlaceDetailScreen
    private lateinit var drivingRestriction: DrivingRestriction
    private lateinit var savedPlaces: SavedPlaces
    private lateinit var tourApi: TourApiClient
    private lateinit var courseRepository: CourseRepository
    /** 관광지 탭: 스크롤하는 만큼 더 먼 곳을 이어 붙인다(최대 20km). 새 추천을 받으면 새로 만든다. */
    private var nearbyPager: NearbyPager? = null
    /** 코스 탭: 스크롤하는 만큼 60km 안의 코스를 가까운 순으로 이어 붙인다. */
    private var coursePager: CoursePager? = null
    private var loadMoreJob: Job? = null
    /** 이어 받기에 실패한 때. 스크롤할 때마다 바로 다시 요청하지 않도록 잠시 쉰다. */
    private var loadMoreFailedAt = 0L
    /** 관광지·코스 목록 맨 아래 문구(더 찾는 중 / 다 봤어요). */
    private var listFooter: TextView? = null
    /** 지역 안내·읽어주기·코스 안내 음성(Pleos TTS SDK). */
    private lateinit var speaker: Speaker
    /** 이미 음성으로 안내한 장소. 같은 곳을 되풀이해 말하지 않는다. */
    private val announcedPlaces = NewPlaceTracker()

    private lateinit var regionText: TextView
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tabButtons: Map<Tab, Button>
    /** 하단 툴바 탭의 아이콘. 모든 탭에 보인다. */
    private lateinit var tabIcons: Map<Tab, android.graphics.drawable.Drawable?>
    /** 하단 탭 이름. 창이 좁으면 글자를 숨기고 아이콘만 보이므로 원래 이름을 따로 들고 있는다. */
    private lateinit var tabLabels: Map<Tab, CharSequence>

    private var latest: Recommendation? = null
    private var upcoming: List<UpcomingSection>? = null
    private var selectedTab = Tab.NEARBY
    /** 지금 목록에 관광지와 코스가 섞여 있어 카드에 종류 배지가 필요한지. */
    private var showBadges = false

    /** SharedPreferences는 리스너를 약하게 잡으므로 필드로 들고 있는다. */
    private val savedListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (selectedTab == Tab.SAVED) render()
        if (detailScreen.isShowing) detailScreen.refreshSaved()
    }

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
        savedPlaces = SavedPlaces(this)
        bindViews()

        ImageLoader.installDiskCache(this)
        notifier = PlaceNotifier(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        speaker = Speaker(this)

        tourApi = TourApiClient(BuildConfig.TOUR_API_KEY)
        courseRepository = CourseRepository(this, tourApi)
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
            override fun speak(text: String, onStarted: () -> Unit, onEnded: (completed: Boolean) -> Unit) {
                speaker.speak(text, onStarted, onEnded)
            }
            override fun stopSpeaking() {
                speaker.stop()
            }
            override fun distanceLabel(place: Place): String? =
                cards.firstOrNull { it.place == place }?.let(::distanceText) ?: liveDistanceText(place)
            override fun relativeDirection(place: Place): Double? = relativeTo(place)
            override fun isSaved(place: Place) = savedPlaces.contains(place)
            override fun toggleSaved(place: Place) { savedPlaces.toggle(place) }
        })
        drivingRestriction = DrivingRestriction(this) { detailScreen.setRestricted(it) }
        drivingRestriction.start()
        savedPlaces.observe(savedListener)

        scope.launch {
            navi.location.filterNotNull().collect { location ->
                statusText.text = location.address ?: "%.5f, %.5f".format(location.latitude, location.longitude)
                currentLocation = location
                updateCards()
                try {
                    engine.onLocation(location)?.let(::onRecommendation)
                } catch (e: Exception) {
                    Log.e(TAG, "Recommendation failed", e)
                    // 예외 내용은 로그로만 남기고, 화면에는 사용자가 할 수 있는 일만 알린다.
                    statusText.text = "추천 정보를 불러오지 못했어요. 네트워크 연결을 확인해 주세요."
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
        findViewById<ImageButton>(R.id.autoVoiceButton).apply {
            styleAutoVoiceButton(this)
            setOnClickListener {
                val on = !VoiceSettings.autoAnnounce(this@MainActivity)
                VoiceSettings.setAutoAnnounce(this@MainActivity, on)
                styleAutoVoiceButton(this)
                Toast.makeText(this@MainActivity, if (on) "자동 음성 안내를 켰어요" else "자동 음성 안내를 껐어요", Toast.LENGTH_SHORT).show()
            }
        }
        // 관광지 목록 끝에 가까워지면 더 먼 곳을 이어 받는다.
        findViewById<ScrollView>(R.id.listScroll).setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMore() }
        tabButtons = mapOf(
            Tab.NEARBY to findViewById(R.id.tabAttractions),
            Tab.COURSES to findViewById(R.id.tabCourses),
            Tab.UPCOMING to findViewById(R.id.tabUpcoming),
            Tab.SAVED to findViewById(R.id.tabSaved),
        )
        tabIcons = tabButtons.mapValues { (_, button) -> (button as? MaterialButton)?.icon }
        tabLabels = tabButtons.mapValues { (_, button) -> button.text }
        tabButtons.forEach { (tab, button) -> button.setOnClickListener { selectTab(tab) } }
        selectTab(selectedTab)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!detailScreen.onBack()) super.onBackPressed()
    }

    override fun onDestroy() {
        savedPlaces.stopObserving(savedListener)
        drivingRestriction.stop()
        navi.stop()
        speaker.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 앞의 안내를 끊고 [text]를 읽는다. 메인 스레드를 막지 않는다.
     * 앱 시작 직후 엔진이 아직 붙지 않았으면 버리지 않고, 붙는 대로 읽는다(첫 지역 안내가 사라지지 않게).
     */
    private fun speak(text: String) {
        speaker.speak(text)
    }

    private fun onRecommendation(rec: Recommendation) {
        latest = rec
        // 새 위치 기준 추천이므로 이어 붙이던 목록을 처음부터 다시 시작한다.
        loadMoreJob?.cancel()
        loadMoreJob = null
        nearbyPager = currentLocation?.let { loc ->
            NearbyPager(rec.attractions) { radius, page ->
                tourApi.nearby(loc.latitude, loc.longitude, PlaceType.ATTRACTION, radius, page = page)
            }
        }
        coursePager = currentLocation?.let { loc ->
            CoursePager(rec.courses) { offset, limit ->
                courseRepository.page(loc.latitude, loc.longitude, offset, limit)
            }
        }
        regionText.text = rec.region ?: "현재 위치 주변"
        // 새 추천으로 다시 그려도 보던 자리를 지킨다.
        val scroll = findViewById<ScrollView>(R.id.listScroll)
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }

        // 지역 경계를 넘을 때마다가 아니라, 아직 안내하지 않은 장소가 생겼을 때만 알린다.
        val newAttractions = announcedPlaces.takeNew(rec.attractions)
        val newCourses = announcedPlaces.takeNew(rec.courses)
        if (newAttractions.isEmpty() && newCourses.isEmpty()) return

        val name = rec.region?.substringAfterLast(' ')
        val message = StringBuilder().apply {
            if (rec.regionChanged && name != null) append("${name}에 진입했습니다. ")
            if (newAttractions.isNotEmpty()) append("새 추천 관광지 ${newAttractions.size}곳이 있습니다.")
            if (newCourses.isNotEmpty()) append(" ${newCourses.first().title} 코스도 가까워요.")
        }
        // 방향을 알면 지나친 쪽이 아닌 장소를 먼저 안내한다.
        val top = sortAhead(newAttractions).firstOrNull()
        val topDirection = top?.let { relativeTo(it) }
        if (top != null && topDirection != null) {
            message.append(" ${Direction.label(topDirection)} ${spokenDistance(top.distanceMeters)}에 ${top.title}이 있어요.")
        }
        // 꺼져 있어도 안내한 것으로 기록했으므로(takeNew) 다시 켰을 때 밀린 안내가 한꺼번에 나오지 않는다.
        if (VoiceSettings.autoAnnounce(this)) speak(message.toString().trim())
        top?.let {
            val where = listOfNotNull(formatDistance(it.distanceMeters), topDirection?.let(Direction::label)).joinToString(" ")
            val title = if (rec.regionChanged && name != null) "${name} 진입" else "새 추천 관광지"
            notifier.notify(title, it, "$where · 새 추천 ${newAttractions.size}곳", force = true)
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
        // 다른 탭 목록을 이어 받던 중이면 그만둔다(받던 쪽은 버려지고, 돌아오면 다시 받는다).
        if (tab != selectedTab) {
            loadMoreJob?.cancel()
            loadMoreJob = null
        }
        selectedTab = tab
        // 밑줄(tab_bg)과 글자색(tab_text)은 selected 상태에 따라 바뀐다. 굵기만 직접 바꾼다.
        // 창이 좁으면(분할 화면에서 앱 쪽이 1200dp 미만) 탭 네 개가 넘치지 않게 선택된 탭만 이름을 보이고
        // 나머지는 아이콘만 둔다. 숨긴 이름은 접근성 설명으로 남긴다.
        val iconOnly = resources.configuration.screenWidthDp < WIDE_WINDOW_DP
        val density = resources.displayMetrics.density
        tabButtons.forEach { (t, button) ->
            button.isSelected = t == tab
            val labeled = !iconOnly || t == tab
            button.text = if (labeled) tabLabels[t] else ""
            button.contentDescription = tabLabels[t]
            (button as? MaterialButton)?.apply {
                icon = tabIcons[t]
                iconPadding = if (labeled) (14 * density).toInt() else 0
            }
            button.setTypeface(null, if (t == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        cards.clear()
        listFooter = null
        showBadges = selectedTab == Tab.UPCOMING
        when (selectedTab) {
            Tab.NEARBY -> {
                val places = nearbyPager?.places ?: latest?.attractions ?: return
                renderPlaces(places) {
                    addEmptyState(R.drawable.ic_place_large, "주변에 추천할 관광지가 없어요", "이동하면 새 지역의 관광지를 다시 찾아볼게요.")
                }
                if (places.isNotEmpty()) addListFooter()
            }
            Tab.COURSES -> {
                val places = coursePager?.places ?: latest?.courses ?: return
                renderPlaces(places) {
                    addEmptyState(
                        R.drawable.ic_route_empty, "가까운 여행코스가 없어요", "반경 60km 안에서 출발하는 코스를 찾지 못했어요.",
                        "주변 관광지 보기",
                    ) { selectTab(Tab.NEARBY) }
                }
                if (places.isNotEmpty()) addListFooter()
            }
            Tab.UPCOMING -> renderUpcoming()
            Tab.SAVED -> renderSaved()
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

    private fun renderSaved() {
        val places = savedPlaces.all()
        if (places.isEmpty()) {
            addEmptyState(
                R.drawable.ic_star_large, "저장한 곳이 없어요",
                "상세 화면의 별이나 알림의 \"나중에 보기\"를 누르면\n여기에 모아 둘게요.",
                "주변 관광지 보기",
            ) { selectTab(Tab.NEARBY) }
            return
        }
        showBadges = places.map { it.type }.distinct().size > 1
        // 저장한 순서를 지킨다(최근에 저장한 곳이 앞).
        addCardGrid(places, liveDistance = true) { place ->
            if (place.type == PlaceType.COURSE) "출발지까지" else ""
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
        // 종류가 섞이는 목록에서만 배지로 구분한다(관광지·코스 탭은 탭 이름이 종류다).
        card.findViewById<TextView>(R.id.badge).apply {
            text = if (place.type == PlaceType.COURSE) "코스" else "관광지"
            compoundDrawableTintList = ColorStateList.valueOf(getColor(place.type.colorRes))
            visibility = if (showBadges) View.VISIBLE else View.GONE
        }
        card.findViewById<Button>(R.id.addWaypoint).setOnClickListener { addWaypoint(place) }
        // 상세에서 좌우로 넘길 목록은 지금 화면에 보이는 카드 순서 그대로다(가는 길 탭은 구간을 이어서).
        card.setOnClickListener { detailScreen.show(cards.map { it.place }, cards.indexOf(binding)) }

        val image = card.findViewById<ImageView>(R.id.image)
        ImageLoader.load(scope, image, place.imageUrl, widthPx) {
            // 사진이 없는 장소는 종류별 일러스트로 대신한다.
            Placeholder.show(image, place.type)
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

    /** 거리를 아직 모르면(저장한 곳을 위치 확인 전에 볼 때) null. */
    private fun distanceText(card: CardBinding): String? {
        val distance = (if (card.liveDistance) liveDistanceText(card.place) else null)
            ?: formatDistance(card.place.distanceMeters).takeIf { !card.liveDistance || card.place.distanceMeters > 0 }
            ?: return null
        return listOf(card.prefix, distance).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun liveDistanceText(place: Place): String? = currentLocation?.let { loc ->
        formatDistance(distanceMeters(loc.latitude, loc.longitude, place.latitude, place.longitude))
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
        // 보이는 목록 영역(탭바에 가려지는 아래 여백 제외) 높이만큼 늘려 세로 가운데에 둔다.
        val scroll = listContainer.parent as View
        fun visibleHeight() = scroll.height - scroll.paddingTop - scroll.paddingBottom
        view.minimumHeight = visibleHeight()
        if (scroll.height == 0) scroll.post { view.minimumHeight = visibleHeight() }
        listContainer.addView(view)
    }

    /** 코스 구간 안내가 시작될 때 들르는 순서를 알려준다. */
    private fun styleAutoVoiceButton(button: ImageButton) {
        val on = VoiceSettings.autoAnnounce(this)
        button.setImageResource(if (on) R.drawable.ic_volume else R.drawable.ic_volume_off)
        button.contentDescription = if (on) "자동 음성 안내 끄기" else "자동 음성 안내 켜기"
        button.alpha = if (on) 1f else 0.55f
    }

    private fun announceCourseLeg(leg: CourseLeg) {
        val names = leg.stops.joinToString(" → ") { it.title }
        val rest = if (leg.remaining > 0) " 도착하면 남은 ${leg.remaining}곳을 이어서 안내합니다." else ""
        Toast.makeText(this, "코스 안내: $names", Toast.LENGTH_LONG).show()
        if (VoiceSettings.autoAnnounce(this)) speak("코스 안내를 시작합니다. ${leg.stops.joinToString(", ") { it.title }} 순서로 들릅니다.$rest")
    }

    private fun addWaypoint(place: Place) {
        navi.addAsWaypoint(place)
        Toast.makeText(this, "${place.title}을(를) 경유지로 추가했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000) else "${meters.toInt()}m"

    /** 지금 탭에서 스크롤로 이어 붙이는 목록. 관광지·코스 탭에만 있다. */
    private fun pagerForTab(): MorePager? = when (selectedTab) {
        Tab.NEARBY -> nearbyPager
        Tab.COURSES -> coursePager
        else -> null
    }

    /** 관광지·코스 목록 맨 아래: 더 찾는 중이거나, 반경 안을 다 봤다고 알린다. 목록이 화면보다 짧으면 바로 더 받는다. */
    private fun addListFooter() {
        val density = resources.displayMetrics.density
        listFooter = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, (16 * density).toInt(), 0, (24 * density).toInt())
        }.also(listContainer::addView)
        updateListFooter()
        listContainer.post { maybeLoadMore() }
    }

    private fun updateListFooter() {
        val footer = listFooter ?: return
        val pager = pagerForTab()
        val what = if (selectedTab == Tab.COURSES) "코스" else "관광지"
        footer.text = when {
            pager == null -> ""
            loadMoreJob?.isActive == true -> "더 먼 ${what}를 찾는 중…"
            pager.done -> {
                val km = (pager as? NearbyPager)?.radiusMeters?.div(1000) ?: CoursePager.RADIUS_KM
                "${km}km 안의 ${what}를 모두 봤어요"
            }
            System.currentTimeMillis() - loadMoreFailedAt < LOAD_MORE_RETRY_MS -> "더 불러오지 못했어요. 잠시 뒤 다시 내려 보세요"
            else -> ""
        }
        footer.visibility = if (footer.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 관광지·코스 탭에서 목록 끝이 한 화면 안으로 들어오면 다음 곳들을 받아 붙인다. */
    private fun maybeLoadMore() {
        if (detailScreen.isShowing) return
        val tab = selectedTab
        val pager = pagerForTab() ?: return
        if (pager.done || loadMoreJob?.isActive == true) return
        if (System.currentTimeMillis() - loadMoreFailedAt < LOAD_MORE_RETRY_MS) return
        val scroll = findViewById<ScrollView>(R.id.listScroll)
        val content = scroll.getChildAt(0) ?: return
        val remaining = content.height + scroll.paddingBottom - (scroll.scrollY + scroll.height)
        if (remaining > scroll.height) return
        loadMoreJob = scope.launch {
            try {
                updateListFooter()
                pager.loadMore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Load more failed", e)
                loadMoreFailedAt = System.currentTimeMillis()
            }
            // 받는 사이 새 추천이 왔거나(다른 목록) 탭을 바꿨으면 그리지 않는다.
            if (pager !== pagerForTab() || selectedTab != tab) return@launch
            loadMoreJob = null
            // 다시 그려도 보던 자리를 지킨다. 받는 사이 분할 화면 전환으로 뷰가 바뀌었을 수 있어 다시 찾는다.
            val current = findViewById<ScrollView>(R.id.listScroll)
            val y = current.scrollY
            render()
            current.post { current.scrollTo(0, y) }
        }
    }

    companion object {
        private const val TAG = "DriveGuide"
        private const val LOAD_MORE_RETRY_MS = 5_000L
        /** layout-w1200dp와 같은 기준. 이보다 좁은 창에서는 하단 탭을 아이콘만 보인다. */
        private const val WIDE_WINDOW_DP = 1200
    }
}
