package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun unknownDefaultsAndMissingCapabilitiesDoNotBlockExistingPrinters() {
        val printer = printer().copy(numberUps = emptyList(), orientations = emptyList(), printScalings = emptyList())
        val options = PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions(numberUp = 4, orientation = 4, printScaling = "fit"))
        assertNull(options.numberUp)
        assertNull(options.orientation)
        assertNull(options.printScaling)
        assertNull(options.xImagePosition)
        assertNull(options.yImagePosition)
    }

    @Test
    fun knownNeutralDefaultsCanBeRequestedEvenWhenSupportedListsAreMissing() {
        val printer = printer().copy(
            numberUps = emptyList(), defaultNumberUp = 1,
            orientations = emptyList(), defaultOrientation = 3,
            printScalings = emptyList(), defaultPrintScaling = "NONE",
            defaultXImagePosition = "CENTER", defaultYImagePosition = "center",
        )
        val options = PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
        assertEquals(1, options.numberUp)
        assertEquals(3, options.orientation)
        assertEquals("none", options.printScaling)
        assertEquals("center", options.xImagePosition)
        assertEquals("center", options.yImagePosition)
    }

    @Test
    fun advertisedNeutralValuesOverrideNonNeutralPrinterDefaults() {
        val printer = printer().copy(
            defaultNumberUp = 4, defaultOrientation = 4, defaultPrintScaling = "fill",
            xImagePositions = listOf("left", "CENTER"), defaultXImagePosition = "left",
            yImagePositions = listOf("top", "center"), defaultYImagePosition = "bottom",
        )
        val options = PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
        assertEquals(1, options.numberUp)
        assertEquals(3, options.orientation)
        assertEquals("none", options.printScaling)
        assertEquals("CENTER", options.xImagePosition)
        assertEquals("center", options.yImagePosition)
    }

    @Test
    fun knownNonNeutralNumberUpWithoutSinglePageSupportFailsInsteadOfBeingOmitted() {
        for (supported in listOf(emptyList(), listOf(2, 4))) {
            assertUnsafeDefaultRejected(printer().copy(numberUps = supported, defaultNumberUp = 2), "모아찍기")
        }
    }

    @Test
    fun knownNonNeutralOrientationWithoutPortraitSupportFailsInsteadOfBeingOmitted() {
        for (orientation in 4..6) {
            for (supported in listOf(emptyList(), listOf(4, 5, 6))) {
                assertUnsafeDefaultRejected(printer().copy(orientations = supported, defaultOrientation = orientation), "문서 방향")
            }
        }
    }

    @Test
    fun knownScalingDefaultsCannotRescaleAlreadyLaidOutPages() {
        for (scaling in listOf("auto", "auto-fit", "fit", "fill", "shrink-to-fit", " FIT ")) {
            for (supported in listOf(emptyList(), listOf("fit", "fill"))) {
                assertUnsafeDefaultRejected(printer().copy(printScalings = supported, defaultPrintScaling = scaling), "내용 크기 조절")
            }
        }
    }

    @Test
    fun knownNonCenteredPositionsWithoutCenterSupportFailInsteadOfBeingOmitted() {
        for (position in listOf("left", "right")) {
            for (supported in listOf(emptyList(), listOf("left", "right"))) {
                assertUnsafeDefaultRejected(printer().copy(xImagePositions = supported, defaultXImagePosition = position), "가로 위치")
            }
        }
        for (position in listOf("top", "bottom")) {
            for (supported in listOf(emptyList(), listOf("top", "bottom"))) {
                assertUnsafeDefaultRejected(printer().copy(yImagePositions = supported, defaultYImagePosition = position), "세로 위치")
            }
        }
    }

    @Test
    fun unrecognizedDefaultsDoNotBecomeInventedPrinterCapabilities() {
        val printer = printer().copy(
            numberUps = emptyList(), defaultNumberUp = 0,
            orientations = emptyList(), defaultOrientation = 0,
            printScalings = emptyList(), defaultPrintScaling = "vendor-unknown",
            defaultXImagePosition = "vendor-unknown", defaultYImagePosition = "vendor-unknown",
        )
        val options = PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
        assertNull(options.numberUp)
        assertNull(options.orientation)
        assertNull(options.printScaling)
        assertNull(options.xImagePosition)
        assertNull(options.yImagePosition)
    }

    @Test
    fun contradictorySupportedListsCanSafelyOmitKnownNeutralDefaults() {
        val printer = printer().copy(
            numberUps = listOf(2), defaultNumberUp = 1,
            orientations = listOf(4), defaultOrientation = 3,
            printScalings = listOf("fit"), defaultPrintScaling = "none",
            xImagePositions = listOf("left"), defaultXImagePosition = "center",
            yImagePositions = listOf("top"), defaultYImagePosition = "center",
        )
        val options = PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions())
        assertNull(options.numberUp)
        assertNull(options.orientation)
        assertNull(options.printScaling)
        assertNull(options.xImagePosition)
        assertNull(options.yImagePosition)
    }

    private fun assertUnsafeDefaultRejected(printer: PrinterRecord, expectedLabel: String) {
        val result = runCatching { PrintLayoutSupport.optionsForLaidOutPages(printer, IppJobOptions()) }
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.startsWith("전송 실패:"))
        assertTrue(message.contains(expectedLabel))
        assertTrue(message.contains("배치가 다시 적용될 수 있어"))
    }
}
