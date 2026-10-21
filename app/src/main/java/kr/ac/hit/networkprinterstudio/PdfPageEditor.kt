package kr.ac.hit.networkprinterstudio

import android.graphics.Matrix as AndroidMatrix
import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.harmony.awt.geom.AffineTransform
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.sqrt

/** Page selection and placement keep PDF content streams, fonts and image samples. */
internal object PdfPageEditor {
    fun write(source: File, destination: File, settings: FilePreparationSettings, cacheDir: File) {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(cacheDir)
        PDDocument.load(source, memory).use { input ->
            require(input.numberOfPages > 0) { "PDF에 출력할 페이지가 없습니다" }
            require(input.currentAccessPermission.canPrint()) { "인쇄가 허용되지 않은 PDF입니다" }
            val ranges = settings.pageRanges
            require(ranges.all { it.lower >= 1 && it.upper >= it.lower && it.upper <= input.numberOfPages }) {
                "페이지 범위가 PDF의 전체 ${input.numberOfPages}페이지를 벗어납니다"
            }
            val selected = selectedPages(input.numberOfPages, ranges)
            if (!settings.contentLayoutChanged && settings.numberUp == 1) {
                // Stay in the same document: annotations, form fields and inherited resources survive.
                val pages = selected.map { index -> input.getPage(index).also { page ->
                    page.resources = page.resources
                    page.mediaBox = page.mediaBox
                    page.cropBox = page.cropBox
                    page.rotation = page.rotation
                } }.toList()
                while (input.numberOfPages > 0) { cancelled(); input.removePage(0) }
                pages.forEach { cancelled(); input.addPage(it) }
                input.save(destination)
                return
            }
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(cacheDir)).use { output ->
                input.documentCatalog.cosObject.getDictionaryObject(COSName.OUTPUT_INTENTS)?.let {
                    output.documentCatalog.cosObject.setItem(COSName.OUTPUT_INTENTS, PDFCloneUtility(output).cloneForNewDocument(it))
                }
                val importer = LayerUtility(output)
                val options = settings.layout
                val perSheet = settings.numberUp
                require(perSheet in setOf(1, 2, 4, 6, 9, 16)) { "앱 모아찍기는 1·2·4·6·9·16페이지를 지원합니다" }
                val columns = ceil(sqrt(perSheet * options.pageWidth.toDouble() / options.pageHeight)).toInt().coerceAtLeast(1)
                val rows = ceil(perSheet.toDouble() / columns).toInt()
                val margins = ImageLayoutGeometry.insets(options)
                val areaWidth = options.pageWidth - margins[0] - margins[2]
                val areaHeight = options.pageHeight - margins[1] - margins[3]
                require(areaWidth > 0 && areaHeight > 0) { "용지보다 여백이 큽니다" }
                val iterator = selected.iterator()
                while (iterator.hasNext()) {
                    cancelled()
                    val sheet = PDPage(PDRectangle(options.pageWidth.toFloat(), options.pageHeight.toFloat()))
                    output.addPage(sheet)
                    PDPageContentStream(output, sheet).use { content ->
                        for (cell in 0 until perSheet) {
                            if (!iterator.hasNext()) break
                            val page = input.getPage(iterator.next())
                            require(page.annotations.isEmpty()) {
                                "주석·입력란이 있는 PDF는 현재 배치 변경을 지원하지 않습니다. 주석을 포함해 평면화한 PDF를 선택하거나 원본 배치로 인쇄하세요."
                            }
                            require(page.userUnit == 1f) { "특수 좌표 단위 PDF는 배치 변경을 지원하지 않습니다. 원본 배치로 인쇄하세요." }
                            val box = page.cropBox
                            val rotation = ((page.rotation % 360) + 360) % 360
                            require(rotation % 90 == 0) { "PDF 페이지 회전값을 처리할 수 없습니다" }
                            // Import the original operators. Override the library's fit-to-unrotated-box matrix.
                            val form = importer.importPageAsForm(input, page)
                            form.setMatrix(AffineTransform())
                            val normalize = AndroidMatrix().apply {
                                setValues(floatArrayOf(1f, 0f, -box.lowerLeftX, 0f, -1f, box.upperRightY, 0f, 0f, 1f))
                                postRotate(rotation.toFloat())
                            }
                            val bounds = RectF(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY).also(normalize::mapRect)
                            normalize.postTranslate(-bounds.left, -bounds.top)
                            val placed = if (perSheet == 1) {
                                DocumentRenderer.positionedTransform(bounds.width(), bounds.height(), options)
                            } else {
                                val cellWidth = areaWidth / columns
                                val cellHeight = areaHeight / rows
                                val cellOptions = options.copy(
                                    pageWidth = cellWidth.toInt(), pageHeight = cellHeight.toInt(), margin = 0,
                                    usePrinterMargins = false, printableLeft = 0, printableTop = 0,
                                    printableRight = 0, printableBottom = 0,
                                )
                                DocumentRenderer.positionedTransform(bounds.width(), bounds.height(), cellOptions).apply {
                                    postTranslate(margins[0] + cell % columns * cellWidth, margins[1] + cell / columns * cellHeight)
                                }
                            }
                            val combined = AndroidMatrix().apply { setConcat(placed, normalize) }
                            val values = FloatArray(9).also(combined::getValues)
                            content.saveGraphicsState()
                            if (perSheet > 1) {
                                val cw = areaWidth / columns; val ch = areaHeight / rows
                                content.addRect(margins[0] + cell % columns * cw,
                                    options.pageHeight - margins[1] - (cell / columns + 1) * ch, cw, ch)
                                content.clip()
                            } else if (options.scale == ContentScale.PRINTABLE_FIT) {
                                content.addRect(margins[0], margins[3], areaWidth, areaHeight)
                                content.clip()
                            }
                            content.transform(Matrix(values[0], -values[3], values[1], -values[4], values[2], options.pageHeight - values[5]))
                            content.drawForm(form)
                            content.restoreGraphicsState()
                        }
                    }
                }
                cancelled()
                output.save(destination)
            }
        }
    }

    internal fun selectedPages(count: Int, ranges: List<IppRange>): Sequence<Int> = sequence {
        if (ranges.isEmpty()) {
            for (index in 0 until count) { cancelled(); yield(index) }
        } else {
            // Preserve requested order; overlaps are rejected instead of silently deleting duplicates.
            ranges.forEachIndexed { index, range ->
                require(ranges.take(index).none { it.lower <= range.upper && range.lower <= it.upper }) {
                    "페이지 범위가 중복됩니다. 겹치지 않게 입력하세요."
                }
                for (page in range.lower..range.upper) { cancelled(); yield(page - 1) }
            }
        }
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("문서 준비를 취소했습니다")
    }
}
