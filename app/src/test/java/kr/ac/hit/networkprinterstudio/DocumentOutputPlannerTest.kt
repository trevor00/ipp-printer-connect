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

    @Test fun colorConversionSkipsMonochromePclForSupportedImageFormats() {
        for (colorMode in listOf("color", "auto-color", "process-color", "full-color", "highlight", "auto", " COLOR ")) {
            for (imageMime in listOf("image/png", "image/jpeg")) {
                val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PCL, imageMime), PrintFormatSupport.PDF, false, false, colorMode)
                assertEquals("$colorMode / $imageMime", imageMime, plan.mime)
                assertTrue(plan.renderPages)
            }
        }
    }

    @Test fun colorConversionKeepsEverySupportedColorPathAheadOfPcl() {
        for (mime in listOf(PrintFormatSupport.PDF, PrintFormatSupport.POSTSCRIPT, PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF)) {
            val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PCL, mime), "text/plain", true, false, "color")
            assertEquals(mime, plan.mime)
            assertTrue(plan.renderPages)
        }
    }

    @Test fun colorCannotSilentlyFallBackToMonochromePcl() {
        for (source in listOf(PrintFormatSupport.PDF, "image/jpeg", "text/plain")) {
            val result = runCatching { DocumentOutputPlanner.plan(listOf("application/PCL"), source, false, false, "color") }
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.startsWith("전송 실패:"))
            assertTrue(message.contains("컬러"))
            assertTrue(message.contains("흑백 전용"))
        }
    }

    @Test fun monochromeAndUnspecifiedModesKeepExistingPclPath() {
        for (colorMode in listOf(null, "monochrome", "auto-monochrome", "process-monochrome", "bi-level", "process-bi-level")) {
            val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PCL, "image/jpeg"), PrintFormatSupport.PDF, false, false, colorMode)
            assertEquals(PrintFormatSupport.PCL, plan.mime)
            assertTrue(plan.renderPages)
        }
    }

    @Test fun supportedOriginalColorFormatsAreNotNeedlesslyConverted() {
        for (mime in listOf(PrintFormatSupport.PDF, PrintFormatSupport.POSTSCRIPT, PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF, "image/png", "image/jpeg", "text/plain")) {
            val plan = DocumentOutputPlanner.plan(listOf(mime, PrintFormatSupport.PCL), mime, false, false, "color")
            assertEquals(mime, plan.mime)
            assertFalse(plan.renderPages)
        }
    }

    @Test fun supportedOriginalPclRemainsUnchangedInColorMode() {
        for (mime in listOf(PrintFormatSupport.PCL, PrintFormatSupport.PCL_XL, "application/PCL", "application/x-pclxl")) {
            val plan = DocumentOutputPlanner.plan(listOf(mime, PrintFormatSupport.POSTSCRIPT), mime, false, false, "color")
            assertEquals(mime, plan.mime)
            assertFalse(plan.renderPages)
        }
    }

    @Test fun originalPclStillPassesThroughWhenColorIsNotRequested() {
        for (mime in listOf(PrintFormatSupport.PCL, PrintFormatSupport.PCL_XL)) {
            for (colorMode in listOf(null, "monochrome")) {
                val plan = DocumentOutputPlanner.plan(listOf(mime), mime, false, false, colorMode)
                assertEquals(mime, plan.mime)
                assertFalse(plan.renderPages)
            }
        }
    }

    @Test fun forcedPostScriptStillUsesColorCapablePostScript() {
        val plan = DocumentOutputPlanner.plan(listOf(PrintFormatSupport.PCL, PrintFormatSupport.POSTSCRIPT), PrintFormatSupport.PDF, false, true, "color")
        assertEquals(PrintFormatSupport.POSTSCRIPT, plan.mime)
        assertTrue(plan.renderPages)
    }
}
