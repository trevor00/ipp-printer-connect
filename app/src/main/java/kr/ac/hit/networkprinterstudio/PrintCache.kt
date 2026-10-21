package kr.ac.hit.networkprinterstudio

import android.app.Application
import android.content.Context
import java.io.File

internal object PrintCache {
    private var initialized = false
    lateinit var directory: File
        private set

    @Synchronized fun initialize(context: Context): File {
        if (initialized) return directory
        val root = context.cacheDir.canonicalFile
        val work = File(root, "print-work").canonicalFile
        check(work.parentFile == root)
        check(work.isDirectory || work.mkdir()) { "인쇄용 임시 폴더를 만들 수 없습니다" }
        // Called once per process, before any preview/print worker can own files here.
        work.listFiles()?.forEach { removeOwned(it, work) }
        root.listFiles()?.filter {
            it.name.startsWith("ipp-document-") || it.name.startsWith("ipp-preview-") || it.name.startsWith("text_vectors_")
        }?.forEach { removeOwned(it, root) }
        directory = work
        initialized = true
        return work
    }

    private fun removeOwned(file: File, parent: File) {
        val resolved = file.canonicalFile
        check(resolved.parentFile == parent.canonicalFile) { "임시 파일 경로를 확인할 수 없습니다" }
        if (resolved.isDirectory) resolved.listFiles()?.forEach { removeOwned(it, resolved) }
        check(!resolved.exists() || resolved.delete()) { "이전 인쇄 임시 파일을 정리하지 못했습니다" }
    }
}

class PrinterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Failure is surfaced by the document preparer; application startup must remain available.
        runCatching { PrintCache.initialize(this) }
        PrintWorkManager.initialize(this)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
    }
}
