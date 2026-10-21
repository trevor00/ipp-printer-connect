package kr.ac.hit.networkprinterstudio

import java.io.File
import java.io.FilterReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal enum class TextFileEncoding {
    AUTO, UTF8, CP949, UTF16_LE, UTF16_BE,
}

internal data class DecodedText(val text: String, val charsetName: String)

internal data class ValidatedTextSource(
    val file: File,
    val charsetName: String,
    val hasVisibleCharacters: Boolean,
    internal val encoding: TextFileEncoding,
    internal val bomBytes: Int,
    internal val length: Long,
    internal val modified: Long,
)

internal object TextFileDecoder {
    /** Complete validation happens before any page is emitted, including UTF-8 -> CP949 retry. */
    fun validate(file: File): ValidatedTextSource {
        checkInterrupted()
        val length = file.length()
        val modified = file.lastModified()
        val bom = readBom(file)
        fun result(encoding: TextFileEncoding, offset: Int): ValidatedTextSource {
            val visible = validateStrict(file, encoding, offset)
            require(file.length() == length && file.lastModified() == modified) { "검증 중 텍스트 파일이 변경되었습니다. 파일을 다시 선택해 주세요." }
            return ValidatedTextSource(file, encoding.charsetName(), visible, encoding, offset, length, modified)
        }
        if (bom != null) {
            try { return result(bom.encoding, bom.length) }
            catch (error: CharacterCodingException) { throw decodingError(bom.encoding, error) }
        }
        try { return result(TextFileEncoding.UTF8, 0) }
        catch (_: CharacterCodingException) { /* Only decoding failures allow another encoding. */ }
        checkInterrupted()
        try { return result(TextFileEncoding.CP949, 0) }
        catch (error: CharacterCodingException) {
            throw IllegalArgumentException("텍스트 인코딩을 확인할 수 없습니다. UTF-8로 저장한 파일을 다시 선택해 주세요.", error)
        }
    }

    fun openReader(source: ValidatedTextSource): Reader {
        checkInterrupted()
        require(source.file.length() == source.length && source.file.lastModified() == source.modified) {
            "검증 후 텍스트 파일이 변경되었습니다. 파일을 다시 선택해 주세요."
        }
        return strictReader(source.file, source.encoding, source.bomBytes)
    }

    /** 인코딩 판별을 위해 원본 파일 전체를 별도의 바이트 배열로 복사하지 않는다. */
    fun decode(file: File): DecodedText {
        checkInterrupted()
        val bom = readBom(file)
        if (bom != null) {
            try {
                return decodeStrict(file, bom.encoding, bom.length)
            } catch (error: CharacterCodingException) {
                throw decodingError(bom.encoding, error)
            }
        }

        try {
            return decodeStrict(file, TextFileEncoding.UTF8)
        } catch (_: CharacterCodingException) {
            // 파일 읽기 오류나 작업 취소는 인코딩 오류로 취급하지 않는다.
        }
        checkInterrupted()
        try {
            return decodeStrict(file, TextFileEncoding.CP949)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException(
                "텍스트 인코딩을 확인할 수 없습니다. UTF-8로 저장한 파일을 다시 선택해 주세요.", error,
            )
        }
    }

