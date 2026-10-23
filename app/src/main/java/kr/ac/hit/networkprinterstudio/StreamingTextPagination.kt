package kr.ac.hit.networkprinterstudio

import java.io.Reader
import java.util.concurrent.CancellationException
import kotlin.math.floor

/** Same code-point wrapping and page capacities as TextPagination, without complete-text lists. */
internal object StreamingTextPagination {
    private const val MAX_LINE_CHARACTERS = 65_536

    fun forEachPage(
        reader: Reader,
        width: Float,
        height: Float,
        firstPageHeader: Float,
        glyphHeight: Float,
        lineHeight: Float,
        measure: (String) -> Float,
        maxPages: Int? = null,
        emit: (List<String>) -> Unit,
    ): Int {
        require(maxPages == null || maxPages > 0) { "최대 페이지 수는 1 이상이어야 합니다." }
        require(width.isFinite() && width > 0) { "텍스트를 배치할 가로 공간이 없습니다. 여백을 확인해 주세요." }
        require(height.isFinite() && firstPageHeader.isFinite() && glyphHeight.isFinite() && lineHeight.isFinite() &&
            firstPageHeader >= 0 && glyphHeight > 0 && lineHeight > 0) { "텍스트 페이지 설정이 올바르지 않습니다." }
        fun capacity(header: Float): Int {
            val available = height - header
            require(available >= glyphHeight) { "선택한 글자 크기와 여백으로 한 줄을 배치할 수 없습니다. 글자 크기나 여백을 확인해 주세요." }
            return (floor(((available - glyphHeight) / lineHeight).toDouble()).toInt() + 1).coerceAtLeast(1)
        }
        val firstCapacity = capacity(firstPageHeader)
        val laterCapacity = capacity(0f)
        val page = ArrayList<String>()
        val line = StringBuilder()
        var pageCount = 0
        var stoppedAtPageLimit = false
        fun flushPage() {
            cancelled()
            emit(page.toList())
            page.clear()
            pageCount++
            stoppedAtPageLimit = maxPages != null && pageCount >= maxPages
        }
        fun finishLine() {
            cancelled()
            page.add(line.toString())
            line.setLength(0)
            if (page.size == if (pageCount == 0) firstCapacity else laterCapacity) flushPage()
        }
        var afterCr = false
        fun accept(character: String) {
            cancelled()
            if (afterCr && character == "\n") { afterCr = false; return }
            afterCr = false
            if (character == "\r" || character == "\n") {
                finishLine()
                afterCr = character == "\r"
                return
            }
            require(measure(character) <= width) { "선택한 글자 크기로 한 글자도 용지 폭에 들어가지 않습니다. 글자 크기나 여백을 확인해 주세요." }
            val candidate = line.toString() + character
            if (line.isNotEmpty() && measure(candidate) > width) {
                finishLine()
                if (stoppedAtPageLimit) return
            }
            require(line.length.toLong() + character.length <= MAX_LINE_CHARACTERS) {
                "전송 실패: 한 줄의 결합문자 등 글자 수가 처리 가능한 범위를 넘었습니다. 내용을 자르거나 글자 크기를 자동 변경하지 않았습니다. 문서를 PDF로 저장해 다시 선택해 주세요."
            }
            line.append(character)
        }
        val characters = CharArray(8192)
        var highSurrogate: Char? = null
        while (!stoppedAtPageLimit) {
            cancelled()
            val count = reader.read(characters)
            cancelled()
            if (count < 0) break
            for (index in 0 until count) {
                val character = characters[index]
                val high = highSurrogate
                if (high != null) {
                    require(Character.isLowSurrogate(character)) { "텍스트에 올바르지 않은 유니코드 문자가 있습니다." }
                    accept(String(charArrayOf(high, character)))
                    highSurrogate = null
                } else if (Character.isHighSurrogate(character)) {
                    highSurrogate = character
                } else {
                    require(!Character.isLowSurrogate(character)) { "텍스트에 올바르지 않은 유니코드 문자가 있습니다." }
                    accept(character.toString())
                }
                if (stoppedAtPageLimit) break
            }
        }
        if (!stoppedAtPageLimit) {
            require(highSurrogate == null) { "텍스트 끝에 올바르지 않은 유니코드 문자가 있습니다." }
            // split('\n') retains the final empty line; do not silently remove it at EOF.
            finishLine()
            if (page.isNotEmpty()) flushPage()
        }
        return pageCount
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("텍스트 페이지 생성을 취소했습니다.")
    }
}
