package kr.ac.hit.networkprinterstudio

import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
