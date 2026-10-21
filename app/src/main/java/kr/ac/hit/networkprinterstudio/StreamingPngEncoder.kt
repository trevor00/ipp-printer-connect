package kr.ac.hit.networkprinterstudio

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/** RGB24 PNG output with one zlib stream and bounded IDAT chunks. */
internal class StreamingPngEncoder(
    private val width: Int,
    private val height: Int,
    dpi: Int,
    private val output: OutputStream,
) : Closeable {
    private val geometry = validateGeometry(width, height, dpi)
    private val rowBytes = geometry.first
    private val previousRow = ByteArray(rowBytes)
    private val filteredRow = ByteArray(rowBytes + 1)
    private val compressed = ByteArray(IDAT_CHUNK_SIZE)
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
    private var compressedCount = 0
    private var rowsWritten = 0L
    private var state = State.OPEN
    private var nativeClosed = false

    init {
        safely {
            checkInterrupted()
            output.write(SIGNATURE)
            val header = ByteArray(13)
            putInt(header, 0, width)
            putInt(header, 4, height)
            header[8] = 8 // sample depth
            header[9] = 2 // truecolor RGB, without alpha
            writeChunk(IHDR, header, header.size)
            val physical = ByteArray(9)
            putInt(physical, 0, geometry.second.toInt())
            putInt(physical, 4, geometry.second.toInt())
            physical[8] = 1 // pixels per metre
            writeChunk(PHYS, physical, physical.size)
        }
    }

    /** Input contains exactly [rowCount] complete RGB rows, in top-to-bottom order. */
    fun writeRgbRows(bytes: ByteArray, rowCount: Int) {
        requireOpen()
        safely {
            checkInterrupted()
            require(rowCount >= 0) { "PNG 행 수는 0 이상이어야 합니다" }
            val expected = rowBytes.toLong() * rowCount.toLong()
            require(expected == bytes.size.toLong()) {
                "PNG RGB 데이터 크기가 맞지 않습니다: 예상 $expected 바이트, 실제 ${bytes.size} 바이트"
            }
            require(rowsWritten + rowCount.toLong() <= height.toLong()) {
                "PNG 이미지 높이($height 행)보다 많은 데이터가 전달되었습니다"
            }
            var offset = 0
            repeat(rowCount) {
                checkInterrupted()
                filterRow(bytes, offset)
                deflater.setInput(filteredRow)
                drain(finishing = false)
                System.arraycopy(bytes, offset, previousRow, 0, rowBytes)
                offset += rowBytes
                rowsWritten++
            }
        }
    }

    /** Completes a PNG only when every declared row has been supplied. */
    fun finish() {
        if (state == State.FINISHED) return
        requireOpen()
        safely {
            checkInterrupted()
            if (rowsWritten != height.toLong()) {
                throw IOException("PNG 이미지 데이터가 부족합니다: $rowsWritten/$height 행")
            }
            deflater.finish()
            drain(finishing = true)
            flushCompressed()
            writeChunk(IEND, EMPTY, 0)
            state = State.FINISHED
            releaseNative()
        }
    }

    /** Aborts unfinished output, releases zlib, and leaves the caller's stream open. */
    override fun close() {
        if (state == State.OPEN) state = State.CLOSED
        releaseNative()
    }

    /** None/Sub/Up are selected per row using the PNG signed-residual heuristic. */
    private fun filterRow(bytes: ByteArray, offset: Int) {
        var noneScore = 0L
        var subScore = 0L
        var upScore = 0L
        for (index in 0 until rowBytes) {
            if (index and 8191 == 0) checkInterrupted()
            val value = bytes[offset + index].toInt() and 255
            val left = if (index >= 3) bytes[offset + index - 3].toInt() and 255 else 0
            val above = previousRow[index].toInt() and 255
            noneScore += abs(value.toByte().toInt())
            subScore += abs((value - left).toByte().toInt())
            upScore += abs((value - above).toByte().toInt())
        }
        val filter = when {
            upScore < noneScore && upScore < subScore -> 2
            subScore < noneScore -> 1
            else -> 0
        }
        filteredRow[0] = filter.toByte()
        for (index in 0 until rowBytes) {
            if (index and 8191 == 0) checkInterrupted()
            val value = bytes[offset + index].toInt() and 255
            val predictor = when (filter) {
                1 -> if (index >= 3) bytes[offset + index - 3].toInt() and 255 else 0
                2 -> previousRow[index].toInt() and 255
                else -> 0
            }
            filteredRow[index + 1] = (value - predictor).toByte()
        }
    }

    private fun drain(finishing: Boolean) {
        while (if (finishing) !deflater.finished() else !deflater.needsInput()) {
            checkInterrupted()
            val count = deflater.deflate(compressed, compressedCount, compressed.size - compressedCount)
            compressedCount += count
            if (compressedCount == compressed.size) flushCompressed()
            if (count == 0 && (if (finishing) !deflater.finished() else !deflater.needsInput())) {
                throw IOException("PNG 압축을 진행하지 못했습니다")
            }
        }
    }

    private fun flushCompressed() {
        if (compressedCount == 0) return
        writeChunk(IDAT, compressed, compressedCount)
        compressedCount = 0
    }

    private fun writeChunk(type: ByteArray, bytes: ByteArray, count: Int) {
        checkInterrupted()
        writeInt(count)
        output.write(type)
        output.write(bytes, 0, count)
        val crc = CRC32()
        crc.update(type)
        crc.update(bytes, 0, count)
        writeInt(crc.value.toInt())
    }

    private fun writeInt(value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun requireOpen() {
        check(state == State.OPEN) { "PNG 출력이 이미 종료되었습니다" }
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            state = State.FAILED
            releaseNative()
            throw error
        }
    }

    private fun releaseNative() {
        if (!nativeClosed) {
            nativeClosed = true
            deflater.end()
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PNG 이미지 변환이 취소되었습니다")
    }

    private enum class State { OPEN, FINISHED, CLOSED, FAILED }

    companion object {
        internal const val IDAT_CHUNK_SIZE = 32 * 1024
        private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val IHDR = byteArrayOf(73, 72, 68, 82)
        private val PHYS = byteArrayOf(112, 72, 89, 115)
        private val IDAT = byteArrayOf(73, 68, 65, 84)
        private val IEND = byteArrayOf(73, 69, 78, 68)
        private val EMPTY = byteArrayOf()

        private fun validateGeometry(width: Int, height: Int, dpi: Int): Pair<Int, Long> {
            require(width > 0 && height > 0) { "PNG 너비와 높이는 1 이상이어야 합니다" }
            require(dpi > 0) { "PNG 해상도는 1 dpi 이상이어야 합니다" }
            val rowSize = width.toLong() * 3L
            require(rowSize < Int.MAX_VALUE.toLong()) { "PNG 한 행의 RGB 데이터가 처리 가능한 크기를 넘습니다" }
            val pixelsPerMetre = Math.round(dpi.toDouble() / 0.0254)
            require(pixelsPerMetre in 1L..0xffffffffL) { "PNG 해상도 정보를 기록할 수 없습니다: $dpi dpi" }
            return rowSize.toInt() to pixelsPerMetre
        }

        private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
