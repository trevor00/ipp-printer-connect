package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A repeatable document source. Each call must open a new stream at byte zero. */
interface DocumentData {
    val length: Long
    fun openStream(): InputStream
}

/** The caller must not mutate [bytes] while a request is being sent. */
class MemoryDocumentData(private val bytes: ByteArray) : DocumentData {
    override val length: Long = bytes.size.toLong()
    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
}

/** Keeps the declared length stable across an authentication retry. */
class FileDocumentData(val file: File) : DocumentData {
    init {
        require(file.isFile) { "전송할 문서 파일이 없습니다: ${file.name}" }
    }

    override val length: Long = file.length()
    override fun openStream(): InputStream = file.inputStream()
}

internal fun DocumentData.writeTo(output: OutputStream) {
    consumeExactly { buffer, count -> output.write(buffer, 0, count) }
}

internal fun DocumentData.digest(algorithm: String): ByteArray =
    MessageDigest.getInstance(algorithm).also { digest ->
        consumeExactly { buffer, count -> digest.update(buffer, 0, count) }
    }.digest()

internal class DocumentDataException(message: String, cause: IOException? = null) : IOException(message, cause)

private class CheckedDocumentInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = checked { delegate.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checked { delegate.read(buffer, offset, length) }

    override fun close() = checked { delegate.close() }

    private inline fun <T> checked(action: () -> T): T = try {
        action()
    } catch (error: DocumentDataException) {
        throw error
    } catch (error: IOException) {
        throw DocumentDataException("문서 파일을 읽지 못했습니다: ${error.message}", error)
    }
}

/** Shares the same checked byte traversal between transport and auth-int hashing. */
private inline fun DocumentData.consumeExactly(consume: (ByteArray, Int) -> Unit) {
    val expected = length
    require(expected >= 0) { "문서 길이는 0 이상이어야 합니다" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val source = try {
        openStream()
    } catch (error: IOException) {
        throw DocumentDataException("전송할 문서 파일을 열지 못했습니다: ${error.message}", error)
    }
    CheckedDocumentInputStream(source).use { input ->
        var remaining = expected
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw DocumentDataException("문서 길이가 변경되었습니다: 예상 $expected 바이트, 실제 ${expected - remaining} 바이트")
            if (count == 0) {
                // InputStream permits poorly behaved wrappers to return zero; avoid an endless loop.
                val next = input.read()
                if (next < 0) throw DocumentDataException("문서가 끝나 전송을 완료하지 못했습니다: ${expected - remaining}/$expected 바이트")
                buffer[0] = next.toByte()
                consume(buffer, 1)
                remaining--
            } else {
                consume(buffer, count)
                remaining -= count
            }
        }
        if (input.read() != -1) throw DocumentDataException("문서 길이가 변경되었습니다: 예상 $expected 바이트보다 큽니다")
    }
}
