package kr.ac.hit.networkprinterstudio

import kotlin.math.floor

/** Reference pagination used by the streaming pagination unit tests. */
internal object TextPagination {
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        require(width.isFinite() && width > 0) { "텍스트를 배치할 가로 공간이 없습니다. 여백을 확인해 주세요." }
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n').flatMap { paragraph ->
            val lines = mutableListOf<String>()
            var line = ""
            var index = 0
            while (index < paragraph.length) {
                val end = index + Character.charCount(paragraph.codePointAt(index))
                val character = paragraph.substring(index, end)
                require(measure(character) <= width) { "선택한 글자 크기로 한 글자도 용지 폭에 들어가지 않습니다. 글자 크기나 여백을 확인해 주세요." }
                if (line.isNotEmpty() && measure(line + character) > width) {
                    lines += line
                    line = character
                } else line += character
                index = end
            }
            lines += line
            lines
        }
    }

    fun pages(lines: List<String>, height: Float, firstPageHeader: Float, glyphHeight: Float, lineHeight: Float): List<List<String>> {
        require(height.isFinite() && firstPageHeader.isFinite() && glyphHeight.isFinite() && lineHeight.isFinite() &&
            firstPageHeader >= 0 && glyphHeight > 0 && lineHeight > 0) { "텍스트 페이지 설정이 올바르지 않습니다." }
        fun capacity(header: Float): Int {
            val available = height - header
            require(available >= glyphHeight) { "선택한 글자 크기와 여백으로 한 줄을 배치할 수 없습니다. 글자 크기나 여백을 확인해 주세요." }
            return (floor(((available - glyphHeight) / lineHeight).toDouble()).toInt() + 1).coerceAtLeast(1)
        }
        val first = capacity(firstPageHeader)
        val later = capacity(0f)
        val result = mutableListOf<List<String>>()
        var start = 0
        while (start < lines.size) {
            val count = if (result.isEmpty()) first else later
            val end = minOf(lines.size.toLong(), start.toLong() + count).toInt()
            result += lines.subList(start, end).toList()
            start = end
        }
        return result
    }
}
