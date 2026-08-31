package kr.ac.hit.networkprinterstudio

import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

internal data class HttpAuthChallenge(
    val scheme: String,
    val parameters: Map<String, String>,
) {
    val realm: String? get() = parameters["realm"]
}

internal object IppHttpAuthentication {
    private val random = SecureRandom()

    fun parseChallenges(headers: List<String>): List<HttpAuthChallenge> =
        headers.flatMap(::splitChallenges).mapNotNull { value ->
            val scheme = value.substringBefore(' ').trim()
            if (scheme.isBlank()) return@mapNotNull null
            val parameters = parseParameters(value.substringAfter(' ', ""))
            HttpAuthChallenge(scheme, parameters)
        }

    fun publicChallenge(challenges: List<HttpAuthChallenge>): IppAuthChallenge {
        val schemes = challenges.map { it.scheme }.distinctBy { it.lowercase(Locale.US) }
        return IppAuthChallenge(
            schemes = schemes,
            realm = challenges.firstNotNullOfOrNull { it.realm },
        )
    }

    fun authorization(
        challenge: HttpAuthChallenge,
        credentials: IppCredentials,
        method: String,
        url: URL,
        body: ByteArray,
    ): String? = when {
        challenge.scheme.equals("Basic", ignoreCase = true) ->
            basic(credentials)
        challenge.scheme.equals("Digest", ignoreCase = true) ->
            digest(challenge, credentials, method, url, body)
        else -> null
    }

    fun basic(credentials: IppCredentials): String {
        val token = "${credentials.username}:${credentials.password}"
            .toByteArray(StandardCharsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(token)}"
    }

    private fun digest(
        challenge: HttpAuthChallenge,
        credentials: IppCredentials,
        method: String,
        url: URL,
        body: ByteArray,
    ): String? {
        val realm = challenge.parameters["realm"] ?: return null
        val nonce = challenge.parameters["nonce"] ?: return null
        val algorithmToken = challenge.parameters["algorithm"] ?: "MD5"
        val algorithm = algorithmToken.uppercase(Locale.US)
        val digestName = when {
            algorithm.startsWith("MD5") -> "MD5"
            algorithm.startsWith("SHA-256") -> "SHA-256"
            algorithm.startsWith("SHA-512-256") -> "SHA-512/256"
            else -> return null
        }
        val charset: Charset =
            if (challenge.parameters["charset"].equals("UTF-8", ignoreCase = true)) {
                StandardCharsets.UTF_8
            } else {
                StandardCharsets.ISO_8859_1
            }
        val requestTarget = url.file.ifBlank { "/" }
        val cnonce = ByteArray(16).also(random::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val qopOptions = challenge.parameters["qop"]
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.US) }
            .orEmpty()
        val qop = when {
            "auth" in qopOptions -> "auth"
            "auth-int" in qopOptions -> "auth-int"
            qopOptions.isEmpty() -> null
            else -> return null
        }
        val nc = "00000001"

        fun hash(value: ByteArray): String =
            MessageDigest.getInstance(digestName).digest(value).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

        fun hash(value: String): String = hash(value.toByteArray(charset))

        var ha1 = hash("${credentials.username}:$realm:${credentials.password}")
        if (algorithm.endsWith("-SESS")) {
            ha1 = hash("$ha1:$nonce:$cnonce")
        }
        val ha2 = if (qop == "auth-int") {
            hash("$method:$requestTarget:${hash(body)}")
        } else {
            hash("$method:$requestTarget")
        }
        val response = if (qop == null) {
            hash("$ha1:$nonce:$ha2")
        } else {
            hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        }
        val userhash = challenge.parameters["userhash"].equals("true", ignoreCase = true)
        val transmittedUsername = if (userhash) {
            hash("${credentials.username}:$realm")
        } else {
            credentials.username
        }

        return buildString {
            append("Digest username=\"").append(escape(transmittedUsername)).append('"')
            append(", realm=\"").append(escape(realm)).append('"')
            append(", nonce=\"").append(escape(nonce)).append('"')
            append(", uri=\"").append(escape(requestTarget)).append('"')
            append(", response=\"").append(response).append('"')
            append(", algorithm=").append(algorithmToken)
            challenge.parameters["opaque"]?.let {
                append(", opaque=\"").append(escape(it)).append('"')
            }
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append('"')
            }
            if (userhash) append(", userhash=true")
        }
    }

    private fun splitChallenges(header: String): List<String> {
        val starts = Regex("""(?i)(?:^|,\s*)(Basic|Digest)\s+""")
            .findAll(header)
            .map { match ->
                val prefix = match.value
                val schemeOffset = prefix.indexOfFirst { it.isLetter() }
                (match.range.first + schemeOffset) to match.groupValues[1]
            }
            .toList()
        if (starts.isEmpty()) return listOf(header.trim()).filter { it.isNotEmpty() }
        return starts.mapIndexed { index, (start, _) ->
            val end = starts.getOrNull(index + 1)?.first ?: header.length
            header.substring(start, end).trim().trimEnd(',')
        }
    }

    private fun parseParameters(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index].isWhitespace() || value[index] == ',')) index++
            val keyStart = index
            while (index < value.length && value[index] != '=' && value[index] != ',') index++
            if (index >= value.length || value[index] != '=') break
            val key = value.substring(keyStart, index).trim().lowercase(Locale.US)
            index++
            val parsed = if (index < value.length && value[index] == '"') {
                index++
                val out = StringBuilder()
                while (index < value.length) {
                    val character = value[index++]
                    when {
                        character == '\\' && index < value.length -> out.append(value[index++])
                        character == '"' -> break
                        else -> out.append(character)
                    }
                }
                out.toString()
            } else {
                val start = index
                while (index < value.length && value[index] != ',') index++
                value.substring(start, index).trim()
            }
            if (key.isNotBlank()) result[key] = parsed
        }
        return result
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
