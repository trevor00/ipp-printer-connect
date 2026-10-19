package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

internal data class FilePreparationSettings(
    val layout: ContentLayoutOptions,
    val contentLayoutChanged: Boolean,
    val applyPdfLayout: Boolean,
    val forcePostScript: Boolean,
    val pageRanges: List<IppRange>,
    val numberUp: Int,
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

internal data class FileDocument(
    val name: String,
    val originalBytes: ByteArray,
    val plan: DocumentOutputPlan,
    val laidOutPdf: ByteArray?,
)

internal class FileDocumentPreparer(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = context.applicationContext.cacheDir

    fun inspectMime(uri: Uri): String {
        val name = fileDisplayName(uri)
        val header = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                if (read == 0) break
                size += read
            }
            buffer.copyOf(size)
        } ?: error("선택한 파일을 열 수 없습니다")
        return FileMimeDetector.detect(resolver.getType(uri), name, header)
    }

    fun build(
        printer: PrinterRecord,
        uri: Uri,
        settings: FilePreparationSettings,
        forPreview: Boolean = false,
    ): FileDocument {
        val name = fileDisplayName(uri)
        val bytes = readFileLimited(uri, MAX_FILE_BYTES)
        val source = PrintFormatSupport.normalize(detectMime(uri, name, bytes))
        val renderable = PrintCapabilityPolicy.appCanLayout(source)
        require(renderable || !settings.contentLayoutChanged) {
            "전송 실패: 이 파일 형식에는 앱의 내용 배치를 적용할 수 없습니다. PDF로 변환한 파일을 선택해 주세요."
        }
        val applyLayout = renderable && (settings.contentLayoutChanged || settings.sheetLayoutChanged ||
            (source == PrintFormatSupport.PDF && settings.applyPdfLayout))
        if (!renderable && !applyLayout && !settings.forcePostScript && !PrintFormatSupport.supports(printer.formats, source)) {
            error(PrintFormatSupport.unsupportedFileMessage(name, source, printer.formats))
        }
        val plan = DocumentOutputPlanner.plan(printer.formats, source, applyLayout, settings.forcePostScript)
        val pdf = if (plan.renderPages || forPreview) {
            val pages = when {
                source == PrintFormatSupport.PDF ->
                    if (settings.applyPdfLayout || settings.contentLayoutChanged) {
                        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            DocumentRenderer.pdfWithLayout(descriptor, settings.layout)
                        } ?: error("PDF 파일을 열 수 없습니다")
                    } else bytes
                source == "text/plain" -> DocumentRenderer.textPdf("", TextFileDecoder.decode(bytes).text, settings.layout)
                source.startsWith("image/") -> DocumentRenderer.imagePdf(bytes, settings.layout)
                else -> error("이 파일 형식은 앱에서 미리 볼 수 없습니다")
            }
            if (plan.renderPages) applySheetOptions(pages, settings.pageRanges, settings.numberUp, settings.layout) else pages
        } else null
        return FileDocument(name, bytes, plan, pdf)
    }

    fun prepare(
        printer: PrinterRecord,
        uri: Uri,
        settings: FilePreparationSettings,
    ): PreparedDocument {
        val document = build(printer, uri, settings)
        if (!document.plan.renderPages) {
            return PreparedDocument(document.originalBytes, document.plan.mime, document.name, layoutBakedIn = false)
        }
        val pdf = document.laidOutPdf ?: error("출력할 페이지를 만들 수 없습니다")
        val canonicalOutput = PrintFormatSupport.normalize(document.plan.mime)
        val bytes = if (canonicalOutput == PrintFormatSupport.PDF) pdf else {
            val temp = java.io.File.createTempFile("ipp-final-pages-", ".pdf", cacheDir)
            try {
                temp.outputStream().use { it.write(pdf) }
                android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    DocumentRenderer.encodeLaidOutPdf(descriptor, canonicalOutput, settings.layout)
                }
            } finally {
                temp.delete()
            }
        }
        val extension = when (canonicalOutput) {
            PrintFormatSupport.PDF -> "pdf"
            PrintFormatSupport.POSTSCRIPT -> "ps"
            PrintFormatSupport.PWG_RASTER -> "pwg"
            PrintFormatSupport.URF -> "urf"
            PrintFormatSupport.PCL -> "pcl"
            "image/png" -> "png"
            else -> "jpg"
        }
        return PreparedDocument(bytes, document.plan.mime, "${document.name}.$extension", layoutBakedIn = true)
    }

    private fun applySheetOptions(
        bytes: ByteArray,
        pageRanges: List<IppRange>,
        numberUp: Int,
        layout: ContentLayoutOptions,
    ): ByteArray {
        if (pageRanges.isEmpty() && numberUp <= 1) return bytes
        val temp = java.io.File.createTempFile("ipp-sheet-source-", ".pdf", cacheDir)
        return try {
            temp.outputStream().use { it.write(bytes) }
            android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                DocumentRenderer.imposePdf(descriptor, pageRanges, numberUp, layout.pageWidth, layout.pageHeight, layout.rasterDpi)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readFileLimited(uri: Uri, limit: Int): ByteArray {
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "파일은 최대 ${limit / 1024 / 1024}MB까지 전송할 수 있습니다" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("파일을 읽을 수 없습니다")
    }

    fun fileDisplayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "휴대폰 파일"
        }
        return uri.lastPathSegment ?: "휴대폰 파일"
    }

    private fun detectMime(uri: Uri, name: String, bytes: ByteArray): String =
        FileMimeDetector.detect(resolver.getType(uri), name, bytes)


    companion object {
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
    }
}
