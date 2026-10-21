package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLayoutGeometryTest {
    private val page = ContentLayoutOptions(pageWidth = 600, pageHeight = 800, margin = 50)

    @Test fun fitPreservesAspectRatioInsideDocumentMargins() {
        assertBounds(125f, 50f, 475f, 750f, ImageLayoutGeometry.place(100f, 200f, page))
    }

    @Test fun fillCoversAvailableAreaAndMayExtendPastItsEdges() {
        assertBounds(50f, -100f, 550f, 900f,
            ImageLayoutGeometry.place(100f, 200f, page.copy(scale = ContentScale.FILL)))
    }

    @Test fun fullBleedIgnoresDocumentAndPrinterMargins() {
        val options = page.copy(scale = ContentScale.FULL_BLEED_FILL,
            printableLeft = 10, printableTop = 20, printableRight = 30, printableBottom = 40)
        assertBounds(0f, -200f, 600f, 1000f, ImageLayoutGeometry.place(100f, 200f, options))
    }

    @Test fun printableFitUsesEachAdvertisedMarginInsteadOfUniformMargin() {
        val options = page.copy(scale = ContentScale.PRINTABLE_FIT, margin = 999,
            printableLeft = 10, printableTop = 20, printableRight = 30, printableBottom = 40)
        assertBounds(105f, 20f, 475f, 760f, ImageLayoutGeometry.place(100f, 200f, options))
    }

    @Test fun actualSizeUsesLogicalSourceDimensionsWithoutFitting() {
        assertBounds(250f, 300f, 350f, 500f,
            ImageLayoutGeometry.place(100f, 200f, page.copy(scale = ContentScale.ACTUAL)))
        assertBounds(-3700f, -2600f, 4300f, 3400f,
            ImageLayoutGeometry.place(8000f, 6000f, page.copy(scale = ContentScale.ACTUAL)))
    }

    @Test fun shrinkOnlyKeepsSmallSourcesAndShrinksLargeSources() {
        val options = page.copy(scale = ContentScale.SHRINK_ONLY)
        assertBounds(250f, 300f, 350f, 500f, ImageLayoutGeometry.place(100f, 200f, options))
        assertBounds(125f, 50f, 475f, 750f, ImageLayoutGeometry.place(1000f, 2000f, options))
    }

    @Test fun eachAlignmentUsesTheSameAvailableArea() {
        val options = page.copy(scale = ContentScale.ACTUAL)
        assertBounds(50f, 50f, 150f, 250f, ImageLayoutGeometry.place(100f, 200f,
            options.copy(horizontal = ContentHorizontal.LEFT, vertical = ContentVertical.TOP)))
        assertBounds(250f, 300f, 350f, 500f, ImageLayoutGeometry.place(100f, 200f,
            options.copy(horizontal = ContentHorizontal.CENTER, vertical = ContentVertical.CENTER)))
        assertBounds(450f, 550f, 550f, 750f, ImageLayoutGeometry.place(100f, 200f,
            options.copy(horizontal = ContentHorizontal.RIGHT, vertical = ContentVertical.BOTTOM)))
    }

    @Test fun percentageAndOffsetsApplyAfterBaseScaleAndAlignment() {
        val bounds = ImageLayoutGeometry.place(100f, 200f,
            page.copy(scale = ContentScale.ACTUAL, scalePercent = 150f, offsetX = 7f, offsetY = -13f))
        assertBounds(232f, 237f, 382f, 537f, bounds)
        assertEquals(150f, bounds.width, 0.001f)
        assertEquals(300f, bounds.height, 0.001f)
    }

    @Test fun rasterDpiDoesNotChangeLogicalPlacement() {
        for (scale in ContentScale.entries) {
            val options = page.copy(scale = scale)
            assertEquals(ImageLayoutGeometry.place(8000f, 6000f, options.copy(rasterDpi = 75)),
                ImageLayoutGeometry.place(8000f, 6000f, options.copy(rasterDpi = 1200)))
        }
    }

    @Test fun invalidSourceDimensionsAreRejected() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(runCatching { ImageLayoutGeometry.place(invalid, 200f, page) }.isFailure)
            assertTrue(runCatching { ImageLayoutGeometry.place(100f, invalid, page) }.isFailure)
        }
    }

    private fun assertBounds(left: Float, top: Float, right: Float, bottom: Float, actual: ImagePageBounds) {
        assertEquals(left, actual.left, 0.001f)
        assertEquals(top, actual.top, 0.001f)
        assertEquals(right, actual.right, 0.001f)
        assertEquals(bottom, actual.bottom, 0.001f)
    }
}
