package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintFormatSupportTest {
    private val samsungFormats = listOf(
        "application/octet-stream",
        "application/PCL",
        "application/postscript",
        "application/vnd.hp-pcl",
        "application/vnd.hp-pclxl",
        "application/x-COLOR-GDI",
        "text/plain",
        "image/urf",
    )

    @Test
    fun normalizesCommonPclAliases() {
        assertEquals(PrintFormatSupport.PCL, PrintFormatSupport.normalize("application/PCL"))
        assertEquals(PrintFormatSupport.PCL, PrintFormatSupport.normalize("application/x-pcl"))
        assertEquals(PrintFormatSupport.PCL_XL, PrintFormatSupport.normalize("application/PCLXL"))
    }

    @Test
    fun preservesTheExactAdvertisedMimeForSubmission() {
        assertEquals("application/PCL", PrintFormatSupport.advertisedMime(listOf("application/PCL"), PrintFormatSupport.PCL))
        assertEquals("application/postscript", PrintFormatSupport.advertisedMime(samsungFormats, PrintFormatSupport.POSTSCRIPT))
    }

    @Test
    fun reportsAvailableConversionBackends() {
        val labels = PrintFormatSupport.conversionLabels(samsungFormats)
        assertTrue(labels.any { it.contains("PostScript") })
        assertTrue(labels.any { it.contains("PCL") })
    }

    @Test
    fun unsupportedOfficeFileSuggestsPdfWhenPrinterHasAConversionPath() {
        val message = PrintFormatSupport.unsupportedFileMessage(
            "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            samsungFormats,
        )
        assertTrue(message.startsWith("전송 실패:"))
        assertTrue(message.contains("PDF로 변환한 뒤 다시 선택하세요"))
        assertTrue(message.contains("PostScript"))
    }

    @Test
    fun unknownFileDoesNotPromisePdfPrintingWithoutAConversionPath() {
        val message = PrintFormatSupport.unsupportedFileMessage(
            "unknown.bin",
            PrintFormatSupport.OCTET_STREAM,
            listOf("application/x-COLOR-GDI"),
        )
        assertTrue(message.startsWith("전송 실패:"))
        assertTrue(message.contains("파일 형식을 판별할 수 없고"))
        assertTrue(message.contains("인쇄가 보장되지 않습니다"))
    }
}
