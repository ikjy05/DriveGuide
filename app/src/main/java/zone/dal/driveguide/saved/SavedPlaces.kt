package zone.dal.driveguide.saved

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import zone.dal.driveguide.tour.Place
import zone.dal.driveguide.tour.PlaceType

/**
 * 나중에 가 볼 장소(즐겨찾기). 계정 없이 이 기기에만 저장한다.
 * 통신이 끊겨도 목록이 보이도록 장소 정보를 통째로 넣고, 최근에 저장한 곳이 앞에 온다.
 * 화면과 알림([SavePlaceReceiver])이 함께 쓰므로 값은 매번 SharedPreferences에서 읽는다.
 */
class SavedPlaces(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Place> = decode(prefs.getString(KEY_PLACES, null))

    fun contains(place: Place): Boolean = all().any { it.id == place.id }

    fun add(place: Place) = write(added(all(), place))

    fun remove(place: Place) = write(all().filterNot { it.id == place.id })

    /** 저장돼 있으면 빼고 없으면 넣는다. 저장된 상태가 되면 true. */
    fun toggle(place: Place): Boolean {
        val saved = !contains(place)
        if (saved) add(place) else remove(place)
        return saved
    }

    /** 목록이 바뀌면 메인 스레드에서 [listener]를 부른다. */
    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun write(places: List<Place>) {
        prefs.edit().putString(KEY_PLACES, encode(places)).apply()
    }

    companion object {
        private const val PREFS = "saved_places"
        private const val KEY_PLACES = "places"

        /** 같은 장소는 하나만 두고, 다시 저장하면 맨 앞으로 옮긴다. */
        internal fun added(places: List<Place>, place: Place): List<Place> =
            listOf(place) + places.filterNot { it.id == place.id }

        internal fun encode(places: List<Place>): String =
            JSONArray().apply { places.forEach { put(toJson(it)) } }.toString()

        internal fun decode(text: String?): List<Place> {
            if (text == null) return emptyList()
            return try {
                val array = JSONArray(text)
                List(array.length()) { fromJson(array.getJSONObject(it)) }
            } catch (e: JSONException) {
                emptyList() // 깨진 값은 버린다. 다음 저장에서 새로 쓴다.
            }
        }

        /** 알림 버튼의 Intent에 장소를 그대로 실어 보낼 때 쓴다. */
        fun encodePlace(place: Place): String = toJson(place).toString()

        fun decodePlace(text: String): Place = fromJson(JSONObject(text))

        // 거리는 저장할 때의 값이 곧 틀려지므로 넣지 않는다. 화면에서 현재 위치로 다시 잰다.
        private fun toJson(place: Place) = JSONObject()
            .put("id", place.id)
            .put("type", place.type.name)
            .put("title", place.title)
            .put("address", place.address)
            .put("latitude", place.latitude)
            .put("longitude", place.longitude)
            .put("imageUrl", place.imageUrl ?: JSONObject.NULL)

        private fun fromJson(json: JSONObject) = Place(
            id = json.getString("id"),
            type = PlaceType.valueOf(json.getString("type")),
            title = json.getString("title"),
            address = json.getString("address"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            distanceMeters = 0.0,
            imageUrl = if (json.isNull("imageUrl")) null else json.getString("imageUrl"),
        )
    }
}
