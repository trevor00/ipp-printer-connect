package kr.ac.hit.networkprinterstudio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class IppResponseParserTest {
    private fun response(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(byteArrayOf(2, 0)); writeShort(1); writeInt(42); body()
        }
    }.toByteArray()
    private fun DataOutputStream.attribute(tag: Int, name: String, bytes: ByteArray) {
        writeByte(tag); writeShort(name.length); write(name.toByteArray()); writeShort(bytes.size); write(bytes)
    }

    @Test fun preservesUnsupportedAttributeGroup() {
        val parsed = IppResponseParser.parse(response {
            writeByte(1); attribute(0x47, "attributes-charset", "utf-8".toByteArray())
            writeByte(5); attribute(0x44, "media", "a4".toByteArray()); writeByte(3)
        })
        assertEquals(42, parsed.requestId)
        assertEquals(1, parsed.status)
        assertEquals(listOf("a4"), parsed.groups.single { it.tag == 5 }.attributes["media"])
    }

    @Test fun truncatedSuccessCannotBeAccepted() {
        val complete = response { writeByte(1); attribute(0x47, "attributes-charset", "utf-8".toByteArray()); writeByte(3) }
        for (length in 0 until complete.size) {
            assertThrows(IllegalArgumentException::class.java) { IppResponseParser.parse(complete.copyOf(length)) }
        }
    }

    @Test fun parsesNestedMediaSize() {
        val parsed = IppResponseParser.parse(response {
            writeByte(4); attribute(0x34, "media-col-default", byteArrayOf())
            attribute(0x4a, "", "media-size".toByteArray()); attribute(0x34, "", byteArrayOf())
            attribute(0x4a, "", "x-dimension".toByteArray()); attribute(0x21, "", byteArrayOf(0, 0, 0x52, 0x08))
            attribute(0x37, "", byteArrayOf()); attribute(0x37, "", byteArrayOf()); writeByte(3)
        })
        val collection = parsed.attributes["media-col-default"]!!.first() as IppCollection
        val size = collection.attributes["media-size"]!!.first() as IppCollection
        assertEquals(21000, size.attributes["x-dimension"]!!.first())
    }

    @Test fun oversizedResponseIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { IppResponseParser.parse(ByteArray(IppResponseParser.MAX_RESPONSE_BYTES + 1)) }
    }
}
