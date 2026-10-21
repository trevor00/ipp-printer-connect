package kr.ac.hit.networkprinterstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.floor

/** Test-only reference for comparing the streaming TXT PDF writer with the previous renderer. */
internal object LegacyTextPdfReference {
    private const val TEXT_SIZE_PT = 15f

    fun write(text: String, options: ContentLayoutOptions, output: OutputStream) {
        require(text.isNotBlank()) { "출력할 텍스트가 비어 있습니다" }
        val size = TEXT_SIZE_PT * options.scalePercent / 100f
        require(size.isFinite() && size > 0) { "글자 크기는 0보다 커야 합니다." }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
        val insets = insets(options)
        val lines = wrap(text, options.pageWidth - insets.left - insets.right) { paint.measureText(it) }
        val metrics = paint.fontMetrics
        val lineHeight = maxOf(size * 1.48f, metrics.bottom - metrics.top)
        val pages = pages(
            lines,
            options.pageHeight - insets.top - insets.bottom - options.offsetY.coerceAtLeast(0f),
            0f,
            metrics.bottom - metrics.top,
            lineHeight,
        )
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, pageLines ->
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(options.pageWidth, options.pageHeight, index + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    val clip = beginPrintableClip(page.canvas, options, insets)
                    val rotation = beginContentRotation(page.canvas, options)
                    drawPage(page.canvas, pageLines, options, paint, insets, lineHeight)
                    page.canvas.restoreToCount(rotation)
                    clip?.let(page.canvas::restoreToCount)
                } finally {
                    pdf.finishPage(page)
                }
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    private fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        require(width.isFinite() && width > 0)
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n').flatMap { paragraph ->
            val lines = mutableListOf<String>()
            var line = ""
            var index = 0
            while (index < paragraph.length) {
                val end = index + Character.charCount(paragraph.codePointAt(index))
                val character = paragraph.substring(index, end)
                require(measure(character) <= width)
                if (line.isNotEmpty() && measure(line + character) > width) { lines += line; line = character }
                else line += character
                index = end
            }
            lines += line
            lines
        }
    }

    private fun pages(lines: List<String>, height: Float, firstPageHeader: Float, glyphHeight: Float, lineHeight: Float): List<List<String>> {
        require(height.isFinite() && firstPageHeader.isFinite() && glyphHeight.isFinite() && lineHeight.isFinite() && firstPageHeader >= 0 && glyphHeight > 0 && lineHeight > 0)
        fun capacity(header: Float): Int {
            val available = height - header
            require(available >= glyphHeight)
            return (floor(((available - glyphHeight) / lineHeight).toDouble()).toInt() + 1).coerceAtLeast(1)
        }
        val first = capacity(firstPageHeader)
        val later = capacity(0f)
        val result = mutableListOf<List<String>>()
        var start = 0
        while (start < lines.size) {
            val end = minOf(lines.size.toLong(), start.toLong() + if (result.isEmpty()) first else later).toInt()
            result += lines.subList(start, end).toList()
            start = end
        }
        return result
    }

    private data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun insets(options: ContentLayoutOptions): Insets = when (options.scale) {
        ContentScale.FULL_BLEED_FILL -> Insets(0f, 0f, 0f, 0f)
        ContentScale.PRINTABLE_FIT -> Insets(
            options.printableLeft.toFloat(), options.printableTop.toFloat(),
            options.printableRight.toFloat(), options.printableBottom.toFloat(),
        )
        else -> Insets(options.margin.toFloat(), options.margin.toFloat(), options.margin.toFloat(), options.margin.toFloat())
    }

    private fun beginPrintableClip(canvas: Canvas, options: ContentLayoutOptions, insets: Insets): Int? {
        if (options.scale != ContentScale.PRINTABLE_FIT) return null
        return canvas.save().also {
            canvas.clipRect(insets.left, insets.top, options.pageWidth - insets.right, options.pageHeight - insets.bottom)
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

    private fun drawPage(
        canvas: Canvas,
        lines: List<String>,
        options: ContentLayoutOptions,
        paint: Paint,
        insets: Insets,
        lineHeight: Float,
    ) {
        paint.color = Color.BLACK
        paint.textAlign = when (options.horizontal) {
            ContentHorizontal.LEFT -> Paint.Align.LEFT
            ContentHorizontal.CENTER -> Paint.Align.CENTER
            ContentHorizontal.RIGHT -> Paint.Align.RIGHT
        }
        val contentHeight = paint.fontMetrics.bottom - paint.fontMetrics.top + (lines.size - 1).coerceAtLeast(0) * lineHeight
        val availableHeight = options.pageHeight - insets.top - insets.bottom - options.offsetY.coerceAtLeast(0f)
        val top = when (options.vertical) {
            ContentVertical.TOP -> insets.top
            ContentVertical.CENTER -> insets.top + ((availableHeight - contentHeight) / 2f).coerceAtLeast(0f)
            ContentVertical.BOTTOM -> insets.top + (availableHeight - contentHeight).coerceAtLeast(0f)
        } + options.offsetY
        val x = when (options.horizontal) {
            ContentHorizontal.LEFT -> insets.left
            ContentHorizontal.CENTER -> insets.left + (options.pageWidth - insets.left - insets.right) / 2f
            ContentHorizontal.RIGHT -> options.pageWidth - insets.right
        } + options.offsetX
        var y = top - paint.fontMetrics.top
        lines.forEach { line ->
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
    }
}
