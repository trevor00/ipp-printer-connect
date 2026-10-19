package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPaginationTest {
    @Test fun overflowContinuesWithoutLosingLines() {
        val lines = (1..101).map { "본문 $it" }
        val pages = TextPagination.pages(lines, 100f, 20f, 10f, 10f)
        assertEquals(8, pages.first().size)
        assertEquals(10, pages[1].size)
        assertEquals(lines, pages.flatten())
    }

    @Test fun exactFitDoesNotAddAnEmptyPage() {
        val lines = List(10) { "줄 $it" }
        assertEquals(listOf(lines), TextPagination.pages(lines, 100f, 0f, 10f, 10f))
        assertEquals(2, TextPagination.pages(lines + "다음 장", 100f, 0f, 10f, 10f).size)
    }

    @Test fun blankLinesAndLineEndingsArePreserved() {
        assertEquals(listOf("가", "", "나", "다", ""), TextPagination.wrap("가\r\n\r\n나\r다\n", 5f) { it.length.toFloat() })
    }

    @Test fun wrappingKeepsUnicodeCodePointsTogether() {
        assertEquals(listOf("😀", "가나", "😀"), TextPagination.wrap("😀가나😀", 2f) { it.length.toFloat() })
    }

    @Test fun largerFontProducesMorePagesWithoutDroppingText() {
        val lines = List(20) { "줄 $it" }
        val small = TextPagination.pages(lines, 100f, 0f, 10f, 10f)
        val large = TextPagination.pages(lines, 100f, 0f, 20f, 20f)
        assertTrue(large.size > small.size)
        assertEquals(lines, large.flatten())
    }

    @Test fun impossibleSettingsFailInsteadOfShrinkingOrTruncating() {
        assertTrue(runCatching { TextPagination.pages(listOf("본문"), 5f, 0f, 10f, 10f) }.isFailure)
        assertTrue(runCatching { TextPagination.wrap("가", 5f) { 10f } }.isFailure)
    }
}
