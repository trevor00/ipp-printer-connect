package kr.ac.hit.networkprinterstudio

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Copies only our own Android-generated, classic-xref, single-page PDFs. Not a user-PDF importer.
 * Font programs, ToUnicode maps and content streams are copied byte-for-byte, never rasterized.
 * Object offsets and page references are disk-backed, not a growing destination object graph.
 * PDF syntax/stream lengths: Adobe PDF Reference 1.7, sections 3.2.7 and 3.4.3.
 */
internal class GeneratedPagePdfMerger(destination: OutputStream, cacheDirectory: File) : Closeable {
    private val offsetsFile = File.createTempFile("text_pdf_offsets_", ".bin", cacheDirectory)
    private val pagesFile = try { File.createTempFile("text_pdf_pages_", ".bin", cacheDirectory) }
        catch (error: Throwable) { offsetsFile.delete(); throw error }
    private val offsets = try { DataOutputStream(offsetsFile.outputStream().buffered()) }
        catch (error: Throwable) { offsetsFile.delete(); pagesFile.delete(); throw error }
    private val pages = try { DataOutputStream(pagesFile.outputStream().buffered()) }
        catch (error: Throwable) {
            try { offsets.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            offsetsFile.delete(); pagesFile.delete()
            throw error
        }
    private val output = CountingOutput(destination)
    private var nextObject = 3
    private var pageCount = 0
    private var finished = false
    private var closed = false

    init {
        try { ascii("%PDF-1.4\n% vector text pages\n") }
        catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun addPage(generatedPage: File) {
        check(!finished && !closed)
        cancelled()
        val source = SinglePageSource(generatedPage)
        val references = hashMapOf(source.catalog to 1, source.pageTree to 2)
        for (id in source.objectIds) if (id != source.catalog && id != source.pageTree) {
            check(nextObject < Int.MAX_VALUE) { "텍스트 PDF의 객체 수가 너무 많습니다." }
            references[id] = nextObject++
        }
        for (id in source.objectIds) {
            if (id == source.catalog || id == source.pageTree) continue
            cancelled()
            val target = references.getValue(id)
            val objectBody = source.readObject(id)
            offsets.writeLong(output.count)
            ascii("$target 0 obj\n")
            var index = 0
            while (index < objectBody.tokens.size) {
                cancelled()
                val token = objectBody.tokens[index]
                if (isUnsigned(token) && index + 2 < objectBody.tokens.size &&
                    isUnsigned(objectBody.tokens[index + 1]) && objectBody.tokens[index + 2] == "R") {
                    require(objectBody.tokens[index + 1] == "0") { unsupported("0세대가 아닌 객체 참조") }
                    val original = token.toIntOrNull() ?: error(unsupported("객체 참조 범위"))
                    val mapped = references[original] ?: error(unsupported("존재하지 않는 객체 참조 $original"))
                    ascii("$mapped 0 R ")
                    index += 3
                } else {
                    ascii(token)
                    ascii(" ")
                    index++
                }
            }
            if (objectBody.streamStart != null) {
                ascii("\nstream\n")
                source.copyStream(objectBody, output)
                ascii("\nendstream\n")
            }
            ascii("\nendobj\n")
        }
        pages.writeInt(references.getValue(source.page))
        pageCount++
    }

    fun finish() {
        check(!finished && !closed && pageCount > 0)
        cancelled()
        offsets.flush(); pages.flush()
        val catalogOffset = output.count
        ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val pagesOffset = output.count
        ascii("2 0 obj\n<< /Type /Pages /Count $pageCount /Kids [ ")
        DataInputStream(pagesFile.inputStream().buffered()).use { input ->
            repeat(pageCount) { cancelled(); ascii("${input.readInt()} 0 R ") }
            check(input.read() < 0)
        }
        ascii("] >>\nendobj\n")
        val xref = output.count
        ascii("xref\n0 $nextObject\n0000000000 65535 f \n")
        xrefEntry(catalogOffset); xrefEntry(pagesOffset)
        DataInputStream(offsetsFile.inputStream().buffered()).use { input ->
            repeat(nextObject - 3) { cancelled(); xrefEntry(input.readLong()) }
            check(input.read() < 0)
        }
        ascii("trailer\n<< /Size $nextObject /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        finished = true
    }

    private fun xrefEntry(offset: Long) {
        require(offset in 1..9_999_999_999L) { "텍스트 PDF가 지원되는 파일 크기를 초과했습니다." }
        ascii(String.format(Locale.US, "%010d 00000 n \n", offset))
    }

    override fun close() {
        if (closed) return
        closed = true
        try { offsets.close() } finally {
            try { pages.close() } finally { offsetsFile.delete(); pagesFile.delete() }
        }
    }

    private fun ascii(value: String) = output.write(value.toByteArray(Charsets.ISO_8859_1))

    private class CountingOutput(private val destination: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(value: Int) { destination.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { destination.write(bytes, offset, length); count += length }
    }

    private class SinglePageSource(private val file: File) {
        private data class Entry(val offset: Long, val end: Long)
        private val entries: Map<Int, Entry>
        val objectIds: List<Int>
        val catalog: Int
        val pageTree: Int
        val page: Int

        init {
            val xref = footerOffset(file)
            val starts = linkedMapOf<Int, Long>()
            val seenEntries = hashSetOf<Int>()
            val trailer: Map<String, List<String>>
            PdfInput(file, xref).use { input ->
                require(input.token() == "xref") { unsupported("xref 스트림") }
                while (true) {
                    val first = input.token() ?: error(unsupported("xref 누락"))
                    if (first == "trailer") break
                    val start = unsignedInt(first)
                    val count = unsignedInt(input.token())
                    require(count <= MAX_SOURCE_OBJECTS && start.toLong() + count <= MAX_SOURCE_OBJECTS) { unsupported("단일 페이지의 과도한 객체 수") }
                    repeat(count) { index ->
                        require(seenEntries.add(start + index)) { unsupported("중복 xref 항목") }
                        val offset = unsignedLong(input.token())
                        val generation = unsignedInt(input.token())
                        val state = input.token()
                        require(state == "n" || state == "f") { unsupported("xref 항목") }
                        if (state == "n") {
                            require(generation == 0 && start + index > 0 && offset in 1 until xref) { unsupported("xref 위치/세대") }
                            require(starts.put(start + index, offset) == null) { unsupported("중복 xref 항목") }
                        }
                    }
                }
                trailer = dictionary(input.dictionaryTokens())
                require(input.token() == "startxref" && unsignedLong(input.token()) == xref) { unsupported("xref 종료 위치") }
            }
            require(trailer.keys.none { it in setOf("/Encrypt", "/Prev", "/XRefStm") }) { unsupported("암호화 또는 증분/xref 혼합 파일") }
            require(trailer.keys.all { it in setOf("/Size", "/Root", "/Info", "/ID") }) { unsupported("추가 trailer 구조") }
            val declaredSize = unsignedInt(single(trailer, "/Size"))
            require(declaredSize <= MAX_SOURCE_OBJECTS && starts.keys.all { it < declaredSize }) { unsupported("xref 크기") }
            catalog = reference(required(trailer, "/Root"))
            require(starts.isNotEmpty() && starts.values.toSet().size == starts.size) { unsupported("중복 객체 위치") }
            val boundaries = (starts.values + xref).sorted()
            val ends = boundaries.zipWithNext().toMap()
            entries = starts.mapValues { (_, offset) -> Entry(offset, ends.getValue(offset)) }
            objectIds = starts.keys.sorted()
            val root = dictionary(readObject(catalog).also { require(it.streamStart == null) { unsupported("Catalog 스트림") } }.tokens)
            require(single(root, "/Type") == "/Catalog") { unsupported("Catalog") }
            require(root.keys.all { it in setOf("/Type", "/Pages") }) { unsupported("단일 페이지 Catalog의 추가 구조") }
            pageTree = reference(required(root, "/Pages"))
            val tree = dictionary(readObject(pageTree).also { require(it.streamStart == null) { unsupported("Pages 스트림") } }.tokens)
            require(single(tree, "/Type") == "/Pages" && single(tree, "/Count") == "1") { unsupported("단일 페이지 트리") }
            require(tree.keys.all { it in setOf("/Type", "/Count", "/Kids") }) { unsupported("상속 속성이 있는 페이지 트리") }
            val kids = required(tree, "/Kids")
            require(kids.firstOrNull() == "[" && kids.lastOrNull() == "]") { unsupported("페이지 목록") }
            page = reference(kids.subList(1, kids.size - 1))
            require(page != catalog && page != pageTree && catalog != pageTree) { unsupported("순환 페이지 트리") }
            val pageValues = dictionary(readObject(page).also { require(it.streamStart == null) { unsupported("Page 스트림") } }.tokens)
            require(single(pageValues, "/Type") == "/Page" && reference(required(pageValues, "/Parent")) == pageTree) { unsupported("페이지 Parent") }
            require(pageValues.containsKey("/MediaBox") && pageValues.containsKey("/Resources")) { unsupported("페이지의 용지/리소스 정보") }
        }

        fun readObject(id: Int): ObjectBody {
            val entry = entries[id] ?: error(unsupported("없는 객체 $id"))
            PdfInput(file, entry.offset, entry.end).use { input ->
                require(unsignedInt(input.token()) == id && input.token() == "0" && input.token() == "obj") { unsupported("객체 시작 위치 $id") }
                val tokens = arrayListOf<String>()
                var size = 0
                var streamStart: Long? = null
                var streamLength = 0L
                while (true) {
                    val token = input.token() ?: error(unsupported("객체 종료 누락 $id"))
                    if (token == "endobj") break
                    if (token == "stream") {
                        val values = dictionary(tokens)
                        require(singleOrNull(values, "/Type") !in setOf("/ObjStm", "/XRef")) { unsupported("압축 객체/xref 스트림") }
                        val lengthValue = values["/Length"] ?: error(unsupported("stream Length 누락"))
                        streamLength = if (lengthValue.size == 1) unsignedLong(lengthValue.single()) else integerObject(reference(lengthValue))
                        input.streamLineEnd()
                        streamStart = input.position
                        require(streamLength <= entry.end - streamStart) { unsupported("stream Length 범위") }
                        input.seek(streamStart + streamLength)
                        require(input.token() == "endstream" && input.token() == "endobj") { unsupported("stream Length/종료 불일치") }
                        break
                    }
                    size += token.length
                    require(size <= MAX_OBJECT_SYNTAX) { unsupported("단일 객체의 과도한 구문 크기") }
                    tokens.add(token)
                }
                require(input.token() == null) { unsupported("객체 끝의 추가 데이터") }
                require(tokens.isNotEmpty()) { unsupported("빈 객체") }
                valueEnd(tokens, 0).also { require(it == tokens.size) { unsupported("객체 값이 하나가 아님") } }
                return ObjectBody(tokens, streamStart, streamLength)
            }
        }

        private fun integerObject(id: Int): Long {
            val entry = entries[id] ?: error(unsupported("stream Length 참조"))
            PdfInput(file, entry.offset, entry.end).use { input ->
                require(unsignedInt(input.token()) == id && input.token() == "0" && input.token() == "obj") { unsupported("Length 객체 위치") }
                val value = unsignedLong(input.token())
                require(input.token() == "endobj" && input.token() == null) { unsupported("Length 객체 값") }
                return value
            }
        }

        fun copyStream(body: ObjectBody, output: OutputStream) {
            val start = body.streamStart ?: return
            PdfInput(file, start, start + body.streamLength).use { input -> input.copyTo(output, body.streamLength) }
        }
    }

    private data class ObjectBody(val tokens: List<String>, val streamStart: Long?, val streamLength: Long)

    private class PdfInput(file: File, start: Long, private val end: Long = file.length()) : Closeable {
        private val source = RandomAccessFile(file, "r")
        private val buffer = ByteArray(8192)
        private var cursor = 0
        private var available = 0
        var position = start
            private set
        init {
            try { require(start in 0..end && end <= source.length()) { unsupported("파일 위치") }; source.seek(start) }
            catch (error: Throwable) {
                try { source.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
        fun seek(offset: Long) { require(offset in 0..end); source.seek(offset); position = offset; cursor = 0; available = 0 }
        private fun peek(): Int {
            if (position >= end) return -1
            if (cursor == available) {
                cancelled()
                available = source.read(buffer, 0, minOf(buffer.size.toLong(), end - position).toInt())
                cursor = 0
                require(available > 0) { unsupported("파일이 잘림") }
            }
            return buffer[cursor].toInt() and 255
        }
        private fun read(): Int = peek().also { if (it >= 0) { cursor++; position++ } }
        fun token(): String? {
            while (true) {
                while (white(peek())) read()
                if (peek() != '%'.code) break
                while (peek() >= 0 && peek() != 10 && peek() != 13) read()
            }
            val first = read()
            if (first < 0) return null
            val value = StringBuilder().append(first.toChar())
            fun append() { require(value.length < MAX_OBJECT_SYNTAX) { unsupported("과도한 토큰 길이") }; value.append(read().also { require(it >= 0) { unsupported("토큰이 잘림") } }.toChar()) }
            when (first) {
                '('.code -> {
                    var depth = 1
                    while (depth > 0) {
                        val next = peek(); append()
                        when (next) {
                            '\\'.code -> { if (peek() >= 0) append() else error(unsupported("문자열 이스케이프")) }
                            '('.code -> depth++
                            ')'.code -> depth--
                        }
                    }
                }
                '<'.code -> {
                    if (peek() == '<'.code) append() else {
                        while (peek() != '>'.code) {
                            require(peek() >= 0 && (white(peek()) || peek().toChar() in "0123456789abcdefABCDEF")) { unsupported("16진 문자열") }
                            append()
                        }
                        append()
                    }
                }
                '>'.code -> { require(peek() == '>'.code) { unsupported("사전 종료") }; append() }
                '['.code, ']'.code -> Unit
                '/'.code -> while (peek() >= 0 && !white(peek()) && !delimiter(peek())) append()
                else -> {
                    require(!delimiter(first)) { unsupported("구문 구분자") }
                    while (peek() >= 0 && !white(peek()) && !delimiter(peek())) append()
                }
            }
            return value.toString()
        }
        fun dictionaryTokens(): List<String> {
            val tokens = arrayListOf<String>()
            require(token() == "<<") { unsupported("trailer 사전") }
            tokens.add("<<")
            var depth = 1
            var count = 2
            while (depth > 0) {
                val next = token() ?: error(unsupported("사전이 잘림"))
                count += next.length
                require(count <= MAX_OBJECT_SYNTAX) { unsupported("과도한 사전 크기") }
                if (next == "<<") depth++ else if (next == ">>") depth--
                tokens.add(next)
            }
            return tokens
        }
        fun streamLineEnd() {
            val next = read()
            require(next == 10 || (next == 13 && read() == 10)) { unsupported("stream 시작 줄바꿈") }
        }
        fun copyTo(output: OutputStream, length: Long) {
            var remaining = length
            while (remaining > 0) {
                cancelled()
                require(peek() >= 0) { unsupported("stream이 잘림") }
                val count = minOf((available - cursor).toLong(), remaining).toInt()
                output.write(buffer, cursor, count)
                cursor += count; position += count; remaining -= count
            }
        }
        override fun close() = source.close()
    }

    companion object {
        private const val MAX_SOURCE_OBJECTS = 100_000
        private const val MAX_OBJECT_SYNTAX = 1024 * 1024
        private val number = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
        private fun cancelled() { if (Thread.currentThread().isInterrupted) throw CancellationException("텍스트 PDF 생성을 취소했습니다.") }
        private fun unsupported(detail: String): String = "전송 실패: 이 Android 장치가 생성한 텍스트 PDF 구조를 안전하게 합칠 수 없습니다($detail). 글자를 이미지로 바꾸거나 내용을 생략하지 않았습니다."
        private fun white(value: Int): Boolean = when (value) { 0, 9, 10, 12, 13, 32 -> true; else -> false }
        private fun delimiter(value: Int): Boolean = value >= 0 && value.toChar() in "()<>[]{}/%"
        private fun isUnsigned(value: String): Boolean = value.isNotEmpty() && value.all { it in '0'..'9' }
        private fun unsignedLong(value: String?): Long {
            require(value != null && isUnsigned(value)) { unsupported("정수 값") }
            return value.toLongOrNull() ?: error(unsupported("정수 범위"))
        }
        private fun unsignedInt(value: String?): Int = unsignedLong(value).also { require(it <= Int.MAX_VALUE) { unsupported("정수 범위") } }.toInt()
        private fun reference(tokens: List<String>): Int {
            require(tokens.size == 3 && tokens[1] == "0" && tokens[2] == "R") { unsupported("객체 참조") }
            return unsignedInt(tokens[0])
        }
        private fun dictionary(tokens: List<String>): Map<String, List<String>> {
            require(tokens.firstOrNull() == "<<" && tokens.lastOrNull() == ">>") { unsupported("객체 사전") }
            val values = linkedMapOf<String, List<String>>()
            var cursor = 1
            while (cursor < tokens.size - 1) {
                val key = tokens[cursor++]
                require(key.startsWith('/') && !values.containsKey(key)) { unsupported("사전 키") }
                val end = valueEnd(tokens, cursor)
                values[key] = tokens.subList(cursor, end)
                cursor = end
            }
            require(cursor == tokens.size - 1) { unsupported("사전 종료 위치") }
            return values
        }
        private fun valueEnd(tokens: List<String>, start: Int, depth: Int = 0): Int {
            require(depth <= 100 && start < tokens.size) { unsupported("값의 깊이/범위") }
            val token = tokens[start]
            if (token == "[") {
                var cursor = start + 1
                while (tokens.getOrNull(cursor) != "]") cursor = valueEnd(tokens, cursor, depth + 1)
                return cursor + 1
            }
            if (token == "<<") {
                var cursor = start + 1
                val keys = hashSetOf<String>()
                while (tokens.getOrNull(cursor) != ">>") {
                    val key = tokens.getOrNull(cursor) ?: error(unsupported("사전 키 누락"))
                    require(key.startsWith('/') && keys.add(key)) { unsupported("사전 키") }
                    cursor = valueEnd(tokens, cursor + 1, depth + 1)
                }
                return cursor + 1
            }
            if (isUnsigned(token) && isUnsigned(tokens.getOrNull(start + 1).orEmpty()) && tokens.getOrNull(start + 2) == "R") return start + 3
            require(token.startsWith('/') || token.startsWith('(') || (token.startsWith('<') && token != "<<") || token in setOf("true", "false", "null") || number.matches(token)) { unsupported("알 수 없는 객체 값") }
            return start + 1
        }
        private fun single(values: Map<String, List<String>>, key: String): String = values[key]?.singleOrNull() ?: error(unsupported("$key 값"))
        private fun required(values: Map<String, List<String>>, key: String): List<String> = values[key] ?: error(unsupported("$key 누락"))
        private fun singleOrNull(values: Map<String, List<String>>, key: String): String? = values[key]?.singleOrNull()
        private fun footerOffset(file: File): Long {
            RandomAccessFile(file, "r").use { input ->
                require(input.length() >= 16) { unsupported("PDF 파일 길이") }
                val header = ByteArray(8); input.readFully(header)
                require(String(header, Charsets.US_ASCII).startsWith("%PDF-1.")) { unsupported("PDF 헤더") }
                val length = minOf(4096L, input.length()).toInt()
                input.seek(input.length() - length)
                val tail = ByteArray(length); input.readFully(tail)
                val match = Regex("startxref[\\x00\\t\\n\\f\\r ]+(\\d+)[\\x00\\t\\n\\f\\r ]+%%EOF[\\x00\\t\\n\\f\\r ]*$").find(String(tail, Charsets.ISO_8859_1))
                    ?: error(unsupported("PDF 종료/xref 위치"))
                return unsignedLong(match.groupValues[1]).also { require(it in 1 until input.length()) { unsupported("xref 범위") } }
            }
        }
    }
}
