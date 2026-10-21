package kr.ac.hit.networkprinterstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** Text is validated and laid out a page at a time; final output keeps Android's vector glyphs. */
internal object TextPdfStreaming {
    fun write(sourceFile: File, options: ContentLayoutOptions, output: OutputStream, cacheDir: File) {
        val source = TextFileDecoder.validate(sourceFile)
        require(source.hasVisibleCharacters) { "출력할 텍스트가 비어 있습니다" }
        val size = 15f * options.scalePercent / 100f
        require(size.isFinite() && size > 0) { "글자 크기는 0보다 커야 합니다." }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.BLACK }
        val metrics = paint.fontMetrics
        val glyphHeight = metrics.bottom - metrics.top
        val lineHeight = maxOf(size * 1.48f, glyphHeight)
        val insets = insets(options)
        val width = options.pageWidth - insets[0] - insets[2]
        val height = options.pageHeight - insets[1] - insets[3] - options.offsetY.coerceAtLeast(0f)
        val directory = File(cacheDir, "text_vectors_${UUID.randomUUID()}")
        check(directory.mkdir()) { "텍스트 출력용 임시 폴더를 만들 수 없습니다." }
        var failure: Throwable? = null
        try {
            GeneratedPagePdfMerger(output, directory).use { merger ->
                var pageNumber = 0
                TextFileDecoder.openReader(source).use { reader ->
                    StreamingTextPagination.forEachPage(reader, width, height, 0f, glyphHeight, lineHeight,
                        measure = { paint.measureText(it) },
                        emit = { lines ->
                            cancelled()
                            val pageFile = File(directory, "page.pdf")
                            val document = PdfDocument()
                            try {
                                val page = document.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, ++pageNumber).create())
                                try { drawPage(page.canvas, lines, options, paint, insets, glyphHeight, lineHeight) }
                                finally { document.finishPage(page) }
                                pageFile.outputStream().buffered().use { document.writeTo(it) }
                            } finally { document.close() }
                            try { merger.addPage(pageFile) }
                            finally { if (pageFile.exists() && !pageFile.delete()) throw IOException("텍스트 페이지 임시 파일을 정리하지 못했습니다.") }
                        },
                    )
                }
                merger.finish()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                check(directory.canonicalFile.parentFile == cacheDir.canonicalFile && directory.name.startsWith("text_vectors_"))
                directory.walkBottomUp().forEach { file ->
                    if (file.exists() && !file.delete()) throw IOException("텍스트 출력 임시 파일을 정리하지 못했습니다.")
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun drawPage(
        canvas: Canvas, lines: List<String>, options: ContentLayoutOptions, paint: Paint,
        insets: FloatArray, glyphHeight: Float, lineHeight: Float,
    ) {
        canvas.drawColor(Color.WHITE)
        val clip = if (options.scale == ContentScale.PRINTABLE_FIT) canvas.save().also {
            canvas.clipRect(insets[0], insets[1], options.pageWidth - insets[2], options.pageHeight - insets[3])
        } else null
        val rotation = canvas.save()
        try {
            val degrees = ((options.rotationDegrees % 360f) + 360f) % 360f
            if (degrees != 0f) {
                val swapsAxes = degrees == 90f || degrees == 270f
                val rotatedWidth = if (swapsAxes) options.pageHeight.toFloat() else options.pageWidth.toFloat()
                val rotatedHeight = if (swapsAxes) options.pageWidth.toFloat() else options.pageHeight.toFloat()
                val fit = minOf(options.pageWidth / rotatedWidth, options.pageHeight / rotatedHeight)
                canvas.translate(options.pageWidth / 2f, options.pageHeight / 2f)
                canvas.rotate(degrees)
                canvas.scale(fit, fit)
                canvas.translate(-options.pageWidth / 2f, -options.pageHeight / 2f)
            }
            val contentHeight = glyphHeight + (lines.size - 1).coerceAtLeast(0) * lineHeight
            val availableHeight = options.pageHeight - insets[1] - insets[3] - options.offsetY.coerceAtLeast(0f)
            val top = when (options.vertical) {
                ContentVertical.TOP -> insets[1]
                ContentVertical.CENTER -> insets[1] + ((availableHeight - contentHeight) / 2f).coerceAtLeast(0f)
                ContentVertical.BOTTOM -> insets[1] + (availableHeight - contentHeight).coerceAtLeast(0f)
            } + options.offsetY
            paint.textAlign = when (options.horizontal) {
                ContentHorizontal.LEFT -> Paint.Align.LEFT
                ContentHorizontal.CENTER -> Paint.Align.CENTER
                ContentHorizontal.RIGHT -> Paint.Align.RIGHT
            }
            val x = when (options.horizontal) {
                ContentHorizontal.LEFT -> insets[0]
                ContentHorizontal.CENTER -> insets[0] + (options.pageWidth - insets[0] - insets[2]) / 2f
                ContentHorizontal.RIGHT -> options.pageWidth - insets[2]
            } + options.offsetX
            var y = top - paint.fontMetrics.top
            for (line in lines) {
                cancelled()
                canvas.drawText(line, x, y, paint)
                y += lineHeight
            }
        } finally {
            canvas.restoreToCount(rotation)
            clip?.let(canvas::restoreToCount)
        }
    }

    private fun insets(options: ContentLayoutOptions): FloatArray = ImageLayoutGeometry.insets(options)

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("텍스트 PDF 생성을 취소했습니다.")
    }
}
