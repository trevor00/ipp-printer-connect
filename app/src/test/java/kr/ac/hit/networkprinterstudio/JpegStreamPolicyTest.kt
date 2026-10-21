package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertTrue
import org.junit.Test

class JpegStreamPolicyTest {
    @Test fun a4At1200DpiIsAcceptedWithoutFullFrameMemoryLimit() {
        JpegStreamPolicy.validate(9917, 14033, 1200, 90)
        JpegStreamPolicy.validate(9917, 14033, 1200, 100)
        JpegStreamPolicy.validate(65_500, 65_500, 600, 100)
    }

    @Test fun oversizedImagesAreRejectedInsteadOfDownscaled() {
        for ((width, height) in listOf(0 to 1, 1 to 0, 65_501 to 1, 1 to 65_501, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertTrue(runCatching { JpegStreamPolicy.validate(width, height, 300, 90) }.isFailure)
        }
    }

    @Test fun invalidDpiAndQualityAreRejectedInsteadOfClamped() {
        for (dpi in listOf(-1, 0, 65_536)) assertTrue(runCatching { JpegStreamPolicy.validate(1, 1, dpi, 90) }.isFailure)
        for (quality in listOf(-1, 101)) assertTrue(runCatching { JpegStreamPolicy.validate(1, 1, 300, quality) }.isFailure)
    }

    @Test fun partialAndBatchRowsUseOnlyTheirDeclaredRgbPrefix() {
        JpegStreamPolicy.validateRows(9917, 14033, 9917 * 3, 1)
        JpegStreamPolicy.validateRows(9917, 128, 9917 * 3 * 128, 128)
        JpegStreamPolicy.validateRows(9917, 1, 9917 * 3 * 128, 1)
    }

    @Test fun truncatedAndOverflowingRowRequestsAreRejected() {
        assertTrue(runCatching { JpegStreamPolicy.validateRows(9917, 2, 9917 * 3 - 1, 1) }.isFailure)
        assertTrue(runCatching { JpegStreamPolicy.validateRows(9917, 1, 9917 * 6, 2) }.isFailure)
        assertTrue(runCatching { JpegStreamPolicy.validateRows(9917, 1, 1, 0) }.isFailure)
        assertTrue(runCatching { JpegStreamPolicy.validateRows(65_500, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) }.isFailure)
    }
}
