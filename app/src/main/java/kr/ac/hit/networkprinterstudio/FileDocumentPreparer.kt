package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.IOException

internal data class FilePreparationSettings(
    val layout: ContentLayoutOptions,
    val contentLayoutChanged: Boolean,
    val pageRanges: List<IppRange>,
    val numberUp: Int,
    val colorMode: String? = null,
    val requestedOutputMime: String? = null,
    val expectedSourceMime: String? = null,
    val sidesMode: String? = null,
    val printQuality: Int? = null,
) {
    val sheetLayoutChanged: Boolean get() = pageRanges.isNotEmpty() || numberUp > 1
}

internal data class PrintWorkRequest(
    val printer: PrinterRecord,
    val file: Uri?,
    val remoteUri: String?,
    val settings: FilePreparationSettings,
    val options: IppJobOptions,
    val submissionMethod: JobSubmissionMethod,
)

internal data class FilePreview(
    val pdfFile: File,
    val layoutApplied: Boolean,
    val printWarning: String?,
    val colorMode: String? = null,
    val outputMime: String? = null,
) : Closeable {
    override fun close() { pdfFile.delete() }
}

internal class FileDocumentPreparer(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = PrintCache.initialize(context.applicationContext)
    init { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }

    fun inspectMime(uri: Uri): String {
        val name = fileDisplayName(uri)
        val header = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
            }
            buffer.copyOf(size)
        } ?: error("선택한 파일을 열 수 없습니다")
        return FileMimeDetector.detect(resolver.getType(uri), name, header)
    }

    private fun needsPageLayout(source: String, settings: FilePreparationSettings): Boolean =
        PrintCapabilityPolicy.usesAppLayout(source, settings.contentLayoutChanged || settings.sheetLayoutChanged)

    private fun detectMime(uri: Uri, name: String, source: File): String {
        val header = source.inputStream().use { input ->
            val bytes = ByteArray(64)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
            bytes.copyOf(count)
        }
        return PrintFormatSupport.normalize(FileMimeDetector.detect(resolver.getType(uri), name, header))
    }

    private fun capture(uri: Uri, workspace: DocumentWorkspace): File {
        val input = resolver.openInputStream(uri) ?: error("선택한 파일을 열 수 없습니다")
        return try {
            workspace.capture(input)
        } catch (error: IOException) {
            if (cacheDir.usableSpace < 64 * 1024) {
                throw IOException("문서를 준비할 휴대폰 저장 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 시도해 주세요.", error)
            }
            throw IOException("문서 파일을 준비하지 못했습니다: ${error.message}", error)
        }
    }

    /** Preview owns only a display PDF; it is never handed to the print sender. */
    fun preview(printer: PrinterRecord, uri: Uri, settings: FilePreparationSettings): FilePreview = synchronized(processingLock) {
        checkNotCancelled()
        DocumentWorkspace(cacheDir).use { workspace ->
            val name = fileDisplayName(uri)
            val sourceFile = capture(uri, workspace)
            val source = detectMime(uri, name, sourceFile)
            settings.expectedSourceMime?.let { require(source == it) { "파일 형식이 변경되었습니다. 다시 선택하세요." } }
            require(PrintCapabilityPolicy.appCanLayout(source)) {
                PrintFormatSupport.unsupportedFileMessage(name, source, printer.formats)
            }
            val paperFit = source == PrintFormatSupport.PDF && PdfPageEditor.needsSelectedPaperFit(
                sourceFile, settings.layout, settings.pageRanges, cacheDir,
            )
            val planningSettings = if (paperFit) settings.copy(contentLayoutChanged = true) else settings
            val output = DocumentOutputPlanner.plan(printer.formats, source, needsPageLayout(source, planningSettings),
                false, settings.colorMode ?: printer.defaultColorMode,
                settings.requestedOutputMime, printer = printer,
                sidesMode = settings.sidesMode ?: printer.defaultSides)
            // If PDF itself is not an advertised input type, preview and generated
            // printer-language output must share the selected-paper page geometry.
            val pageSettings = if (source == PrintFormatSupport.PDF && output.mime != PrintFormatSupport.PDF &&
                !planningSettings.contentLayoutChanged) planningSettings.copy(contentLayoutChanged = true) else planningSettings
            val layoutApplied = output.renderPages && (source != PrintFormatSupport.PDF || pageSettings.contentLayoutChanged ||
                settings.numberUp > 1 || settings.pageRanges.isNotEmpty() || output.mime != PrintFormatSupport.PDF)
            val printerLayoutMustBeNeutral = source == PrintFormatSupport.PDF &&
                settings.layout.pageWidth > 1 && settings.layout.pageHeight > 1
            val layoutWarning = if (layoutApplied || printerLayoutMustBeNeutral) runCatching {
                PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
            }.exceptionOrNull()?.message else null
            val displaySettings = pageSettings.copy(layout = PreviewRenderPolicy.optionsForDisplay(pageSettings.layout))
            if (source != PrintFormatSupport.PDF || output.mime != PrintFormatSupport.PDF) {
                require(settings.layout.pageWidth > 1 && settings.layout.pageHeight > 1) { "미리보기에 사용할 용지 크기를 확인할 수 없습니다. 용지를 선택하세요." }
            }
            if (source.startsWith("image/")) {
                ImageInputPolicy.requireSingleFrame(sourceFile, source)
            }
            val pages = renderPages(sourceFile, source, displaySettings, workspace, preview = true)
            checkNotCancelled()
            val pdf = applySheetOptions(pages, displaySettings.copy(expectedSourceMime = source), displaySettings.layout, workspace, preview = true)
            checkNotCancelled()
            FilePreview(workspace.retain(pdf), layoutApplied, layoutWarning,
                settings.colorMode ?: printer.defaultColorMode, if (output.renderPages) output.mime else null)
        }
    }

    fun prepare(printer: PrinterRecord, uri: Uri, settings: FilePreparationSettings): PreparedDocument = synchronized(processingLock) {
        checkNotCancelled()
        DocumentWorkspace(cacheDir).use { workspace ->
            val name = fileDisplayName(uri)
            val sourceFile = capture(uri, workspace)
            val source = detectMime(uri, name, sourceFile)
            settings.expectedSourceMime?.let { require(source == it) { "파일 형식이 변경되었습니다. 다시 선택하세요." } }
            require(PrintCapabilityPolicy.appCanLayout(source)) {
                PrintFormatSupport.unsupportedFileMessage(name, source, printer.formats)
            }
            val paperFit = source == PrintFormatSupport.PDF && PdfPageEditor.needsSelectedPaperFit(
                sourceFile, settings.layout, settings.pageRanges, cacheDir,
            )
            val planningSettings = if (paperFit) settings.copy(contentLayoutChanged = true) else settings
            val plan = DocumentOutputPlanner.plan(printer.formats, source, needsPageLayout(source, planningSettings),
                false, settings.colorMode ?: printer.defaultColorMode,
                settings.requestedOutputMime, printer = printer,
                sidesMode = settings.sidesMode ?: printer.defaultSides)
            if (!plan.renderPages) {
                val pageSizeKnown = settings.layout.pageWidth > 1 && settings.layout.pageHeight > 1
                val prepared = PreparedDocument(FileDocumentData(sourceFile), plan.mime, name, pageSizeKnown, sourceFile)
                workspace.retain(sourceFile)
                return@use prepared
            }
            require(settings.numberUp in setOf(1, 2, 4, 6, 9, 16)) { "앱에서 적용할 수 없는 모아찍기 설정입니다. 한 장당 페이지를 다시 선택하세요." }
            val pageSettings = if (source == PrintFormatSupport.PDF && plan.mime != PrintFormatSupport.PDF &&
                !planningSettings.contentLayoutChanged) planningSettings.copy(contentLayoutChanged = true) else planningSettings
            val baked = source != PrintFormatSupport.PDF || pageSettings.contentLayoutChanged || settings.numberUp > 1 ||
                (source == PrintFormatSupport.PDF && plan.mime != PrintFormatSupport.PDF) ||
                (source == PrintFormatSupport.PDF && pageSettings.layout.pageWidth > 1 && pageSettings.layout.pageHeight > 1)
            if (baked) PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
            val printDpi = pageSettings.layout.rasterDpi.takeIf { it > 0 } ?: AutomaticRasterPolicy.outputDpi(printer, plan.mime)
            val printSettings = pageSettings.copy(
                layout = pageSettings.layout.copy(rasterDpi = printDpi ?: pageSettings.layout.rasterDpi),
            )
            PrinterOutputPolicy.rasterProblem(printer, plan.mime, printDpi, settings.sidesMode)?.let { error(it) }
            if (source.startsWith("image/")) ImageInputPolicy.requireSingleFrame(sourceFile, source)
            val pages = renderPages(
                sourceFile,
                source,
                printSettings,
                workspace,
                preview = false,
                jpegAsPdf = PrintFormatSupport.normalize(plan.mime) == PrintFormatSupport.PDF,
            )
            val pdf = applySheetOptions(pages, printSettings.copy(expectedSourceMime = source), printSettings.layout, workspace)
            checkNotCancelled()
            val mime = PrintFormatSupport.normalize(plan.mime)
            require(mime == PrintFormatSupport.PDF || printSettings.layout.rasterDpi > 0) { "자동 변환 해상도를 결정할 수 없습니다" }
            val outputFile = if (mime == PrintFormatSupport.PDF) pdf else {
                workspace.create(".output").also { target ->
                    target.outputStream().buffered(64 * 1024).use { output ->
                        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            DocumentRenderer.encodeLaidOutPdfTo(descriptor, mime, printSettings.layout, output,
                                printSettings.sidesMode ?: printer.defaultSides, printSettings.printQuality ?: printer.defaultPrintQuality)
                        }
                    }
                }
            }
            val extension = when (mime) {
                PrintFormatSupport.PDF -> "pdf"
                PrintFormatSupport.POSTSCRIPT -> "ps"
                PrintFormatSupport.PWG_RASTER -> "pwg"
                PrintFormatSupport.URF -> "urf"
                PrintFormatSupport.PCL -> "pcl"
                "image/png" -> "png"
                else -> "jpg"
            }
            val prepared = PreparedDocument(FileDocumentData(outputFile), plan.mime, "$name.$extension", baked, outputFile,
                pageSelectionApplied = settings.pageRanges.isNotEmpty())
            workspace.retain(outputFile)
            prepared
        }
    }

    private fun renderPages(
        sourceFile: File,
        source: String,
        settings: FilePreparationSettings,
        workspace: DocumentWorkspace,
        preview: Boolean,
        jpegAsPdf: Boolean = false,
    ): File {
        checkNotCancelled()
        settings.expectedSourceMime?.let { require(source == it) { "선택 후 파일 형식이 변경되었습니다. 파일을 다시 선택하세요." } }
        if (source == PrintFormatSupport.PDF) {
            if (!settings.contentLayoutChanged && !settings.sheetLayoutChanged) return sourceFile
            return workspace.create(".pdf").also { PdfPageEditor.write(sourceFile, it, settings, cacheDir) }
        }
        val target = workspace.create(".pdf")
        target.outputStream().buffered(64 * 1024).use { output ->
            when {
                source == "text/plain" -> TextPdfStreaming.write(sourceFile, settings.layout, output, cacheDir, settings.pageRanges)
                source.startsWith("image/") -> when {
                    preview -> DocumentRenderer.imageFilePreviewPdfTo(sourceFile, settings.layout, output)
                    jpegAsPdf && source == "image/jpeg" -> DocumentRenderer.jpegPhotoPdfTo(sourceFile, settings.layout, output)
                    else -> DocumentRenderer.imageFilePdfTo(sourceFile, settings.layout, output)
                }
                else -> error("이 파일 형식은 앱에서 페이지를 만들 수 없습니다")
            }
        }
        return target
    }

    private fun applySheetOptions(
        sourceFile: File,
        settings: FilePreparationSettings,
        layout: ContentLayoutOptions,
        workspace: DocumentWorkspace,
        preview: Boolean = false,
    ): File {
        // PDF input has already been selected and laid out in one vector-preserving pass.
        if (settings.expectedSourceMime == PrintFormatSupport.PDF || !settings.sheetLayoutChanged) return sourceFile
        checkNotCancelled()
        return workspace.create(".pdf").also { target ->
            val sheet = settings.copy(contentLayoutChanged = false, layout = layout.copy(
                scale = ContentScale.FIT, scalePercent = 100f, offsetX = 0f, offsetY = 0f, rotationDegrees = 0f,
                horizontal = ContentHorizontal.CENTER, vertical = ContentVertical.CENTER,
            ))
            PdfPageEditor.write(sourceFile, target, sheet, cacheDir)
        }
    }

    fun fileDisplayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "휴대폰 파일"
        }
        return uri.lastPathSegment ?: "휴대폰 파일"
    }

    companion object {
        private val processingLock = Any()

        private fun checkNotCancelled() {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("문서 처리가 취소되었습니다")
        }
    }
}
