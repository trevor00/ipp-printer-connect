package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Adds physical pixel density to images encoded by the app, without recompressing their pixels. */
object ImagePrintDensity {
    private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val jfifIdentifier = byteArrayOf(74, 70, 73, 70, 0)

    fun withDpi(image: ByteArray, mime: String, dpi: Int): ByteArray {
        require(dpi > 0) { "이미지 해상도는 0보다 커야 합니다" }
        return when (mime.trim().lowercase()) {
            "image/jpeg" -> jpegWithDpi(image, dpi)
            "image/png" -> pngWithDpi(image, dpi)
            else -> error("이 이미지 형식($mime)에는 출력 해상도를 저장할 수 없습니다")
        }
    }

    private fun jpegWithDpi(image: ByteArray, dpi: Int): ByteArray {
        require(dpi <= 65_535) { "JPEG 해상도는 65535 dpi를 넘을 수 없습니다" }
        require(image.size >= 4 && unsigned(image[0]) == 0xff && unsigned(image[1]) == 0xd8) {
            "JPEG 이미지 헤더가 올바르지 않습니다"
        }
        val output = ByteArrayOutputStream(image.size + 18)
        output.write(image, 0, 2)
        // JFIF 1.02: units=1 means dots per inch; X/Y densities are unsigned 16-bit integers.
        output.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 16))
        output.write(jfifIdentifier)
        output.write(byteArrayOf(1, 2, 1))
        output.writeShortBigEndian(dpi)
        output.writeShortBigEndian(dpi)
        output.write(byteArrayOf(0, 0))
        var offset = 2
        while (offset < image.size) {
            val markerStart = offset
            require(unsigned(image[offset]) == 0xff) { "JPEG 마커가 올바르지 않습니다" }
            while (offset < image.size && unsigned(image[offset]) == 0xff) offset += 1
            require(offset < image.size) { "JPEG 마커가 잘렸습니다" }
            val marker = unsigned(image[offset++])
            if (marker == 0xda || marker == 0xd9) {
                // Preserve the scan header and compressed scan bytes exactly as encoded.
                output.write(image, markerStart, image.size - markerStart)
                return output.toByteArray()
            }
            require(marker !in listOf(0, 0xd8) && marker !in 0xd0..0xd7) { "JPEG 헤더 마커가 올바르지 않습니다" }
            if (marker == 1) {
                output.write(image, markerStart, offset - markerStart)
                continue
            }
            require(offset + 2 <= image.size) { "JPEG 마커 길이가 잘렸습니다" }
            val length = unsigned(image[offset]) * 256 + unsigned(image[offset + 1])
            require(length >= 2 && offset.toLong() + length <= image.size) { "JPEG 마커 길이가 올바르지 않습니다" }
            val isJfif = marker == 0xe0 && length >= 7 &&
                jfifIdentifier.indices.all { image[offset + 2 + it] == jfifIdentifier[it] }
            if (!isJfif) output.write(image, markerStart, offset + length - markerStart)
            offset += length
        }
        error("JPEG 이미지 데이터가 잘렸습니다")
    }

    private fun pngWithDpi(image: ByteArray, dpi: Int): ByteArray {
        require(image.size >= pngSignature.size && pngSignature.indices.all { image[it] == pngSignature[it] }) {
            "PNG 이미지 헤더가 올바르지 않습니다"
        }
        val pixelsPerMeter = (dpi.toLong() * 10_000 + 127) / 254
        require(pixelsPerMeter in 1..0xffff_ffffL) { "PNG에 저장할 수 있는 해상도 범위를 벗어났습니다" }
        val output = ByteArrayOutputStream(image.size + 21)
        output.write(pngSignature)
        var offset = pngSignature.size
        var firstChunk = true
        var sawImageData = false
        while (offset.toLong() + 12 <= image.size) {
            val length = unsignedIntBigEndian(image, offset)
            val chunkEnd = offset.toLong() + length + 12
            require(length <= Int.MAX_VALUE && chunkEnd <= image.size) { "PNG 청크 길이가 올바르지 않습니다" }
            val type = String(image, offset + 4, 4, Charsets.US_ASCII)
            if (firstChunk) require(type == "IHDR" && length == 13L) { "PNG IHDR 청크가 올바르지 않습니다" }
            if (type != "pHYs") output.write(image, offset, length.toInt() + 12)
            if (firstChunk) {
                val density = ByteArrayOutputStream(9).apply {
                    writeIntBigEndian(pixelsPerMeter)
                    writeIntBigEndian(pixelsPerMeter)
                    write(1) // pHYs unit=1 is pixels per meter.
                }.toByteArray()
                val densityType = "pHYs".toByteArray(Charsets.US_ASCII)
                output.writeIntBigEndian(density.size.toLong())
                output.write(densityType)
                output.write(density)
                val crc = CRC32().apply { update(densityType); update(density) }
                output.writeIntBigEndian(crc.value)
                firstChunk = false
            }
            if (type == "IDAT") sawImageData = true
            offset = chunkEnd.toInt()
            if (type == "IEND") {
                require(length == 0L && sawImageData && offset == image.size) { "PNG 이미지 끝부분이 올바르지 않습니다" }
                return output.toByteArray()
            }
        }
        error("PNG 이미지 데이터가 잘렸습니다")
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private fun unsignedIntBigEndian(bytes: ByteArray, offset: Int): Long =
        (unsigned(bytes[offset]).toLong() shl 24) or (unsigned(bytes[offset + 1]).toLong() shl 16) or
            (unsigned(bytes[offset + 2]).toLong() shl 8) or unsigned(bytes[offset + 3]).toLong()

    private fun ByteArrayOutputStream.writeShortBigEndian(value: Int) {
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeIntBigEndian(value: Long) {
        write((value ushr 24).toInt())
        write((value ushr 16).toInt())
        write((value ushr 8).toInt())
        write(value.toInt())
    }
}