    fun decode(bytes: ByteArray, encoding: TextFileEncoding = TextFileEncoding.AUTO): DecodedText {
        val bom = detectBom(bytes)
        if (encoding != TextFileEncoding.AUTO) {
            require(bom == null || bom.encoding == encoding) {
                "파일의 인코딩 표시(${bom!!.encoding.charsetName()})와 읽기 방식(${encoding.charsetName()})이 다릅니다. UTF-8로 저장한 파일을 다시 선택해 주세요."
            }
            return decodeStrict(bytes, encoding, bom?.length ?: 0)
        }
        if (bom != null) return decodeStrict(bytes, bom.encoding, bom.length)

        try {
            return decodeStrict(bytes, TextFileEncoding.UTF8)
        } catch (_: IllegalArgumentException) {
            // Without a BOM, prefer valid UTF-8, then the common Korean Windows encoding.
        }
        try {
            return decodeStrict(bytes, TextFileEncoding.CP949)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "텍스트 인코딩을 확인할 수 없습니다. UTF-8로 저장한 파일을 다시 선택해 주세요.", error,
            )
        }
    }

    private fun detectBom(bytes: ByteArray): Bom? = when {
        bytes.startsWith(0x00, 0x00, 0xfe, 0xff) || bytes.startsWith(0xff, 0xfe, 0x00, 0x00) ->
            throw IllegalArgumentException("UTF-32 텍스트는 지원하지 않습니다. UTF-8 또는 UTF-16으로 저장한 파일을 선택해 주세요.")
        bytes.startsWith(0xef, 0xbb, 0xbf) -> Bom(TextFileEncoding.UTF8, 3)
        bytes.startsWith(0xff, 0xfe) -> Bom(TextFileEncoding.UTF16_LE, 2)
        bytes.startsWith(0xfe, 0xff) -> Bom(TextFileEncoding.UTF16_BE, 2)
        else -> null
    }

    private fun readBom(file: File): Bom? = file.inputStream().buffered().use { input ->
        val prefix = ByteArray(4)
        var count = 0
        while (count < prefix.size) {
            checkInterrupted()
            val read = input.read(prefix, count, prefix.size - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        checkInterrupted()
        detectBom(prefix.copyOf(count))
    }

    private fun validateStrict(file: File, encoding: TextFileEncoding, offset: Int): Boolean {
        var visible = false
        strictReader(file, encoding, offset).use { reader ->
            val characters = CharArray(8192)
            while (true) {
                checkInterrupted()
                val count = reader.read(characters)
                if (count < 0) break
                for (index in 0 until count) if (!characters[index].isWhitespace()) visible = true
            }
        }
        checkInterrupted()
        return visible
    }

    private fun strictReader(file: File, encoding: TextFileEncoding, offset: Int): Reader {
        checkInterrupted()
        val decoder = Charset.forName(if (encoding == TextFileEncoding.CP949) "MS949" else encoding.charsetName()).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = file.inputStream().buffered()
        try {
            repeat(offset) {
                checkInterrupted()
                if (input.read() < 0) throw java.io.EOFException("텍스트 인코딩 표시를 읽는 중 파일이 끝났습니다.")
            }
            return object : FilterReader(InputStreamReader(input, decoder)) {
                override fun read(): Int { checkInterrupted(); return super.read().also { checkInterrupted() } }
                override fun read(characters: CharArray, offset: Int, length: Int): Int {
                    checkInterrupted()
                    return super.read(characters, offset, length).also { checkInterrupted() }
                }
            }
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    @Throws(CharacterCodingException::class)
    private fun decodeStrict(file: File, encoding: TextFileEncoding, offset: Int = 0): DecodedText {
        checkInterrupted()
        val name = encoding.charsetName()
        val decoder = Charset.forName(if (encoding == TextFileEncoding.CP949) "MS949" else name).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = StringBuilder()
        file.inputStream().buffered().use { input ->
            repeat(offset) {
                checkInterrupted()
                if (input.read() < 0) throw java.io.EOFException("텍스트 인코딩 표시를 읽는 중 파일이 끝났습니다.")
            }
            checkInterrupted()
            InputStreamReader(input, decoder).use { reader ->
                val characters = CharArray(8 * 1024)
                while (true) {
                    checkInterrupted()
                    val count = reader.read(characters)
                    checkInterrupted()
                    if (count < 0) break
                    text.append(characters, 0, count)
                }
            }
        }
        checkInterrupted()
        val decoded = text.toString()
        checkInterrupted()
        return DecodedText(decoded, name)
    }

    private fun decodeStrict(bytes: ByteArray, encoding: TextFileEncoding, offset: Int = 0): DecodedText {
        val name = encoding.charsetName()
        val charset = Charset.forName(if (encoding == TextFileEncoding.CP949) "MS949" else name)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            val text = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            return DecodedText(text, name)
        } catch (error: CharacterCodingException) {
            throw decodingError(encoding, error)
        }
    }

    private fun decodingError(encoding: TextFileEncoding, error: CharacterCodingException) = IllegalArgumentException(
        "${encoding.charsetName()} 인코딩으로 텍스트를 읽을 수 없습니다. 파일이 손상되었는지 확인하거나 UTF-8로 저장한 파일을 다시 선택해 주세요.", error,
    )

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("텍스트 파일 읽기가 취소되었습니다.")
    }

    private fun TextFileEncoding.charsetName(): String = when (this) {
        TextFileEncoding.AUTO -> error("자동 감지는 인코딩 이름이 없습니다.")
        TextFileEncoding.UTF8 -> "UTF-8"
        TextFileEncoding.CP949 -> "CP949"
        TextFileEncoding.UTF16_LE -> "UTF-16LE"
        TextFileEncoding.UTF16_BE -> "UTF-16BE"
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }

    private data class Bom(val encoding: TextFileEncoding, val length: Int)
}
