package kr.ac.hit.networkprinterstudio

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrintDensityTest {
    @Test fun jpegReplacesExistingDensityWithoutChangingCompressedPixels() {
        val original = imageFixture("jpeg")
        val result = ImagePrintDensity.withDpi(original, "image/jpeg", 600)
        val density = metadataNode(result, "javax_imageio_jpeg_image_1.0", "app0JFIF")
        assertEquals("1", density.getAttribute("resUnits"))
        assertEquals("600", density.getAttribute("Xdensity"))
        assertEquals("600", density.getAttribute("Ydensity"))
        assertArrayEquals(jpegScan(original), jpegScan(result))
        assertSamePixels(original, result)
        assertArrayEquals(result, ImagePrintDensity.withDpi(result, "image/jpeg", 600))
    }

    @Test fun jpegAddsJfifWhenTheEncoderHasNotIncludedIt() {
        val fixture = imageFixture("jpeg")
        assertEquals(0xe0, fixture[3].toInt() and 0xff)
        val firstLength = (fixture[4].toInt() and 0xff) * 256 + (fixture[5].toInt() and 0xff)
        val withoutJfif = fixture.copyOfRange(0, 2) + fixture.copyOfRange(firstLength + 4, fixture.size)
        val result = ImagePrintDensity.withDpi(withoutJfif, "image/jpeg", 1200)
        val density = metadataNode(result, "javax_imageio_jpeg_image_1.0", "app0JFIF")
        assertEquals("1200", density.getAttribute("Xdensity"))
        assertEquals("1200", density.getAttribute("Ydensity"))
        assertArrayEquals(jpegScan(withoutJfif), jpegScan(result))
        assertSamePixels(fixture, result)
    }

    @Test fun pngAddsMetersAndAValidKnownCrcWithoutChangingPixels() {
        val original = imageFixture("png")
        val result = ImagePrintDensity.withDpi(original, "image/png", 300)
        val density = metadataNode(result, "javax_imageio_png_1.0", "pHYs")
        assertEquals("11811", density.getAttribute("pixelsPerUnitXAxis"))
        assertEquals("11811", density.getAttribute("pixelsPerUnitYAxis"))
        assertEquals("meter", density.getAttribute("unitSpecifier"))
        val chunks = pngChunks(result)
        val actual = chunks.single { it.first == "pHYs" }.second
        val expected = "000000097048597300002e2300002e230178a53f76"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, actual)
        assertTrue(chunks.indexOfFirst { it.first == "pHYs" } < chunks.indexOfFirst { it.first == "IDAT" })
        assertSamePixels(original, result)
    }

    @Test fun pngReplacesDensityWithoutDuplicatingChunksOrReencodingIdat() {
        val original = imageFixture("png")
        val first = ImagePrintDensity.withDpi(original, "image/png", 75)
        val result = ImagePrintDensity.withDpi(first, "image/png", 1200)
        val density = metadataNode(result, "javax_imageio_png_1.0", "pHYs")
        assertEquals("47244", density.getAttribute("pixelsPerUnitXAxis"))
        assertEquals("47244", density.getAttribute("pixelsPerUnitYAxis"))
        assertEquals(1, pngChunks(result).count { it.first == "pHYs" })
        val beforeIdat = pngChunks(original).filter { it.first == "IDAT" }
        val afterIdat = pngChunks(result).filter { it.first == "IDAT" }
        assertEquals(beforeIdat.size, afterIdat.size)
        beforeIdat.indices.forEach { assertArrayEquals(beforeIdat[it].second, afterIdat[it].second) }
        assertSamePixels(original, result)
        assertArrayEquals(result, ImagePrintDensity.withDpi(result, "image/png", 1200))
    }

    @Test fun truncatedImagesAndInvalidChunkLengthsAreRejected() {
        val truncatedJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x7f, 0xff.toByte())
        assertTrue(runCatching { ImagePrintDensity.withDpi(truncatedJpeg, "image/jpeg", 300) }.isFailure)
        val png = imageFixture("png")
        assertTrue(runCatching { ImagePrintDensity.withDpi(png.copyOf(png.size - 3), "image/png", 300) }.isFailure)
        val hugeChunk = png.copyOf().apply { this[8] = 0x7f; this[9] = 0xff.toByte(); this[10] = 0xff.toByte(); this[11] = 0xff.toByte() }
        assertTrue(runCatching { ImagePrintDensity.withDpi(hugeChunk, "image/png", 300) }.isFailure)
    }

    @Test fun invalidOrUnrepresentableDensityIsRejectedInsteadOfTruncated() {
        val jpeg = imageFixture("jpeg")
        val png = imageFixture("png")
        for (dpi in listOf(0, -1, 65_536)) {
            assertTrue(runCatching { ImagePrintDensity.withDpi(jpeg, "image/jpeg", dpi) }.isFailure)
        }
        assertTrue(runCatching { ImagePrintDensity.withDpi(png, "image/png", Int.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { ImagePrintDensity.withDpi(png, "image/gif", 300) }.isFailure)
    }

    private fun imageFixture(format: String): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        val colors = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff, 0x333333, 0xffff00)
        image.setRGB(0, 0, 3, 2, colors, 0, 3)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }
    }

    private fun metadataNode(image: ByteArray, format: String, nodeName: String): IIOMetadataNode {
        ImageIO.createImageInputStream(ByteArrayInputStream(image)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            return try {
                reader.input = input
                val tree = reader.getImageMetadata(0).getAsTree(format) as IIOMetadataNode
                val nodes = tree.getElementsByTagName(nodeName)
                assertEquals(1, nodes.length)
                nodes.item(0) as IIOMetadataNode
            } finally {
                reader.dispose()
            }
        }
    }

    private fun assertSamePixels(before: ByteArray, after: ByteArray) {
        val original = ImageIO.read(ByteArrayInputStream(before))
        val result = ImageIO.read(ByteArrayInputStream(after))
        assertEquals(original.width, result.width)
        assertEquals(original.height, result.height)
        assertArrayEquals(
            original.getRGB(0, 0, original.width, original.height, null, 0, original.width),
            result.getRGB(0, 0, result.width, result.height, null, 0, result.width),
        )
    }

    private fun jpegScan(image: ByteArray): ByteArray {
        val start = (0 until image.size - 1).first { image[it] == 0xff.toByte() && image[it + 1] == 0xda.toByte() }
        return image.copyOfRange(start, image.size)
    }

    private fun pngChunks(image: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var offset = 8
        while (offset < image.size) {
            val length = ((image[offset].toInt() and 0xff) shl 24) or ((image[offset + 1].toInt() and 0xff) shl 16) or
                ((image[offset + 2].toInt() and 0xff) shl 8) or (image[offset + 3].toInt() and 0xff)
            val end = offset + length + 12
            chunks += String(image, offset + 4, 4, Charsets.US_ASCII) to image.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
}
