package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedPagePdfMergerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fontBytes = ("binary font 12 0 R endstream endobj\n".toByteArray() + ByteArray(16_387) { (it * 73).toByte() })
    private val cmap = "/CIDInit /ProcSet findresource begin\n<0001> <D55C>\n<0002> <AE00>\nend".toByteArray()
    private val content = "BT /F1 15 Tf 10 20 Td (text 12 0 R \\(safe\\)) Tj ET\n".toByteArray()

    @Test fun directAndIndirectStreamLengthsKeepBinaryFontsContentAndUnicodeByteExact() {
        for (indirect in listOf(false, true)) {
            val source = fixture(indirect = indirect)
            val output = ByteArrayOutputStream()
            GeneratedPagePdfMerger(output, temporary.root).use { merger -> merger.addPage(source); merger.finish() }
            val result = String(output.toByteArray(), Charsets.ISO_8859_1)
            assertTrue(result.contains(String(fontBytes, Charsets.ISO_8859_1)))
            assertTrue(result.contains(String(cmap, Charsets.ISO_8859_1)))
            assertTrue(result.contains(String(content, Charsets.ISO_8859_1)))
            assertTrue(result.contains("/ToUnicode"))
            assertTrue(result.contains("/FontFile2"))
            assertFalse(result.contains("/Subtype /Image"))
            assertTrue(result.contains("/Count 1"))
            assertXref(output.toByteArray())
            assertNoSpools()
        }
    }

    @Test fun pageLocalReferencesAreRemappedButLiteralAndHexStringsAreUntouched() {
        val source = fixture()
        val output = ByteArrayOutputStream()
        GeneratedPagePdfMerger(output, temporary.root).use { merger ->
            merger.addPage(source); merger.addPage(source); merger.finish()
        }
        val result = String(output.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(result.contains("/Count 2 /Kids [ 3 0 R 11 0 R"))
        assertEquals(2, Regex("/Parent 2 0 R").findAll(result).count())
        assertEquals(2, Regex("/Literal \\(literal 12 0 R\\)").findAll(result).count())
        assertEquals(2, Regex("/Hex <313220302052>").findAll(result).count())
        assertTrue(result.contains("/Ref 5 0 R"))
        assertTrue(result.contains("/Ref 13 0 R"))
        assertXref(output.toByteArray())
        assertNoSpools()
    }

    @Test fun manyPagesUseDiskBackedIndexFilesThatAreRemovedOnClose() {
        val source = fixture(fontPayload = "font".toByteArray())
        val destination = File(temporary.root, "merged.pdf")
        destination.outputStream().buffered().use { output ->
            GeneratedPagePdfMerger(output, temporary.root).use { merger ->
                repeat(120) { merger.addPage(source) }
                assertTrue(temporary.root.listFiles().orEmpty().any { it.name.startsWith("text_pdf_pages_") })
                merger.finish()
            }
        }
        assertTrue(destination.readText(Charsets.ISO_8859_1).contains("/Count 120"))
        assertXref(destination.readBytes())
        assertNoSpools()
    }

    @Test fun unsupportedStructuresAndInvalidObjectReferencesFailExplicitly() {
        val invalid = listOf(
            fixture { it[2] = ascii("<< /Type /Pages /Count 2 /Kids [3 0 R 3 0 R] >>") },
            fixture { it[2] = ascii("<< /Type /Pages /Count 1 /Kids [3 0 R] /MediaBox [0 0 300 300] >>") },
            fixture { it[3] = ascii("<< /Type /Page /Parent 2 0 R /Resources << /Bad 999 0 R >> /MediaBox [0 0 300 300] /Contents 4 0 R >>") },
            fixture { it[3] = ascii("<< /Type /Page /Parent 3 0 R /Resources <<>> /MediaBox [0 0 300 300] /Contents 4 0 R >>") },
            fixture { it[4] = ascii("<< /Length 2 >>\nstream\nwrong length\nendstream") },
            fixture { it[6] = ascii("-1") },
            fixture { it[4] = ascii("<< /Length 6 0 R >>\nstream\nshort\nendstream") },
            fixture { it[10] = ascii("<< /Bad 5 1 R >>") },
            fixture { it[10] = ascii("<< /Bad (unclosed literal >>") },
            fixture { it[10] = ascii("<< /Bad <xx> >>") },
            fixture { it[10] = ascii("<< /Duplicate 1 /Duplicate 2 >>") },
        )
        for (source in invalid) {
            val result = runCatching { GeneratedPagePdfMerger(ByteArrayOutputStream(), temporary.root).use { it.addPage(source); it.finish() } }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("안전하게 합칠 수 없습니다"))
            assertNoSpools()
        }
    }

    @Test fun encryptedIncrementalHybridAndBrokenXrefInputsAreRejected() {
        for (trailer in listOf("/Encrypt 5 0 R", "/Prev 1", "/XRefStm 1", "/Size 2")) {
            val source = fixture(trailerExtra = trailer)
            assertTrue(runCatching { GeneratedPagePdfMerger(ByteArrayOutputStream(), temporary.root).use { it.addPage(source) } }.isFailure)
            assertNoSpools()
        }
        val source = fixture()
        val bytes = source.readBytes()
        val xref = String(bytes, Charsets.ISO_8859_1).substringAfterLast("startxref\n").substringBefore('\n').toLong()
        java.io.RandomAccessFile(source, "rw").use { it.seek(xref); it.write("xxxx".toByteArray()) }
        assertTrue(runCatching { GeneratedPagePdfMerger(ByteArrayOutputStream(), temporary.root).use { it.addPage(source) } }.isFailure)
        assertNoSpools()
    }

    @Test fun constructorAndLaterOutputFailuresBothRemoveTheirTemporaryIndices() {
        val immediate = IOException("initial output failed")
        val initialOutput = object : OutputStream() { override fun write(value: Int): Unit = throw immediate }
        assertEquals(immediate, runCatching { GeneratedPagePdfMerger(initialOutput, temporary.root) }.exceptionOrNull())
        assertNoSpools()
        val later = IOException("later output failed")
        val output = object : OutputStream() {
            var count = 0
            override fun write(value: Int) { if (++count > 200) throw later }
        }
        assertEquals(later, runCatching { GeneratedPagePdfMerger(output, temporary.root).use { it.addPage(fixture()) } }.exceptionOrNull())
        assertNoSpools()
    }

    @Test fun cancellationBeforeCopyClosesFilesAndDoesNotLoseTheInterruptFlag() {
        val source = fixture()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            failure.set(runCatching {
                GeneratedPagePdfMerger(ByteArrayOutputStream(), temporary.root).use {
                    Thread.currentThread().interrupt()
                    it.addPage(source)
                }
            }.exceptionOrNull())
        }
        thread.start(); thread.join(5000)
        assertTrue(failure.get() is CancellationException)
        assertNoSpools()
    }

    private fun fixture(
        indirect: Boolean = true,
        fontPayload: ByteArray = fontBytes,
        trailerExtra: String = "",
        change: (MutableMap<Int, ByteArray>) -> Unit = {},
    ): File {
        val objects = linkedMapOf(
            1 to ascii("<< /Type /Catalog /Pages 2 0 R >>"),
            2 to ascii("<< /Type /Pages /Count 1 /Kids [3 0 R] >>"),
            3 to ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"),
            4 to (ascii("<< /Length ${if (indirect) "6 0 R" else content.size.toString()} >>\nstream\n") + content + ascii("\nendstream")),
            5 to ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 7 0 R /FontDescriptor 8 0 R >>"),
            6 to ascii(content.size.toString()),
            7 to (ascii("<< /Length ${cmap.size} >>\nstream\n") + cmap + ascii("\nendstream")),
            8 to ascii("<< /Type /FontDescriptor /FontFile2 9 0 R >>"),
            9 to (ascii("<< /Length ${fontPayload.size} >>\nstream\n") + fontPayload + ascii("\nendstream")),
            10 to ascii("<< /Literal (literal 12 0 R) /Hex <313220302052> /Ref 5 0 R >>"),
        )
        change(objects)
        val result = ByteArrayOutputStream()
        result.write(ascii("%PDF-1.4\n"))
        val offsets = mutableListOf(0)
        objects.forEach { (id, body) -> offsets.add(result.size()); result.write(ascii("$id 0 obj\n")); result.write(body); result.write(ascii("\nendobj\n")) }
        val xref = result.size()
        result.write(ascii("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
        offsets.drop(1).forEach { result.write(ascii(String.format(Locale.US, "%010d 00000 n \n", it))) }
        result.write(ascii("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R $trailerExtra >>\nstartxref\n$xref\n%%EOF\n"))
        return temporary.newFile().apply { writeBytes(result.toByteArray()) }
    }

    private fun assertXref(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        val start = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.substring(start).startsWith("xref\n"))
        val lines = text.substring(start).lines()
        val count = lines[1].substringAfter(' ').toInt()
        for (id in 1 until count) {
            val offset = lines[id + 2].substring(0, 10).toInt()
            assertTrue("object $id points to $offset", text.substring(offset).startsWith("$id 0 obj\n"))
        }
    }

    private fun assertNoSpools() {
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.startsWith("text_pdf_offsets_") || it.name.startsWith("text_pdf_pages_") })
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)
}
