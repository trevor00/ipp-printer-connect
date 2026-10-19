package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintCapabilityPolicyTest {
    private fun printer(
        operations: List<Int> = emptyList(),
        ranges: Boolean = false,
        numberUps: List<Int> = emptyList(),
    ) = PrinterRecord(
        endpoint = PrinterEndpoint("localhost"), name = "시험 프린터", model = "", location = "",
        formats = emptyList(), colorModes = emptyList(), defaultColorMode = null,
        operations = operations, source = "test", pageRangesSupported = ranges, numberUps = numberUps,
    )

    @Test fun locallyRenderedDocumentsAllowRangesWithoutPrinterSupport() {
        for (mime in listOf("application/pdf", "image/jpeg", "image/png", "text/plain")) {
            val controls = PrintCapabilityPolicy.layoutControls(printer(), mime)
            assertTrue(controls.appCanLayout)
            assertTrue(controls.pageRangesAllowed)
            assertEquals(listOf(1, 2, 4, 6, 9, 16), controls.numberUpChoices)
        }
    }

    @Test fun advertisedUnusualNumberUpRemainsAvailableBesideAppChoices() {
        val controls = PrintCapabilityPolicy.layoutControls(printer(numberUps = listOf(3, 2, 3, 0, -1)), "application/pdf")
        assertEquals(listOf(1, 2, 3, 4, 6, 9, 16), controls.numberUpChoices)
    }

    @Test fun printerLanguagesOnlyExposeOptionsThePrinterAdvertises() {
        for (mime in listOf(PrintFormatSupport.POSTSCRIPT, PrintFormatSupport.PCL, PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF)) {
            val unsupported = PrintCapabilityPolicy.layoutControls(printer(), mime)
            assertFalse(unsupported.appCanLayout)
            assertFalse(unsupported.pageRangesAllowed)
            assertTrue(unsupported.numberUpChoices.isEmpty())
            val supported = PrintCapabilityPolicy.layoutControls(printer(ranges = true, numberUps = listOf(4, 2)), mime)
            assertFalse(supported.appCanLayout)
            assertTrue(supported.pageRangesAllowed)
            assertEquals(listOf(2, 4), supported.numberUpChoices)
        }
    }

    @Test fun remoteDocumentsAlwaysUsePrinterLayoutCapabilities() {
        val controls = PrintCapabilityPolicy.layoutControls(printer(numberUps = listOf(2)), "application/pdf", isRemoteUri = true)
        assertFalse(controls.appCanLayout)
        assertFalse(controls.pageRangesAllowed)
        assertEquals(listOf(2), controls.numberUpChoices)
        assertTrue(PrintCapabilityPolicy.layoutControls(printer(ranges = true), "text/plain", isRemoteUri = true).pageRangesAllowed)
    }

    @Test fun unknownAndNonDecodableImageFormatsDoNotClaimAppLayoutSupport() {
        for (mime in listOf(null, "application/octet-stream", "image/*", "image/tiff", "image/svg+xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            assertFalse(PrintCapabilityPolicy.appCanLayout(mime))
        }
        assertTrue(PrintCapabilityPolicy.appCanLayout("APPLICATION/X-PDF; version=1.7"))
        assertTrue(PrintCapabilityPolicy.appCanLayout("image/pjpeg"))
    }

    @Test fun uriOnlyPrinterAcceptsUrlsButNotUploadedFiles() {
        for (operations in listOf(listOf(3), listOf(5, 7))) {
            val target = printer(operations = operations)
            assertTrue(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = true))
            assertFalse(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = false))
        }
    }

    @Test fun fileOnlyPrinterDoesNotClaimUrlSupport() {
        for (operations in listOf(listOf(2), listOf(5, 6))) {
            val target = printer(operations = operations)
            assertTrue(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = false))
            assertFalse(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = true))
        }
    }

    @Test fun partialCreateJobOperationDoesNotEnableEitherSubmission() {
        for (operations in listOf(emptyList(), listOf(5), listOf(6), listOf(7))) {
            val target = printer(operations = operations)
            assertFalse(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = false))
            assertFalse(PrintCapabilityPolicy.supportsSubmission(target, isRemoteUri = true))
        }
    }
}
