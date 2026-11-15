package zone.dal.driveguide.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAlongTextTest {

    private fun split(text: String) = ReadAlongText.sentences(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun splitsOnSentenceEndsAndNewlines() {
        assertEquals(
            listOf("정자동 카페거리는 분당에 있다.", "테라스가 많다!", "(정자동)이라고도 한다.", "끝"),
            split("정자동 카페거리는 분당에 있다. 테라스가 많다!\n(정자동)이라고도 한다.  끝"),
        )
    }

    @Test
    fun keepsClosingQuoteWithSentenceAndIgnoresDotsInsideWords() {
        assertEquals(
            listOf("\"좋다.\"", "버전 2.1을 쓴다."), split("\"좋다.\" 버전 2.1을 쓴다."),
        )
    }

    @Test
    fun emptyOrBlankTextHasNoSentences() {
        assertEquals(emptyList<String>(), split("  \n "))
    }

    @Test
    fun wordStartMovesToBeginningOfTappedWord() {
        val text = "분당 정자동 카페거리"
        assertEquals(3, ReadAlongText.wordStart(text, 5)) // "정자동" 안
        assertEquals(7, ReadAlongText.wordStart(text, 6)) // 공백 위면 다음 낱말
        assertEquals(0, ReadAlongText.wordStart(text, 0))
    }

    @Test
    fun sentenceAtFindsContainingOrNextSentence() {
        val text = "가나다. 라마바. 사아"
        val s = ReadAlongText.sentences(text)
        assertEquals(0, ReadAlongText.sentenceAt(s, 2))
        assertEquals(1, ReadAlongText.sentenceAt(s, 4)) // 문장 사이 공백 → 다음 문장
        assertEquals(2, ReadAlongText.sentenceAt(s, 11))
        assertEquals(-1, ReadAlongText.sentenceAt(s, 99))
    }
}
