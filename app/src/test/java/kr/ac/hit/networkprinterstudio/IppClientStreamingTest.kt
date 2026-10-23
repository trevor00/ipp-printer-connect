package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only ephemeral loopback sockets are used; no discovered or physical printer is contacted. */
class IppClientStreamingTest {
    @Test
    fun jobStatusQueriesOnlyTheSubmittedJobAndReadsAbortedState() {
        withLoopbackServer(1, { _, request ->
            val header = request.body.copyOfRange(0, 8).apply { this[2] = 0; this[3] = 0 }
            fun integerAttribute(tag: Int, name: String, value: Int): ByteArray {
                val key = name.toByteArray()
                return byteArrayOf(tag.toByte(), 0, key.size.toByte()) + key + byteArrayOf(0, 4) +
                    ByteBuffer.allocate(4).putInt(value).array()
            }
            HttpReply(200, "OK", listOf("Content-Type: application/ipp"), header + byteArrayOf(2) +
                integerAttribute(0x23, "job-state", 8) + integerAttribute(0x21, "job-impressions-completed", 0) + byteArrayOf(3))
        }) { endpoint, requests ->
            val status = IppClient.getJobStatus(endpoint, 67, null)
            assertEquals(8, status.state)
            assertTrue(status.hasError)
            assertEquals(0, status.impressionsCompleted)
            val sent = requests().single().body
            assertEquals(9, ByteBuffer.wrap(sent, 2, 2).short.toInt())
            val attributes = IppClient.parse(sent).attributes
            assertEquals(67, attributes["job-id"]?.single())
            assertEquals(endpoint.uri, attributes["printer-uri"]?.single())
            assertEquals("all", attributes["requested-attributes"]?.single())
            assertEquals(3, sent.last().toInt())
        }
    }

    @Test
    fun printJobStreamsTheUnchangedDocumentWithAnExactContentLength() {
        val document = ByteArray(20_017) { (it and 0xff).toByte() }
        val source = CountedSource(document)
        withLoopbackServer(1, { _, request -> successResponse(request.body) }) { endpoint, requests ->
            val result = IppClient.print(printer(endpoint), source, "application/pdf", "streamed.pdf")

            assertTrue(result.error.orEmpty(), result.accepted)
            val sent = requests().single()
            assertEquals(sent.body.size.toString(), sent.headers["content-length"])
            assertEquals("application/ipp", sent.headers["content-type"])
            assertEquals(2, ByteBuffer.wrap(sent.body, 2, 2).short.toInt())
            assertArrayEquals(document, sent.body.takeLast(document.size).toByteArray())
            assertEquals(1, source.opens)
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun digestAuthIntRetryReopensTheSourceAndSendsTheExactSameWireBody() {
        val source = CountedSource(ByteArray(20_017) { (it * 7).toByte() })
        withLoopbackServer(2, { index, request ->
            if (index == 0) {
                HttpReply(401, "Unauthorized", listOf("WWW-Authenticate: Digest realm=\"printer\", nonce=\"nonce\", qop=\"auth-int\", algorithm=SHA-256"), byteArrayOf())
            } else {
                successResponse(request.body)
            }
        }) { endpoint, requests ->
            IppClient.setCredentials(endpoint, IppCredentials("alice", "secret"))
            try {
                val result = IppClient.print(printer(endpoint), source, "application/pdf", "authenticated.pdf")

                assertTrue(result.error.orEmpty(), result.accepted)
                val sent = requests()
                assertEquals(2, sent.size)
                assertFalse(sent[0].headers.containsKey("authorization"))
                assertTrue(sent[1].headers["authorization"].orEmpty().startsWith("Digest "))
                assertTrue(sent[1].headers["authorization"].orEmpty().contains("qop=auth-int"))
                assertArrayEquals(sent[0].body, sent[1].body)
                assertEquals(3, source.opens) // first send, entity hash, authenticated send
                assertEquals(3, source.closes)
            } finally {
                IppClient.clearCredentials(endpoint)
            }
        }
    }

    @Test
    fun unsupportedAuthenticationReturnsAnExplicitFailureWithoutResending() {
        val source = CountedSource(byteArrayOf(1, 2, 3))
        withLoopbackServer(1, { _, _ ->
            HttpReply(401, "Unauthorized", listOf("WWW-Authenticate: Digest realm=\"printer\", nonce=\"nonce\", algorithm=MD5-unknown"), byteArrayOf())
        }) { endpoint, requests ->
            IppClient.setCredentials(endpoint, IppCredentials("alice", "secret"))
            try {
                val result = IppClient.print(printer(endpoint), source, "application/pdf", "unsupported.pdf")

                assertFalse(result.accepted)
                assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("인증 방식을 지원하지 않습니다"))
                assertEquals(1, requests().size)
                assertEquals(1, source.opens)
            } finally {
                IppClient.clearCredentials(endpoint)
            }
        }
    }

    private fun printer(endpoint: PrinterEndpoint): PrinterRecord = PrinterRecord(
        endpoint, "loopback test", "test", "", listOf("application/pdf"), emptyList(), null, listOf(2), "test",
    )

    private class CountedSource(private val bytes: ByteArray) : DocumentData {
        override val length = bytes.size.toLong()
        var opens = 0
        var closes = 0
        override fun openStream(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                private var closed = false
                override fun close() {
                    if (!closed) closes++
                    closed = true
                    super.close()
                }
            }
        }
    }

    private data class RecordedRequest(val headers: Map<String, String>, val body: ByteArray)
    private data class HttpReply(val status: Int, val reason: String, val headers: List<String>, val body: ByteArray)

    private fun successResponse(request: ByteArray): HttpReply {
        val jobId = byteArrayOf(0x21, 0, 6) + "job-id".toByteArray() + byteArrayOf(0, 4) +
            ByteBuffer.allocate(4).putInt(17).array()
        val response = request.copyOfRange(0, 8) + byteArrayOf(1, 2) + jobId + byteArrayOf(3)
        response[2] = 0
        response[3] = 0
        return HttpReply(200, "OK", listOf("Content-Type: application/ipp"), response)
    }

    private fun withLoopbackServer(
        count: Int,
        response: (Int, RecordedRequest) -> HttpReply,
        action: (PrinterEndpoint, () -> List<RecordedRequest>) -> Unit,
    ) {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = ServerSocket(0, 4, loopback)
        server.soTimeout = 5_000
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            buildList {
                repeat(count) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        val requestLine = readLine(input)
                        check(requestLine.startsWith("POST /ipp/print ")) { requestLine }
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = readLine(input)
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val length = requireNotNull(headers["content-length"]).toInt()
                        require(length in 0..512_000)
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < body.size) {
                            val read = input.read(body, offset, body.size - offset)
                            check(read > 0) { "incomplete local test request" }
                            offset += read
                        }
                        val recorded = RecordedRequest(headers, body)
                        add(recorded)
                        val reply = response(index, recorded)
                        socket.getOutputStream().use { output ->
                            val head = buildString {
                                append("HTTP/1.1 ${reply.status} ${reply.reason}\r\n")
                                reply.headers.forEach { append(it).append("\r\n") }
                                append("Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n")
                            }.toByteArray(Charsets.ISO_8859_1)
                            output.write(head)
                            output.write(reply.body)
                            output.flush()
                        }
                    }
                }
            }
        })
        try {
            action(PrinterEndpoint("127.0.0.1", server.localPort), { future.get(10, TimeUnit.SECONDS) })
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            check(next >= 0) { "incomplete local test headers" }
            if (next == 10) break
            out.write(next)
            check(out.size() <= 16_384) { "local test header too long" }
        }
        return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }
}
