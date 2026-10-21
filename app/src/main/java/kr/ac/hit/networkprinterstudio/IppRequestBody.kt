package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/** IPP attributes and document bytes are adjacent on the wire, not in a copied array. */
internal class IppRequestBody(
    private val header: ByteArray,
    private val document: DocumentData? = null,
) : DocumentData {
    init {
        require(document == null || document.length >= 0) { "문서 길이는 0 이상이어야 합니다" }
    }

    override val length: Long = Math.addExact(header.size.toLong(), document?.length ?: 0L)

    override fun openStream(): InputStream {
        val prefix = ByteArrayInputStream(header)
        return document?.let { SequenceInputStream(prefix, it.openStream()) } ?: prefix
    }
}
