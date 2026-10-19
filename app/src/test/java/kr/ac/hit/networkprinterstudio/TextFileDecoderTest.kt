package kr.ac.hit.networkprinterstudio

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextFileDecoderTest {
    private val document = "한글 출력 시험\r\n두 번째 줄 😀\n마지막 줄"

    @Test
    fun automaticUtf8KeepsKoreanEmojiAndLineBreaks() {
        val decoded = TextFileDecoder.decode(document.toByteArray(Charsets.UTF_8))
        assertEquals(document, decoded.text)
        assertEquals("UTF-8", decoded.charsetName)
    }

    @Test
    fun automaticCp949KeepsKoreanText() {
        val text = "한글 출력 시험\r\n두 번째 줄\n마지막 줄"
        val decoded = TextFileDecoder.decode(text.toByteArray(Charset.forName("MS949")))
        assertEquals(text, decoded.text)
        assertEquals("CP949", decoded.charsetName)
    }

    @Test
    fun automaticBomSelectsEncodingAndRemovesOnlyLeadingBom() {
        val text = "$document\uFEFF"
        val examples = listOf(
            Triple(bytes(0xef, 0xbb, 0xbf), Charsets.UTF_8, "UTF-8"),
            Triple(bytes(0xff, 0xfe), Charsets.UTF_16LE, "UTF-16LE"),
            Triple(bytes(0xfe, 0xff), Charsets.UTF_16BE, "UTF-16BE"),
        )
        for ((bom, charset, name) in examples) {
            val decoded = TextFileDecoder.decode(bom + text.toByteArray(charset))
            assertEquals(text, decoded.text)
            assertEquals(name, decoded.charsetName)
        }
    }

    @Test
    fun explicitEncodingDecodesBomlessUtf16AndMatchingBom() {
        for ((encoding, charset, bom) in listOf(
            Triple(TextFileEncoding.UTF16_LE, Charsets.UTF_16LE, bytes(0xff, 0xfe)),
            Triple(TextFileEncoding.UTF16_BE, Charsets.UTF_16BE, bytes(0xfe, 0xff)),
        )) {
            val payload = document.toByteArray(charset)
            assertEquals(document, TextFileDecoder.decode(payload, encoding).text)
            assertEquals(document, TextFileDecoder.decode(bom + payload, encoding).text)
        }
    }

    @Test
    fun explicitEncodingDoesNotFallBackToAnotherEncoding() {
        val cp949 = "한글".toByteArray(Charset.forName("MS949"))
        assertFailure("UTF-8") { TextFileDecoder.decode(cp949, TextFileEncoding.UTF8) }
        assertEquals("한글", TextFileDecoder.decode(cp949, TextFileEncoding.CP949).text)
        assertFailure("다릅니다") {
            TextFileDecoder.decode(bytes(0xff, 0xfe) + document.toByteArray(Charsets.UTF_16LE), TextFileEncoding.UTF16_BE)
        }
        assertFailure("다릅니다") {
            TextFileDecoder.decode(bytes(0xef, 0xbb, 0xbf) + "hello".toByteArray(), TextFileEncoding.CP949)
        }
    }

    @Test
    fun utf8AndCp949DecodingRejectBrokenCharacters() {
        assertFailure("UTF-8") { TextFileDecoder.decode(bytes(0xc3, 0x28), TextFileEncoding.UTF8) }
        assertFailure("CP949") { TextFileDecoder.decode(bytes(0x81), TextFileEncoding.CP949) }
        assertFailure("인코딩을 확인할 수 없습니다") { TextFileDecoder.decode(bytes(0xff)) }
        // A BOM is authoritative: malformed UTF-8 does not fall back to CP949.
        assertFailure("UTF-8") { TextFileDecoder.decode(bytes(0xef, 0xbb, 0xbf, 0xc3, 0x28)) }
    }

    @Test
    fun utf16RejectsOddByteCountAndUnpairedSurrogates() {
        for (invalid in listOf(bytes(0x41), bytes(0x00, 0xd8), bytes(0x00, 0xdc), bytes(0x00, 0xd8, 0x41, 0x00))) {
            assertFailure("UTF-16LE") { TextFileDecoder.decode(invalid, TextFileEncoding.UTF16_LE) }
            assertFailure("UTF-16LE") { TextFileDecoder.decode(bytes(0xff, 0xfe) + invalid) }
        }
        assertFailure("UTF-16BE") { TextFileDecoder.decode(bytes(0xd8, 0x00), TextFileEncoding.UTF16_BE) }
    }

    @Test
    fun emptyTextAndLiteralReplacementCharacterArePreserved() {
        assertEquals("", TextFileDecoder.decode(byteArrayOf()).text)
        assertEquals("", TextFileDecoder.decode(bytes(0xff, 0xfe)).text)
        assertEquals("문서 \uFFFD", TextFileDecoder.decode("문서 \uFFFD".toByteArray(Charsets.UTF_8)).text)
    }

    @Test
    fun utf32BomIsNotMistakenForUtf16() {
        assertFailure("UTF-32") { TextFileDecoder.decode(bytes(0xff, 0xfe, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00)) }
        assertFailure("UTF-32") { TextFileDecoder.decode(bytes(0x00, 0x00, 0xfe, 0xff, 0x00, 0x00, 0x00, 0x41)) }
    }

    @Test
    fun automaticErrorsDoNotReferToRemovedEncodingControls() {
        for (invalid in listOf(bytes(0xff), bytes(0xef, 0xbb, 0xbf, 0xc3, 0x28), bytes(0xff, 0xfe, 0x41))) {
            val message = runCatching { TextFileDecoder.decode(invalid) }.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("UTF-8로 저장한 파일"))
            assertFalse(message.contains("직접 선택"))
            assertFalse(message.contains("인코딩 선택"))
        }
    }

    private fun assertFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("인코딩 오류를 안내해야 합니다.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message, error.message.orEmpty().contains(message))
        }
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
