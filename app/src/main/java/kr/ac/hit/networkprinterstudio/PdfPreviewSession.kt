package kr.ac.hit.networkprinterstudio

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

class PdfPreviewSession private constructor(private val pdfFile: File, private val colorMode: String? = null,
    private val outputMime: String? = null) : Closeable {
    private val stateLock = Any()
    private var closed = false
    private var readers = 0

    fun renderPage(index: Int): RenderedPreview {
        val descriptor = synchronized(stateLock) {
            check(!closed) { "닫힌 미리보기에서는 페이지를 열 수 없습니다" }
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).also { readers += 1 }
        }
        return try {
            descriptor.use { DocumentRenderer.pdfPagePreview(it, index) }.also { preview ->
                try {
                    val mono = outputMime?.let(PrintFormatSupport::normalize) == PrintFormatSupport.PCL
                    if (mono || colorMode in setOf("monochrome", "process-monochrome", "bi-level")) {
                        val bitmap = preview.bitmap
                        val row = IntArray(bitmap.width)
                        for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                            for (x in row.indices) {
                                val color = row[x]
                                var gray = (android.graphics.Color.red(color) * 299 + android.graphics.Color.green(color) * 587 + android.graphics.Color.blue(color) * 114) / 1000
                                if (mono || colorMode == "bi-level") gray = if (gray < 170) 0 else 255
                                row[x] = android.graphics.Color.rgb(gray, gray, gray)
                            }
                            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        }
                    }
                } catch (error: Throwable) { preview.bitmap.recycle(); throw error }
            }
        } finally {
            val deleteFile = synchronized(stateLock) {
                readers -= 1
                closed && readers == 0
            }
            if (deleteFile) pdfFile.delete()
        }
    }

    override fun close() {
        val deleteFile = synchronized(stateLock) {
            closed = true
            readers == 0
        }
        if (deleteFile) pdfFile.delete()
    }

    companion object {
        internal fun fromOwnedFile(file: File, colorMode: String? = null, outputMime: String? = null): PdfPreviewSession {
            require(file.isFile) { "미리보기 파일이 없습니다" }
            return PdfPreviewSession(file, colorMode, outputMime)
        }

        fun create(bytes: ByteArray, cacheDir: File): PdfPreviewSession {
            val file = File.createTempFile("ipp-preview-", ".pdf", cacheDir)
            return try {
                file.outputStream().use { it.write(bytes) }
                PdfPreviewSession(file)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }
}
