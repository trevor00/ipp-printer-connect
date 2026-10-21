package kr.ac.hit.networkprinterstudio

import org.junit.Assert.*
import org.junit.Test

class PrinterOutputPolicyTest {
    private fun printer() = PrinterRecord(PrinterEndpoint("localhost"), "test", "", "", emptyList(), emptyList(), null, listOf(2), "test")

    @Test fun unknownMediaDoesNotBecomeA4() {
        assertNull(PrinterOutputPolicy.pageSize(printer(), "unknown-paper"))
        assertNull(PrinterOutputPolicy.pageSize(printer(), null))
        assertEquals(595 to 842, PrinterOutputPolicy.pageSize(printer(), "iso_a4_210x297mm"))
    }

    @Test fun ambiguousMarginsAreNotGuessed() {
        val record = printer().copy(mediaLeftMargins = listOf(0, 420), mediaTopMargins = listOf(420),
            mediaRightMargins = listOf(420), mediaBottomMargins = listOf(420))
        assertNull(PrinterOutputPolicy.marginsMm(record, "iso_a4_210x297mm"))
    }

    @Test fun asymmetricKnownMarginsArePreserved() {
        val record = printer().copy(mediaLeftMargins = listOf(100), mediaTopMargins = listOf(200),
            mediaRightMargins = listOf(300), mediaBottomMargins = listOf(400))
        assertEquals(listOf(1f, 2f, 3f, 4f), PrinterOutputPolicy.marginsMm(record, null))
    }

    @Test fun rasterResolutionIsNotSubstituted() {
        val record = printer().copy(pwgTypes = listOf("srgb_8"), pwgResolutions = listOf(IppResolution(300, 300, 3)))
        assertNull(PrinterOutputPolicy.rasterProblem(record, PrintFormatSupport.PWG_RASTER, 300, "one-sided"))
        assertNotNull(PrinterOutputPolicy.rasterProblem(record, PrintFormatSupport.PWG_RASTER, 1200, "one-sided"))
        assertNotNull(PrinterOutputPolicy.rasterProblem(printer(), PrintFormatSupport.URF, 300, "one-sided"))
    }
}
