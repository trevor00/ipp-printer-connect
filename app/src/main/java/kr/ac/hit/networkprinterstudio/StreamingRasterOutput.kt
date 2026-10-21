package kr.ac.hit.networkprinterstudio

import java.io.OutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToLong

/** A band is a memory boundary, not a change in the requested print resolution. */
internal object RasterBandPolicy {
    const val MAX_BAND_BYTES = 4 * 1024 * 1024
    fun dimensions(widthPoints: Int, heightPoints: Int, dpi: Int): Pair<Int, Int> {
        require(widthPoints > 0 && heightPoints > 0 && dpi > 0) { "용지 크기와 해상도는 0보다 커야 합니다" }
        val width = (widthPoints.toDouble() * dpi / 72).roundToLong().coerceAtLeast(1)
        val height = (heightPoints.toDouble() * dpi / 72).roundToLong().coerceAtLeast(1)
        require(width <= Int.MAX_VALUE / 4 && height <= Int.MAX_VALUE) { "선택한 해상도의 페이지 크기를 처리할 수 없습니다. 해상도를 직접 변경해 주세요." }
        return width.toInt() to height.toInt()
    }
    fun rows(width: Int, remaining: Int): Int {
        require(width > 0 && width <= Int.MAX_VALUE / 4 && remaining > 0)
        return minOf(128, remaining, (MAX_BAND_BYTES / (width * 4)).coerceAtLeast(1))
    }
}

/** Incremental ASCII85; finish deliberately leaves the document stream open. */
internal class Ascii85OutputStream(private val destination: OutputStream) : OutputStream() {
    private var value = 0L
    private var count = 0
    private var column = 0
    private var finished = false
    private val encoded = IntArray(5)
    override fun write(value: Int) {
        check(!finished)
        this.value = (this.value shl 8) or (value.toLong() and 255)
        count++
        if (count == 4) emitGroup()
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        for (index in offset until offset + length) write(bytes[index].toInt())
    }
    private fun emit(character: Int) {
        destination.write(character)
        if (++column == 75) { destination.write(10); column = 0 }
    }
    private fun emitGroup() {
        if (count == 4 && value == 0L) emit('z'.code) else {
            var remaining = value shl ((4 - count) * 8)
            for (index in 4 downTo 0) { encoded[index] = (remaining % 85).toInt() + 33; remaining /= 85 }
            for (index in 0..count) emit(encoded[index])
        }
        count = 0; value = 0
    }
    fun finish() {
        if (finished) return
        if (count > 0) emitGroup()
        destination.write('~'.code); destination.write('>'.code)
        finished = true
    }
    override fun close() = finish()
}

