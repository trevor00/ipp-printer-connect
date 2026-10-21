package kr.ac.hit.networkprinterstudio

import java.io.File
import java.io.RandomAccessFile

internal object ImageInputPolicy {
    fun requireSingleFrame(file: File, mime: String) {
        val multiple = RandomAccessFile(file, "r").use { input ->
            when (PrintFormatSupport.normalize(mime)) {
                "image/gif" -> {
                    require(input.length() >= 13) { "GIF 헤더가 잘렸습니다" }
                    input.seek(10)
                    val packed = input.readUnsignedByte()
                    input.skipBytes(2)
                    if (packed and 128 != 0) input.skipBytes(3 * (1 shl ((packed and 7) + 1)))
                    var frames = 0
                    while (input.filePointer < input.length()) {
                        checkCancellation()
                        when (input.readUnsignedByte()) {
                            0x3b -> break
                            0x21 -> { input.readUnsignedByte(); skipBlocks(input) }
                            0x2c -> {
                                if (++frames > 1) return@use true
                                input.skipBytes(8)
                                val flags = input.readUnsignedByte()
                                if (flags and 128 != 0) input.skipBytes(3 * (1 shl ((flags and 7) + 1)))
                                input.readUnsignedByte(); skipBlocks(input)
                            }
                            else -> error("GIF 구조를 확인할 수 없습니다")
                        }
                    }
                    false
                }
                "image/png" -> {
                    input.seek(8)
                    while (input.filePointer + 12 <= input.length()) {
                        checkCancellation()
                        val length = input.readInt().toLong() and 0xffffffffL
                        val type = input.readInt()
                        require(length <= input.length() - input.filePointer - 4) { "PNG 데이터가 잘렸습니다" }
                        if (type == 0x6163544c) return@use true // acTL
                        if (type == 0x49454e44) break
                        input.seek(input.filePointer + length + 4)
                    }
                    false
                }
                "image/webp" -> {
                    input.seek(12)
                    while (input.filePointer + 8 <= input.length()) {
                        checkCancellation()
                        val type = input.readInt()
                        val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                        require(length <= input.length() - input.filePointer) { "WebP 데이터가 잘렸습니다" }
                        if (type == 0x414e494d || type == 0x414e4d46) return@use true // ANIM / ANMF
                        input.seek(input.filePointer + length + (length and 1))
                    }
                    false
                }
                "image/heic", "image/heif", "image/avif" -> {
                    val prefix = ByteArray(minOf(input.length(), 128).toInt()).also(input::readFully)
                    val brands = prefix.toString(Charsets.ISO_8859_1)
                    brands.contains("msf1") || brands.contains("avis") || brands.contains("hevc") || brands.contains("hevx")
                }
                else -> false
            }
        }
        require(!multiple) { "여러 프레임이 있는 이미지는 변환하지 않습니다. 인쇄할 프레임을 사진이나 PDF로 저장한 뒤 선택하세요." }
    }
    private fun skipBlocks(input: RandomAccessFile) {
        while (true) {
            checkCancellation()
            val size = input.readUnsignedByte()
            if (size == 0) return
            require(size <= input.length() - input.filePointer) { "이미지 데이터가 잘렸습니다" }
            input.seek(input.filePointer + size)
        }
    }
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("이미지 확인을 취소했습니다")
    }
}
