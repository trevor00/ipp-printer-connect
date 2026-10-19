package kr.ac.hit.networkprinterstudio

import java.util.Locale

object FileMimeDetector {
    /** 파일 헤더를 우선 확인하고, 제공자가 형식을 모를 때만 확장자를 사용한다. */
    fun detect(providerMime: String?, fileName: String, bytes: ByteArray): String {
        signatureMime(bytes)?.let { return it }
        providerMime?.let(PrintFormatSupport::normalize)
            ?.takeUnless(PrintFormatSupport::isGenericMime)
            ?.let { return it }
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> PrintFormatSupport.PDF
            "jpg", "jpeg", "jpe" -> "image/jpeg"
            "png" -> "image/png"
            "tif", "tiff" -> "image/tiff"
            "txt", "csv", "log" -> "text/plain"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "ps", "eps" -> PrintFormatSupport.POSTSCRIPT
            "pcl" -> PrintFormatSupport.PCL
            "pclxl" -> PrintFormatSupport.PCL_XL
            "pwg", "ras" -> PrintFormatSupport.PWG_RASTER
            "urf" -> PrintFormatSupport.URF
            "pclm" -> "application/pclm"
            else -> PrintFormatSupport.OCTET_STREAM
        }
    }

    // 헤더를 식별하는 과정이며, 문서 전체가 정상인지 또는 렌더링 가능한지를 검증하지는 않는다.
    private fun signatureMime(bytes: ByteArray): String? = when {
        bytes.hasAscii(0, "%PDF-") -> PrintFormatSupport.PDF
        bytes.hasBytes(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.hasBytes(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.hasBytes(0, 0x49, 0x49, 0x2a, 0x00) ||
            bytes.hasBytes(0, 0x4d, 0x4d, 0x00, 0x2a) ||
            bytes.hasBytes(0, 0x49, 0x49, 0x2b, 0x00, 0x08, 0x00, 0x00, 0x00) ||
            bytes.hasBytes(0, 0x4d, 0x4d, 0x00, 0x2b, 0x00, 0x08, 0x00, 0x00) -> "image/tiff"
        bytes.size >= 14 && bytes.hasAscii(0, "BM") -> "image/bmp"
        bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WEBP") -> "image/webp"
        bytes.hasAscii(0, "%!") -> PrintFormatSupport.POSTSCRIPT
        bytes.hasAscii(0, "RaS2") -> PrintFormatSupport.PWG_RASTER
        bytes.hasAscii(0, "UNIRAST\u0000") -> PrintFormatSupport.URF
        else -> null
    }

    private fun ByteArray.hasAscii(offset: Int, expected: String): Boolean =
        size >= offset + expected.length && expected.indices.all { this[offset + it].toInt() == expected[it].code }

    private fun ByteArray.hasBytes(offset: Int, vararg expected: Int): Boolean =
        size >= offset + expected.size && expected.indices.all { (this[offset + it].toInt() and 0xff) == expected[it] }
}
