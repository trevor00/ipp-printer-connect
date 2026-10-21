package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRenderPolicyTest {
    @Test fun ordinaryA4FitsBothDisplayBounds() {
        assertEquals(900 to 1273, PreviewRenderPolicy.pagePixelSize(595, 842))
        assertEquals(108, PreviewRenderPolicy.rasterDpi(595, 842))
    }

    @Test fun veryTallPdfIsHeightLimited() {
        assertEquals(16 to 1600, PreviewRenderPolicy.pagePixelSize(1000, 100_000))
        assertEquals(1, PreviewRenderPolicy.rasterDpi(1000, 100_000))
    }

    @Test fun veryWidePdfIsWidthLimited() {
        assertEquals(900 to 9, PreviewRenderPolicy.pagePixelSize(100_000, 1000))
        assertEquals(1, PreviewRenderPolicy.rasterDpi(100_000, 1000))
    }

    @Test fun smallPagesAreNotUpscaledBeyondTwoTimes() {
        assertEquals(200 to 400, PreviewRenderPolicy.pagePixelSize(100, 200))
        assertEquals(150, PreviewRenderPolicy.rasterDpi(1, 1))
        assertEquals(1 to 1, PreviewRenderPolicy.pagePixelSize(1, 1, 1, 1))
    }

    @Test fun customBoundsPreserveAspectRatioWithinPixelRounding() {
        assertEquals(320 to 240, PreviewRenderPolicy.pagePixelSize(4000, 3000, 320, 320))
        assertEquals(240 to 320, PreviewRenderPolicy.pagePixelSize(3000, 4000, 320, 320))
    }

    @Test fun smallPhotosKeepTheirOriginalPixels() {
        assertEquals(1, PreviewRenderPolicy.imageSampleSize(640, 480))
        assertEquals(1, PreviewRenderPolicy.imageSampleSize(2000, 1000))
        assertEquals(1, PreviewRenderPolicy.imageSampleSize(1, 1))
    }

    @Test fun highResolutionPhotosUsePowerOfTwoSamplingWithinBothLimits() {
        for ((width, height) in listOf(8000 to 6000, 12000 to 9000, 1000 to 100_000, 100_000 to 1000)) {
            val sample = PreviewRenderPolicy.imageSampleSize(width, height)
            assertTrue(sample > 1 && (sample and (sample - 1)) == 0)
            assertSampleFits(width, height, sample)
            assertTrue("The previous power of two should exceed a preview limit", !sampleFits(width, height, sample / 2))
        }
        assertEquals(8, PreviewRenderPolicy.imageSampleSize(8000, 6000))
    }

    @Test fun imageSamplingAccountsForDecoderRoundingUp() {
        assertEquals(2, PreviewRenderPolicy.imageSampleSize(2049, 1))
        assertEquals(2, PreviewRenderPolicy.imageSampleSize(2000, 1001))
        assertEquals(4, PreviewRenderPolicy.imageSampleSize(4001, 2001))
    }

    @Test fun maximumIntDimensionsCannotOverflow() {
        assertEquals(900 to 900, PreviewRenderPolicy.pagePixelSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE,
            PreviewRenderPolicy.pagePixelSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1 to 1600, PreviewRenderPolicy.pagePixelSize(1, Int.MAX_VALUE))
        assertEquals(900 to 1, PreviewRenderPolicy.pagePixelSize(Int.MAX_VALUE, 1))
        assertSampleFits(Int.MAX_VALUE, Int.MAX_VALUE, PreviewRenderPolicy.imageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertSampleFits(Int.MAX_VALUE, 1, PreviewRenderPolicy.imageSampleSize(Int.MAX_VALUE, 1))
        assertEquals(1, PreviewRenderPolicy.rasterDpi(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun nonPositiveDimensionsAndBoundsAreRejected() {
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            expectInvalid { PreviewRenderPolicy.pagePixelSize(invalid, 100) }
            expectInvalid { PreviewRenderPolicy.pagePixelSize(100, invalid) }
            expectInvalid { PreviewRenderPolicy.pagePixelSize(100, 100, invalid, 100) }
            expectInvalid { PreviewRenderPolicy.pagePixelSize(100, 100, 100, invalid) }
            expectInvalid { PreviewRenderPolicy.imageSampleSize(invalid, 100) }
            expectInvalid { PreviewRenderPolicy.imageSampleSize(100, invalid) }
            expectInvalid { PreviewRenderPolicy.rasterDpi(invalid, 100) }
            expectInvalid { PreviewRenderPolicy.rasterDpi(100, invalid) }
        }
    }

    @Test fun displayCopyChangesOnlyDpiAndNeverMutatesPrintOptions() {
        val printOptions = ContentLayoutOptions(
            horizontal = ContentHorizontal.RIGHT,
            vertical = ContentVertical.BOTTOM,
            scale = ContentScale.FULL_BLEED_FILL,
            margin = 17,
            rasterDpi = 1200,
            postScriptJpegQuality = null,
            pageWidth = 595,
            pageHeight = 842,
            scalePercent = 135f,
            offsetX = 23f,
            offsetY = -19f,
            rotationDegrees = 90f,
            printableLeft = 11,
            printableTop = 12,
            printableRight = 13,
            printableBottom = 14,
        )
        val original = printOptions.copy()
        val display = PreviewRenderPolicy.optionsForDisplay(printOptions)

        assertEquals(original, printOptions)
        assertEquals(1200, printOptions.rasterDpi)
        assertEquals(108, display.rasterDpi)
        assertEquals(printOptions.copy(rasterDpi = 108), display)
        assertTrue(display !== printOptions)
    }

    @Test fun lowResolutionPrintSettingIsNotUpscaledForDisplay() {
        val printOptions = ContentLayoutOptions(rasterDpi = 75)
        val display = PreviewRenderPolicy.optionsForDisplay(printOptions)

        assertEquals(75, display.rasterDpi)
        assertEquals(printOptions, display)
        assertTrue(display !== printOptions)
    }

    private fun assertSampleFits(width: Int, height: Int, sample: Int) {
        assertTrue("$width × $height at sample $sample", sampleFits(width, height, sample))
    }

    private fun sampleFits(width: Int, height: Int, sample: Int): Boolean {
        val sampledWidth = (width.toLong() + sample - 1L) / sample
        val sampledHeight = (height.toLong() + sample - 1L) / sample
        return sampledWidth <= PreviewRenderPolicy.MAX_IMAGE_EDGE && sampledHeight <= PreviewRenderPolicy.MAX_IMAGE_EDGE &&
            sampledWidth * sampledHeight <= PreviewRenderPolicy.MAX_IMAGE_PIXELS
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: never allow invalid dimensions to reach bitmap allocation.
        }
    }
}
