package kr.ac.hit.networkprinterstudio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Offscreen graphics checks. No Activity, network, printer or device setting is accessed. */
class RenderingSmokeInstrumentation : Instrumentation() {
    private lateinit var directory: File
    private val details = StringBuilder()
    private var passed = 0
    private var failed = 0
    private var fileNumber = 0
    private val quadrantColors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            directory = File(targetContext.cacheDir, "rendering_smoke_${System.nanoTime()}")
            check(directory.mkdir()) { "Cannot create private fixture directory" }
            runCases()
            StreamingOutputChecks.run(directory, ::case)
        } catch (error: Throwable) {
            failed++
            appendFailure("runner", error)
        } finally {
            if (::directory.isInitialized && directory.exists()) {
                try {
                    check(directory.canonicalFile.parentFile == targetContext.cacheDir.canonicalFile)
                    check(directory.name.startsWith("rendering_smoke_"))
                    directory.walkBottomUp().forEach { check(it.delete()) { "Fixture cleanup failed: ${it.name}" } }
                } catch (error: Throwable) {
                    failed++
                    appendFailure("cleanup", error)
                }
            }
        }
        val results = Bundle().apply {
            putInt("passed", passed)
            putInt("failed", failed)
            putString("details", details.toString())
            putString(REPORT_KEY_STREAMRESULT, "\nRendering smoke: $passed passed, $failed failed\n$details")
        }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun runCases() {
        val options = ContentLayoutOptions(margin = 0, rasterDpi = 150, pageWidth = 300, pageHeight = 300)
        val quadrants = pdfFixture(300, 300, listOf(null))
        case("PDF layout bands preserve colors and every row") {
            val laidOut = output("pdf") { output ->
                readDescriptor(quadrants) { DocumentRenderer.pdfWithLayoutTo(it, options, output) }
            }
            render(laidOut).useBitmap { bitmap ->
                assertQuadrants(bitmap, quadrantColors)
                for (row in 0 until bitmap.height) {
                    if (abs(row.toFloat() / bitmap.height - 0.5f) < 0.02f) continue
                    assertColor("left scan row $row", bitmap.getPixel(bitmap.width / 4, row), quadrantColors[if (row < bitmap.height / 2) 0 else 2])
                    assertColor("right scan row $row", bitmap.getPixel(bitmap.width * 3 / 4, row), quadrantColors[if (row < bitmap.height / 2) 1 else 3])
                }
            }
        }
        case("Printable margins clip rotated and offset PDF content") {
            val layout = options.copy(
                scale = ContentScale.PRINTABLE_FIT, rotationDegrees = 90f,
                printableLeft = 30, printableRight = 40, printableTop = 20, printableBottom = 50,
                offsetX = 40f, offsetY = 35f,
            )
            val laidOut = output("pdf") { output ->
                readDescriptor(quadrants) { DocumentRenderer.pdfWithLayoutTo(it, layout, output) }
            }
            render(laidOut).useBitmap { bitmap ->
                val expected = intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
                val positions = listOf(127.5f to 112.5f, 242.5f to 112.5f, 127.5f to 227.5f, 242.5f to 227.5f)
                positions.forEachIndexed { index, (x, y) ->
                    assertColor("rotated printable quadrant $index", sample(bitmap, x / 300f, y / 300f), expected[index])
                }
                for ((x, y) in listOf(280f to 100f, 100f to 270f, 10f to 150f, 150f to 10f)) {
                    assertColor("outside printable area $x,$y", sample(bitmap, x / 300f, y / 300f), Color.WHITE)
                }
            }
        }

        val solidPages = pdfFixture(300, 300, quadrantColors.map { it })
        case("4-up preserves all four independently rendered pages") {
            val imposed = output("pdf") { output ->
                readDescriptor(solidPages) { DocumentRenderer.imposePdfTo(it, emptyList(), 4, 300, 300, 150, output) }
            }
            render(imposed).useBitmap { assertQuadrants(it, quadrantColors) }
        }
        case("4-up page range 2-3 excludes first and last pages") {
            val imposed = output("pdf") { output ->
                readDescriptor(solidPages) { DocumentRenderer.imposePdfTo(it, listOf(IppRange(2, 3)), 4, 300, 300, 150, output) }
            }
            render(imposed).useBitmap { assertQuadrants(it, intArrayOf(Color.GREEN, Color.BLUE, Color.WHITE, Color.WHITE)) }
        }

        val originalJpeg = jpegFixture(400, 400)
        val exifExpected = listOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 0, 1),
            intArrayOf(0, 2, 1, 3), intArrayOf(2, 0, 3, 1), intArrayOf(3, 1, 2, 0), intArrayOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) {
            val jpeg = nextFile("jpg")
            originalJpeg.copyTo(jpeg)
            ExifInterface(jpeg.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            for (rotation in listOf(0f, 90f)) case("JPEG EXIF $orientation content rotation ${rotation.toInt()} preview/print") {
                val layout = options.copy(rotationDegrees = rotation)
                val preview = output("pdf") { DocumentRenderer.imageFilePreviewPdfTo(jpeg, layout, it) }
                val printable = output("pdf") { DocumentRenderer.imageFilePdfTo(jpeg, layout, it) }
                var indices = exifExpected[orientation - 1]
                if (rotation == 90f) indices = intArrayOf(indices[2], indices[0], indices[3], indices[1])
                val expected = indices.map { quadrantColors[it] }.toIntArray()
                render(preview).useBitmap { previewBitmap ->
                    render(printable).useBitmap { printBitmap ->
                        assertQuadrants(previewBitmap, expected)
                        assertQuadrants(printBitmap, expected)
                        for ((x, y) in listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.2f to 0.8f, 0.8f to 0.8f)) {
                            assertColor("preview/print $x,$y", sample(previewBitmap, x, y), sample(printBitmap, x, y))
                        }
                    }
                }
            }
        }

        case("2200x1800 JPEG tiles preserve corners and tile boundaries") {
            val jpeg = jpegFixture(2200, 1800)
            val printable = output("pdf") { DocumentRenderer.imageFilePdfTo(jpeg, options, it) }
            render(printable).useBitmap { bitmap ->
                assertQuadrants(bitmap, quadrantColors)
                for ((x, y) in listOf(1022f to 400f, 1026f to 400f, 2046f to 400f, 2050f to 400f, 400f to 1022f, 400f to 1026f)) {
                    val targetX = x / 2200f
                    val targetY = ((300f - 1800f * 300f / 2200f) / 2f + y * 300f / 2200f) / 300f
                    val expected = quadrantColors[(if (y < 900f) 0 else 2) + if (x < 1100f) 0 else 1]
                    assertColor("tile seam $x,$y", sample(bitmap, targetX, targetY), expected)
                }
            }
        }

        val smallPdf = pdfFixture(72, 72, listOf(null))
        for (dpi in listOf(150, 1200)) {
            val layout = options.copy(pageWidth = 72, pageHeight = 72, rasterDpi = dpi, postScriptJpegQuality = null)
            case("PostScript exact $dpi DPI bands") {
                val encoded = output("ps") { output -> readDescriptor(smallPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.POSTSCRIPT, layout, output) } }
                assertPostScript(encoded, dpi, jpeg = false)
            }
            case("PWG Raster exact $dpi DPI header and pixel colors") {
                val encoded = output("pwg") { output -> readDescriptor(smallPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.PWG_RASTER, layout, output) } }
                assertRaster(encoded, dpi, pwg = true)
            }
            case("URF exact $dpi DPI header and pixel colors") {
                val encoded = output("urf") { output -> readDescriptor(smallPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.URF, layout, output) } }
                assertRaster(encoded, dpi, pwg = false)
            }
        }
        case("PostScript JPEG band compression remains streamable") {
            val layout = options.copy(pageWidth = 72, pageHeight = 72, postScriptJpegQuality = 90)
            val encoded = output("ps") { output -> readDescriptor(smallPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.POSTSCRIPT, layout, output) } }
            assertPostScript(encoded, 150, jpeg = true)
        }
        val pclPdf = pdfFixture(420, 595, listOf(null))
        case("A5 PCL 75 DPI header and monochrome pixel rows") {
            val layout = options.copy(pageWidth = 420, pageHeight = 595, rasterDpi = 75)
            val encoded = output("pcl") { output -> readDescriptor(pclPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.PCL, layout, output) } }
            assertPcl(encoded, (420.0 * 75 / 72).roundToInt(), (595.0 * 75 / 72).roundToInt(), 75)
        }
        case("Unsupported PCL 1200 DPI is rejected without lowering resolution") {
            val layout = options.copy(pageWidth = 420, pageHeight = 595, rasterDpi = 1200)
            val result = runCatching {
                output("pcl") { output -> readDescriptor(pclPdf) { DocumentRenderer.encodeLaidOutPdfTo(it, PrintFormatSupport.PCL, layout, output) } }
            }
            check(result.isFailure) { "PCL 1200 DPI must not be silently lowered" }
            check(result.exceptionOrNull()?.message.orEmpty().contains("dpi", ignoreCase = true)) { "PCL failure must explain its DPI limit" }
        }
    }

    private fun case(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            details.append("PASS ").append(name).append('\n')
        } catch (error: Throwable) {
            failed++
            appendFailure(name, error)
        }
    }

    private fun appendFailure(name: String, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        details.append("FAIL ").append(name).append('\n').append(stack).append('\n')
    }

    private fun nextFile(extension: String): File = File(directory, "fixture_${++fileNumber}.$extension")

    private fun output(extension: String, write: (java.io.OutputStream) -> Unit): File = nextFile(extension).also { file ->
        file.outputStream().buffered().use(write)
        check(file.length() > 0) { "Empty $extension output" }
    }

    private fun <T> readDescriptor(file: File, read: (ParcelFileDescriptor) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(read)

    private fun pdfFixture(width: Int, height: Int, solidColors: List<Int?>): File = output("pdf") { output ->
        val document = PdfDocument()
        try {
            solidColors.forEachIndexed { index, solid ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                try {
                    if (solid == null) drawQuadrants(page.canvas, width, height) else page.canvas.drawColor(solid)
                } finally { document.finishPage(page) }
            }
            document.writeTo(output)
        } finally { document.close() }
    }

    private fun jpegFixture(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawQuadrants(Canvas(bitmap), width, height)
            output("jpg") { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun drawQuadrants(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint()
        for (row in 0..1) for (column in 0..1) {
            paint.color = quadrantColors[row * 2 + column]
            canvas.drawRect(column * width / 2f, row * height / 2f, (column + 1) * width / 2f, (row + 1) * height / 2f, paint)
        }
    }

    private fun render(file: File): Bitmap = readDescriptor(file) { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            check(renderer.pageCount == 1) { "Expected one output page, found ${renderer.pageCount}" }
            renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun sample(bitmap: Bitmap, x: Float, y: Float): Int = bitmap.getPixel(
        (x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
        (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
    )

    private fun assertQuadrants(bitmap: Bitmap, expected: IntArray) {
        val points = listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f)
        points.forEachIndexed { index, (x, y) -> assertColor("quadrant $index", sample(bitmap, x, y), expected[index]) }
    }

    private fun assertColor(label: String, actual: Int, expected: Int) {
        check(abs(Color.red(actual) - Color.red(expected)) <= 36 &&
            abs(Color.green(actual) - Color.green(expected)) <= 36 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 36 && Color.alpha(actual) >= 250) {
            "$label expected #${Integer.toHexString(expected)}, found #${Integer.toHexString(actual)}"
        }
    }

    private fun assertPostScript(file: File, dpi: Int, jpeg: Boolean) {
        var width = 0
        var rows = 0
        var page = false
        var filter = false
        file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEach { line ->
                if (line.contains("/PageSize [72 72]")) page = true
                if (line.contains("/ImageType 1")) {
                    val currentWidth = Regex("/Width (\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: error("Missing PS width")
                    val currentRows = Regex("/Height (\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: error("Missing PS height")
                    check(currentWidth == dpi && currentRows in 1..128) { "PS band changed requested DPI: $currentWidth x $currentRows" }
                    width = currentWidth
                    rows += currentRows
                    if (line.contains("/DCTDecode")) filter = true
                }
            }
        }
        check(page && width == dpi && rows == dpi) { "PS dimensions/bands mismatch: page=$page width=$width totalRows=$rows expected=$dpi" }
        check(filter == jpeg) { "PS JPEG/lossless setting was not respected" }
    }

    private fun assertRaster(file: File, dpi: Int, pwg: Boolean) {
        file.inputStream().buffered().use { input ->
            val header = readExact(input, if (pwg) 1800 else 44)
            if (pwg) {
                check(String(header, 0, 4, Charsets.US_ASCII) == "RaS2")
                check(int(header, 4 + 256 + 5 * 4) == dpi && int(header, 4 + 256 + 6 * 4) == dpi) { "PWG DPI changed" }
                check(int(header, 4 + 256 + 29 * 4) == dpi && int(header, 4 + 256 + 30 * 4) == dpi) { "PWG width/height changed" }
                check(int(header, 4 + 256 + 33 * 4) == 24) { "PWG color depth changed" }
            } else {
                check(String(header, 0, 8, Charsets.US_ASCII) == "UNIRAST\u0000")
                check(int(header, 8) == 1) { "URF page count mismatch" }
                check(header[12].toInt() == 24 && header[13].toInt() == 1) { "URF color format mismatch" }
                check(int(header, 12 + 12) == dpi && int(header, 12 + 16) == dpi && int(header, 12 + 20) == dpi) { "URF width/height/DPI changed" }
            }
            var row = 0
            while (row < dpi) {
                val repetitions = readByte(input) + 1
                val pixels = ByteArray(dpi * 3)
                var column = 0
                while (column < dpi) {
                    val control = readByte(input)
                    val count = if (control <= 127) control + 1 else 257 - control
                    check(column + count <= dpi) { "Raster row overflows advertised width" }
                    if (control <= 127) {
                        val rgb = readExact(input, 3)
                        repeat(count) { rgb.copyInto(pixels, (column + it) * 3) }
                    } else readExact(input, pixels, column * 3, count * 3)
                    column += count
                }
                check(row + repetitions <= dpi) { "Raster row count overflows advertised height" }
                repeat(repetitions) { repeatRow ->
                    val y = row + repeatRow
                    if (y == dpi / 4 || y == dpi * 3 / 4) {
                        for (x in listOf(dpi / 4, dpi * 3 / 4)) {
                            val color = Color.rgb(pixels[x * 3].toInt() and 255, pixels[x * 3 + 1].toInt() and 255, pixels[x * 3 + 2].toInt() and 255)
                            assertColor("raster $x,$y", color, quadrantColors[(if (y < dpi / 2) 0 else 2) + if (x < dpi / 2) 0 else 1])
                        }
                    }
                }
                row += repetitions
            }
            check(input.read() == -1) { "Unexpected raster bytes after final row" }
        }
    }

    private fun assertPcl(file: File, width: Int, height: Int, dpi: Int) {
        val rowBytes = (width + 7) / 8
        val rowPrefix = "\u001b*b${rowBytes}W".toByteArray(Charsets.US_ASCII)
        file.inputStream().buffered().use { input ->
            val prefix = StringBuilder()
            while (!prefix.endsWith(String(rowPrefix, Charsets.US_ASCII))) {
                check(prefix.length < 4096) { "PCL raster row header not found" }
                prefix.append(readByte(input).toChar())
            }
            check(prefix.contains("\u001b*t${dpi}R") && prefix.contains("\u001b*r${width}S") && prefix.contains("\u001b*r${height}T")) { "PCL DPI/dimensions changed" }
            for (row in 0 until height) {
                if (row > 0) check(readExact(input, rowPrefix.size).contentEquals(rowPrefix)) { "PCL row prefix mismatch at $row" }
                val data = readExact(input, rowBytes)
                if (row == height / 4 || row == height * 3 / 4) for (x in listOf(width / 4, width * 3 / 4)) {
                    val black = data[x / 8].toInt() and (0x80 ushr (x % 8)) != 0
                    val expectedBlack = row < height / 2 || x < width / 2
                    check(black == expectedBlack) { "PCL pixel mismatch at $x,$row" }
                }
            }
            check(String(readExact(input, 5), Charsets.US_ASCII) == "\u001b*rB\u000c") { "PCL page terminator missing" }
        }
    }

    private fun readByte(input: InputStream): Int = input.read().also { check(it >= 0) { "Unexpected end of encoded data" } }

    private fun readExact(input: InputStream, count: Int): ByteArray = ByteArray(count).also { readExact(input, it, 0, count) }

    private fun readExact(input: InputStream, destination: ByteArray, offset: Int, count: Int) {
        var cursor = 0
        while (cursor < count) {
            val read = input.read(destination, offset + cursor, count - cursor)
            check(read > 0) { "Unexpected end of encoded data" }
            cursor += read
        }
    }

    private fun int(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
}
