package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintLayoutSupportTest {
    @Test
    fun mapsIppOrientationToContentRotationWithoutChangingPaper() {
        assertEquals(0f, PrintLayoutSupport.contentRotationDegrees(3))
        assertEquals(90f, PrintLayoutSupport.contentRotationDegrees(4))
        assertEquals(270f, PrintLayoutSupport.contentRotationDegrees(5))
        assertEquals(180f, PrintLayoutSupport.contentRotationDegrees(6))
    }
}
