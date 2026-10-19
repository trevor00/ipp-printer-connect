package kr.ac.hit.networkprinterstudio

import java.net.URI

data class PrinterEndpoint(
    val host: String,
    val port: Int = 631,
    val path: String = "/ipp/print",
    val secure: Boolean = false,
    val ippMajor: Int = 2,
    val ippMinor: Int = 0,
) {
    val uri: String get() = "${if (secure) "ipps" else "ipp"}://$host:$port$path"
    val key: String get() = uri
    val ippVersion: String get() = "$ippMajor.$ippMinor"

    companion object {
        fun parse(value: String): PrinterEndpoint {
            val uri = URI(value.trim())
            val scheme = uri.scheme?.lowercase() ?: error("URI 스킴이 필요합니다")
            require(scheme in setOf("ipp", "ipps", "http", "https")) {
                "ipp://, ipps://, http://, https:// URI만 지원합니다"
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: error("프린터 호스트가 필요합니다")
            val secure = scheme == "ipps" || scheme == "https"
            val port = if (uri.port > 0) uri.port else when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> 631
            }
            val path = uri.rawPath?.takeIf { it.startsWith('/') && it.isNotBlank() } ?: "/ipp/print"
            return PrinterEndpoint(host, port, path, secure)
        }
    }
}

data class PrinterRecord(
    val endpoint: PrinterEndpoint,
    val name: String,
    val model: String,
    val location: String,
    val formats: List<String>,
    val colorModes: List<String>,
    val defaultColorMode: String?,
    val operations: List<Int>,
    val source: String,
    val available: Boolean = true,
    val statusDetail: String? = null,
    val media: List<String> = emptyList(),
    val defaultMedia: String? = null,
    val printScalings: List<String> = emptyList(),
    val defaultPrintScaling: String? = null,
    val sides: List<String> = emptyList(),
    val defaultSides: String? = null,
    val orientations: List<Int> = emptyList(),
    val defaultOrientation: Int? = null,
    val printQualities: List<Int> = emptyList(),
    val defaultPrintQuality: Int? = null,
    val resolutions: List<IppResolution> = emptyList(),
    val defaultResolution: IppResolution? = null,
    val numberUps: List<Int> = emptyList(),
    val defaultNumberUp: Int? = null,
    val xImagePositions: List<String> = emptyList(),
    val defaultXImagePosition: String? = null,
    val yImagePositions: List<String> = emptyList(),
    val defaultYImagePosition: String? = null,
    val copiesSupported: IppRange? = null,
    val pageRangesSupported: Boolean = false,
    val uriAuthentication: List<String> = emptyList(),
    val uriSecurity: List<String> = emptyList(),
    val authChallenge: IppAuthChallenge? = null,
    /** IPP media-*-margin-supported values, in hundredths of a millimetre. */
    val mediaLeftMargins: List<Int> = emptyList(),
    val mediaRightMargins: List<Int> = emptyList(),
    val mediaTopMargins: List<Int> = emptyList(),
    val mediaBottomMargins: List<Int> = emptyList(),
) {
    val supportsPrintJob: Boolean get() = operations.contains(2)
    val supportsPrintUri: Boolean get() = operations.contains(3)
    val supportsValidateJob: Boolean get() = operations.contains(4)
    val supportsCreateSendDocument: Boolean get() = operations.contains(5) && operations.contains(6)
    val supportsCreateSendUri: Boolean get() = operations.contains(5) && operations.contains(7)
    val supportedSubmissionMethods: List<String> get() = buildList {
        if (supportsCreateSendDocument) add("Create-Job + Send-Document")
        if (supportsPrintJob) add("Print-Job")
        if (supportsCreateSendUri) add("Create-Job + Send-URI")
        if (supportsPrintUri) add("Print-URI")
    }
    override fun toString(): String = buildString {
        append(if (name.isBlank()) endpoint.host else name)
        if (model.isNotBlank()) append(" · $model")
        append("\n${endpoint.uri} · IPP ${endpoint.ippVersion} · $source")
    }
}

data class CupsPrinterEndpoint(val endpoint: PrinterEndpoint)

data class IppResponse(val status: Int, val requestId: Int, val attributes: Map<String, List<Any>>)

data class IppCredentials(
    val username: String,
    val password: String,
    val preemptiveBasic: Boolean = false,
)

data class IppAuthChallenge(
    val schemes: List<String>,
    val realm: String? = null,
    val supportedByApp: Boolean = schemes.any {
        it.equals("Basic", ignoreCase = true) ||
            it.equals("Digest", ignoreCase = true)
    },
) {
    val label: String
        get() = buildString {
            append(schemes.ifEmpty { listOf("방식 미확인") }.joinToString(" / "))
            if (!realm.isNullOrBlank()) append(" · realm $realm")
        }
}

data class PreparedDocument(
    val bytes: ByteArray,
    val mime: String,
    val displayName: String,
    /** false면 프린터가 광고한 원본 MIME을 그대로 보내며 배치는 IPP 옵션에 맡긴다. */
    val layoutBakedIn: Boolean = true,
)

data class IppResolution(val x: Int, val y: Int, val units: Int) {
    val label: String get() = if (x == y) "$x${if (units == 4) "dpcm" else "dpi"}" else "${x}×${y}${if (units == 4) "dpcm" else "dpi"}"
}

data class IppRange(val lower: Int, val upper: Int)

data class IppJobOptions(
    val copies: Int = 1,
    val pageRanges: List<IppRange> = emptyList(),
    val colorMode: String? = null,
    val media: String? = null,
    val printScaling: String? = null,
    val sides: String? = null,
    val orientation: Int? = null,
    val printQuality: Int? = null,
    val resolution: IppResolution? = null,
    val numberUp: Int? = null,
    val xImagePosition: String? = null,
    val yImagePosition: String? = null,
)

enum class JobSubmissionMethod {
    AUTO,
    CREATE_JOB_SEND_DOCUMENT,
    PRINT_JOB,
}

data class BgpScope(val egressIp: String, val prefix: String)

data class NetworkSnapshot(
    val localIp: String,
    val connectedCidr: String,
    val gatewayIp: String?,
    val connectionType: String,
)

data class PrintResult(
    val accepted: Boolean,
    val httpStatus: Int?,
    val ippStatus: Int?,
    val jobId: Int?,
    val error: String?,
    val method: String? = null,
    val jobUri: String? = null,
    val authRequired: Boolean = false,
    val authChallenge: IppAuthChallenge? = null,
)
