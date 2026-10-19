package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintLayoutSupportTest {
    @Test
    fun shrinkOnlyDoesNotEnlargeSmallPages() {
        assertEquals(1f, PrintLayoutSupport.fitScale(100f, 200f, 500f, 800f, shrinkOnly = true))
        assertEquals(4f, PrintLayoutSupport.fitScale(100f, 200f, 500f, 800f))
        assertEquals(0.5f, PrintLayoutSupport.fitScale(1000f, 1600f, 500f, 800f, shrinkOnly = true))
    }

    private fun printer() = PrinterRecord(
        endpoint = PrinterEndpoint("localhost", 631, "/ipp/print"),
        name = "시험 프린터", model = "", location = "", formats = emptyList(), colorModes = emptyList(),
        defaultColorMode = null, operations = emptyList(), source = "test",
        orientations = listOf(3, 4), numberUps = listOf(1, 2), printScalings = listOf("fit", "none"),
    )

    @Test
    fun appliedLayoutIsNotRequestedAgainThroughIpp() {
        val options = IppJobOptions(copies = 2, pageRanges = listOf(IppRange(2, 3)), numberUp = 2,
            orientation = 4, printScaling = "fill", xImagePosition = "left", yImagePosition = "bottom",
            media = "iso_a4_210x297mm", sides = "two-sided-long-edge", colorMode = "monochrome")
        val finalOptions = PrintLayoutSupport.optionsForLaidOutPages(printer(), options)
        assertEquals(emptyList<IppRange>(), finalOptions.pageRanges)
        assertEquals(1, finalOptions.numberUp)
        assertEquals(3, finalOptions.orientation)
        assertEquals("none", finalOptions.printScaling)
        assertNull(finalOptions.xImagePosition)
        assertNull(finalOptions.yImagePosition)
        assertEquals(2, finalOptions.copies)
        assertEquals(options.media, finalOptions.media)
        assertEquals(options.sides, finalOptions.sides)
        assertEquals(options.colorMode, finalOptions.colorMode)
    }

    @Test
    fun mapsIppOrientationToContentRotationWithoutChangingPaper() {
        assertEquals(0f, PrintLayoutSupport.contentRotationDegrees(3))
        assertEquals(90f, PrintLayoutSupport.contentRotationDegrees(4))
        assertEquals(270f, PrintLayoutSupport.contentRotationDegrees(5))
        assertEquals(180f, PrintLayoutSupport.contentRotationDegrees(6))
    }
}
