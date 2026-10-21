package kr.ac.hit.networkprinterstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PclPageSetupTest {
    @Test fun standardPapersUseDocumentedHpCodesInBothOrientations() {
        for ((size, code) in listOf(
            (522 to 756) to 1, (612 to 792) to 2, (612 to 1008) to 3, (792 to 1224) to 6,
            (420 to 595) to 25, (595 to 842) to 26, (842 to 1191) to 27,
        )) {
            assertEquals(code, PclPageSetup.pageSizeCode(size.first, size.second))
            assertEquals(code, PclPageSetup.pageSizeCode(size.second, size.first))
        }
    }

    @Test fun pdfIntegerPointRoundingDoesNotMisclassifyMetricPapers() {
        assertEquals(26, PclPageSetup.pageSizeCode(595, 841))
        assertEquals(26, PclPageSetup.pageSizeCode(596, 842))
        assertEquals(27, PclPageSetup.pageSizeCode(841, 1190))
        assertEquals(25, PclPageSetup.pageSizeCode(419, 596))
    }

    @Test fun landscapeUsesLandscapeAndRasterPresentationFollowingTheLogicalPage() {
        val commands = PclPageSetup.commands(842, 595, 300, 3508, 2479)
        assertTrue(commands.startsWith("\u001b&l26A\u001b&l1O"))
        assertTrue(commands.contains("\u001b*r0F"))
        assertFalse(commands.contains("\u001b*r3F"))
        assertTrue(commands.endsWith("\u001b*t300R\u001b*r3508S\u001b*r2479T\u001b*r1A\u001b*b0M"))
    }

    @Test fun portraitSetsPaperAndDirectionExplicitly() {
        val commands = PclPageSetup.commands(612, 1008, 600, 5100, 8400)
        assertTrue(commands.startsWith("\u001b&l3A\u001b&l0O"))
        assertTrue(commands.contains("\u001b&a0P"))
        assertTrue(commands.contains("\u001b*t600R"))
    }

    @Test fun marginClearingComesAfterDefaultRestoringCommandsAndBeforeRasterStart() {
        val commands = PclPageSetup.commands(595, 842, 150, 1240, 1754)
        val order = listOf("\u001b&l26A", "\u001b&l0O", "\u001b&l0L", "\u001b&l0E", "\u001b9", "\u001b&a0P", "\u001b*p0x0Y", "\u001b*r0F", "\u001b*r1A")
        order.zipWithNext().forEach { (first, next) -> assertTrue(commands.indexOf(first) < commands.indexOf(next)) }
    }

    @Test fun customPapersAreRejectedRatherThanSilentlyBecomingLetter() {
        for ((width, height) in listOf(600 to 850, 595 to 595, 300 to 400, 297 to 420)) {
            val failure = runCatching { PclPageSetup.commands(width, height, 300, 100, 100) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("자동 변경하지 않았습니다"))
        }
    }

    @Test fun pclSupports1200DpiAndRejectsInvalidSizes() {
        assertTrue(PclPageSetup.commands(595, 842, 1200, 9917, 14033).contains("\u001b*t1200R"))
        assertTrue(runCatching { PclPageSetup.commands(595, 842, 300, 0, 1) }.isFailure)
        assertTrue(runCatching { PclPageSetup.pageSizeCode(0, 842) }.isFailure)
    }
}