/** Disk-backed indexes keep document length out of the raster working set. */
internal class StreamingRasterPdfWriter(destination: OutputStream) : java.io.Closeable {
    private class Counter(val destination: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(value: Int) { destination.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { destination.write(bytes, offset, length); count += length }
        override fun close() { flush() }
        override fun flush() = destination.flush()
    }
    private val output = Counter(destination)
    private val files = mutableListOf<java.io.File>()
    private val handles = mutableListOf<java.io.RandomAccessFile>()
    private fun scratch(): java.io.RandomAccessFile {
        val directory = runCatching { PrintCache.directory }.getOrNull()
        val file = java.io.File.createTempFile("ipp-raster-index-", ".tmp", directory)
        files.add(file)
        return java.io.RandomAccessFile(file, "rw").also(handles::add)
    }
    private lateinit var offsets: java.io.RandomAccessFile
    private lateinit var pages: java.io.RandomAccessFile
    private lateinit var resources: java.io.RandomAccessFile
    private lateinit var commands: java.io.RandomAccessFile
    private var objectCount = 3
    private var pageCount = 0
    private var resourceCount = 0
    private var pageWidth = 0
    private var pageHeight = 0
    private var closed = false
    init {
        try {
            offsets = scratch(); pages = scratch(); resources = scratch(); commands = scratch()
            offsets.setLength(24)
            ascii("%PDF-1.4\n% lossless print raster\n")
        } catch (error: Throwable) { close(); throw error }
    }
    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("PDF 생성을 취소했습니다")
    }
    private fun allocate(): Int {
        require(objectCount < Int.MAX_VALUE) { "PDF 객체 수가 처리 범위를 넘었습니다" }
        return objectCount++
    }
    private fun begin(id: Int) {
        cancelled(); offsets.seek(id.toLong() * 8); offsets.writeLong(output.count)
        ascii("$id 0 obj\n")
    }
    private fun ascii(text: String) = output.write(text.toByteArray(Charsets.US_ASCII))
    private fun command(text: String) = commands.write(text.toByteArray(Charsets.US_ASCII))
    fun beginPage(width: Int, height: Int, clip: FloatArray? = null) {
        check(!closed && pageWidth == 0)
        pageWidth = width; pageHeight = height; resourceCount = 0
        resources.setLength(0); resources.seek(0); commands.setLength(0); commands.seek(0)
        command("q\n")
        if (clip != null) command("${n(clip[0])} ${n(height - clip[3])} ${n(clip[2] - clip[0])} ${n(clip[3] - clip[1])} re W n\n")
    }
    fun image(width: Int, height: Int, matrix: FloatArray, writeRgb: (OutputStream) -> Unit) {
        check(!closed && pageWidth > 0); cancelled()
        val image = allocate(); val length = allocate(); val name = "Im${resourceCount++}"
        resources.writeInt(image)
        begin(image)
        ascii("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Interpolate false /Filter /FlateDecode /Length $length 0 R >>\nstream\n")
        val start = output.count
        DeflaterOutputStream(output).use(writeRgb)
        val size = output.count - start
        ascii("\nendstream\nendobj\n"); begin(length); ascii("$size\nendobj\n")
        val a = matrix[0] * width; val b = -matrix[3] * width
        val c = -matrix[1] * height; val d = matrix[4] * height
        val e = matrix[1] * height + matrix[2]
        val f = pageHeight - (matrix[4] * height + matrix[5])
        command("q ${n(a)} ${n(b)} ${n(c)} ${n(d)} ${n(e)} ${n(f)} cm /$name Do Q\n")
    }
    fun endPage() {
        check(!closed && pageWidth > 0); command("Q\n")
        val contents = allocate(); begin(contents)
        ascii("<< /Length ${commands.length()} >>\nstream\n")
        commands.seek(0)
        val buffer = ByteArray(8192)
        while (true) { cancelled(); val count = commands.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
        ascii("endstream\nendobj\n")
        val page = allocate(); begin(page)
        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] /Resources << /XObject << ")
        resources.seek(0)
        repeat(resourceCount) { index -> cancelled(); ascii("/Im$index ${resources.readInt()} 0 R ") }
        ascii(">> >> /Contents $contents 0 R >>\nendobj\n")
        pages.writeInt(page); pageCount++; pageWidth = 0
    }
    fun finish() {
        check(!closed && pageWidth == 0 && pageCount > 0)
        try {
            begin(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            begin(2); ascii("<< /Type /Pages /Count $pageCount /Kids [")
            pages.seek(0)
            repeat(pageCount) { cancelled(); ascii("${pages.readInt()} 0 R ") }
            ascii("] >>\nendobj\n")
            val xref = output.count
            ascii("xref\n0 $objectCount\n0000000000 65535 f \n")
            offsets.seek(8)
            repeat(objectCount - 1) {
                cancelled(); val offset = offsets.readLong()
                require(offset <= 9_999_999_999L) { "PDF 파일이 지원되는 크기를 초과했습니다" }
                ascii(String.format(Locale.US, "%010d 00000 n \n", offset))
            }
            ascii("trailer\n<< /Size $objectCount /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        } finally { close() }
    }
    override fun close() {
        if (closed) return
        closed = true
        handles.forEach { runCatching { it.close() } }
        files.forEach { it.delete() }
    }
    private fun n(value: Float): String {
        require(value.isFinite())
        if (value == 0f) return "0"
        return String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
