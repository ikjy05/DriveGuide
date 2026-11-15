package zone.dal.driveguide.voice

/**
 * 소개글을 따라 읽기(읽은 글자를 채워 나가기)에 쓰는 문장·낱말 경계 계산.
 * Pleos TTS는 몇 번째 글자를 읽는지 알려 주지 않으므로 문장 단위로 끊어 읽고, 문장 안에서는 시간으로 어림한다.
 */
object ReadAlongText {

    /**
     * [text]를 문장으로 나눈다. 각 범위는 [시작, 끝)이고 앞뒤 공백은 뺀다.
     * 마침표·물음표·느낌표(뒤따르는 따옴표·괄호 포함) 다음에 공백이 오거나, 줄바꿈이 나오면 문장을 끊는다.
     */
    fun sentences(text: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            var end = -1
            if (c == '\n') {
                end = i
            } else if (c in ENDERS) {
                var j = i + 1
                while (j < text.length && text[j] in CLOSERS) j++
                if (j >= text.length || text[j].isWhitespace()) end = j
                i = j - 1
            }
            if (end >= 0) {
                addTrimmed(text, start, end, result)
                start = end
            }
            i++
        }
        addTrimmed(text, start, text.length, result)
        return result
    }

    /** [offset]이 속한 낱말의 첫 글자 위치. 공백 위면 다음 낱말의 첫 글자. */
    fun wordStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        if (i < text.length && text[i].isWhitespace()) {
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    /** [offset]을 포함하거나 그 뒤에 처음 오는 문장의 번호. 없으면 -1. */
    fun sentenceAt(sentences: List<IntRange>, offset: Int): Int =
        sentences.indexOfFirst { offset <= it.last }

    private fun addTrimmed(text: String, from: Int, to: Int, out: MutableList<IntRange>) {
        var a = from
        var b = to
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a < b) out += a until b
    }

    private const val ENDERS = ".!?。"
    private const val CLOSERS = "\"'”’)]」』"
}
