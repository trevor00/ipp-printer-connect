package kr.ac.hit.networkprinterstudio

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

class PdfPreviewSession private constructor(private val pdfFile: File) : Closeable {
    private val stateLock = Any()
    private var closed = false
    private var readers = 0

    fun renderPage(index: Int): RenderedPreview {
        val descriptor = synchronized(stateLock) {
            check(!closed) { "닫힌 미리보기에서는 페이지를 열 수 없습니다" }
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).also { readers += 1 }
        }
        return try {
            descriptor.use { DocumentRenderer.pdfPagePreview(it, index) }
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
