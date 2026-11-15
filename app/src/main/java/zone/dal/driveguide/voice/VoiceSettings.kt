package zone.dal.driveguide.voice

import android.content.Context

/** 음성 안내 설정. 기기에 저장해 앱을 다시 켜도 유지한다. */
object VoiceSettings {
    private const val PREFS = "settings"
    private const val KEY_AUTO = "voice_auto_announce"

    /**
     * 자동 안내(지역 진입·새 추천 장소, 코스 구간 안내)를 소리 내어 할지. 기본은 켜짐.
     * 사용자가 직접 누르는 읽어주기는 이 설정과 관계없이 동작한다.
     */
    fun autoAnnounce(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoAnnounce(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
