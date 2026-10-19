package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentOutputPlannerTest {
    @Test fun unchangedSupportedTextKeepsOriginalBytes() {
        val plan = DocumentOutputPlanner.plan(listOf("text/plain", PrintFormatSupport.POSTSCRIPT), "text/plain", false, false)
        assertEquals("text/plain", plan.mime)
        assertFalse(plan.renderPages)
    }

    @Test fun unsupportedTextConvertsWithoutLayoutEdits() {
        val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.POSTSCRIPT), "text/plain", false, false)
        assertEquals(PrintFormatSupport.POSTSCRIPT, plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun unchangedSupportedPhotoKeepsOriginalBytes() {
        val plan = DocumentOutputPlanner.plan(listOf("image/jpeg", PrintFormatSupport.POSTSCRIPT), "image/jpeg", false, false)
        assertEquals("image/jpeg", plan.mime)
        assertFalse(plan.renderPages)
    }

    @Test fun editedSupportedPhotoUsesRenderedPages() {
        val plan = DocumentOutputPlanner.plan(listOf("image/jpeg", PrintFormatSupport.POSTSCRIPT), "image/jpeg", true, false)
        assertEquals(PrintFormatSupport.POSTSCRIPT, plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun textLayoutOverridesRawTextSupport() {
        val plan = DocumentOutputPlanner.plan(listOf("text/plain", PrintFormatSupport.PDF), "text/plain", true, false)
        assertEquals(PrintFormatSupport.PDF, plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun unsupportedPdfStillConvertsWithoutLayoutEdits() {
        val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.URF), PrintFormatSupport.PDF, false, false)
        assertEquals(PrintFormatSupport.URF, plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun missingConversionPathNeverDiscardsRequestedLayout() {
        assertTrue(runCatching { DocumentOutputPlanner.plan(listOf("text/plain"), "text/plain", true, false) }.isFailure)
    }

    @Test fun editedPhotoCanBeEncodedBackToAdvertisedJpeg() {
        val plan = DocumentOutputPlanner.plan(listOf("image/jpeg"), "image/jpeg", true, false)
        assertEquals("image/jpeg", plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun unsupportedLayoutForPrinterLanguageIsRejected() {
        assertTrue(runCatching { DocumentOutputPlanner.plan(listOf(PrintFormatSupport.POSTSCRIPT), PrintFormatSupport.POSTSCRIPT, true, false) }.isFailure)
    }

    @Test fun forcedPostScriptRequiresAdvertisedSupport() {
        assertTrue(runCatching { DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PDF), PrintFormatSupport.PDF, false, true) }.isFailure)
        val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PDF, "application/x-postscript"), PrintFormatSupport.PDF, true, true)
        assertEquals("application/x-postscript", plan.mime)
        assertTrue(plan.renderPages)
    }

    @Test fun unknownSourceCannotBypassConversionThroughAutomaticDetection() {
        val formats = listOf(PrintFormatSupport.OCTET_STREAM, "*/*", PrintFormatSupport.POSTSCRIPT)
        for (layout in listOf(false, true)) {
            for (forcePostScript in listOf(false, true)) {
                val result = runCatching {
                    DocumentOutputPlanner.plan(formats, PrintFormatSupport.OCTET_STREAM, layout, forcePostScript)
                }
                assertTrue(result.isFailure)
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.startsWith("전송 실패:"))
                assertTrue(message.contains("파일 형식을 판별할 수 없고"))
                assertTrue(message.contains("PDF로 변환한 뒤 다시 선택하세요"))
            }
        }
    }

    @Test fun wildcardSourcesCannotBeTreatedAsActualImages() {
        val result = runCatching {
            DocumentOutputPlanner.plan(listOf("image/*", PrintFormatSupport.PDF), "image/*", false, false)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("파일 형식을 판별할 수 없고"))
    }

    @Test fun detectedPdfUsesRealConversionPathInsteadOfAutomaticDetection() {
        val source = FileMimeDetector.detect("application/octet-stream", "download.bin", "%PDF-1.7\n".toByteArray())
        val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.OCTET_STREAM, PrintFormatSupport.URF), source, false, false)
        assertEquals(PrintFormatSupport.URF, plan.mime)
        assertTrue(plan.renderPages)
    }
}
