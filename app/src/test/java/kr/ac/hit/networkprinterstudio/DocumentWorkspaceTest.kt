package kr.ac.hit.networkprinterstudio

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DocumentWorkspaceTest {
    @Test fun inputAboveOld50MiBLimitIsCopiedWithoutAWholeDocumentBuffer() {
        val directory = Files.createTempDirectory("ipp-workspace-test-").toFile()
        try {
            DocumentWorkspace(directory).use { workspace ->
                var remaining = 51L * 1024 * 1024 + 7
                var largestRequest = 0
                val source = object : InputStream() {
                    override fun read(): Int = if (remaining-- > 0) 0x5a else -1
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        largestRequest = maxOf(largestRequest, length)
                        if (remaining == 0L) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        bytes.fill(0x5a.toByte(), offset, offset + count)
                        remaining -= count
                        return count
                    }
                }
                val captured = workspace.capture(source)
                assertEquals(51L * 1024 * 1024 + 7, captured.length())
                assertTrue(largestRequest <= 64 * 1024)
                assertEquals(0x5a, captured.inputStream().use { it.read() })
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.delete() }
    }

    @Test fun onlyTheRetainedOutputSurvivesWorkspaceCleanup() {
        val directory = Files.createTempDirectory("ipp-workspace-test-").toFile()
        var kept: File? = null
        try {
            val discarded: File
            DocumentWorkspace(directory).use { workspace ->
                discarded = workspace.create(".pdf")
                kept = workspace.retain(workspace.create(".ps"))
            }
            assertFalse(discarded.exists())
            assertTrue(kept!!.exists())
            assertEquals(1, directory.listFiles().orEmpty().size)
        } finally { kept?.delete(); directory.delete() }
    }

    @Test fun aFailedCopyIsRemovedAndTheInputIsClosed() {
        val directory = Files.createTempDirectory("ipp-workspace-test-").toFile()
        var closed = false
        try {
            try {
                DocumentWorkspace(directory).use { workspace ->
                    workspace.capture(object : InputStream() {
                        override fun read(): Int = throw java.io.IOException("test read failure")
                        override fun close() { closed = true }
                    })
                }
                fail("Copy should fail")
            } catch (expected: java.io.IOException) { assertEquals("test read failure", expected.message) }
            assertTrue(closed)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.delete() }
    }
}
