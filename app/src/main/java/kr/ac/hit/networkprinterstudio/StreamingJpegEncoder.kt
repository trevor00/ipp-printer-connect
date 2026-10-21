package kr.ac.hit.networkprinterstudio

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** A single baseline JPEG; input and codec memory are bounded by row width, not image height. */
internal class StreamingJpegEncoder(
    private val width: Int,
    private val height: Int,
    dpi: Int,
    quality: Int,
    private val output: OutputStream,
) : Closeable {
    private var handle = 0L
    private var writtenRows = 0
    private var finished = false
    private var failed = false
    private var nativeCallActive = false

    init {
        JpegStreamPolicy.validate(width, height, dpi, quality)
        checkCancellation()
        JpegNativeLibrary.ensureLoaded()
        handle = withNativeCall { nativeCreate(width, height, dpi, quality) }
        check(handle != 0L) { "JPEG 인코더를 초기화할 수 없습니다" }
    }

    @Synchronized
    fun writeRgbRows(bytes: ByteArray, rowCount: Int) {
        checkNotReentrant()
        check(!finished && !failed && handle != 0L) { "종료된 JPEG 인코더에는 데이터를 추가할 수 없습니다" }
        checkCancellation()
        JpegStreamPolicy.validateRows(width, height - writtenRows, bytes.size, rowCount)
        try {
            withNativeCall { nativeWrite(handle, bytes, rowCount) }
            writtenRows += rowCount
        } catch (failure: Throwable) {
            failed = true
            dispose()
            throw failure
        }
    }

    @Synchronized
    fun finish() {
        checkNotReentrant()
        if (finished) return
        check(!failed && handle != 0L) { "종료된 JPEG 인코더를 완료할 수 없습니다" }
        checkCancellation()
        check(writtenRows == height) { "JPEG 이미지의 $height 행 중 $writtenRows 행만 전달되었습니다" }
        try {
            withNativeCall { nativeFinish(handle) }
            finished = true
        } catch (failure: Throwable) {
            failed = true
            throw failure
        } finally {
            dispose()
        }
    }

    /** Abort an unfinished image. The caller's stream is never finished, flushed or closed here. */
    @Synchronized
    override fun close() {
        checkNotReentrant()
        dispose()
    }

    // JVM monitors are reentrant: a callback must not destroy or reuse the compressor while its
    // native stack is still running. Reject it before changing rows, completion state or the handle.
    private fun checkNotReentrant() {
        check(!nativeCallActive) { "JPEG 출력 콜백에서는 인코더를 다시 호출하거나 종료할 수 없습니다" }
    }

    private inline fun <T> withNativeCall(action: () -> T): T {
        checkNotReentrant()
        nativeCallActive = true
        try {
            return action()
        } finally {
            nativeCallActive = false
        }
    }

    private fun dispose() {
        checkNotReentrant()
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    @Suppress("unused") // Invoked synchronously by the JNI destination manager.
    private fun writeCompressed(bytes: ByteArray, length: Int) {
        checkCancellation()
        output.write(bytes, 0, length)
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("JPEG 변환을 취소했습니다")
    }

    private external fun nativeCreate(width: Int, height: Int, dpi: Int, quality: Int): Long
    private external fun nativeWrite(handle: Long, bytes: ByteArray, rowCount: Int)
    private external fun nativeFinish(handle: Long)
    private external fun nativeDestroy(handle: Long)
}

internal object JpegStreamPolicy {
    // libjpeg-turbo's JPEG_MAX_DIMENSION; reject rather than resize an unsupported image.
    const val MAX_DIMENSION = 65_500
    fun validate(width: Int, height: Int, dpi: Int, quality: Int) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "JPEG는 가로·세로 각각 $MAX_DIMENSION 픽셀까지 변환할 수 있습니다. 선택한 해상도를 자동 변경하지 않았습니다."
        }
        require(dpi in 1..65_535) { "JPEG에 저장할 해상도는 1~65535 dpi여야 합니다" }
        require(quality in 0..100) { "JPEG 품질은 0~100이어야 합니다" }
    }
    fun validateRows(width: Int, remainingRows: Int, byteCount: Int, rowCount: Int) {
        require(width in 1..MAX_DIMENSION && rowCount > 0 && rowCount <= remainingRows) { "JPEG에 전달할 행 수가 올바르지 않습니다" }
        require(width.toLong() * 3 * rowCount <= byteCount) { "JPEG RGB 행 데이터가 부족합니다" }
    }
}

private object JpegNativeLibrary {
    init { System.loadLibrary("ippjpeg") }
    fun ensureLoaded() = Unit
}
