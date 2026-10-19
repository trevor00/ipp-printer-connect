package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderQualityTest {
    @Test fun selectedDpiIsNotSilentlyCapped() {
        val high = RenderQuality.pixelDimensions(595, 842, 1200 / 72f)
        assertEquals(9917, high.first)
        assertEquals(14033, high.second)
        assertTrue(high.first.toLong() * high.second > 12_000_000)
    }

    @Test fun aPageAt300DpiMatchesPhysicalDimensions() {
        assertEquals(2479 to 3508, RenderQuality.pixelDimensions(595, 842, 300 / 72f))
    }

    @Test fun invalidDimensionsAreRejected() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(runCatching { RenderQuality.pixelDimensions(595, 842, scale) }.isFailure)
        }
        assertTrue(runCatching { RenderQuality.pixelDimensions(Int.MAX_VALUE, Int.MAX_VALUE, 1f) }.isFailure)
    }

    @Test fun pclRejectsUnsupportedDpiInsteadOfReducingIt() {
        RenderQuality.requirePclDpi(600)
        assertTrue(runCatching { RenderQuality.requirePclDpi(1200) }.isFailure)
    }
}
