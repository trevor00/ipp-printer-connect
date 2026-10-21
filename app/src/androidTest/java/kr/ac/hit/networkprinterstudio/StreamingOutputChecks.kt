package kr.ac.hit.networkprinterstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs

/** Exercises the real Android renderer/codecs with private, synthetic files only. */
internal object StreamingOutputChecks {
    fun run(directory: File, case: (String, () -> Unit) -> Unit) {
        val small = fixture(directory, "stream-small.pdf", 72, 72)
        for (mime in listOf("image/png", "image/jpeg")) for (dpi in listOf(150, 1200)) {
            case("$mime stream retains $dpi DPI, pixel dimensions and colors") {
                val file = encode(directory, small, mime, dpi, 90)
                checkImage(file, dpi, dpi)
                checkDensity(file, mime, dpi)
            }
        }
        case("PNG streaming matches every rendered source pixel losslessly") {
            val file = encode(directory, small, "image/png", 150, 90)
            val actual = BitmapFactory.decodeFile(file.path) ?: error("PNG decode failed")
            val expected = render(small, 0, 150, 150)
            try {
                val expectedRow = IntArray(150)
                val actualRow = IntArray(150)
                repeat(150) { y ->
                    expected.getPixels(expectedRow, 0, 150, 0, y, 150, 1)
                    actual.getPixels(actualRow, 0, 150, 0, y, 150, 1)
                    check(expectedRow.contentEquals(actualRow)) { "PNG changed source pixels at row $y" }
                }
            } finally {
                actual.recycle()
                expected.recycle()
            }
        }
        case("JPEG quality 100 retains its quantization setting") {
            val q90 = encode(directory, small, "image/jpeg", 150, 90)
            val q100 = encode(directory, small, "image/jpeg", 150, 100)
            val table90 = jpegQuantizers(q90)
            val table100 = jpegQuantizers(q100)
            check(table90.isNotEmpty() && table100.isNotEmpty())
            check(table100.all { it == 1 }) { "Quality 100 quantization was lowered" }
            check(table90.any { it > 1 }) { "Quality 90 setting was not applied" }
        }
        case("JPEG output IOException remains recoverable and does not close caller stream") {
            var closed = false
            val broken = object : OutputStream() {
                override fun write(value: Int) { throw IOException("test-output-failure") }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("test-output-failure") }
                override fun close() { closed = true }
            }
            val failure = runCatching {
                StreamingJpegEncoder(16, 16, 300, 90, broken).use { encoder ->
                    repeat(16) { encoder.writeRgbRows(ByteArray(16 * 3) { 127 }, 1) }
                    encoder.finish()
                }
            }.exceptionOrNull()
            check(failure is IOException && failure.message.orEmpty().contains("test-output-failure")) {
                "Native encoder lost original output exception: $failure"
            }
            check(!closed) { "Encoder closed caller stream" }
            val healthy = encode(directory, small, "image/jpeg", 150, 100)
            checkImage(healthy, 150, 150)
        }
        case("JPEG incomplete rows, invalid dimensions and cancellation release native state") {
            val incomplete = runCatching {
                StreamingJpegEncoder(16, 16, 300, 90, ByteArrayOutputStream()).use { encoder ->
                    encoder.writeRgbRows(ByteArray(48), 1)
                    encoder.finish()
                }
            }
            check(incomplete.isFailure)
            check(runCatching {
                StreamingJpegEncoder(65536, 1, 300, 90, ByteArrayOutputStream()).close()
            }.isFailure) { "JPEG dimensions exceeding the format limit were accepted" }
            try {
                Thread.currentThread().interrupt()
                check(runCatching {
                    StreamingJpegEncoder(16, 16, 300, 90, ByteArrayOutputStream()).use { encoder ->
                        encoder.writeRgbRows(ByteArray(48), 1)
                    }
                }.isFailure) { "Cancellation was ignored" }
            } finally {
                Thread.interrupted()
            }
        }
        case("JPEG output callbacks cannot reenter or destroy an active native encoder") {
            for (action in listOf("close", "finish", "write")) {
                lateinit var encoder: StreamingJpegEncoder
                val callback = object : OutputStream() {
                    override fun write(value: Int) { error("Unexpected single-byte callback") }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        when (action) {
                            "close" -> encoder.close()
                            "finish" -> encoder.finish()
                            else -> encoder.writeRgbRows(ByteArray(48), 1)
                        }
                    }
                }
                encoder = StreamingJpegEncoder(16, 16, 300, 90, callback)
                val failure = runCatching {
                    encoder.use {
                        repeat(16) { encoder.writeRgbRows(ByteArray(48), 1) }
                        encoder.finish()
                    }
                }.exceptionOrNull()
                check(failure is IllegalStateException) { "Native callback $action was not safely rejected: $failure" }
                encoder.close()
            }
            lateinit var encoder: StreamingJpegEncoder
            val bytes = ByteArrayOutputStream()
            var rejected = false
            val caught = object : OutputStream() {
                override fun write(value: Int) { error("Unexpected single-byte callback") }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    rejected = runCatching { encoder.close() }.exceptionOrNull() is IllegalStateException
                    bytes.write(buffer, offset, length)
                }
            }
            encoder = StreamingJpegEncoder(16, 16, 300, 90, caught)
            encoder.use {
                repeat(16) { encoder.writeRgbRows(ByteArray(48) { 127 }, 1) }
                encoder.finish()
            }
            check(rejected)
            val decoded = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
                ?: error("Caught reentrant call corrupted JPEG")
            try { check(decoded.width == 16 && decoded.height == 16) } finally { decoded.recycle() }
        }
        val a4 = fixture(directory, "stream-a4.pdf", 595, 842)
        for (mime in listOf("image/png", "image/jpeg")) {
            case("A4 $mime 1200 DPI encodes without a 531 MiB full-page bitmap") {
                val encoded = encode(directory, a4, mime, 1200, 100)
                checkImage(encoded, 9917, 14033)
                checkDensity(encoded, mime, 1200)
            }
        }
        val text = "한글 문서 출력 확인 ABC 가나다\r\n둘째 줄 12345\r끝 줄\n".repeat(18) + "마지막 문장\n"
        for (rotation in listOf(0f, 90f)) {
            case("Streaming TXT preserves Korean vector pages, wrapping and $rotation degree layout") {
                val source = File(directory, "stream-korean-$rotation.txt")
                source.writeText(text)
                val options = ContentLayoutOptions(
                    pageWidth = 300, pageHeight = 300, margin = 20,
                    rotationDegrees = rotation, vertical = ContentVertical.BOTTOM,
                )
                val streamed = File(directory, "stream-korean-$rotation.pdf")
                streamed.outputStream().buffered().use { TextPdfStreaming.write(source, options, it, directory) }
                val old = File(directory, "reference-korean-$rotation.pdf")
                old.outputStream().buffered().use { LegacyTextPdfReference.write(text, options, it) }
                val count = pageCount(old)
                check(count > 1 && pageCount(streamed) == count) { "TXT pagination changed" }
                for (page in 0 until count) {
                    val actual = render(streamed, page, 600, 600)
                    val expected = render(old, page, 600, 600)
                    try {
                        val actualRow = IntArray(600)
                        val expectedRow = IntArray(600)
                        repeat(600) { y ->
                            actual.getPixels(actualRow, 0, 600, 0, y, 600, 1)
                            expected.getPixels(expectedRow, 0, 600, 0, y, 600, 1)
                            check(actualRow.contentEquals(expectedRow)) { "TXT glyph/layout changed at page $page row $y" }
                        }
                    } finally {
                        actual.recycle()
                        expected.recycle()
                    }
                }
                // These fixtures contain ordinary vector glyphs, not color emoji/image glyphs.
                val syntax = streamed.readText(Charsets.ISO_8859_1)
                check(syntax.contains("/Font")) { "Text lost vector fonts" }
                check(!Regex("/Subtype\\s*/Image").containsMatchIn(syntax)) { "Text was silently rasterized" }
            }
        }
        case("Large TXT generates many vector pages and cleans per-page temporary files") {
            val source = File(directory, "stream-long.txt")
            source.bufferedWriter().use { writer ->
                repeat(1500) { writer.write("한글 $it 번째 줄 abcdefghijklmnopqrstuvwxyz\n") }
            }
            val options = ContentLayoutOptions(pageWidth = 300, pageHeight = 300, margin = 20)
            val result = File(directory, "stream-long.pdf")
            result.outputStream().buffered().use { TextPdfStreaming.write(source, options, it, directory) }
            val count = pageCount(result)
            check(count > 100) { "Large text was truncated: $count pages" }
            val last = render(result, count - 1, 300, 300)
            try {
                var dark = false
                for (y in 0 until last.height) for (x in 0 until last.width) {
                    if (Color.red(last.getPixel(x, y)) < 100) dark = true
                }
                check(dark) { "Last TXT page is empty" }
            } finally { last.recycle() }
            assertTextTempsClean(directory)
        }
        case("TXT BOM and Korean CP949 preserve text through vector PDF generation") {
            val contents = "한글과 영어 ABC\r\n다음 줄\n"
            val reference = File(directory, "encoding-reference.pdf")
            reference.outputStream().use { LegacyTextPdfReference.write(contents, ContentLayoutOptions(), it) }
            val encodings = listOf(
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + contents.toByteArray(Charsets.UTF_16LE),
                byteArrayOf(0xfe.toByte(), 0xff.toByte()) + contents.toByteArray(Charsets.UTF_16BE),
                contents.toByteArray(java.nio.charset.Charset.forName("MS949")),
            )
            for ((index, bytes) in encodings.withIndex()) {
                val source = File(directory, "encoding-$index.txt").apply { writeBytes(bytes) }
                val output = File(directory, "encoding-$index.pdf")
                output.outputStream().use { TextPdfStreaming.write(source, ContentLayoutOptions(), it, directory) }
                val actual = render(output, 0, 595, 842)
                val expected = render(reference, 0, 595, 842)
                try { check(actual.sameAs(expected)) { "Text encoding $index changed glyphs" } }
                finally { actual.recycle(); expected.recycle() }
                assertTextTempsClean(directory)
            }
        }
        case("TXT invalid input, output failure and cancellation leave no temporary page files") {
            val source = File(directory, "text-error.txt").apply { writeText("정상 문장\n".repeat(50)) }
            val broken = object : OutputStream() {
                var count = 0
                override fun write(value: Int) {
                    if (++count > 200) throw IOException("test-text-output-failure")
                }
            }
            val failedWrite = runCatching {
                TextPdfStreaming.write(source, ContentLayoutOptions(), broken, directory)
            }.exceptionOrNull()
            check(failedWrite is IOException && failedWrite.message == "test-text-output-failure") { "$failedWrite" }
            assertTextTempsClean(directory)
            val cancelled = object : OutputStream() {
                override fun write(value: Int) { Thread.currentThread().interrupt() }
            }
            try {
                check(runCatching {
                    TextPdfStreaming.write(source, ContentLayoutOptions(), cancelled, directory)
                }.isFailure) { "TXT cancellation was ignored" }
            } finally { Thread.interrupted() }
            assertTextTempsClean(directory)
            for (bytes in listOf(byteArrayOf(), " \n\t".toByteArray(),
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte()))) {
                source.writeBytes(bytes)
                val output = ByteArrayOutputStream()
                check(runCatching {
                    TextPdfStreaming.write(source, ContentLayoutOptions(), output, directory)
                }.isFailure)
                check(output.size() == 0) { "Invalid text was partially emitted before validation finished" }
                assertTextTempsClean(directory)
            }
        }
    }

    private fun assertTextTempsClean(directory: File) {
        check(directory.listFiles().orEmpty().none { it.name.startsWith("text_vectors_") }) {
            "Text conversion left temporary page files behind"
        }
    }

    private fun fixture(directory: File, name: String, width: Int, height: Int): File {
        val file = File(directory, name)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            try {
                val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                val paint = Paint()
                for (row in 0..1) for (column in 0..1) {
                    paint.color = colors[row * 2 + column]
                    page.canvas.drawRect(column * width / 2f, row * height / 2f,
                        (column + 1) * width / 2f, (row + 1) * height / 2f, paint)
                }
            } finally { document.finishPage(page) }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }

    private fun encode(directory: File, pdf: File, mime: String, dpi: Int, quality: Int): File {
        val result = File.createTempFile("encoded-", if (mime == "image/png") ".png" else ".jpg", directory)
        result.outputStream().buffered(64 * 1024).use { output ->
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                DocumentRenderer.encodeLaidOutPdfTo(descriptor, mime,
                    ContentLayoutOptions(rasterDpi = dpi, postScriptJpegQuality = quality), output)
            }
        }
        return result
    }

    private fun checkImage(file: File, width: Int, height: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth == width && bounds.outHeight == height) {
            "Image dimensions changed: ${bounds.outWidth}x${bounds.outHeight}, expected ${width}x$height"
        }
        val regions = BitmapRegionDecoder.newInstance(file.path, false) ?: error("Region decode unavailable")
        try {
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            for (row in 0..1) for (column in 0..1) {
                val x = width * (if (column == 0) 1 else 3) / 4
                val y = height * (if (row == 0) 1 else 3) / 4
                val bitmap = regions.decodeRegion(Rect(x, y, x + 2, y + 2), BitmapFactory.Options())
                    ?: error("Region decode failed")
                try {
                    val actual = bitmap.getPixel(0, 0)
                    val expected = colors[row * 2 + column]
                    check(abs(Color.red(actual) - Color.red(expected)) < 12 &&
                        abs(Color.green(actual) - Color.green(expected)) < 12 &&
                        abs(Color.blue(actual) - Color.blue(expected)) < 12) { "Output color changed" }
                } finally { bitmap.recycle() }
            }
        } finally { regions.recycle() }
    }

    private fun checkDensity(file: File, mime: String, dpi: Int) {
        if (mime == "image/png") {
            file.inputStream().buffered().use { input ->
                check(input.readN(8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
                while (true) {
                    val count = int(input.readN(4), 0)
                    val type = String(input.readN(4), Charsets.US_ASCII)
                    check(count in 0..65536) { "PNG chunk exceeds streaming bound" }
                    val data = input.readN(count)
                    input.readN(4)
                    if (type == "pHYs") {
                        val ppm = ((dpi.toLong() * 10_000 + 127) / 254).toInt()
                        check(data.size == 9 && int(data, 0) == ppm && int(data, 4) == ppm && data[8].toInt() == 1)
                        return
                    }
                    check(type != "IDAT" && type != "IEND") { "PNG DPI is missing" }
                }
            }
        } else {
            val header = jpegSegments(file).firstOrNull { (marker, bytes) ->
                marker == 0xe0 && bytes.size >= 14 && String(bytes, 0, 5, Charsets.US_ASCII) == "JFIF\u0000"
            }?.second ?: error("JPEG DPI header missing")
            check(header[7].toInt() == 1 && short(header, 8) == dpi && short(header, 10) == dpi) { "JPEG DPI changed" }
        }
    }

    private fun jpegQuantizers(file: File): List<Int> = jpegSegments(file).filter { it.first == 0xdb }.flatMap { (_, bytes) ->
        val values = mutableListOf<Int>()
        var offset = 0
        while (offset < bytes.size) {
            val precision = (bytes[offset++].toInt() and 255) ushr 4
            check(precision == 0)
            repeat(64) { values.add(bytes[offset++].toInt() and 255) }
        }
        values
    }

    private fun jpegSegments(file: File): List<Pair<Int, ByteArray>> = file.inputStream().buffered().use { input ->
        check(input.read() == 0xff && input.read() == 0xd8)
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        while (true) {
            check(input.read() == 0xff)
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker == 0xda || marker == 0xd9) break
            check(marker > 0)
            val length = (input.read() shl 8) or input.read()
            check(length >= 2)
            segments += marker to input.readN(length - 2)
        }
        segments
    }

    private fun java.io.InputStream.readN(size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            check(count > 0) { "Unexpected end of file" }
            offset += count
        }
    }

    private fun short(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun int(bytes: ByteArray, offset: Int): Int = (short(bytes, offset) shl 16) or short(bytes, offset + 2)

    private fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { it.pageCount }
    }

    private fun render(file: File, pageIndex: Int, width: Int, height: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bitmap
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }
}
