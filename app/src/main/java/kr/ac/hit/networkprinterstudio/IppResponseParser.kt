package kr.ac.hit.networkprinterstudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object IppResponseParser {
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    fun parse(data: ByteArray): IppResponse {
        require(data.size in 9..MAX_RESPONSE_BYTES) { "IPP 응답 크기가 올바르지 않습니다" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val major = buffer.get().toInt() and 255
        buffer.get()
        require(major in 1..2) { "지원하지 않는 IPP 응답 버전입니다" }
        val status = buffer.short.toInt() and 65535
        val id = buffer.int
        val groups = mutableListOf<IppAttributeGroup>()
        var currentGroup: MutableMap<String, MutableList<Any>>? = null
        var name = ""
        var ended = false
        fun bytes(): ByteArray {
            require(buffer.remaining() >= 2) { "IPP 응답 길이가 잘렸습니다" }
            val count = buffer.short.toInt() and 65535
            require(buffer.remaining() >= count) { "IPP 응답 값이 잘렸습니다" }
            return ByteArray(count).also(buffer::get)
        }
        fun scalar(tag: Int, value: ByteArray): Any = when (tag) {
            0x21, 0x23 -> {
                require(value.size == 4) { "IPP 정수 길이가 올바르지 않습니다" }
                ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int
            }
            0x22 -> {
                require(value.size == 1 && value[0].toInt() in 0..1) { "IPP 불리언 값이 올바르지 않습니다" }
                value[0].toInt() == 1
            }
            0x32 -> {
                require(value.size == 9) { "IPP 해상도 길이가 올바르지 않습니다" }
                ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).let { IppResolution(it.int, it.int, it.get().toInt() and 255) }
            }
            0x33 -> {
                require(value.size == 8) { "IPP 범위 길이가 올바르지 않습니다" }
                ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).let { IppRange(it.int, it.int) }
            }
            else -> value.toString(Charsets.UTF_8)
        }
        fun collection(depth: Int): IppCollection {
            require(depth <= 8) { "IPP 컬렉션 중첩이 너무 깊습니다" }
            val members = linkedMapOf<String, MutableList<Any>>()
            var member = ""
            while (buffer.hasRemaining()) {
                val tag = buffer.get().toInt() and 255
                require(tag > 0x0f) { "IPP 컬렉션이 잘렸습니다" }
                require(bytes().isEmpty()) { "IPP 컬렉션 이름 필드가 올바르지 않습니다" }
                val value = bytes()
                if (tag == 0x37) {
                    require(value.isEmpty()) { "IPP 컬렉션 끝 값이 올바르지 않습니다" }
                    return IppCollection(members)
                }
                if (tag == 0x4a) {
                    member = value.toString(Charsets.UTF_8)
                    require(member.isNotBlank()) { "IPP 컬렉션 멤버 이름이 없습니다" }
                } else {
                    require(member.isNotBlank()) { "IPP 컬렉션 멤버가 없습니다" }
                    val decoded = if (tag == 0x34) {
                        require(value.isEmpty()); collection(depth + 1)
                    } else scalar(tag, value)
                    members.getOrPut(member) { mutableListOf() }.add(decoded)
                }
            }
            error("IPP 컬렉션 응답이 잘렸습니다")
        }
        while (buffer.hasRemaining()) {
            val tag = buffer.get().toInt() and 255
            if (tag == 3) { ended = true; break }
            if (tag <= 0x0f) {
                require(tag in setOf(1, 2, 4, 5, 6, 7, 9, 10)) { "알 수 없는 IPP 속성 그룹입니다" }
                val attrs = linkedMapOf<String, MutableList<Any>>()
                currentGroup = attrs
                groups.add(IppAttributeGroup(tag, attrs))
                name = ""
                continue
            }
            val attrs = requireNotNull(currentGroup) { "IPP 속성 그룹이 없습니다" }
            val nextName = bytes().toString(Charsets.UTF_8)
            if (nextName.isNotEmpty()) name = nextName
            require(name.isNotBlank()) { "IPP 속성 이름이 없습니다" }
            val raw = bytes()
            val value = if (tag == 0x34) { require(raw.isEmpty()); collection(1) } else scalar(tag, raw)
            attrs.getOrPut(name) { mutableListOf() }.add(value)
        }
        require(ended && groups.isNotEmpty()) { "IPP 응답이 끝나기 전에 연결이 종료되었습니다" }
        val flat = linkedMapOf<String, MutableList<Any>>()
        groups.forEach { group -> group.attributes.forEach { (key, value) -> flat.getOrPut(key) { mutableListOf() }.addAll(value) } }
        return IppResponse(status, id, flat, groups)
    }
}
