package kr.ac.hit.networkprinterstudio

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

enum class ContentHorizontal { LEFT, CENTER, RIGHT }
enum class ContentVertical { TOP, CENTER, BOTTOM }
enum class ContentScale { FIT, FILL, FULL_BLEED_FILL, PRINTABLE_FIT, ACTUAL }

data class ContentLayoutOptions(
    val horizontal: ContentHorizontal = ContentHorizontal.CENTER,
    val vertical: ContentVertical = ContentVertical.CENTER,
    val scale: ContentScale = ContentScale.FIT,
    val margin: Int = 43,
    val textSize: Float = 15f,
    val pageWidth: Int = 595,
    val pageHeight: Int = 842,
    val scalePercent: Float = 100f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val printableLeft: Int = 0,
    val printableTop: Int = 0,
    val printableRight: Int = 0,
    val printableBottom: Int = 0,
)

data class RenderedPreview(val bitmap: Bitmap, val pageIndex: Int, val pageCount: Int)

object DocumentRenderer {
    private const val RASTER_DPI = 150
    private const val RASTER_PIXELS_PER_POINT = RASTER_DPI / 72f
    private const val MAX_RASTER_PIXELS = 12_000_000f

    private data class LayoutInsets(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private fun layoutInsets(options: ContentLayoutOptions): LayoutInsets = when (options.scale) {
        ContentScale.FULL_BLEED_FILL -> LayoutInsets(0f, 0f, 0f, 0f)
        ContentScale.PRINTABLE_FIT -> LayoutInsets(
            options.printableLeft.toFloat(),
            options.printableTop.toFloat(),
            options.printableRight.toFloat(),
            options.printableBottom.toFloat(),
        )
        else -> {
            val value = options.margin.toFloat()
            LayoutInsets(value, value, value, value)
        }
    }

    fun imposePdf(
        descriptor: ParcelFileDescriptor,
        pageRanges: List<IppRange>,
        numberUp: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): ByteArray {
        val outputPdf = PdfDocument()
        try {
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
                val sourceIndices = if (pageRanges.isEmpty()) {
                    (0 until renderer.pageCount).toList()
                } else {
                    pageRanges.flatMap { range -> (range.lower..range.upper).map { it - 1 } }.distinct()
                }
                require(sourceIndices.isNotEmpty() && sourceIndices.all { it in 0 until renderer.pageCount }) {
                    "페이지 범위가 PDF의 전체 ${renderer.pageCount}페이지를 벗어납니다"
                }
                val perSheet = numberUp.coerceAtLeast(1)
                val columns = ceil(sqrt(perSheet * pageWidth.toFloat() / pageHeight)).toInt().coerceAtLeast(1)
                val rows = ceil(perSheet.toFloat() / columns).toInt().coerceAtLeast(1)
                val gap = 6f
                val cellWidth = (pageWidth - gap * (columns + 1)) / columns
                val cellHeight = (pageHeight - gap * (rows + 1)) / rows
                sourceIndices.chunked(perSheet).forEachIndexed { sheetIndex, indices ->
                    val target = outputPdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, sheetIndex + 1).create())
                    target.canvas.drawColor(Color.WHITE)
                    indices.forEachIndexed { cellIndex, sourceIndex ->
                        renderer.openPage(sourceIndex).use { source ->
                            val renderScale = minOf(2f, sqrt(6_000_000f / (source.width * source.height)))
                            val bitmap = Bitmap.createBitmap(
                                (source.width * renderScale).toInt().coerceAtLeast(1),
                                (source.height * renderScale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                Canvas(bitmap).drawColor(Color.WHITE)
                                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val column = cellIndex % columns
                                val row = cellIndex / columns
                                val cellLeft = gap + column * (cellWidth + gap)
                                val cellTop = gap + row * (cellHeight + gap)
                                val scale = minOf(cellWidth / bitmap.width, cellHeight / bitmap.height)
                                val width = bitmap.width * scale
                                val height = bitmap.height * scale
                                val left = cellLeft + (cellWidth - width) / 2f
                                val top = cellTop + (cellHeight - height) / 2f
                                target.canvas.drawBitmap(
                                    bitmap,
                                    null,
                                    RectF(left, top, left + width, top + height),
                                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    outputPdf.finishPage(target)
                }
            }
            return ByteArrayOutputStream().use { output -> outputPdf.writeTo(output); output.toByteArray() }
        } finally {
            outputPdf.close()
        }
    }

    fun pdfPagePreview(
        descriptor: ParcelFileDescriptor,
        pageIndex: Int = 0,
        orientation: Int? = null,
        maxWidth: Int = 900,
    ): RenderedPreview {
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF에 미리 볼 페이지가 없습니다" }
            require(pageIndex in 0 until renderer.pageCount) {
                "선택한 ${pageIndex + 1}페이지가 PDF의 전체 ${renderer.pageCount}페이지를 벗어납니다"
            }
            renderer.openPage(pageIndex).use { page ->
                val scale = minOf(maxWidth.toFloat() / page.width, 2f)
                val rendered = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                android.graphics.Canvas(rendered).drawColor(Color.WHITE)
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val degrees = when (orientation) {
                    4 -> 90f
                    5 -> 270f
                    6 -> 180f
                    else -> 0f
                }
                val result = if (degrees == 0f) rendered else {
                    Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, Matrix().apply { postRotate(degrees) }, true)
                        .also { rendered.recycle() }
                }
                return RenderedPreview(result, pageIndex, renderer.pageCount)
            }
        }
    }

    fun textPdf(title: String, text: String, options: ContentLayoutOptions = ContentLayoutOptions()): ByteArray {
        require(text.isNotBlank()) { "출력할 텍스트가 비어 있습니다" }
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, 1).create())
        val canvas = page.canvas; canvas.drawColor(Color.WHITE)
        val printableClipSaveCount = beginPrintableClip(canvas, options)
        val rotationSaveCount = beginContentRotation(canvas, options)
        val effectiveTextSize = options.textSize * options.scalePercent / 100f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = effectiveTextSize * 1.35f }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = effectiveTextSize }
        val insets = layoutInsets(options)
        val maxWidth = options.pageWidth - insets.left - insets.right
        val lineHeight = effectiveTextSize * 1.48f
        val lines = mutableListOf<String>()
        for (paragraph in text.replace("\r\n", "\n").split("\n")) {
            var line = ""
            for (character in paragraph) {
                val candidate = line + character
                if (line.isNotEmpty() && bodyPaint.measureText(candidate) > maxWidth) {
                    lines += line
                    line = character.toString()
                } else line = candidate
            }
            lines += line
        }
        val hasTitle = title.isNotBlank()
        val titleGap = if (hasTitle) effectiveTextSize * 1.9f else 0f
        val contentHeight = titleGap + lines.size * lineHeight
        val top = when (options.vertical) {
            ContentVertical.TOP -> insets.top
            ContentVertical.CENTER -> ((options.pageHeight - contentHeight) / 2f).coerceAtLeast(insets.top)
            ContentVertical.BOTTOM -> (options.pageHeight - insets.bottom - contentHeight).coerceAtLeast(insets.top)
        } + options.offsetY
        val align = when (options.horizontal) {
            ContentHorizontal.LEFT -> Paint.Align.LEFT
            ContentHorizontal.CENTER -> Paint.Align.CENTER
            ContentHorizontal.RIGHT -> Paint.Align.RIGHT
        }
        val x = when (options.horizontal) {
            ContentHorizontal.LEFT -> insets.left
            ContentHorizontal.CENTER -> insets.left + maxWidth / 2f
            ContentHorizontal.RIGHT -> options.pageWidth - insets.right
        } + options.offsetX
        titlePaint.textAlign = align
        bodyPaint.textAlign = align
        if (hasTitle) canvas.drawText(title.take(70), x, top + titlePaint.textSize, titlePaint)
        var y = top + titleGap + bodyPaint.textSize
        val bottom = options.pageHeight - insets.bottom
        for (line in lines) {
            if (y > bottom) break
            canvas.drawText(line, x, y, bodyPaint)
            y += lineHeight
        }
        canvas.restoreToCount(rotationSaveCount)
        printableClipSaveCount?.let(canvas::restoreToCount)
        pdf.finishPage(page)
        return ByteArrayOutputStream().use { output -> pdf.writeTo(output); pdf.close(); output.toByteArray() }
    }

    fun imagePdf(image: ByteArray, options: ContentLayoutOptions = ContentLayoutOptions()): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원되는 이미지 파일이 아닙니다" }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong() > 12_000_000L) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("이미지를 읽을 수 없습니다")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, 1).create())
            page.canvas.drawColor(Color.WHITE)
            drawRotatedPositionedBitmap(page.canvas, bitmap, options)
            pdf.finishPage(page)
            return ByteArrayOutputStream().use { output -> pdf.writeTo(output); output.toByteArray() }
        } finally {
            bitmap.recycle()
            pdf.close()
        }
    }

    fun pdfWithLayout(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions): ByteArray {
        val outputPdf = PdfDocument()
        try {
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { sourcePage ->
                        val renderScale = minOf(2f, kotlin.math.sqrt(12_000_000f / (sourcePage.width * sourcePage.height)))
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            (sourcePage.width * renderScale).toInt().coerceAtLeast(1),
                            (sourcePage.height * renderScale).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        try {
                            android.graphics.Canvas(bitmap).drawColor(Color.WHITE)
                            sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val targetPage = outputPdf.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, index + 1).create())
                            targetPage.canvas.drawColor(Color.WHITE)
                            drawRotatedPositionedBitmap(targetPage.canvas, bitmap, options)
                            outputPdf.finishPage(targetPage)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            return ByteArrayOutputStream().use { output -> outputPdf.writeTo(output); output.toByteArray() }
        } finally {
            outputPdf.close()
        }
    }

    fun pdfPostScript(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions): ByteArray =
        postScriptDocument(options) { emit -> renderPdfPages(descriptor, options, emit) }

    fun imagePostScript(image: ByteArray, options: ContentLayoutOptions): ByteArray =
        postScriptDocument(options) { emit -> withImagePage(image, options, emit) }

    fun textPostScript(title: String, text: String, options: ContentLayoutOptions): ByteArray =
        postScriptDocument(options) { emit -> withTextPage(title, text, options, emit) }

    fun pdfPcl(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions): ByteArray =
        pclDocument { emit -> renderPdfPages(descriptor, options, emit) }

    fun imagePcl(image: ByteArray, options: ContentLayoutOptions): ByteArray =
        pclDocument { emit -> withImagePage(image, options, emit) }

    fun textPcl(title: String, text: String, options: ContentLayoutOptions): ByteArray =
        pclDocument { emit -> withTextPage(title, text, options, emit) }

    fun pdfPwgRaster(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions): ByteArray =
        pwgRasterDocument(options) { emit -> renderPdfPages(descriptor, options, emit) }

    fun imagePwgRaster(image: ByteArray, options: ContentLayoutOptions): ByteArray =
        pwgRasterDocument(options) { emit -> withImagePage(image, options, emit) }

    fun textPwgRaster(title: String, text: String, options: ContentLayoutOptions): ByteArray =
        pwgRasterDocument(options) { emit -> withTextPage(title, text, options, emit) }

    fun pdfUrf(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions): ByteArray =
        urfDocument { emit -> renderPdfPages(descriptor, options, emit) }

    fun imageUrf(image: ByteArray, options: ContentLayoutOptions): ByteArray =
        urfDocument { emit -> withImagePage(image, options, emit) }

    fun textUrf(title: String, text: String, options: ContentLayoutOptions): ByteArray =
        urfDocument { emit -> withTextPage(title, text, options, emit) }

    private fun postScriptDocument(options: ContentLayoutOptions, render: (((Bitmap) -> Unit) -> Unit)): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("%!PS-Adobe-3.0\n%%Creator: IPP Printer Connect\n%%LanguageLevel: 2\n%%Pages: (atend)\n%%EndComments\n")
        var pageCount = 0
        render { bitmap ->
            pageCount += 1
            writePostScriptPage(output, bitmap, pageCount, options.pageWidth, options.pageHeight)
        }
        output.writeAscii("%%Trailer\n%%Pages: $pageCount\n%%EOF\n")
        return output.toByteArray()
    }

    private fun pclDocument(render: (((Bitmap) -> Unit) -> Unit)): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("\u001bE")
        render { bitmap -> writePclPage(output, bitmap) }
        output.writeAscii("\u001bE")
        return output.toByteArray()
    }

    private fun pwgRasterDocument(
        options: ContentLayoutOptions,
        render: (((Bitmap) -> Unit) -> Unit),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("RaS2")
        render { bitmap ->
            output.write(pwgPageHeader(bitmap, options))
            writePackBitsRgbRows(output, bitmap)
        }
        return output.toByteArray()
    }

    private fun urfDocument(render: (((Bitmap) -> Unit) -> Unit)): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf('U'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), 0))
        output.writeIntBigEndian(0)
        var pages = 0
        render { bitmap ->
            pages += 1
            val header = ByteArray(32)
            header[0] = 24
            header[1] = 1
            header[2] = 1
            header[3] = 4
            putIntBigEndian(header, 12, bitmap.width)
            putIntBigEndian(header, 16, bitmap.height)
            putIntBigEndian(header, 20, RASTER_DPI)
            output.write(header)
            writePackBitsRgbRows(output, bitmap)
        }
        val result = output.toByteArray()
        putIntBigEndian(result, 8, pages)
        return result
    }

    private fun pwgPageHeader(bitmap: Bitmap, options: ContentLayoutOptions): ByteArray {
        val output = ByteArrayOutputStream(1796)
        output.writeFixedString("PwgRaster")
        output.writeFixedString("")
        output.writeFixedString("auto")
        output.writeFixedString("sRGB_8")
        val values = IntArray(42)
        values[5] = RASTER_DPI
        values[6] = RASTER_DPI
        values[7] = 0
        values[8] = 0
        values[9] = bitmap.width
        values[10] = bitmap.height
        values[21] = 1
        values[24] = options.pageWidth
        values[25] = options.pageHeight
        values[29] = bitmap.width
        values[30] = bitmap.height
        values[32] = 8
        values[33] = 24
        values[34] = bitmap.width * 3
        values[35] = 0
        values[36] = 19
        values[41] = 3
        values.forEach { output.writeIntBigEndian(it) }
        repeat(7) { output.writeIntBigEndian(0) }
        val integers = IntArray(16)
        integers[0] = 1
        integers[1] = 1
        integers[2] = 1
        integers[3] = 0
        integers[4] = 0
        integers[5] = bitmap.width
        integers[6] = bitmap.height
        integers[7] = 0xFFFFFF
        integers.forEach { output.writeIntBigEndian(it) }
        repeat(16) { output.writeIntBigEndian(0) }
        output.write(ByteArray(16 * 64))
        output.writeFixedString("")
        output.writeFixedString("Perceptual")
        output.writeFixedString("iso_a4_210x297mm")
        return output.toByteArray().also { check(it.size == 1796) { "PWG Raster 페이지 헤더 크기가 올바르지 않습니다" } }
    }

    private fun writePackBitsRgbRows(output: ByteArrayOutputStream, bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width)
        val row = ByteArray(bitmap.width * 3)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            var byteIndex = 0
            for (color in pixels) {
                val alpha = Color.alpha(color)
                row[byteIndex++] = ((Color.red(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
                row[byteIndex++] = ((Color.green(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
                row[byteIndex++] = ((Color.blue(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
            }
            writePackBitsRgbRow(output, row, bitmap.width)
        }
    }

    private fun writePackBitsRgbRow(output: ByteArrayOutputStream, row: ByteArray, width: Int) {
        output.write(0)
        var pixel = 0
        while (pixel < width) {
            var repeat = 1
            while (pixel + repeat < width && repeat < 128 && sameRgb(row, pixel, pixel + repeat)) repeat += 1
            if (repeat >= 2) {
                output.write(repeat - 1)
                output.write(row, pixel * 3, 3)
                pixel += repeat
                continue
            }
            val start = pixel
            pixel += 1
            while (pixel < width && pixel - start < 128) {
                if (pixel + 1 < width && sameRgb(row, pixel, pixel + 1)) break
                pixel += 1
            }
            val count = pixel - start
            output.write(257 - count)
            output.write(row, start * 3, count * 3)
        }
    }

    private fun sameRgb(row: ByteArray, firstPixel: Int, secondPixel: Int): Boolean {
        val first = firstPixel * 3
        val second = secondPixel * 3
        return row[first] == row[second] && row[first + 1] == row[second + 1] && row[first + 2] == row[second + 2]
    }

    private fun ByteArrayOutputStream.writeFixedString(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        write(bytes, 0, minOf(bytes.size, 63))
        write(ByteArray(64 - minOf(bytes.size, 63)))
    }

    private fun ByteArrayOutputStream.writeIntBigEndian(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun putIntBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun renderPdfPages(
        descriptor: ParcelFileDescriptor,
        options: ContentLayoutOptions,
        emit: (Bitmap) -> Unit,
    ) {
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { sourcePage ->
                    val renderScale = minOf(2f, sqrt(MAX_RASTER_PIXELS / (sourcePage.width * sourcePage.height)))
                    val source = Bitmap.createBitmap(
                        (sourcePage.width * renderScale).toInt().coerceAtLeast(1),
                        (sourcePage.height * renderScale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        Canvas(source).drawColor(Color.WHITE)
                        sourcePage.render(source, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        withPositionedPage(source, options, emit)
                    } finally {
                        source.recycle()
                    }
                }
            }
        }
    }

    private fun withImagePage(image: ByteArray, options: ContentLayoutOptions, emit: (Bitmap) -> Unit) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원되는 이미지 파일이 아닙니다" }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong() > MAX_RASTER_PIXELS) sample *= 2
        val source = BitmapFactory.decodeByteArray(
            image,
            0,
            image.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("이미지를 읽을 수 없습니다")
        try {
            withPositionedPage(source, options, emit)
        } finally {
            source.recycle()
        }
    }

    private fun withTextPage(
        title: String,
        text: String,
        options: ContentLayoutOptions,
        emit: (Bitmap) -> Unit,
    ) {
        require(text.isNotBlank()) { "출력할 텍스트가 비어 있습니다" }
        val (bitmap, rasterScale) = blankRasterPage(options)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(rasterScale, rasterScale)
            val printableClipSaveCount = beginPrintableClip(canvas, options)
            val rotationSaveCount = beginContentRotation(canvas, options)
            drawTextPage(canvas, title, text, options)
            canvas.restoreToCount(rotationSaveCount)
            printableClipSaveCount?.let(canvas::restoreToCount)
            emit(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun withPositionedPage(source: Bitmap, options: ContentLayoutOptions, emit: (Bitmap) -> Unit) {
        val (bitmap, rasterScale) = blankRasterPage(options)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(rasterScale, rasterScale)
            drawRotatedPositionedBitmap(canvas, source, options)
            emit(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun blankRasterPage(options: ContentLayoutOptions): Pair<Bitmap, Float> {
        val scale = minOf(
            RASTER_PIXELS_PER_POINT,
            sqrt(MAX_RASTER_PIXELS / (options.pageWidth.toFloat() * options.pageHeight.toFloat())),
        )
        val bitmap = Bitmap.createBitmap(
            (options.pageWidth * scale).toInt().coerceAtLeast(1),
            (options.pageHeight * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).drawColor(Color.WHITE)
        return bitmap to scale
    }

    private fun drawTextPage(canvas: Canvas, title: String, text: String, options: ContentLayoutOptions) {
        val effectiveTextSize = options.textSize * options.scalePercent / 100f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = effectiveTextSize * 1.35f }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = effectiveTextSize }
        val insets = layoutInsets(options)
        val maxWidth = options.pageWidth - insets.left - insets.right
        val lineHeight = effectiveTextSize * 1.48f
        val lines = mutableListOf<String>()
        for (paragraph in text.replace("\r\n", "\n").split("\n")) {
            var line = ""
            for (character in paragraph) {
                val candidate = line + character
                if (line.isNotEmpty() && bodyPaint.measureText(candidate) > maxWidth) {
                    lines += line
                    line = character.toString()
                } else line = candidate
            }
            lines += line
        }
        val hasTitle = title.isNotBlank()
        val titleGap = if (hasTitle) effectiveTextSize * 1.9f else 0f
        val contentHeight = titleGap + lines.size * lineHeight
        val top = when (options.vertical) {
            ContentVertical.TOP -> insets.top
            ContentVertical.CENTER -> ((options.pageHeight - contentHeight) / 2f).coerceAtLeast(insets.top)
            ContentVertical.BOTTOM -> (options.pageHeight - insets.bottom - contentHeight).coerceAtLeast(insets.top)
        } + options.offsetY
        val align = when (options.horizontal) {
            ContentHorizontal.LEFT -> Paint.Align.LEFT
            ContentHorizontal.CENTER -> Paint.Align.CENTER
            ContentHorizontal.RIGHT -> Paint.Align.RIGHT
        }
        val x = when (options.horizontal) {
            ContentHorizontal.LEFT -> insets.left
            ContentHorizontal.CENTER -> insets.left + maxWidth / 2f
            ContentHorizontal.RIGHT -> options.pageWidth - insets.right
        } + options.offsetX
        titlePaint.textAlign = align
        bodyPaint.textAlign = align
        if (hasTitle) canvas.drawText(title.take(70), x, top + titlePaint.textSize, titlePaint)
        var y = top + titleGap + bodyPaint.textSize
        val bottom = options.pageHeight - insets.bottom
        for (line in lines) {
            if (y > bottom) break
            canvas.drawText(line, x, y, bodyPaint)
            y += lineHeight
        }
    }

    private fun writePostScriptPage(
        output: ByteArrayOutputStream,
        bitmap: Bitmap,
        pageNumber: Int,
        pageWidth: Int,
        pageHeight: Int,
    ) {
        val jpeg = ByteArrayOutputStream().use { encoded ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded)) { "PostScript용 JPEG 변환에 실패했습니다" }
            encoded.toByteArray()
        }
        output.writeAscii(
            "%%Page: $pageNumber $pageNumber\n" +
                "<< /PageSize [$pageWidth $pageHeight] >> setpagedevice\n" +
                "gsave\n$pageWidth $pageHeight scale\n" +
                "<< /ImageType 1 /Width ${bitmap.width} /Height ${bitmap.height} " +
                "/BitsPerComponent 8 /Decode [0 1 0 1 0 1] " +
                "/ImageMatrix [${bitmap.width} 0 0 -${bitmap.height} 0 ${bitmap.height}] " +
                "/DataSource currentfile /ASCII85Decode filter /DCTDecode filter >> image\n",
        )
        writeAscii85(output, jpeg)
        output.writeAscii("\ngrestore\nshowpage\n")
    }

    private fun writeAscii85(output: ByteArrayOutputStream, data: ByteArray) {
        var index = 0
        var column = 0
        fun emit(character: Int) {
            output.write(character)
            column += 1
            if (column >= 75) {
                output.write('\n'.code)
                column = 0
            }
        }
        while (index < data.size) {
            val count = minOf(4, data.size - index)
            var value = 0L
            for (offset in 0 until 4) {
                value = value shl 8
                if (offset < count) value = value or (data[index + offset].toLong() and 0xff)
            }
            if (count == 4 && value == 0L) {
                emit('z'.code)
            } else {
                val encoded = IntArray(5)
                var remaining = value
                for (position in 4 downTo 0) {
                    encoded[position] = (remaining % 85L).toInt() + 33
                    remaining /= 85L
                }
                for (position in 0..count) emit(encoded[position])
            }
            index += count
        }
        output.write('~'.code)
        output.write('>'.code)
    }

    private fun writePclPage(output: ByteArrayOutputStream, bitmap: Bitmap) {
        val rowBytes = (bitmap.width + 7) / 8
        val pixels = IntArray(bitmap.width)
        val packed = ByteArray(rowBytes)
        output.writeAscii("\u001b&l0O\u001b*t${RASTER_DPI}R\u001b*r${bitmap.width}S\u001b*r${bitmap.height}T\u001b*r1A\u001b*b0M")
        for (y in 0 until bitmap.height) {
            java.util.Arrays.fill(packed, 0)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in pixels.indices) {
                val color = pixels[x]
                val alpha = Color.alpha(color)
                val red = (Color.red(color) * alpha + 255 * (255 - alpha)) / 255
                val green = (Color.green(color) * alpha + 255 * (255 - alpha)) / 255
                val blue = (Color.blue(color) * alpha + 255 * (255 - alpha)) / 255
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                if (luminance < 170) {
                    val byteIndex = x / 8
                    packed[byteIndex] = (packed[byteIndex].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            output.writeAscii("\u001b*b${rowBytes}W")
            output.write(packed)
        }
        output.writeAscii("\u001b*rB\u000c")
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun drawPositionedBitmap(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, options: ContentLayoutOptions) {
        val insets = layoutInsets(options)
        val availableWidth = options.pageWidth - insets.left - insets.right
        val availableHeight = options.pageHeight - insets.top - insets.bottom
        val baseScale = when (options.scale) {
            ContentScale.FIT -> minOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
            ContentScale.FILL, ContentScale.FULL_BLEED_FILL ->
                maxOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
            ContentScale.PRINTABLE_FIT -> minOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
            ContentScale.ACTUAL -> 1f
        }
        val scale = baseScale * options.scalePercent / 100f
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = when (options.horizontal) {
            ContentHorizontal.LEFT -> insets.left
            ContentHorizontal.CENTER -> insets.left + (availableWidth - width) / 2f
            ContentHorizontal.RIGHT -> options.pageWidth - insets.right - width
        } + options.offsetX
        val top = when (options.vertical) {
            ContentVertical.TOP -> insets.top
            ContentVertical.CENTER -> insets.top + (availableHeight - height) / 2f
            ContentVertical.BOTTOM -> options.pageHeight - insets.bottom - height
        } + options.offsetY
        val clipSaveCount = beginPrintableClip(canvas, options)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG))
        clipSaveCount?.let(canvas::restoreToCount)
    }

    private fun beginPrintableClip(canvas: Canvas, options: ContentLayoutOptions): Int? {
        if (options.scale != ContentScale.PRINTABLE_FIT) return null
        val insets = layoutInsets(options)
        val saveCount = canvas.save()
        canvas.clipRect(
            insets.left,
            insets.top,
            options.pageWidth - insets.right,
            options.pageHeight - insets.bottom,
        )
        return saveCount
    }

    private fun drawRotatedPositionedBitmap(
        canvas: Canvas,
        source: Bitmap,
        options: ContentLayoutOptions,
    ) {
        val degrees = ((options.rotationDegrees % 360f) + 360f) % 360f
        if (degrees == 0f) {
            drawPositionedBitmap(canvas, source, options)
            return
        }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
        try {
            drawPositionedBitmap(canvas, rotated, options.copy(rotationDegrees = 0f))
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun beginContentRotation(canvas: Canvas, options: ContentLayoutOptions): Int {
        val saveCount = canvas.save()
        val degrees = ((options.rotationDegrees % 360f) + 360f) % 360f
        if (degrees == 0f) return saveCount
        val swapsAxes = degrees == 90f || degrees == 270f
        val rotatedWidth = if (swapsAxes) options.pageHeight.toFloat() else options.pageWidth.toFloat()
        val rotatedHeight = if (swapsAxes) options.pageWidth.toFloat() else options.pageHeight.toFloat()
        val fitScale = minOf(options.pageWidth / rotatedWidth, options.pageHeight / rotatedHeight)
        canvas.translate(options.pageWidth / 2f, options.pageHeight / 2f)
        canvas.rotate(degrees)
        canvas.scale(fitScale, fitScale)
        canvas.translate(-options.pageWidth / 2f, -options.pageHeight / 2f)
        return saveCount
    }
}
