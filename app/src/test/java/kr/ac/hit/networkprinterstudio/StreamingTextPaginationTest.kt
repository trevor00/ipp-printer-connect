package kr.ac.hit.networkprinterstudio

import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextPaginationTest {
    @Test fun samePagesAsExistingWrapperForUnicodeWhitespaceAndChunkBoundaries() {
        val examples = listOf("", "\n", "\r", "가\r\n\r\n나\r다\n", "😀가나😺\r\n끝\r", "  가 나  \n\n", "각가\u0301😀끝", "a".repeat(113))
        for (text in examples) for (chunk in 1..5) for (width in listOf(2f, 5f, 17f)) for (header in listOf(0f, 9f)) {
            val expected = TextPagination.pages(TextPagination.wrap(text, width) { it.length.toFloat() }, 50f, header, 10f, 12f)
            val actual = mutableListOf<List<String>>()
            val count = StreamingTextPagination.forEachPage(ChunkReader(text, chunk), width, 50f, header, 10f, 12f, { it.length.toFloat() }) { actual.add(it) }
            assertEquals("chunk=$chunk width=$width header=$header text=$text", expected, actual)
            assertEquals(expected.size, count)
        }
    }

    @Test fun exactPageEndAndTrailingNewlineMatchExistingPagination() {
        for (text in listOf("a\nb", "a\nb\n", "a\nb\r\n", "a\nb\rc", "😀\r\n😺")) {
            val actual = mutableListOf<List<String>>()
            StreamingTextPagination.forEachPage(ChunkReader(text, 1), 20f, 20f, 0f, 10f, 10f, { it.length.toFloat() }) { actual.add(it) }
            assertEquals(TextPagination.pages(TextPagination.wrap(text, 20f) { it.length.toFloat() }, 20f, 0f, 10f, 10f), actual)
        }
    }

    @Test fun oneLongSourceLineEmitsPagesWithoutRetainingTheParagraph() {
        val text = "한".repeat(100_003)
        var emittedCharacters = 0
        var emittedPages = 0
        val count = StreamingTextPagination.forEachPage(StringReader(text), 10f, 50f, 0f, 10f, 10f, { it.length.toFloat() }) { page ->
            assertTrue(page.size in 1..5)
            assertTrue(page.all { it.length in 1..10 })
            emittedCharacters += page.sumOf { it.length }
            emittedPages++
        }
        assertEquals(text.length, emittedCharacters)
        assertEquals(2001, count)
        assertEquals(count, emittedPages)
    }

    @Test fun explicitPageLimitStopsParsingAndEmittingAtTheRequestedLastPage() {
        val text = "a\n".repeat(100_000)
        val reader = CountingReader(text)
        val pages = mutableListOf<List<String>>()
        val count = StreamingTextPagination.forEachPage(
            reader, 10f, 20f, 0f, 10f, 10f, { it.length.toFloat() }, maxPages = 3,
        ) { pages.add(it) }
        assertEquals(3, count)
        assertEquals(3, pages.size)
        assertTrue("page-range parsing should stop before EOF", reader.consumed < text.length)
    }

    @Test fun invalidSurrogatesFailInsteadOfDroppingCharacters() {
        for (text in listOf("a\uD83D", "\uDE00", "\uD83Da")) {
            val result = runCatching { StreamingTextPagination.forEachPage(ChunkReader(text, 1), 10f, 50f, 0f, 10f, 10f, { it.length.toFloat() }) {} }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun pathologicalZeroAdvanceLineFailsInsteadOfTruncatingOrShrinking() {
        var pages = 0
        val result = runCatching {
            StreamingTextPagination.forEachPage(StringReader("\u0301".repeat(65_537)), 10f, 50f, 0f, 10f, 10f, { 0f }) { pages++ }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("자르거나 글자 크기를 자동 변경하지 않았습니다"))
        assertEquals(0, pages)
    }

    @Test fun readFailureAndPageSinkFailureAreNotSwallowed() {
        val ioFailure = java.io.IOException("read failed")
        val brokenReader = object : Reader() {
            override fun read(destination: CharArray, offset: Int, length: Int): Int = throw ioFailure
            override fun close() = Unit
        }
        assertEquals(ioFailure, runCatching { StreamingTextPagination.forEachPage(brokenReader, 10f, 50f, 0f, 10f, 10f, { 1f }) {} }.exceptionOrNull())
        val sinkFailure = java.io.IOException("page failed")
        assertEquals(sinkFailure, runCatching { StreamingTextPagination.forEachPage(StringReader("a"), 10f, 50f, 0f, 10f, 10f, { 1f }) { throw sinkFailure } }.exceptionOrNull())
    }

    @Test fun interruptionIsObservedBeforeReadingOrEmitting() {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            Thread.currentThread().interrupt()
            failure.set(runCatching { StreamingTextPagination.forEachPage(StringReader("a"), 10f, 50f, 0f, 10f, 10f, { 1f }) {} }.exceptionOrNull())
        }
        thread.start(); thread.join(5000)
        assertTrue(failure.get() is CancellationException)
    }

    private class ChunkReader(private val text: String, private val chunk: Int) : Reader() {
        private var cursor = 0
        override fun read(destination: CharArray, offset: Int, length: Int): Int {
            if (cursor == text.length) return -1
            val count = minOf(chunk, length, text.length - cursor)
            text.toCharArray(destination, offset, cursor, cursor + count)
            cursor += count
            return count
        }
        override fun close() = Unit
    }

    private class CountingReader(private val text: String) : Reader() {
        var consumed = 0
            private set
        override fun read(destination: CharArray, offset: Int, length: Int): Int {
            if (consumed == text.length) return -1
            val count = minOf(length, text.length - consumed)
            text.toCharArray(destination, offset, consumed, consumed + count)
            consumed += count
            return count
        }
        override fun close() = Unit
    }
}
