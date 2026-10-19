package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterRecordStateTest {
    @Test fun printerCapabilitiesSurviveStateRoundTrip() {
        val original = PrinterRecord(
            endpoint = PrinterEndpoint("printer.local", 8631, "/printers/연구실", secure = true, ippMajor = 1, ippMinor = 1),
            name = "연구실 프린터", model = "시험 모델", location = "4층", source = "mDNS",
            formats = listOf("application/pdf", "image/jpeg", "application/postscript"),
            colorModes = listOf("monochrome", "color"), defaultColorMode = "monochrome",
            operations = listOf(2, 3, 4, 5, 6, 7), available = true, statusDetail = "준비됨",
            media = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"), defaultMedia = "iso_a4_210x297mm",
            printScalings = listOf("none", "fit"), defaultPrintScaling = "fit",
            sides = listOf("one-sided", "two-sided-long-edge"), defaultSides = "two-sided-long-edge",
            orientations = listOf(3, 4, 5, 6), defaultOrientation = 3,
            printQualities = listOf(3, 4, 5), defaultPrintQuality = 4,
            resolutions = listOf(IppResolution(300, 300, 3), IppResolution(600, 1200, 3)),
            defaultResolution = IppResolution(600, 1200, 3),
            numberUps = listOf(1, 2, 4), defaultNumberUp = 1,
            xImagePositions = listOf("left", "center", "right"), defaultXImagePosition = "center",
            yImagePositions = listOf("top", "center", "bottom"), defaultYImagePosition = "center",
            copiesSupported = IppRange(1, 99), pageRangesSupported = true,
            uriAuthentication = listOf("digest"), uriSecurity = listOf("tls"),
            authChallenge = IppAuthChallenge(listOf("Digest", "Basic"), realm = "연구실"),
            mediaLeftMargins = listOf(420), mediaRightMargins = listOf(425),
            mediaTopMargins = listOf(430), mediaBottomMargins = listOf(435),
        )
        val restored = roundTrip(original)

        assertNotSame(original, restored)
        assertNotSame(original.endpoint, restored.endpoint)
        assertEquals(original, restored)
        assertEquals(original.endpoint.uri, restored.endpoint.uri)
        assertTrue(restored.supportsPrintJob)
        assertTrue(restored.supportsPrintUri)
        assertTrue(restored.supportsCreateSendDocument)
        assertTrue(restored.supportsCreateSendUri)
        assertEquals("600×1200dpi", restored.defaultResolution?.label)
        assertEquals("Digest / Basic · realm 연구실", restored.authChallenge?.label)
        assertEquals(listOf(420), restored.mediaLeftMargins)
        assertEquals(listOf(435), restored.mediaBottomMargins)
    }

    @Test fun emptyOptionalCapabilitiesSurviveStateRoundTrip() {
        val original = PrinterRecord(
            endpoint = PrinterEndpoint("192.0.2.10"), name = "프린터", model = "", location = "",
            formats = emptyList(), colorModes = emptyList(), defaultColorMode = null,
            operations = emptyList(), source = "직접 연결",
        )
        assertEquals(original, roundTrip(original))
    }

    @Test fun credentialsAreNotSerializableState() {
        assertFalse(Serializable::class.java.isAssignableFrom(IppCredentials::class.java))
    }

    private fun roundTrip(printer: PrinterRecord): PrinterRecord {
        val bytes = ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(printer) }
            buffer.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as PrinterRecord }
    }
}
