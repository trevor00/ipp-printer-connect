package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRasterOutputTest {
    @Test fun selectedHighDpiKeepsExactPageDimensionsWithoutFullBitmapLimit() {
        assertEquals(9917 to 14033, RasterBandPolicy.dimensions(595, 842, 1200))
        assertEquals(33333 to 33333, RasterBandPolicy.dimensions(2000, 2000, 1200))
        assertTrue(RasterBandPolicy.dimensions(2000, 2000, 1200).first.toLong() * 33333 * 4 > Int.MAX_VALUE)
    }

    @Test fun bandsCoverEveryRowExactlyAndStayBounded() {
        for (width in listOf(1, 2479, 9917, 33333)) {
            var top = 0; val height = 14033
            while (top < height) {
                val rows = RasterBandPolicy.rows(width, height - top)
                assertTrue(rows in 1..128)
                assertTrue(width.toLong() * rows * 4 <= RasterBandPolicy.MAX_BAND_BYTES)
                top += rows
            }
            assertEquals(height, top)
        }
    }

    @Test fun invalidDimensionsCannotOverflow() {
        for ((width, height, dpi) in listOf(Triple(0, 842, 150), Triple(595, -1, 150), Triple(595, 842, 0), Triple(Int.MAX_VALUE, 842, 1200))) {
            assertTrue(runCatching { RasterBandPolicy.dimensions(width, height, dpi) }.isFailure)
        }
    }

    @Test fun ascii85MatchesKnownVectorsForEveryChunkBoundary() {
        val cases = listOf(
            byteArrayOf() to "~>", byteArrayOf(0) to "!!~>", byteArrayOf(0, 0) to "!!!~>",
            byteArrayOf(0, 0, 0) to "!!!!~>", byteArrayOf(0, 0, 0, 0) to "z~>",
            "Man ".toByteArray() to "9jqo^~>", "Hello, world!".toByteArray() to "87cURD_*#TDfTZ)+T~>",
        )
        for ((input, expected) in cases) for (chunk in 1..7) {
            val output = ByteArrayOutputStream(); val encoder = Ascii85OutputStream(output)
            input.indices.step(chunk).forEach { encoder.write(input, it, minOf(chunk, input.size - it)) }
            encoder.finish(); encoder.finish()
            assertEquals(expected, output.toString("US-ASCII"))
            output.write('X'.code)
            assertTrue(output.toString("US-ASCII").endsWith("X"))
        }
    }

    @Test fun ascii85RoundTripsLargeStreamingDataAndBoundsLines() {
        val input = ByteArray(40_003) { ((it * 73 + it / 13) and 255).toByte() }
        val output = ByteArrayOutputStream(); val encoder = Ascii85OutputStream(output)
        input.indices.step(97).forEach { encoder.write(input, it, minOf(97, input.size - it)) }
        encoder.finish()
        val encoded = output.toString("US-ASCII")
        assertTrue(encoded.lines().all { it.length <= 77 })
        assertArrayEquals(input, decodeAscii85(encoded))
    }

    @Test fun pdfStreamsRgbLosslesslyAndRecordsRealObjectOffsets() {
        val output = ByteArrayOutputStream(); val writer = StreamingRasterPdfWriter(output)
        val rgb = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0)
        writer.beginPage(595, 842)
        writer.image(2, 1, floatArrayOf(10f, 0f, 20f, 0f, 10f, 30f, 0f, 0f, 1f)) { it.write(rgb) }
        writer.endPage(); writer.finish()
        val bytes = output.toByteArray(); val pdf = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pdf.contains("/MediaBox [0 0 595 842]"))
        assertTrue(pdf.contains("q 20 0 0 10 20 802 cm /Im0 Do Q"))
        val imageHeader = Regex("/Subtype /Image.*?/Length (\\d+) 0 R >>\\nstream\\n").find(pdf)!!
        val lengthId = imageHeader.groupValues[1]
        val size = Regex("$lengthId 0 obj\\n(\\d+)\\nendobj").find(pdf)!!.groupValues[1].toInt()
        val compressed = bytes.copyOfRange(imageHeader.range.last + 1, imageHeader.range.last + 1 + size)
        assertArrayEquals(rgb, InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() })
        val xrefStart = Regex("startxref\\n(\\d+)").find(pdf)!!.groupValues[1].toInt()
        assertEquals("xref", pdf.substring(xrefStart, xrefStart + 4))
        val xrefLines = pdf.substring(xrefStart).lines()
        val count = xrefLines[1].substringAfter(' ').toInt()
        for (id in 1 until count) {
            val offset = xrefLines[id + 2].substring(0, 10).toInt()
            assertTrue(pdf.substring(offset).startsWith("$id 0 obj\n"))
        }
    }

    @Test fun pdfMaintainsIndependentPageResourcesAndPhysicalClipping() {
        val output = ByteArrayOutputStream(); val writer = StreamingRasterPdfWriter(output)
        repeat(2) {
            writer.beginPage(100, 200, floatArrayOf(4f, 5f, 96f, 195f))
            writer.image(1, 1, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) { stream -> stream.write(byteArrayOf(1, 2, 3)) }
            writer.endPage()
        }
        writer.finish()
        val pdf = output.toString("ISO-8859-1")
        assertTrue(pdf.contains("/Count 2"))
        assertEquals(2, Regex("4 5 92 190 re W n").findAll(pdf).count())
        assertEquals(2, Regex("/Im0 \\d+ 0 R").findAll(pdf).count())
    }

    private fun decodeAscii85(encoded: String): ByteArray {
        val output = ByteArrayOutputStream(); val group = arrayListOf<Int>()
        fun emit(count: Int) {
            while (group.size < 5) group.add(84)
            var value = 0L; group.forEach { value = value * 85 + it }
            for (index in 0 until count) output.write((value ushr ((3 - index) * 8)).toInt())
            group.clear()
        }
        for (character in encoded.substringBefore("~>")) when {
            character.isWhitespace() -> Unit
            character == 'z' -> repeat(4) { output.write(0) }
            else -> { group.add(character.code - 33); if (group.size == 5) emit(4) }
        }
        if (group.isNotEmpty()) emit(group.size - 1)
        return output.toByteArray()
    }
}
