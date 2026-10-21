package kr.ac.hit.networkprinterstudio

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

/** A request owns its temporary files until one output is handed off to the sender. */
internal class DocumentWorkspace(private val cacheDir: File) : Closeable {
    private val files = linkedSetOf<File>()

    fun create(suffix: String): File = File.createTempFile("ipp-document-", suffix, cacheDir).also(files::add)

    fun capture(input: InputStream): File = input.use { source ->
        val target = create(".source")
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("문서 처리가 취소되었습니다")
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val next = source.read()
                    if (next < 0) break
                    output.write(next)
                } else output.write(buffer, 0, count)
            }
        }
        target
    }

    fun retain(file: File): File {
        check(files.remove(file)) { "문서 작업이 소유하지 않은 파일입니다" }
        return file
    }

    override fun close() {
        files.forEach { it.delete() }
        files.clear()
    }
}
