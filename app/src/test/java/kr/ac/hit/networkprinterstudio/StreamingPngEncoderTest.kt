package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPngEncoderTest {
    @Test
    fun imageIoDecodesExactRgbPixelsAcrossBatchBoundaries() {
        val width = 129
        val height = 17
        val rgb = ByteArray(width * height * 3) { ((it * 37 + it / 19) and 255).toByte() }
        val encoded = encode(width, height, 600, rgb, listOf(3, 1, 7, 6))
        val image = ImageIO.read(ByteArrayInputStream(encoded))

        assertNotNull(image)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (y * width + x) * 3
            val expected = ((rgb[offset].toInt() and 255) shl 16) or
                ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
            assertEquals("pixel $x,$y", expected, image.getRGB(x, y) and 0xffffff)
        }
    }

    @Test
    fun bytesAndFiltersAreIndependentOfBatchSize() {
        val width = 43
        val height = 13
        val rgb = ByteArray(width * height * 3) { ((it * 29 + it / 37) and 255).toByte() }

        val whole = encode(width, height, 300, rgb, listOf(height))
        val singleRows = encode(width, height, 300, rgb, List(height) { 1 })
        val mixed = encode(width, height, 300, rgb, listOf(2, 4, 1, 3, 3))

        assertArrayEquals(whole, singleRows)
        assertArrayEquals(whole, mixed)
    }

    @Test
    fun metadataContainsExactGeometryAndSelectedPhysicalDensity() {
        val png = encode(2, 3, 1200, ByteArray(18), listOf(1, 2))
        val chunks = chunks(png)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), png.copyOf(8))
        assertEquals(listOf("IHDR", "pHYs", "IDAT", "IEND"), chunks.map { it.type })
        val ihdr = chunks.single { it.type == "IHDR" }.bytes
        assertEquals(2, intAt(ihdr, 0))
        assertEquals(3, intAt(ihdr, 4))
        assertArrayEquals(byteArrayOf(8, 2, 0, 0, 0), ihdr.copyOfRange(8, 13))
        val physical = chunks.single { it.type == "pHYs" }.bytes
        val expected = Math.round(1200.0 / 0.0254).toInt()
        assertEquals(expected, intAt(physical, 0))
        assertEquals(expected, intAt(physical, 4))
        assertEquals(1, physical[8].toInt())
    }

    @Test
    fun everyChunkHasItsOwnValidCrc() {
        val rgb = ByteArray(257 * 200 * 3).also(Random(7)::nextBytes)
        val png = encode(257, 200, 150, rgb, List(200) { 1 })
        val chunks = chunks(png)

        assertTrue(chunks.count { it.type == "IDAT" } > 1)
        chunks.forEach { chunk ->
            val crc = CRC32()
            crc.update(chunk.type.toByteArray(Charsets.US_ASCII))
            crc.update(chunk.bytes)
            assertEquals(chunk.type, crc.value, chunk.crc)
            if (chunk.type == "IDAT") assertTrue(chunk.bytes.size <= StreamingPngEncoder.IDAT_CHUNK_SIZE)
        }
        assertEquals("IEND", chunks.last().type)
        assertTrue(chunks.last().bytes.isEmpty())
    }

    @Test
    fun idatChunksFormOneContinuousZlibStream() {
        val width = 257
        val height = 200
        val rgb = ByteArray(width * height * 3).also(Random(11)::nextBytes)
        val png = encode(width, height, 600, rgb, List(height) { 1 })
        val compressed = ByteArrayOutputStream().apply {
            chunks(png).filter { it.type == "IDAT" }.forEach { write(it.bytes) }
        }.toByteArray()
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val raw = ByteArray(height * (width * 3 + 1))
            var read = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(raw, read, raw.size - read)
                if (count == 0 && !inflater.finished()) throw AssertionError("zlib stream did not finish")
                read += count
            }
            assertEquals(raw.size, read)
            assertEquals(0, inflater.remaining)
            assertArrayEquals(rgb, unfilter(raw, width, height))
        } finally {
            inflater.end()
        }
    }

    @Test
    fun adaptiveFiltersUseSubAndUpAndCompressRepeatedRows() {
        val width = 1024
        val height = 64
        val row = ByteArray(width * 3) { index -> ((index / 3 + index % 3 * 7) and 255).toByte() }
        val output = ByteArrayOutputStream()
        StreamingPngEncoder(width, height, 300, output).use { encoder ->
            repeat(height) { encoder.writeRgbRows(row, 1) }
            encoder.finish()
        }
        val idat = ByteArrayOutputStream().apply {
            chunks(output.toByteArray()).filter { it.type == "IDAT" }.forEach { write(it.bytes) }
        }.toByteArray()
        val raw = InflaterInputStream(ByteArrayInputStream(idat)).use { it.readBytes() }

        assertEquals(1, raw[0].toInt())
        assertEquals(2, raw[width * 3 + 1].toInt())
        assertTrue("repeated row compression", output.size() < 4096)
    }

    @Test
    fun tallImagesDoNotGrowOutputWritesOrNeedAllRowsAtOnce() {
        val output = CountingOutput()
        val row = byteArrayOf(1, 2, 3, 4, 5, 6)
        StreamingPngEncoder(2, 100_000, 300, output).use { encoder ->
            repeat(100_000) { encoder.writeRgbRows(row, 1) }
            encoder.finish()
        }

        assertTrue(output.bytes > 0)
        assertTrue(output.largestWrite <= StreamingPngEncoder.IDAT_CHUNK_SIZE)
        assertFalse(output.closed)
        assertFalse(output.flushed)
    }

    @Test
    fun incompressibleOutputStillUsesBoundedChunks() {
        val output = CountingOutput()
        val row = ByteArray(2048 * 3)
        val random = Random(19)
        StreamingPngEncoder(2048, 200, 600, output).use { encoder ->
            repeat(200) { random.nextBytes(row); encoder.writeRgbRows(row, 1) }
            encoder.finish()
        }

        assertTrue(output.bytes > 1_000_000)
        assertEquals(StreamingPngEncoder.IDAT_CHUNK_SIZE, output.largestWrite)
    }

    @Test
    fun wrongBatchSizeAndNegativeCountFailBeforeRowsAreAccepted() {
        for ((bytes, count) in listOf(byteArrayOf(1, 2) to 1, byteArrayOf(1, 2, 3, 4) to 1, byteArrayOf() to -1)) {
            StreamingPngEncoder(1, 1, 300, ByteArrayOutputStream()).use { encoder ->
                assertTrue(runCatching { encoder.writeRgbRows(bytes, count) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { encoder.finish() }.exceptionOrNull() is IllegalStateException)
            }
        }
    }

    @Test
    fun countArithmeticCannotWrapToAcceptTinyInput() {
        StreamingPngEncoder(3, 1, 300, ByteArrayOutputStream()).use { encoder ->
            assertTrue(runCatching { encoder.writeRgbRows(byteArrayOf(), Int.MAX_VALUE) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun rowsBeyondDeclaredHeightFail() {
        StreamingPngEncoder(1, 1, 300, ByteArrayOutputStream()).use { encoder ->
            encoder.writeRgbRows(byteArrayOf(1, 2, 3), 1)
            assertTrue(runCatching { encoder.writeRgbRows(byteArrayOf(4, 5, 6), 1) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun incompleteImageCannotBeFinishedAndOutputStaysOpen() {
        val output = CountingOutput()
        val encoder = StreamingPngEncoder(1, 2, 300, output)
        encoder.writeRgbRows(byteArrayOf(1, 2, 3), 1)
        val failure = runCatching { encoder.finish() }.exceptionOrNull()
        encoder.close()

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("1/2"))
        assertFalse(output.closed)
        output.write(17)
    }

    @Test
    fun finishAndCloseAreIdempotentAndDoNotCloseCallerOutput() {
        val output = CountingOutput()
        val encoder = StreamingPngEncoder(1, 1, 300, output)
        encoder.writeRgbRows(byteArrayOf(), 0)
        encoder.writeRgbRows(byteArrayOf(1, 2, 3), 1)
        encoder.finish()
        val before = output.bytes
        encoder.finish()
        encoder.close()
        encoder.close()

        assertEquals(before, output.bytes)
        assertFalse(output.closed)
        assertTrue(runCatching { encoder.writeRgbRows(byteArrayOf(), 0) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun closingUnfinishedEncoderAbortsWithoutPretendingToFinish() {
        val output = ByteArrayOutputStream()
        val encoder = StreamingPngEncoder(1, 2, 300, output)
        encoder.close()
        encoder.close()

        assertTrue(chunks(output.toByteArray()).none { it.type == "IEND" })
        assertTrue(runCatching { encoder.finish() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun interruptionIsPreservedAndCallerOutputRemainsOpen() {
        val output = CountingOutput()
        val encoder = StreamingPngEncoder(1, 1, 300, output)
        val failure: Throwable?
        try {
            Thread.currentThread().interrupt()
            failure = runCatching { encoder.writeRgbRows(byteArrayOf(1, 2, 3), 1) }.exceptionOrNull()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            encoder.close()
        }

        assertTrue(failure is InterruptedIOException)
        assertFalse(output.closed)
        assertTrue(runCatching { encoder.finish() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun interruptedConstructionDoesNotWriteHeadersOrCloseOutput() {
        val output = CountingOutput()
        try {
            Thread.currentThread().interrupt()
            assertTrue(runCatching { StreamingPngEncoder(1, 1, 300, output) }.exceptionOrNull() is InterruptedIOException)
            assertEquals(0L, output.bytes)
            assertFalse(output.closed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun outputFailureIsNotHiddenAndStopsFurtherEncoding() {
        val expected = IOException("test output failure")
        val output = object : OutputStream() {
            var fail = false
            var closed = false
            override fun write(value: Int) { if (fail) throw expected }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (fail) throw expected }
            override fun close() { closed = true }
        }
        val encoder = StreamingPngEncoder(1, 1, 300, output)
        encoder.writeRgbRows(byteArrayOf(1, 2, 3), 1)
        output.fail = true
        val failure = runCatching { encoder.finish() }.exceptionOrNull()
        encoder.close()

        assertTrue(failure === expected)
        assertFalse(output.closed)
        assertTrue(runCatching { encoder.finish() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun invalidGeometryAndDensityAreRejectedWithoutWritingOutput() {
        val invalid = listOf(
            Triple(0, 1, 300), Triple(1, -1, 300), Triple(1, 1, 0),
            Triple(Int.MAX_VALUE, 1, 300), Triple(1, 1, Int.MAX_VALUE),
        )
        invalid.forEach { (width, height, dpi) ->
            val output = CountingOutput()
            assertTrue(runCatching { StreamingPngEncoder(width, height, dpi, output) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0L, output.bytes)
        }
    }

    private fun encode(width: Int, height: Int, dpi: Int, rgb: ByteArray, batches: List<Int>): ByteArray {
        val output = ByteArrayOutputStream()
        var row = 0
        StreamingPngEncoder(width, height, dpi, output).use { encoder ->
            for (count in batches) {
                encoder.writeRgbRows(rgb.copyOfRange(row * width * 3, (row + count) * width * 3), count)
                row += count
            }
            encoder.finish()
        }
        return output.toByteArray()
    }

    private data class Chunk(val type: String, val bytes: ByteArray, val crc: Long)

    private fun chunks(png: ByteArray): List<Chunk> = buildList {
        var offset = 8
        while (offset < png.size) {
            val count = intAt(png, offset)
            require(count >= 0 && offset.toLong() + count.toLong() + 12L <= png.size)
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            add(Chunk(type, png.copyOfRange(offset + 8, offset + 8 + count), intAt(png, offset + 8 + count).toLong() and 0xffffffffL))
            offset += count + 12
        }
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).int

    private fun unfilter(raw: ByteArray, width: Int, height: Int): ByteArray {
        val rowBytes = width * 3
        val result = ByteArray(rowBytes * height)
        for (row in 0 until height) {
            val source = row * (rowBytes + 1)
            val target = row * rowBytes
            val filter = raw[source].toInt() and 255
            require(filter in 0..2)
            for (index in 0 until rowBytes) {
                val predictor = when (filter) {
                    1 -> if (index >= 3) result[target + index - 3].toInt() and 255 else 0
                    2 -> if (row > 0) result[target + index - rowBytes].toInt() and 255 else 0
                    else -> 0
                }
                result[target + index] = ((raw[source + 1 + index].toInt() and 255) + predictor).toByte()
            }
        }
        return result
    }

    private class CountingOutput : OutputStream() {
        var bytes = 0L
        var largestWrite = 0
        var closed = false
        var flushed = false
        override fun write(value: Int) { bytes++; largestWrite = maxOf(largestWrite, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            this.bytes += length
            largestWrite = maxOf(largestWrite, length)
        }
        override fun close() { closed = true }
        override fun flush() { flushed = true }
    }
}
