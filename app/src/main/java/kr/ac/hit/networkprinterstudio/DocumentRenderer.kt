package kr.ac.hit.networkprinterstudio

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix as PdfBoxMatrix
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.sqrt

enum class ContentHorizontal { LEFT, CENTER, RIGHT }
enum class ContentVertical { TOP, CENTER, BOTTOM }
enum class ContentScale { FIT, FILL, FULL_BLEED_FILL, PRINTABLE_FIT, ACTUAL, SHRINK_ONLY }

data class ContentLayoutOptions(
    val horizontal: ContentHorizontal = ContentHorizontal.CENTER,
    val vertical: ContentVertical = ContentVertical.CENTER,
    val scale: ContentScale = ContentScale.FIT,
    val margin: Int = 43,
    val rasterDpi: Int = 150,
    val postScriptJpegQuality: Int? = null,
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
    val usePrinterMargins: Boolean = false,
)

data class RenderedPreview(val bitmap: Bitmap, val pageIndex: Int, val pageCount: Int)

object DocumentRenderer {
    private fun checkNotCancelled() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("미리보기 생성을 취소했습니다")
    }

    fun pdfPagePreview(
        descriptor: ParcelFileDescriptor,
        pageIndex: Int = 0,
        orientation: Int? = null,
        maxWidth: Int = 900,
        maxHeight: Int = PreviewRenderPolicy.MAX_HEIGHT,
    ): RenderedPreview {
        var ownedBitmap: Bitmap? = null
        return try {
            val preview = PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF에 미리 볼 페이지가 없습니다" }
                require(pageIndex in 0 until renderer.pageCount) {
                    "선택한 ${pageIndex + 1}페이지가 PDF의 전체 ${renderer.pageCount}페이지를 벗어납니다"
                }
                renderer.openPage(pageIndex).use { page ->
                    checkNotCancelled()
                    val (pixelWidth, pixelHeight) = PreviewRenderPolicy.pagePixelSize(page.width, page.height, maxWidth, maxHeight)
                    val rendered = Bitmap.createBitmap(
                        pixelWidth,
                        pixelHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    ownedBitmap = rendered
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
                            .also {
                                ownedBitmap = it
                                if (it !== rendered) rendered.recycle()
                            }
                    }
                    RenderedPreview(result, pageIndex, renderer.pageCount)
                }
            }
            ownedBitmap = null
            preview
        } finally {
            ownedBitmap?.recycle()
        }
    }

    /** Final output is encoded from bounded raster bands at the requested print DPI. */
    fun encodeLaidOutPdfTo(
        descriptor: ParcelFileDescriptor,
        mime: String,
        options: ContentLayoutOptions,
        output: OutputStream,
        sidesMode: String? = null,
        printQuality: Int? = null,
    ) {
        val format = PrintFormatSupport.normalize(mime)
        require(format in setOf(PrintFormatSupport.POSTSCRIPT, PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF, PrintFormatSupport.PCL, "image/jpeg", "image/png")) {
            "배치한 PDF를 $mime 형식으로 변환할 수 없습니다"
        }
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
            if (format == "image/jpeg" || format == "image/png") {
                require(renderer.pageCount == 1) { "전송 실패: ${renderer.pageCount}페이지 문서는 JPEG·PNG 한 파일로 전송할 수 없습니다. 여러 페이지를 지원하는 출력 형식이 필요합니다." }
                renderer.openPage(0).use { page ->
                    val (width, height) = RasterBandPolicy.dimensions(page.width, page.height, options.rasterDpi)
                    if (format == "image/png") {
                        StreamingPngEncoder(width, height, options.rasterDpi, output).use { encoder ->
                            renderPageBands(page, page.width, page.height, options.rasterDpi) { band, _, _, _ ->
                                forEachRgbRow(band) { encoder.writeRgbRows(it, 1) }
                            }
                            encoder.finish()
                        }
                    } else {
                        val quality = options.postScriptJpegQuality
                            ?: error("전송 실패: JPEG 출력에는 무손실 RGB를 적용할 수 없습니다. JPEG 품질을 직접 선택해 주세요.")
                        StreamingJpegEncoder(width, height, options.rasterDpi, quality, output).use { encoder ->
                            renderPageBands(page, page.width, page.height, options.rasterDpi) { band, _, _, _ ->
                                forEachRgbRow(band) { encoder.writeRgbRows(it, 1) }
                            }
                            encoder.finish()
                        }
                    }
                }
                return
            }
            if (format == PrintFormatSupport.PCL) {
                RenderQuality.requirePclDpi(options.rasterDpi)
                output.writeAscii("\u001bE")
            }
            if (format == PrintFormatSupport.POSTSCRIPT) {
                output.writeAscii("%!PS-Adobe-3.0\n%%Creator: IPP Printer Connect\n%%LanguageLevel: 2\n%%Pages: ${renderer.pageCount}\n%%EndComments\n")
            }
            if (format == PrintFormatSupport.PWG_RASTER) output.writeAscii("RaS2")
            if (format == PrintFormatSupport.URF) {
                output.writeAscii("UNIRAST\u0000")
                output.writeIntBigEndian(renderer.pageCount)
            }
            for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                checkNotCancelled()
                val (width, height) = RasterBandPolicy.dimensions(page.width, page.height, options.rasterDpi)
                when (format) {
                    PrintFormatSupport.POSTSCRIPT -> output.writeAscii("%%Page: ${index + 1} ${index + 1}\n<< /PageSize [${page.width} ${page.height}] >> setpagedevice\n")
                    PrintFormatSupport.PWG_RASTER -> output.write(pwgPageHeader(width, height,
                        options.copy(pageWidth = page.width, pageHeight = page.height), renderer.pageCount, sidesMode, printQuality))
                    PrintFormatSupport.URF -> output.write(ByteArray(32).also { header ->
                        header[0] = 24; header[1] = 1; header[2] = 1; header[3] = (printQuality ?: 0).toByte()
                        putIntBigEndian(header, 12, width); putIntBigEndian(header, 16, height); putIntBigEndian(header, 20, options.rasterDpi)
                    })
                    PrintFormatSupport.PCL -> output.writeAscii(PclPageSetup.commands(page.width, page.height, options.rasterDpi, width, height))
                }
                renderPageBands(page, page.width, page.height, options.rasterDpi) { band, top, _, totalHeight ->
                    when (format) {
                        PrintFormatSupport.POSTSCRIPT -> writePostScriptBand(output, band, top, totalHeight, page.width, page.height, options.postScriptJpegQuality)
                        PrintFormatSupport.PCL -> writePclRows(output, band)
                        else -> writePackBitsRgbRows(output, band)
                    }
                }
                when (format) {
                    PrintFormatSupport.POSTSCRIPT -> output.writeAscii("showpage\n")
                    PrintFormatSupport.PCL -> output.writeAscii("\u001b*rB\u000c")
                }
            }
            if (format == PrintFormatSupport.POSTSCRIPT) output.writeAscii("%%Trailer\n%%Pages: ${renderer.pageCount}\n%%EOF\n")
            if (format == PrintFormatSupport.PCL) output.writeAscii("\u001bE")
        }
    }

    fun pdfWithLayoutTo(descriptor: ParcelFileDescriptor, options: ContentLayoutOptions, output: OutputStream, preview: Boolean = false) {
        StreamingRasterPdfWriter(output).use { writer ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
            for (index in 0 until renderer.pageCount) renderer.openPage(index).use { source ->
                writer.beginPage(options.pageWidth, options.pageHeight)
                val transform = positionedTransform(source.width.toFloat(), source.height.toFloat(), options)
                val clip = if (options.scale == ContentScale.PRINTABLE_FIT) RectF(
                    options.printableLeft.toFloat(), options.printableTop.toFloat(),
                    (options.pageWidth - options.printableRight).toFloat(), (options.pageHeight - options.printableBottom).toFloat(),
                ) else null
                renderPageBands(source, options.pageWidth, options.pageHeight, options.rasterDpi, transform, clip, preview) { band, top, width, height ->
                    writePdfBand(writer, band, top, width, height, options.pageWidth, options.pageHeight)
                }
                writer.endPage()
            }
        }
        writer.finish()
        }
    }

    fun imposePdfTo(
        descriptor: ParcelFileDescriptor,
        pageRanges: List<IppRange>,
        numberUp: Int,
        pageWidth: Int,
        pageHeight: Int,
        rasterDpi: Int,
        output: OutputStream,
        preview: Boolean = false,
    ) {
        StreamingRasterPdfWriter(output).use { writer ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF에 출력할 페이지가 없습니다" }
            require(pageRanges.all { it.lower >= 1 && it.upper >= it.lower && it.upper <= renderer.pageCount }) { "페이지 범위가 PDF의 전체 ${renderer.pageCount}페이지를 벗어납니다" }
            val indices = PdfPageEditor.selectedPages(renderer.pageCount, pageRanges)
            val perSheet = numberUp.coerceAtLeast(1)
            val columns = ceil(sqrt(perSheet * pageWidth.toFloat() / pageHeight)).toInt().coerceAtLeast(1)
            val rows = ceil(perSheet.toFloat() / columns).toInt().coerceAtLeast(1)
            val gap = 6f
            val cellWidth = (pageWidth - gap * (columns + 1)) / columns
            val cellHeight = (pageHeight - gap * (rows + 1)) / rows
            indices.chunked(perSheet).forEach { sheet ->
                if (perSheet == 1) {
                    renderer.openPage(sheet.single()).use { source ->
                        writer.beginPage(source.width, source.height)
                        renderPageBands(source, source.width, source.height, rasterDpi, preview = preview) { band, top, width, height ->
                            writePdfBand(writer, band, top, width, height, source.width, source.height)
                        }
                        writer.endPage()
                    }
                } else {
                    writer.beginPage(pageWidth, pageHeight)
                    val (width, height) = rasterDimensions(pageWidth, pageHeight, rasterDpi, preview)
                    var top = 0
                    while (top < height) {
                        checkNotCancelled()
                        val band = createBand(width, RasterBandPolicy.rows(width, height - top))
                        try {
                            Canvas(band).drawColor(Color.WHITE)
                            sheet.forEachIndexed { cell, sourceIndex -> renderer.openPage(sourceIndex).use { source ->
                                val scale = minOf(cellWidth / source.width, cellHeight / source.height)
                                val left = gap + (cell % columns) * (cellWidth + gap) + (cellWidth - source.width * scale) / 2
                                val upper = gap + (cell / columns) * (cellHeight + gap) + (cellHeight - source.height * scale) / 2
                                val transform = Matrix().apply { setScale(scale, scale); postTranslate(left, upper) }
                                renderIntoBand(source, band, top, width, height, pageWidth, pageHeight, transform,
                                    RectF(left, upper, left + source.width * scale, upper + source.height * scale))
                            } }
                            writePdfBand(writer, band, top, width, height, pageWidth, pageHeight)
                        } finally { band.recycle() }
                        top += RasterBandPolicy.rows(width, height - top)
                    }
                    writer.endPage()
                }
            }
        }
        writer.finish()
        }
    }

    fun imageFilePreviewPdfTo(imageFile: File, options: ContentLayoutOptions, output: OutputStream) {
        checkNotCancelled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원되는 이미지 파일이 아닙니다" }
        val orientation = try { ExifInterface(imageFile.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val decoded = try {
            BitmapFactory.decodeFile(imageFile.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = PreviewRenderPolicy.imageSampleSize(bounds.outWidth, bounds.outHeight)
                inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("미리보기 이미지를 읽을 수 없습니다")
        } catch (_: OutOfMemoryError) { error("미리보기 사진을 읽을 메모리가 부족합니다. 실제 인쇄 품질은 변경하지 않았습니다.") }
        try {
            val transform = imageFileTransform(bounds.outWidth, bounds.outHeight, orientation, options).apply {
                preScale(bounds.outWidth.toFloat() / decoded.width, bounds.outHeight.toFloat() / decoded.height)
            }
            val clip = if (options.scale == ContentScale.PRINTABLE_FIT) floatArrayOf(
                options.printableLeft.toFloat(), options.printableTop.toFloat(),
                (options.pageWidth - options.printableRight).toFloat(), (options.pageHeight - options.printableBottom).toFloat(),
            ) else null
            StreamingRasterPdfWriter(output).use { writer ->
            writer.beginPage(options.pageWidth, options.pageHeight, clip)
            writer.image(decoded.width, decoded.height, FloatArray(9).also(transform::getValues)) { writeRgbRows(it, decoded) }
            writer.endPage(); writer.finish()
        }
        } finally { decoded.recycle() }
    }

    /**
     * Embeds the source JPEG in a page-sized PDF without resampling its pixels.
     */
    fun jpegPhotoPdfTo(imageFile: File, options: ContentLayoutOptions, output: OutputStream) {
        checkNotCancelled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원되는 JPEG 파일이 아닙니다" }
        val orientation = imageOrientation(imageFile)
        val sample = photoSampleSize(bounds.outWidth, bounds.outHeight, orientation, options)
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(PrintCache.directory)
        PDDocument(memory).use { document ->
            val page = PDPage(PDRectangle(options.pageWidth.toFloat(), options.pageHeight.toFloat()))
            document.addPage(page)
            val image = if (sample == 1) {
                imageFile.inputStream().buffered().use { JPEGFactory.createFromStream(document, it) }
            } else {
                val bitmap = try {
                    BitmapFactory.decodeFile(imageFile.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: error("JPEG 사진을 읽을 수 없습니다")
                } catch (_: OutOfMemoryError) {
                    error("선택한 용지에 맞춰 사진을 준비할 메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요.")
                }
                try {
                    JPEGFactory.createFromImage(document, bitmap, 1f, options.rasterDpi.coerceAtLeast(1))
                } finally {
                    bitmap.recycle()
                }
            }
            val transform = imageFileTransform(bounds.outWidth, bounds.outHeight, orientation, options)
            val values = FloatArray(9).also(transform::getValues)
            val bitmapWidth = if (sample == 1) bounds.outWidth else image.width
            val bitmapHeight = if (sample == 1) bounds.outHeight else image.height
            val scaleX = bounds.outWidth.toFloat() / bitmapWidth
            val scaleY = bounds.outHeight.toFloat() / bitmapHeight
            val pdfMatrix = imagePdfMatrix(values, bitmapWidth, bitmapHeight, scaleX, scaleY, options.pageHeight)
            PDPageContentStream(document, page).use { content ->
                content.saveGraphicsState()
                clipPrintableArea(content, options)
                content.drawImage(image, pdfMatrix)
                content.restoreGraphicsState()
            }
            document.save(output)
        }
    }

    fun imageFilePdfTo(imageFile: File, options: ContentLayoutOptions, output: OutputStream) {
        checkNotCancelled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원되는 이미지 파일이 아닙니다" }
        val orientation = imageOrientation(imageFile)
        val transform = imageFileTransform(bounds.outWidth, bounds.outHeight, orientation, options)
        val sample = photoSampleSize(bounds.outWidth, bounds.outHeight, orientation, options)
        val tileSpan = (1024L * sample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(sample)
        StreamingRasterPdfWriter(output).use { writer ->
        val clip = if (options.scale == ContentScale.PRINTABLE_FIT) floatArrayOf(
            options.printableLeft.toFloat(), options.printableTop.toFloat(),
            (options.pageWidth - options.printableRight).toFloat(), (options.pageHeight - options.printableBottom).toFloat(),
        ) else null
        writer.beginPage(options.pageWidth, options.pageHeight, clip)
        val region = try { BitmapRegionDecoder.newInstance(imageFile.absolutePath, false) }
            catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
        if (region != null) {
            try {
                var top = 0
                while (top < bounds.outHeight) {
                    var left = 0
                    while (left < bounds.outWidth) {
                        checkNotCancelled()
                        val rectangle = Rect(left, top, minOf(left + tileSpan, bounds.outWidth), minOf(top + tileSpan, bounds.outHeight))
                        val bitmap = try {
                            region.decodeRegion(rectangle, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                                ?: error("사진의 일부 영역을 읽을 수 없습니다")
                        } catch (_: OutOfMemoryError) { error("선택한 용지에 맞춰 사진을 준비할 메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요.") }
                        try {
                            val tileTransform = Matrix(transform).apply {
                                preTranslate(left.toFloat(), top.toFloat())
                                preScale(sample.toFloat(), sample.toFloat())
                            }
                            val values = FloatArray(9).also(tileTransform::getValues)
                            writer.image(bitmap.width, bitmap.height, values) { writeRgbRows(it, bitmap) }
                        } finally { bitmap.recycle() }
                        left += rectangle.width()
                    }
                    top += minOf(tileSpan, bounds.outHeight - top)
                }
            } finally { region.recycle() }
        } else {
            val bitmap = try { BitmapFactory.decodeFile(imageFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false })
                ?: error("이미지를 읽을 수 없습니다") }
                catch (_: OutOfMemoryError) { error("이 이미지 형식은 영역별로 읽을 수 없으며, 선택한 용지에 맞출 메모리가 부족합니다.") }
            try {
                val sampledTransform = Matrix(transform).apply {
                    preScale(bounds.outWidth.toFloat() / bitmap.width, bounds.outHeight.toFloat() / bitmap.height)
                }
                writer.image(bitmap.width, bitmap.height, FloatArray(9).also(sampledTransform::getValues)) { writeRgbRows(it, bitmap) }
            }
            finally { bitmap.recycle() }
        }
        writer.endPage(); writer.finish()
        }
    }

    private fun imageOrientation(imageFile: File): Int = try {
        ExifInterface(imageFile.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun photoSampleSize(width: Int, height: Int, orientation: Int, options: ContentLayoutOptions): Int {
        // Page-sized PDF output defines physical placement separately; keep source pixels intact.
        if (options.rasterDpi < 0) return 1
        val orientedWidth = if (orientation in 5..8) height.toFloat() else width.toFloat()
        val orientedHeight = if (orientation in 5..8) width.toFloat() else height.toFloat()
        val rotation = Matrix().apply { setRotate(((options.rotationDegrees % 360f) + 360f) % 360f) }
        val rotated = RectF(0f, 0f, orientedWidth, orientedHeight).also(rotation::mapRect)
        val placed = ImageLayoutGeometry.place(rotated.width(), rotated.height(), options)
        val visibleWidth = minOf(placed.width, options.pageWidth.toFloat())
        val visibleHeight = minOf(placed.height, options.pageHeight.toFloat())
        val targetLongEdge = maxOf(visibleWidth, visibleHeight) *
            (options.rasterDpi.takeIf { it > 0 } ?: AutomaticRasterPolicy.STANDARD_DPI) / 72f
        val sourceLongEdge = maxOf(width, height).toDouble()
        val divisor = ceil(sourceLongEdge / targetLongEdge.coerceAtLeast(1f)).toLong()
        return divisor.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun imagePdfMatrix(
        values: FloatArray,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
        pageHeight: Int,
    ): PdfBoxMatrix {
        val a = values[0] * scaleX * width
        val b = -values[3] * scaleX * width
        val c = -values[1] * scaleY * height
        val d = values[4] * scaleY * height
        val e = values[1] * scaleY * height + values[2]
        val f = pageHeight - (values[4] * scaleY * height + values[5])
        return PdfBoxMatrix(a, b, c, d, e, f)
    }

    private fun clipPrintableArea(content: PDPageContentStream, options: ContentLayoutOptions) {
        if (options.scale != ContentScale.PRINTABLE_FIT) return
        val left = options.printableLeft.toFloat()
        val bottom = (options.pageHeight - options.printableBottom).toFloat()
        val width = (options.pageWidth - options.printableLeft - options.printableRight).toFloat()
        val height = (options.pageHeight - options.printableTop - options.printableBottom).toFloat()
        content.addRect(left, bottom, width, height)
        content.clip()
    }

    private fun imageFileTransform(width: Int, height: Int, orientation: Int, options: ContentLayoutOptions): Matrix {
        val exif = Matrix().apply { ImageOrientation.matrixValues(orientation, width, height)?.let(::setValues) }
        val oriented = RectF(0f, 0f, width.toFloat(), height.toFloat()).also(exif::mapRect)
        return Matrix().apply { setConcat(positionedTransform(oriented.width(), oriented.height(), options), exif) }
    }

    internal fun positionedTransform(width: Float, height: Float, options: ContentLayoutOptions): Matrix {
        val rotation = Matrix().apply { setRotate(((options.rotationDegrees % 360f) + 360f) % 360f) }
        val bounds = RectF(0f, 0f, width, height).also(rotation::mapRect)
        val destination = ImageLayoutGeometry.place(bounds.width(), bounds.height(), options)
        return Matrix(rotation).apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(destination.width / bounds.width(), destination.height / bounds.height())
            postTranslate(destination.left, destination.top)
        }
    }

    private fun rasterDimensions(width: Int, height: Int, dpi: Int, preview: Boolean): Pair<Int, Int> =
        if (preview) PreviewRenderPolicy.pagePixelSize(width, height) else RasterBandPolicy.dimensions(width, height, dpi)

    private fun createBand(width: Int, height: Int): Bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { error("선택한 해상도의 이미지 줄을 처리할 메모리가 부족합니다. 해상도를 직접 변경해 주세요. 자동 축소하지 않았습니다.") }

    private fun renderPageBands(
        source: PdfRenderer.Page, widthPoints: Int, heightPoints: Int, dpi: Int,
        transform: Matrix = Matrix(), clip: RectF? = null, preview: Boolean = false,
        emit: (Bitmap, Int, Int, Int) -> Unit,
    ) {
        val (width, height) = rasterDimensions(widthPoints, heightPoints, dpi, preview)
        var top = 0
        while (top < height) {
            checkNotCancelled()
            val count = RasterBandPolicy.rows(width, height - top)
            val bitmap = createBand(width, count)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                renderIntoBand(source, bitmap, top, width, height, widthPoints, heightPoints, transform, clip)
                emit(bitmap, top, width, height)
            } finally { bitmap.recycle() }
            top += count
        }
    }

    private fun renderIntoBand(
        source: PdfRenderer.Page, bitmap: Bitmap, top: Int, width: Int, height: Int,
        widthPoints: Int, heightPoints: Int, transform: Matrix, clip: RectF?,
    ) {
        val sx = width.toFloat() / widthPoints; val sy = height.toFloat() / heightPoints
        val matrix = Matrix(transform).apply { postScale(sx, sy); postTranslate(0f, -top.toFloat()) }
        val destinationClip = if (clip == null) Rect(0, 0, bitmap.width, bitmap.height) else Rect(
            kotlin.math.floor(clip.left * sx).toInt().coerceIn(0, bitmap.width),
            kotlin.math.floor(clip.top * sy - top).toInt().coerceIn(0, bitmap.height),
            ceil(clip.right * sx).toInt().coerceIn(0, bitmap.width),
            ceil(clip.bottom * sy - top).toInt().coerceIn(0, bitmap.height),
        )
        if (!destinationClip.isEmpty) source.render(bitmap, destinationClip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }

    private fun writePdfBand(writer: StreamingRasterPdfWriter, band: Bitmap, top: Int, width: Int, height: Int, widthPoints: Int, heightPoints: Int) {
        val matrix = floatArrayOf(widthPoints.toFloat() / width, 0f, 0f, 0f, heightPoints.toFloat() / height, top * heightPoints.toFloat() / height, 0f, 0f, 1f)
        writer.image(band.width, band.height, matrix) { writeRgbRows(it, band) }
    }

    private fun writeRgbRows(output: OutputStream, bitmap: Bitmap) =
        forEachRgbRow(bitmap) { output.write(it) }

    private inline fun forEachRgbRow(bitmap: Bitmap, emit: (ByteArray) -> Unit) {
        val pixels = IntArray(bitmap.width); val bytes = ByteArray(bitmap.width * 3)
        for (row in 0 until bitmap.height) {
            checkNotCancelled(); bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            var offset = 0
            pixels.forEach { color ->
                val alpha = Color.alpha(color)
                bytes[offset++] = ((Color.red(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
                bytes[offset++] = ((Color.green(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
                bytes[offset++] = ((Color.blue(color) * alpha + 255 * (255 - alpha)) / 255).toByte()
            }
            emit(bytes)
        }
    }

    private fun writePostScriptBand(output: OutputStream, bitmap: Bitmap, top: Int, height: Int, widthPoints: Int, heightPoints: Int, jpegQuality: Int?) {
        require(jpegQuality == null || jpegQuality in 0..100) { "JPEG 품질은 0~100이어야 합니다" }
        val bottom = (height - top - bitmap.height).toDouble() * heightPoints / height
        val bandHeight = bitmap.height.toDouble() * heightPoints / height
        val filter = if (jpegQuality == null) "/ASCII85Decode filter" else "/ASCII85Decode filter /DCTDecode filter"
        output.writeAscii("gsave\n0 $bottom translate\n$widthPoints $bandHeight scale\n/DeviceRGB setcolorspace\n<< /ImageType 1 /Width ${bitmap.width} /Height ${bitmap.height} /BitsPerComponent 8 /Decode [0 1 0 1 0 1] /ImageMatrix [${bitmap.width} 0 0 -${bitmap.height} 0 ${bitmap.height}] /DataSource currentfile $filter >> image\n")
        val ascii85 = Ascii85OutputStream(output)
        if (jpegQuality == null) writeRgbRows(ascii85, bitmap)
        else check(bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, ascii85)) { "PostScript용 JPEG 변환에 실패했습니다" }
        ascii85.finish()
        output.writeAscii("\ngrestore\n")
    }

    private fun writePclRows(output: OutputStream, bitmap: Bitmap) {
        val rowBytes = (bitmap.width + 7) / 8; val pixels = IntArray(bitmap.width); val packed = ByteArray(rowBytes)
        for (y in 0 until bitmap.height) {
            checkNotCancelled(); java.util.Arrays.fill(packed, 0)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in pixels.indices) {
                val color = pixels[x]; val alpha = Color.alpha(color)
                val red = (Color.red(color) * alpha + 255 * (255 - alpha)) / 255
                val green = (Color.green(color) * alpha + 255 * (255 - alpha)) / 255
                val blue = (Color.blue(color) * alpha + 255 * (255 - alpha)) / 255
                if ((red * 299 + green * 587 + blue * 114) / 1000 < 170) packed[x / 8] = (packed[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
            }
            output.writeAscii("\u001b*b${rowBytes}W"); output.write(packed)
        }
    }

    private fun pwgPageHeader(width: Int, height: Int, options: ContentLayoutOptions, pageCount: Int, sidesMode: String?, printQuality: Int?): ByteArray {
        val output = ByteArrayOutputStream(1796)
        output.writeFixedString("PwgRaster")
        output.writeFixedString("")
        output.writeFixedString("auto")
        output.writeFixedString("sRGB_8")
        val values = IntArray(42)
        values[4] = if (sidesMode?.startsWith("two-sided") == true) 1 else 0
        values[5] = options.rasterDpi
        values[6] = options.rasterDpi
        values[7] = 0
        values[8] = 0
        values[9] = options.pageWidth
        values[10] = options.pageHeight
        values[21] = 1
        values[24] = options.pageWidth
        values[25] = options.pageHeight
        values[28] = if (sidesMode == "two-sided-short-edge") 1 else 0
        values[29] = width
        values[30] = height
        values[32] = 8
        values[33] = 24
        values[34] = width * 3
        values[35] = 0
        values[36] = 19
        values[41] = 3
        values.forEach { output.writeIntBigEndian(it) }
        repeat(7) { output.writeIntBigEndian(0) }
        val integers = IntArray(16)
        integers[0] = pageCount
        integers[1] = 1
        integers[2] = 1
        integers[3] = 0
        integers[4] = 0
        integers[5] = width
        integers[6] = height
        integers[7] = 0xFFFFFF
        integers[8] = printQuality ?: 0
        integers.forEach { output.writeIntBigEndian(it) }
        repeat(16) { output.writeIntBigEndian(0) }
        output.write(ByteArray(16 * 64))
        output.writeFixedString("")
        output.writeFixedString("Perceptual")
        output.writeFixedString(pwgMediaSizeName(options.pageWidth, options.pageHeight))
        return output.toByteArray().also { check(it.size == 1796) { "PWG Raster 페이지 헤더 크기가 올바르지 않습니다" } }
    }

    private fun pwgMediaSizeName(widthPoints: Int, heightPoints: Int): String {
        val standardName = when (widthPoints to heightPoints) {
            595 to 842 -> "iso_a4_210x297mm"
            842 to 1191 -> "iso_a3_297x420mm"
            420 to 595 -> "iso_a5_148x210mm"
            612 to 792 -> "na_letter_8.5x11in"
            612 to 1008 -> "na_legal_8.5x14in"
            else -> null
        }
        if (standardName != null) return standardName
        fun millimeters(points: Int): String = BigDecimal.valueOf(points.toLong())
            .multiply(BigDecimal("25.4"))
            .divide(BigDecimal("72"), 4, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return "custom_document_${millimeters(widthPoints)}x${millimeters(heightPoints)}mm"
    }

    private fun OutputStream.writeIntBigEndian(value: Int) {
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

    private fun writePackBitsRgbRows(output: OutputStream, bitmap: Bitmap) {
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

    private fun writePackBitsRgbRow(output: OutputStream, row: ByteArray, width: Int) {
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

    private fun OutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

}
