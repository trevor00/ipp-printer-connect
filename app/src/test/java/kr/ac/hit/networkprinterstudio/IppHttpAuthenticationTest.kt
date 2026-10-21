package kr.ac.hit.networkprinterstudio

import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IppHttpAuthenticationTest {
    @Test
    fun parsesCombinedDigestAndBasicChallenges() {
        val parsed = IppHttpAuthentication.parseChallenges(
            listOf(
                """Digest realm="printer", nonce="abc", qop="auth", algorithm=MD5, Basic realm="printer"""",
            ),
        )

        assertEquals(listOf("Digest", "Basic"), parsed.map { it.scheme })
        assertEquals("printer", parsed[0].realm)
        assertEquals("abc", parsed[0].parameters["nonce"])
        assertEquals("printer", parsed[1].realm)
    }

    @Test
    fun buildsBasicAuthorization() {
        val header = IppHttpAuthentication.basic(
            IppCredentials("alice", "secret"),
        )

        assertEquals("Basic YWxpY2U6c2VjcmV0", header)
    }

    @Test
    fun buildsRfcDigestResponseForIppRequestTarget() {
        val challenge = IppHttpAuthentication.parseChallenges(
            listOf(
                """Digest realm="printer", nonce="abcdef", qop="auth", algorithm=MD5, opaque="opaque-token"""",
            ),
        ).single()
        val header = IppHttpAuthentication.authorization(
            challenge = challenge,
            credentials = IppCredentials("alice", "secret"),
            method = "POST",
            url = URL("http://printer.local:631/ipp/print"),
            body = byteArrayOf(1, 2, 3),
        )

        assertNotNull(header)
        val value = requireNotNull(header)
        val cnonce = Regex("""cnonce="([^"]+)"""").find(value)!!.groupValues[1]
        val actualResponse = Regex("""response="([^"]+)"""").find(value)!!.groupValues[1]
        fun md5(text: String): String =
            MessageDigest.getInstance("MD5")
                .digest(text.toByteArray(Charsets.ISO_8859_1))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val ha1 = md5("alice:printer:secret")
        val ha2 = md5("POST:/ipp/print")
        val expected = md5("$ha1:abcdef:00000001:$cnonce:auth:$ha2")

        assertEquals(expected, actualResponse)
        assertTrue(value.contains("""uri="/ipp/print""""))
        assertTrue(value.contains("""opaque="opaque-token""""))
    }

    @Test
    fun digestAuthIntHashesTheExactHeaderAndDocumentStream() {
        val challenge = HttpAuthChallenge("Digest", mapOf(
            "realm" to "printer", "nonce" to "nonce-value", "qop" to "auth-int", "algorithm" to "SHA-256",
        ))
        val header = byteArrayOf(2, 0, 0, 2, 0, 0, 0, 1, 3)
        val document = byteArrayOf(0, -1, 3, 13, 10)
        var opens = 0
        var closes = 0
        val body = IppRequestBody(header, object : DocumentData {
            override val length = document.size.toLong()
            override fun openStream(): InputStream {
                opens++
                return object : ByteArrayInputStream(document) {
                    private var closed = false
                    override fun close() { if (!closed) closes++; closed = true; super.close() }
                }
            }
        })

        val authorization = requireNotNull(IppHttpAuthentication.authorization(
            challenge, IppCredentials("alice", "secret"), "POST", URL("http://printer.local:631/ipp/print"), body,
        ))
        val cnonce = Regex("""cnonce="([^"]+)"""").find(authorization)!!.groupValues[1]
        val actualResponse = Regex("""response="([^"]+)"""").find(authorization)!!.groupValues[1]
        fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun hash(value: String): String = hash(value.toByteArray(Charsets.ISO_8859_1))
        val ha1 = hash("alice:printer:secret")
        val ha2 = hash("POST:/ipp/print:${hash(header + document)}")
        val expected = hash("$ha1:nonce-value:00000001:$cnonce:auth-int:$ha2")

        assertEquals(expected, actualResponse)
        assertEquals(1, opens)
        assertEquals(1, closes)
        val sent = ByteArrayOutputStream().also(body::writeTo).toByteArray()
        org.junit.Assert.assertArrayEquals(header + document, sent)
        assertEquals(2, opens)
        assertEquals(2, closes)
    }

    @Test
    fun digestAuthAndBasicDoNotReadTheDocumentForTheirHeader() {
        val body = object : DocumentData {
            override val length = 4L
            override fun openStream(): InputStream = error("qop=auth must not read the document")
        }
        val credentials = IppCredentials("alice", "secret")
        val url = URL("http://printer.local/ipp/print")

        assertNotNull(IppHttpAuthentication.authorization(HttpAuthChallenge("Basic", emptyMap()), credentials, "POST", url, body))
        assertNotNull(IppHttpAuthentication.authorization(
            HttpAuthChallenge("Digest", mapOf("realm" to "printer", "nonce" to "abc", "qop" to "auth")),
            credentials, "POST", url, body,
        ))
    }

    @Test
    fun unsupportedDigestAlgorithmOrQopIsNotMislabeledAsSupported() {
        val credentials = IppCredentials("alice", "secret")
        val url = URL("http://printer.local/ipp/print")
        val parameters = mapOf("realm" to "printer", "nonce" to "abc", "qop" to "auth", "algorithm" to "MD5-unknown")

        assertNull(IppHttpAuthentication.authorization(HttpAuthChallenge("Digest", parameters), credentials, "POST", url, byteArrayOf()))
        assertNull(IppHttpAuthentication.authorization(
            HttpAuthChallenge("Digest", parameters + mapOf("algorithm" to "SHA-256", "qop" to "unknown")),
            credentials, "POST", url, byteArrayOf(),
        ))
    }

    @Test(expected = DocumentDataException::class)
    fun digestAuthIntRejectsChangedSourceLength() {
        val body = object : DocumentData {
            override val length = 3L
            override fun openStream(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2))
        }
        IppHttpAuthentication.authorization(
            HttpAuthChallenge("Digest", mapOf("realm" to "printer", "nonce" to "abc", "qop" to "auth-int")),
            IppCredentials("alice", "secret"), "POST", URL("http://printer.local/ipp/print"), body,
        )
    }
}
