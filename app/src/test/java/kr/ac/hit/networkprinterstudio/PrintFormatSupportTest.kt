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
    fun supportedOriginalMimeTakesPriorityOverConversionBackends() {
        assertEquals(
            "text/plain",
            PrintFormatSupport.directSubmissionMime(samsungFormats, "text/plain"),
        )
        assertEquals(
            "image/jpeg",
            PrintFormatSupport.directSubmissionMime(
                listOf("application/postscript", "image/jpeg", "application/pdf"),
                "image/jpeg",
            ),
        )
        assertEquals(
            "application/pdf",
            PrintFormatSupport.directSubmissionMime(
                listOf("application/postscript", "application/pdf"),
                "application/pdf",
            ),
        )
    }

    @Test
    fun conversionIsUsedOnlyWhenOriginalMimeIsMissingOrPostscriptIsForced() {
        assertEquals(null, PrintFormatSupport.directSubmissionMime(samsungFormats, "application/pdf"))
        assertEquals(
            null,
            PrintFormatSupport.directSubmissionMime(samsungFormats, "text/plain", forcePostScript = true),
        )
    }

    @Test
    fun reportsAvailableConversionBackends() {
        val labels = PrintFormatSupport.conversionLabels(samsungFormats)
        assertTrue(labels.any { it.contains("PostScript") })
        assertTrue(labels.any { it.contains("PCL") })
    }

    @Test
    fun textFileCanBeSubmittedWithoutAnyConversionBackend() {
        assertEquals("text/plain", PrintFormatSupport.directSubmissionMime(listOf("text/plain"), "text/plain"))
    }

    @Test
    fun textFileStillHasConversionCandidatesWhenOriginalIsNotSupported() {
        val formats = listOf(PrintFormatSupport.PDF, PrintFormatSupport.POSTSCRIPT, PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF, PrintFormatSupport.PCL)
        assertEquals(null, PrintFormatSupport.directSubmissionMime(formats, "text/plain"))
        assertEquals(5, PrintFormatSupport.conversionLabels(formats).count { it.contains("텍스트") })
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

    @Test
    fun automaticDetectionAndWildcardsNeverSupportAnUnknownSource() {
        val generic = listOf(PrintFormatSupport.OCTET_STREAM, "application/x-octet-stream", "*/*", "image/*")
        for (source in generic) {
            assertEquals(null, PrintFormatSupport.advertisedMime(generic, source))
            assertEquals(null, PrintFormatSupport.directSubmissionMime(generic, source))
            assertTrue(!PrintFormatSupport.supports(generic, source))
        }
        assertEquals(null, PrintFormatSupport.directSubmissionMime(generic, "image/jpeg"))
    }

    @Test
    fun mimeParametersAndImageAliasesPreserveSpecificAdvertisedSupport() {
        val formats = listOf("image/jpeg", "application/pdf")
        assertEquals("image/jpeg", PrintFormatSupport.directSubmissionMime(formats, "IMAGE/PJPEG; name=photo.jpg"))
        assertEquals("application/pdf", PrintFormatSupport.directSubmissionMime(formats, " application/PDF; version=1.7 "))
    }

    @Test
    fun unknownFormatExplainsAutomaticDetectionAndSuggestsAnAvailablePdfPath() {
        val message = PrintFormatSupport.unsupportedFileMessage(
            "unknown.bin", "application/octet-stream; charset=binary", samsungFormats,
        )
        assertTrue(message.startsWith("전송 실패:"))
        assertTrue(message.contains("자동 형식 감지 광고만으로는"))
        assertTrue(message.contains("PDF로 변환한 뒤 다시 선택하세요"))
    }
}
