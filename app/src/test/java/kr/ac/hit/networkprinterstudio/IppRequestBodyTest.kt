package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IppRequestBodyTest {
    @Test
    fun writesHeaderThenDocumentWithoutChangingTheirBytes() {
        val header = byteArrayOf(2, 0, 0, 2, 0, 0, 0, 7, 3)
        val document = byteArrayOf(0, 1, -1, 3, 10, 13)
        val body = IppRequestBody(header, MemoryDocumentData(document))

        assertEquals((header.size + document.size).toLong(), body.length)
        assertArrayEquals(header + document, ByteArrayOutputStream().also(body::writeTo).toByteArray())
    }

    @Test
    fun transportAndDigestReopenAndCloseTheSameDocumentSource() {
        val source = TrackingSource("document".toByteArray())
        val prefix = "header".toByteArray()
        val body = IppRequestBody(prefix, source)
        val expected = prefix + source.bytes

        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), body.digest("SHA-256"))
        assertArrayEquals(expected, ByteArrayOutputStream().also(body::writeTo).toByteArray())
        assertArrayEquals(expected, ByteArrayOutputStream().also(body::writeTo).toByteArray())
        assertEquals(3, source.opens)
        assertEquals(3, source.closes)
    }

    @Test
    fun streamsLargeSourceInBoundedChunks() {
        val total = 3L * 1024L * 1024L + 17L
        var opens = 0
        var closes = 0
        var largestRead = 0
        val source = object : DocumentData {
            override val length = total
            override fun openStream(): InputStream {
                opens++
                return object : InputStream() {
                    var remaining = total
                    override fun read(): Int = if (remaining-- > 0) 0x5a else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        largestRead = maxOf(largestRead, length)
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        buffer.fill(0x5a.toByte(), offset, offset + count)
                        remaining -= count
                        return count
                    }
                    override fun close() { closes++ }
                }
            }
        }
        var written = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        }

        IppRequestBody(byteArrayOf(2, 0, 3), source).writeTo(output)

        assertEquals(total + 3L, written)
        assertTrue(largestRead <= DEFAULT_BUFFER_SIZE)
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test
    fun lengthUsesLongWithoutAllocatingOrOpeningDocument() {
        var opened = false
        val source = object : DocumentData {
            override val length = Int.MAX_VALUE.toLong() + 999L
            override fun openStream(): InputStream { opened = true; error("must remain unopened") }
        }
        val body = IppRequestBody(byteArrayOf(1, 2, 3), source)

        assertEquals(Int.MAX_VALUE.toLong() + 1002L, body.length)
        assertTrue(!opened)
    }

    @Test
    fun shortDocumentFailsAndClosesItsStream() {
        val source = TrackingSource(byteArrayOf(1), declaredLength = 2)

        val error = expectIOException { IppRequestBody(byteArrayOf(3), source).writeTo(ByteArrayOutputStream()) }

        assertTrue(error is DocumentDataException)
        assertTrue(error.message.orEmpty().contains("문서 길이"))
        assertEquals(1, source.closes)
    }

    @Test
    fun oversizedDocumentFailsAndClosesItsStream() {
        val source = TrackingSource(byteArrayOf(1, 2), declaredLength = 1)

        val error = expectIOException { IppRequestBody(byteArrayOf(3), source).digest("MD5") }

        assertTrue(error is DocumentDataException)
        assertEquals(1, source.closes)
    }

    @Test
    fun outputFailureClosesDocumentButDoesNotClaimSourceWasUnreadable() {
        val source = TrackingSource(ByteArray(20))
        val transportError = IOException("closed output")
        val output = object : OutputStream() {
            override fun write(value: Int) { throw transportError }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { throw transportError }
        }

        val error = expectIOException { source.writeTo(output) }

        assertTrue(error === transportError)
        assertEquals(1, source.closes)
    }

    @Test
    fun fileLengthIsFixedAndLaterChangesFailRatherThanTruncate() {
        val file = File.createTempFile("ipp-body-test", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val source = FileDocumentData(file)
            file.writeBytes(byteArrayOf(1, 2))

            assertEquals(3L, source.length)
            assertTrue(expectIOException { source.writeTo(ByteArrayOutputStream()) } is DocumentDataException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletedFileProducesDocumentErrorBeforeAnyRetry() {
        val file = File.createTempFile("ipp-body-test", ".bin")
        val source = FileDocumentData(file)
        file.delete()

        assertTrue(expectIOException { source.writeTo(ByteArrayOutputStream()) } is DocumentDataException)
    }

    @Test
    fun emptyDocumentStillWritesHeaderAndClosesSource() {
        val source = TrackingSource(byteArrayOf())
        val output = ByteArrayOutputStream()

        IppRequestBody(byteArrayOf(2, 0, 3), source).writeTo(output)

        assertArrayEquals(byteArrayOf(2, 0, 3), output.toByteArray())
        assertEquals(1, source.closes)
    }

    @Test
    fun zeroLengthReadsDoNotCauseInfiniteLoop() {
        val source = object : DocumentData {
            override val length = 2L
            override fun openStream(): InputStream = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            }
        }

        assertArrayEquals(byteArrayOf(1, 2), ByteArrayOutputStream().also(source::writeTo).toByteArray())
    }

    private class TrackingSource(val bytes: ByteArray, declaredLength: Int = bytes.size) : DocumentData {
        override val length = declaredLength.toLong()
        var opens = 0
        var closes = 0
        override fun openStream(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                private var closed = false
                override fun close() {
                    if (!closed) closes++
                    closed = true
                    super.close()
                }
            }
        }
    }

    private fun expectIOException(action: () -> Unit): IOException {
        try {
            action()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }
}
