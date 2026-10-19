package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOrientationTest {
    @Test fun allExifOrientationsPreserveEveryPixelAndUseTheExpectedDirection() {
        val expected = mapOf(
            1 to listOf("ab", "cd", "ef"),
            2 to listOf("ba", "dc", "fe"),
            3 to listOf("fe", "dc", "ba"),
            4 to listOf("ef", "cd", "ab"),
            5 to listOf("ace", "bdf"),
            6 to listOf("eca", "fdb"),
            7 to listOf("fdb", "eca"),
            8 to listOf("bdf", "ace"),
        )
        for ((orientation, rows) in expected) {
            val matrix = ImageOrientation.matrixValues(orientation, 2, 3)
                ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            val target = Array(rows.size) { CharArray(rows.first().length) { ' ' } }
            val source = listOf("ab", "cd", "ef")
            source.forEachIndexed { y, line ->
                line.forEachIndexed { x, pixel ->
                    val targetX = (matrix[0] * (x + .5f) + matrix[1] * (y + .5f) + matrix[2]).toInt()
                    val targetY = (matrix[3] * (x + .5f) + matrix[4] * (y + .5f) + matrix[5]).toInt()
                    assertTrue("orientation $orientation x=$targetX", targetX in target.first().indices)
                    assertTrue("orientation $orientation y=$targetY", targetY in target.indices)
                    target[targetY][targetX] = pixel
                }
            }
            assertEquals("orientation $orientation", rows, target.map { String(it) })
        }
    }

    @Test fun normalMissingAndUnknownMetadataNeedNoBitmapCopy() {
        for (orientation in listOf(-1, 0, 1, 9, Int.MAX_VALUE)) {
            assertNull(ImageOrientation.matrixValues(orientation, 100, 200))
        }
    }

    @Test fun rotatedImageKeepsOriginalResolution() {
        val matrix = ImageOrientation.matrixValues(6, 4032, 3024)!!
        assertEquals(3024f, matrix[2], 0f)
        assertEquals(1f, matrix[3], 0f)
        assertEquals(-1f, matrix[1], 0f)
    }
}
