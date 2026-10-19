package kr.ac.hit.networkprinterstudio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal enum class TextFileEncoding {
    AUTO, UTF8, CP949, UTF16_LE, UTF16_BE,
}

internal data class DecodedText(val text: String, val charsetName: String)

internal object TextFileDecoder {
    fun decode(bytes: ByteArray, encoding: TextFileEncoding = TextFileEncoding.AUTO): DecodedText {
        val bom = when {
            bytes.startsWith(0x00, 0x00, 0xfe, 0xff) || bytes.startsWith(0xff, 0xfe, 0x00, 0x00) ->
                throw IllegalArgumentException("UTF-32 텍스트는 지원하지 않습니다. UTF-8 또는 UTF-16으로 저장한 파일을 선택해 주세요.")
            bytes.startsWith(0xef, 0xbb, 0xbf) -> Bom(TextFileEncoding.UTF8, 3)
            bytes.startsWith(0xff, 0xfe) -> Bom(TextFileEncoding.UTF16_LE, 2)
            bytes.startsWith(0xfe, 0xff) -> Bom(TextFileEncoding.UTF16_BE, 2)
            else -> null
        }
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
            throw IllegalArgumentException(
                "$name 인코딩으로 텍스트를 읽을 수 없습니다. 파일이 손상되었는지 확인하거나 UTF-8로 저장한 파일을 다시 선택해 주세요.", error,
            )
        }
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
