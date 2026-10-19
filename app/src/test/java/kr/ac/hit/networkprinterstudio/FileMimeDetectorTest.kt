package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMimeDetectorTest {
    @Test
    fun genericProviderAndUnhelpfulNameStillRecognizePdfAndJpeg() {
        assertEquals(PrintFormatSupport.PDF, FileMimeDetector.detect("application/octet-stream", "download", "%PDF-1.7\n".toByteArray()))
        assertEquals("image/jpeg", FileMimeDetector.detect("*/*", "download.bin", bytes(0xff, 0xd8, 0xff, 0xe0)))
    }

    @Test
    fun signatureOverridesWrongProviderAndExtension() {
        assertEquals(PrintFormatSupport.PDF, FileMimeDetector.detect("image/jpeg", "photo.jpg", "%PDF-1.7\n".toByteArray()))
        assertEquals("image/jpeg", FileMimeDetector.detect(PrintFormatSupport.PDF, "report.pdf", bytes(0xff, 0xd8, 0xff, 0xe1)))
    }

    @Test
    fun recognizesRasterAndPostscriptHeadersBeforeMetadata() {
        val examples = listOf(
            "image/png" to bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            "image/tiff" to bytes(0x49, 0x49, 0x2a, 0x00),
            "image/tiff" to bytes(0x4d, 0x4d, 0x00, 0x2a),
            "image/bmp" to ("BM".toByteArray() + ByteArray(12)),
            "image/webp" to ("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()),
            PrintFormatSupport.POSTSCRIPT to "%!PS-Adobe-3.0\n".toByteArray(),
            PrintFormatSupport.PWG_RASTER to "RaS2".toByteArray(),
            PrintFormatSupport.URF to "UNIRAST\u0000".toByteArray(),
        )
        for ((expected, content) in examples) {
            assertEquals(expected, FileMimeDetector.detect("text/plain", "wrong.txt", content))
        }
    }

    @Test
    fun specificProviderWinsOverExtensionWhenThereIsNoKnownSignature() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileMimeDetector.detect(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "report.pdf", "PK\u0003\u0004".toByteArray(),
            ),
        )
        assertEquals("image/jpeg", FileMimeDetector.detect(" IMAGE/PJPEG; name=photo ", "wrong.bin", byteArrayOf()))
    }

    @Test
    fun genericMimeAliasesFallBackToKnownExtensions() {
        for (provider in listOf(null, "application/octet-stream; charset=binary", "application/x-octet-stream", "application/force-download", "image/*", "")) {
            assertEquals(PrintFormatSupport.PDF, FileMimeDetector.detect(provider, "REPORT.PDF", byteArrayOf()))
            for (extension in listOf("txt", "csv", "log")) {
                assertEquals("text/plain", FileMimeDetector.detect(provider, "notes.$extension", "hello".toByteArray()))
            }
        }
    }

    @Test
    fun unknownBytesAreNotGuessedToBeTextOrOffice() {
        assertEquals(PrintFormatSupport.OCTET_STREAM, FileMimeDetector.detect(null, "unknown.bin", "plain-looking text".toByteArray()))
        assertEquals(PrintFormatSupport.OCTET_STREAM, FileMimeDetector.detect(null, "archive", "PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun truncatedHeadersDoNotClaimARecognizedFormat() {
        for (content in listOf(byteArrayOf(), "%PDF".toByteArray(), bytes(0xff, 0xd8), "UNIRAST".toByteArray(), "RIFF".toByteArray())) {
            assertEquals(PrintFormatSupport.OCTET_STREAM, FileMimeDetector.detect(null, "unknown", content))
        }
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
