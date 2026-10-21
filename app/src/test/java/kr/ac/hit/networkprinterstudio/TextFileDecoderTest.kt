package kr.ac.hit.networkprinterstudio

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextFileDecoderTest {
    private val document = "한글 출력 시험\r\n두 번째 줄 😀\n마지막 줄"
    @get:Rule val temporaryFolder = TemporaryFolder()

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

    @Test
    fun fileDecoderMatchesByteDecoderForAutomaticUtf8AndCp949() {
        for (text in listOf("", "plain text\r\n", "한글 출력\r\n문서\n", "$document\uFEFF", "문서 \uFFFD")) {
            assertFileMatchesBytes(text.toByteArray(Charsets.UTF_8))
        }
        assertFileMatchesBytes("한글 출력 시험\r\n두 번째 줄\n마지막 줄".toByteArray(Charset.forName("MS949")))
    }

    @Test
    fun fileBomSelectsEncodingAndPreservesAdditionalBomCharacters() {
        val examples = listOf(
            bytes(0xef, 0xbb, 0xbf) to Charsets.UTF_8,
            bytes(0xff, 0xfe) to Charsets.UTF_16LE,
            bytes(0xfe, 0xff) to Charsets.UTF_16BE,
        )
        for ((bom, charset) in examples) {
            assertFileMatchesBytes(bom)
            val payload = bom + "\uFEFF$document\uFEFF".toByteArray(charset)
            assertFileMatchesBytes(payload)
            assertEquals("\uFEFF$document\uFEFF", TextFileDecoder.decode(textFile(payload)).text)
        }
    }

    @Test
    fun fileDecoderPreservesMultibyteCharactersAcrossReadBuffers() {
        val text = "a".repeat(8191) + "한글😀\r\n" + "나".repeat(20000) + "\n끝\uFEFF"
        assertFileMatchesBytes(text.toByteArray(Charsets.UTF_8))
        assertFileMatchesBytes(bytes(0xff, 0xfe) + text.toByteArray(Charsets.UTF_16LE))
        assertFileMatchesBytes(bytes(0xfe, 0xff) + text.toByteArray(Charsets.UTF_16BE))
        assertFileMatchesBytes(text.replace("😀", "").toByteArray(Charset.forName("MS949")))
    }

    @Test
    fun fileDecoderMatchesAuthoritativeBomAndInvalidEncodingErrors() {
        val invalidFiles = listOf(
            bytes(0xff), bytes(0x81),
            bytes(0xef, 0xbb, 0xbf, 0xc3, 0x28),
            bytes(0xff, 0xfe, 0x41), bytes(0xff, 0xfe, 0x00, 0xd8),
            bytes(0xfe, 0xff, 0xd8, 0x00),
            bytes(0xff, 0xfe, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00),
            bytes(0x00, 0x00, 0xfe, 0xff, 0x00, 0x00, 0x00, 0x41),
        )
        for (payload in invalidFiles) {
            val byteError = runCatching { TextFileDecoder.decode(payload) }.exceptionOrNull()
            val fileError = runCatching { TextFileDecoder.decode(textFile(payload)) }.exceptionOrNull()
            assertTrue(byteError is IllegalArgumentException)
            assertTrue(fileError is IllegalArgumentException)
            assertEquals(byteError?.message, fileError?.message)
        }
    }

    @Test
    fun fileReadErrorsAreNotReportedAsEncodingFailures() {
        for (file in listOf(File(temporaryFolder.root, "missing.txt"), temporaryFolder.newFolder("directory"))) {
            val error = runCatching { TextFileDecoder.decode(file) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains("텍스트 인코딩을 확인할 수 없습니다"))
        }
    }

    @Test
    fun cancelledFileReadDoesNotAttemptAnotherEncodingOrClearInterruptFlag() {
        val file = textFile(document.toByteArray(Charsets.UTF_8))
        val failure = AtomicReference<Throwable?>()
        val interruptPreserved = AtomicReference(false)
        val readerThread = Thread {
            Thread.currentThread().interrupt()
            failure.set(runCatching { TextFileDecoder.decode(file) }.exceptionOrNull())
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }
        readerThread.start()
        readerThread.join(5000)
        assertFalse("취소된 파일 읽기가 끝나야 합니다.", readerThread.isAlive)
        assertTrue(failure.get() is InterruptedIOException)
        assertTrue(interruptPreserved.get())
    }

    @Test
    fun validatedReaderMatchesAutomaticDecodeWithoutCreatingACompleteStringInValidation() {
        val fixtures = listOf(
            document.toByteArray(Charsets.UTF_8),
            bytes(0xef, 0xbb, 0xbf) + "\uFEFF$document\uFEFF".toByteArray(Charsets.UTF_8),
            bytes(0xff, 0xfe) + document.toByteArray(Charsets.UTF_16LE),
            bytes(0xfe, 0xff) + document.toByteArray(Charsets.UTF_16BE),
            "한글\r\n끝\n".toByteArray(Charset.forName("MS949")),
        )
        for (payload in fixtures) {
            val source = TextFileDecoder.validate(textFile(payload))
            val expected = TextFileDecoder.decode(payload)
            assertEquals(expected.charsetName, source.charsetName)
            assertTrue(source.hasVisibleCharacters)
            assertEquals(expected.text, TextFileDecoder.openReader(source).use { it.readText() })
        }
    }

    @Test
    fun utf8FailureAtEndIsFullyRevalidatedAsCp949BeforeReaderIsReturned() {
        val text = "a".repeat(25_000) + "한글"
        val source = TextFileDecoder.validate(textFile(text.toByteArray(Charset.forName("MS949"))))
        assertEquals("CP949", source.charsetName)
        assertEquals(text, TextFileDecoder.openReader(source).use { it.readText() })
    }

    @Test
    fun validationDetectsWhitespaceOnlyDocumentsBeforePagination() {
        for (text in listOf("", " \t\r\n", "\u2003\u3000\r")) {
            assertFalse(TextFileDecoder.validate(textFile(text.toByteArray(Charsets.UTF_8))).hasVisibleCharacters)
            assertFalse(TextFileDecoder.validate(textFile(bytes(0xff, 0xfe) + text.toByteArray(Charsets.UTF_16LE))).hasVisibleCharacters)
        }
        assertTrue(TextFileDecoder.validate(textFile(bytes(0xef, 0xbb, 0xbf) + "\uFEFF".toByteArray(Charsets.UTF_8))).hasVisibleCharacters)
    }

    @Test
    fun invalidEncodingAndBomStillFailDuringValidation() {
        for (payload in listOf(bytes(0xff), bytes(0xef, 0xbb, 0xbf, 0xc3, 0x28), bytes(0xff, 0xfe, 0x41), bytes(0xff, 0xfe, 0, 0))) {
            val expected = runCatching { TextFileDecoder.decode(payload) }.exceptionOrNull()
            val actual = runCatching { TextFileDecoder.validate(textFile(payload)) }.exceptionOrNull()
            assertEquals(expected?.message, actual?.message)
        }
    }

    @Test
    fun validatedReaderRejectsChangedFilesAndValidationPreservesIoFailures() {
        val file = textFile(document.toByteArray(Charsets.UTF_8))
        val source = TextFileDecoder.validate(file)
        file.appendText("changed")
        assertTrue(runCatching { TextFileDecoder.openReader(source) }.exceptionOrNull()?.message.orEmpty().contains("변경"))
        assertTrue(runCatching { TextFileDecoder.validate(File(temporaryFolder.root, "does_not_exist.txt")) }.exceptionOrNull() is IOException)
    }

    private fun textFile(bytes: ByteArray): File = temporaryFolder.newFile().apply { writeBytes(bytes) }

    private fun assertFileMatchesBytes(bytes: ByteArray) {
        assertEquals(TextFileDecoder.decode(bytes), TextFileDecoder.decode(textFile(bytes)))
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
