package kr.ac.hit.networkprinterstudio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object IppClient {
    private val requestIds = AtomicInteger((System.currentTimeMillis() and 0x7fffffff).toInt())
    private val credentials = ConcurrentHashMap<String, IppCredentials>()
    private val challenges = ConcurrentHashMap<String, IppAuthChallenge>()
    private val httpChallenges = ConcurrentHashMap<String, List<HttpAuthChallenge>>()

    fun setCredentials(endpoint: PrinterEndpoint, value: IppCredentials) {
        credentials[endpoint.key] = value
    }

    fun clearCredentials(endpoint: PrinterEndpoint) {
        credentials.remove(endpoint.key)
    }

    fun clearAllCredentials() {
        credentials.clear()
    }

    fun hasCredentials(endpoint: PrinterEndpoint): Boolean =
        credentials.containsKey(endpoint.key)

    fun authChallenge(endpoint: PrinterEndpoint): IppAuthChallenge? =
        challenges[endpoint.key]

    private fun attr(tag: Int, name: String, value: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(tag); write((n.size shr 8) and 255); write(n.size and 255); write(n)
            write((value.size shr 8) and 255); write(value.size and 255); write(value)
        }.toByteArray()
    }

    private fun text(tag: Int, name: String, value: String) = attr(tag, name, value.toByteArray(Charsets.UTF_8))
    private fun integer(name: String, value: Int) = attr(0x21, name, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    private fun enumValue(name: String, value: Int) = attr(0x23, name, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    private fun resolution(name: String, value: IppResolution): ByteArray = attr(
        0x32,
        name,
        ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .putInt(value.x).putInt(value.y).put(value.units.toByte()).array(),
    )
    private fun range(name: String, value: IppRange): ByteArray = attr(
        0x33,
        name,
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(value.lower).putInt(value.upper).array(),
    )

    fun getPrinterAttributes(endpoint: PrinterEndpoint, timeoutMs: Int = 1500): PrinterRecord? {
        for ((major, minor) in versionCandidates(endpoint)) {
            val versionedEndpoint = endpoint.copy(ippMajor = major, ippMinor = minor)
            try {
                val id = requestIds.incrementAndGet()
                val out = ByteArrayOutputStream()
                out.write(header(0x000b, id, major, minor)); out.write(0x01)
                out.write(text(0x47, "attributes-charset", "utf-8"))
                out.write(text(0x48, "attributes-natural-language", "en"))
                out.write(text(0x45, "printer-uri", versionedEndpoint.uri))
                out.write(text(0x42, "requesting-user-name", requestingUserName(versionedEndpoint)))
                val names = listOf(
                    "printer-name", "printer-info", "printer-location", "printer-make-and-model",
                    "printer-uri-supported", "uri-authentication-supported", "uri-security-supported",
                    "ipp-versions-supported", "ipp-features-supported", "operations-supported",
                    "document-format-supported", "compression-supported", "multiple-document-jobs-supported",
                    "print-color-mode-supported", "print-color-mode-default", "copies-supported", "page-ranges-supported",
                    "media-supported", "media-default", "sides-supported", "sides-default",
                    "orientation-requested-supported", "print-quality-supported",
                    "print-scaling-supported", "print-scaling-default", "orientation-requested-default",
                    "print-quality-default", "printer-resolution-supported", "printer-resolution-default",
                    "number-up-supported", "number-up-default", "x-image-position-supported", "x-image-position-default",
                    "y-image-position-supported", "y-image-position-default",
                    "media-left-margin-supported", "media-right-margin-supported",
                    "media-top-margin-supported", "media-bottom-margin-supported",
                    "media-col-database", "media-col-default", "pwg-raster-document-type-supported",
                    "pwg-raster-document-resolution-supported", "pwg-raster-document-sheet-back", "urf-supported"
                )
                names.forEachIndexed { index, value -> out.write(text(0x44, if (index == 0) "requested-attributes" else "", value)) }
                out.write(0x03)
                val response = send(versionedEndpoint, out.toByteArray(), timeoutMs)
                if (response.first != 200) continue
                val parsed = parse(response.second)
                if (parsed.requestId != id) continue
                if (parsed.status == IPP_VERSION_NOT_SUPPORTED) continue
                if (parsed.status !in 0x0000..0x00ff) return null
                fun strings(name: String) = parsed.attributes[name].orEmpty().map { it.toString() }
                fun first(name: String) = strings(name).firstOrNull().orEmpty()
                fun integers(name: String) = parsed.attributes[name].orEmpty().mapNotNull { it as? Int }
                fun integer(name: String) = integers(name).firstOrNull()
                fun resolutions(name: String) = parsed.attributes[name].orEmpty().mapNotNull { it as? IppResolution }
                fun ranges(name: String) = parsed.attributes[name].orEmpty().mapNotNull { it as? IppRange }
                fun boolean(name: String) = parsed.attributes[name].orEmpty().firstOrNull() as? Boolean
                val operations = parsed.attributes["operations-supported"].orEmpty().mapNotNull { it as? Int }
                return PrinterRecord(
                    endpoint = versionedEndpoint,
                    name = first("printer-name"),
                    model = first("printer-make-and-model").ifBlank { first("printer-info") },
                    location = first("printer-location"),
                    formats = strings("document-format-supported"),
                    colorModes = strings("print-color-mode-supported"),
                    defaultColorMode = first("print-color-mode-default").ifBlank { null },
                    operations = operations,
                    source = "active-ipp",
                    media = strings("media-supported"),
                    defaultMedia = first("media-default").ifBlank { null },
                    printScalings = strings("print-scaling-supported"),
                    defaultPrintScaling = first("print-scaling-default").ifBlank { null },
                    sides = strings("sides-supported"),
                    defaultSides = first("sides-default").ifBlank { null },
                    orientations = integers("orientation-requested-supported"),
                    defaultOrientation = integer("orientation-requested-default"),
                    printQualities = integers("print-quality-supported"),
                    defaultPrintQuality = integer("print-quality-default"),
                    resolutions = resolutions("printer-resolution-supported"),
                    defaultResolution = resolutions("printer-resolution-default").firstOrNull(),
                    numberUps = integers("number-up-supported"),
                    defaultNumberUp = integer("number-up-default"),
                    xImagePositions = strings("x-image-position-supported"),
                    defaultXImagePosition = first("x-image-position-default").ifBlank { null },
                    yImagePositions = strings("y-image-position-supported"),
                    defaultYImagePosition = first("y-image-position-default").ifBlank { null },
                    copiesSupported = ranges("copies-supported").firstOrNull(),
                    pageRangesSupported = boolean("page-ranges-supported") == true,
                    uriAuthentication = strings("uri-authentication-supported"),
                    uriSecurity = strings("uri-security-supported"),
                    authChallenge = authChallenge(versionedEndpoint),
                    mediaLeftMargins = integers("media-left-margin-supported"),
                    mediaRightMargins = integers("media-right-margin-supported"),
                    mediaTopMargins = integers("media-top-margin-supported"),
                    mediaBottomMargins = integers("media-bottom-margin-supported"),
                    mediaCollections = parsed.attributes["media-col-database"].orEmpty().filterIsInstance<IppCollection>(),
                    defaultMediaCollection = parsed.attributes["media-col-default"]?.firstOrNull() as? IppCollection,
                    pwgResolutions = resolutions("pwg-raster-document-resolution-supported"),
                    pwgTypes = strings("pwg-raster-document-type-supported"),
                    pwgSheetBack = first("pwg-raster-document-sheet-back").ifBlank { null },
                    urfSupported = strings("urf-supported"),
                )
            } catch (_: IppAuthenticationRequiredException) {
                return null
            } catch (_: Exception) {
                // Some IPP 1.x devices close the connection instead of returning
                // server-error-version-not-supported. Try the next standard version.
            }
        }
        return null
    }

    /** CUPS-Get-Printers is a read-only IPP extension that reveals queue URIs. */
    fun getCupsPrinterEndpoints(
        host: String,
        port: Int = 631,
        secure: Boolean = false,
        timeoutMs: Int = 1500,
    ): List<CupsPrinterEndpoint> {
        for ((major, minor) in STANDARD_VERSIONS) {
            try {
                val id = requestIds.incrementAndGet()
                val queryEndpoint = PrinterEndpoint(host, port, "/", secure = secure, ippMajor = major, ippMinor = minor)
                val out = ByteArrayOutputStream()
                out.write(header(0x4002, id, major, minor)); out.write(0x01)
                out.write(text(0x47, "attributes-charset", "utf-8"))
                out.write(text(0x48, "attributes-natural-language", "en"))
                out.write(text(0x42, "requesting-user-name", requestingUserName(queryEndpoint)))
                out.write(text(0x44, "requested-attributes", "printer-uri-supported"))
                out.write(0x03)
                val response = send(queryEndpoint, out.toByteArray(), timeoutMs)
                if (response.first != 200) continue
                val parsed = parse(response.second)
                if (parsed.requestId != id) continue
                if (parsed.status == IPP_VERSION_NOT_SUPPORTED) continue
                if (parsed.status !in 0x0000..0x00ff) return emptyList()
                return parsed.attributes["printer-uri-supported"].orEmpty().mapNotNull { value ->
                    runCatching {
                        val uri = URI(value.toString())
                        if (uri.scheme != "ipp" && uri.scheme != "ipps") return@runCatching null
                        val path = uri.rawPath?.takeIf { it.startsWith('/') }.orEmpty()
                        if (path.isBlank() || path == "/") return@runCatching null
                        CupsPrinterEndpoint(
                            PrinterEndpoint(
                                host, if (uri.port > 0) uri.port else port, path, uri.scheme == "ipps", major, minor
                            )
                        )
                    }.getOrNull()
                }.distinctBy { it.endpoint.key }
            } catch (_: Exception) {
                // Try the next IPP version.
            }
        }
        return emptyList()
    }

    fun getServerSoftware(host: String, port: Int = 631, secure: Boolean = false, timeoutMs: Int = 1200): String? {
        val connection = URL("${if (secure) "https" else "http"}://$host:$port/").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "IPP-Printer-Connect-read-only")
            runCatching { connection.responseCode }
            connection.getHeaderField("Server")?.trim()?.takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    fun print(
        printer: PrinterRecord,
        document: ByteArray,
        mime: String,
        jobName: String,
        options: IppJobOptions = IppJobOptions(),
        preferredMethod: JobSubmissionMethod = JobSubmissionMethod.AUTO,
    ): PrintResult = print(printer, MemoryDocumentData(document), mime, jobName, options, preferredMethod)

    fun print(
        printer: PrinterRecord,
        document: DocumentData,
        mime: String,
        jobName: String,
        options: IppJobOptions = IppJobOptions(),
        preferredMethod: JobSubmissionMethod = JobSubmissionMethod.AUTO,
    ): PrintResult {
        val endpoint = printer.endpoint
        if (printer.supportsValidateJob) {
            validateJob(endpoint, mime, jobName, options)?.let { return it }
        }
        return when (preferredMethod) {
            JobSubmissionMethod.CREATE_JOB_SEND_DOCUMENT -> {
                if (!printer.supportsCreateSendDocument) PrintResult(false, null, null, null, "프린터가 Create-Job/Send-Document를 지원하지 않습니다")
                else createJobAndSendDocument(endpoint, document, mime, jobName, options)
            }
            JobSubmissionMethod.PRINT_JOB -> {
                if (!printer.supportsPrintJob) PrintResult(false, null, null, null, "프린터가 Print-Job을 지원하지 않습니다")
                else printJob(endpoint, document, mime, jobName, options)
            }
            JobSubmissionMethod.AUTO -> when {
                printer.supportsCreateSendDocument -> createJobAndSendDocument(endpoint, document, mime, jobName, options)
                printer.supportsPrintJob -> printJob(endpoint, document, mime, jobName, options)
                else -> PrintResult(false, null, null, null, "지원되는 작업 제출 방식이 없습니다")
            }
        }
    }

    fun printRemoteUri(
        printer: PrinterRecord,
        documentUri: String,
        jobName: String,
        options: IppJobOptions = IppJobOptions(),
    ): PrintResult = when {
        printer.supportsCreateSendUri -> createJobAndSendUri(printer.endpoint, documentUri, jobName, options)
        printer.supportsPrintUri -> printUri(printer.endpoint, documentUri, jobName, options)
        else -> PrintResult(false, null, null, null, "프린터가 Print-URI 또는 Send-URI를 지원하지 않습니다")
    }

    private fun printUri(
        endpoint: PrinterEndpoint,
        documentUri: String,
        jobName: String,
        options: IppJobOptions,
    ): PrintResult {
        val id = requestIds.incrementAndGet()
        return try {
            val out = ByteArrayOutputStream()
            writeCommonOperationAttributes(out, endpoint, jobName)
            out.write(text(0x45, "document-uri", documentUri))
            out.write(attr(0x22, "ipp-attribute-fidelity", byteArrayOf(1)))
            writeJobAttributes(out, options)
            out.write(0x03)
            val raw = send(endpoint, withHeader(PRINT_URI, id, endpoint, out), 20_000)
            if (raw.first != 200) return PrintResult(false, raw.first, null, null, "Print-URI HTTP ${raw.first}", "Print-URI")
            val parsed = parse(raw.second)
            require(parsed.requestId == id) { "IPP 응답 번호가 요청과 다릅니다" }
            val jobId = parsed.attributes["job-id"]?.firstOrNull() as? Int
            val jobUri = parsed.attributes["job-uri"]?.firstOrNull()?.toString()
            if (isSuccessful(parsed.status) && parsed.requestId == id) {
                require(jobId != null || !jobUri.isNullOrBlank()) { "접수 응답에 작업 식별자가 없습니다" }
                PrintResult(true, raw.first, parsed.status, jobId, null, "Print-URI", jobUri, warning = responseWarning(parsed))
            } else {
                ippFailureResult(endpoint, raw.first, parsed.status, "Print-URI", jobId, jobUri)
            }
        } catch (e: Exception) {
            exceptionPrintResult(e, "Print-URI")
        }
    }

    private fun createJobAndSendUri(
        endpoint: PrinterEndpoint,
        documentUri: String,
        jobName: String,
        options: IppJobOptions,
    ): PrintResult {
        val createId = requestIds.incrementAndGet()
        var jobId: Int? = null
        var jobUri: String? = null
        var documentSubmitted = false
        try {
            val create = ByteArrayOutputStream()
            writeCommonOperationAttributes(create, endpoint, jobName)
            create.write(attr(0x22, "ipp-attribute-fidelity", byteArrayOf(1)))
            writeJobAttributes(create, options)
            create.write(0x03)
            val createRaw = send(endpoint, withHeader(CREATE_JOB, createId, endpoint, create), 10_000)
            if (createRaw.first != 200) return PrintResult(false, createRaw.first, null, null, "Create-Job HTTP ${createRaw.first}", "Create-Job + Send-URI")
            val created = parse(createRaw.second)
            require(created.requestId == createId) { "작업 생성 응답 번호가 요청과 다릅니다" }
            if (!isSuccessful(created.status)) {
                return ippFailureResult(endpoint, createRaw.first, created.status, "Create-Job + Send-URI")
            }
            jobId = created.attributes["job-id"]?.firstOrNull() as? Int
            jobUri = created.attributes["job-uri"]?.firstOrNull()?.toString()
            if (responseWarning(created) != null) {
                cancelJob(endpoint, jobId, jobUri)
                return PrintResult(false, createRaw.first, created.status, jobId,
                    "문서를 보내지 않았습니다. ${responseWarning(created)}", "Create-Job", jobUri)
            }
            if (jobId == null && jobUri.isNullOrBlank()) {
                return PrintResult(false, createRaw.first, created.status, null, "Create-Job 응답에 job-id/job-uri가 없습니다", "Create-Job + Send-URI")
            }

            val sendId = requestIds.incrementAndGet()
            val sendUri = ByteArrayOutputStream()
            sendUri.write(0x01)
            sendUri.write(text(0x47, "attributes-charset", "utf-8"))
            sendUri.write(text(0x48, "attributes-natural-language", "ko"))
            writeJobTarget(sendUri, endpoint, jobId, jobUri)
            sendUri.write(text(0x42, "requesting-user-name", requestingUserName(endpoint)))
            sendUri.write(text(0x45, "document-uri", documentUri))
            sendUri.write(attr(0x22, "last-document", byteArrayOf(1)))
            sendUri.write(0x03)
            documentSubmitted = true
            val sendRaw = send(endpoint, withHeader(SEND_URI, sendId, endpoint, sendUri), 20_000)
            if (sendRaw.first != 200) {
                cancelJob(endpoint, jobId, jobUri)
                return PrintResult(false, sendRaw.first, null, jobId, "Send-URI HTTP ${sendRaw.first}", "Create-Job + Send-URI", jobUri)
            }
            val sent = parse(sendRaw.second)
            require(sent.requestId == sendId) { "문서 전송 응답 번호가 요청과 다릅니다" }
            if (sent.requestId != sendId || !isSuccessful(sent.status)) cancelJob(endpoint, jobId, jobUri)
            return if (isSuccessful(sent.status) && sent.requestId == sendId) {
                PrintResult(true, sendRaw.first, sent.status, jobId, null, "Create-Job + Send-URI", jobUri, warning = responseWarning(sent))
            } else {
                ippFailureResult(endpoint, sendRaw.first, sent.status, "Create-Job + Send-URI", jobId, jobUri)
            }
        } catch (e: Exception) {
            if (!documentSubmitted && (jobId != null || !jobUri.isNullOrBlank())) cancelJob(endpoint, jobId, jobUri)
            return exceptionPrintResult(e, "Create-Job + Send-URI", jobId, jobUri)
        }
    }

    private fun validateJob(
        endpoint: PrinterEndpoint,
        mime: String,
        jobName: String,
        options: IppJobOptions,
    ): PrintResult? {
        val id = requestIds.incrementAndGet()
        return try {
            val out = ByteArrayOutputStream()
            writeCommonOperationAttributes(out, endpoint, jobName)
            out.write(text(0x49, "document-format", mime))
            out.write(attr(0x22, "ipp-attribute-fidelity", byteArrayOf(1)))
            writeJobAttributes(out, options)
            out.write(0x03)
            val raw = send(endpoint, withHeader(VALIDATE_JOB, id, endpoint, out), 8_000)
            if (raw.first != 200) return PrintResult(false, raw.first, null, null, "Validate-Job HTTP ${raw.first}", "Validate-Job")
            val parsed = parse(raw.second)
            if (parsed.requestId != id) error("IPP 사전 검사 응답 번호가 다릅니다")
            if (parsed.status == 0 && responseWarning(parsed) == null) null
            else if (isSuccessful(parsed.status)) PrintResult(false, raw.first, parsed.status, null,
                "설정 변경이 필요한 작업입니다. ${responseWarning(parsed)}", "Validate-Job")
            else ippFailureResult(endpoint, raw.first, parsed.status, "Validate-Job")
        } catch (e: Exception) {
            exceptionPrintResult(e, "Validate-Job")
        }
    }

    private fun printJob(
        endpoint: PrinterEndpoint,
        document: DocumentData,
        mime: String,
        jobName: String,
        options: IppJobOptions,
    ): PrintResult {
        val id = requestIds.incrementAndGet()
        return try {
            val out = ByteArrayOutputStream()
            writeCommonOperationAttributes(out, endpoint, jobName)
            out.write(text(0x49, "document-format", mime))
            out.write(attr(0x22, "ipp-attribute-fidelity", byteArrayOf(1)))
            writeJobAttributes(out, options)
            out.write(0x03)
            val raw = send(endpoint, IppRequestBody(withHeader(PRINT_JOB, id, endpoint, out), document), 20_000)
            if (raw.first != 200) return PrintResult(false, raw.first, null, null, "HTTP ${raw.first}", "Print-Job")
            val parsed = parse(raw.second)
            require(parsed.requestId == id) { "IPP 응답 번호가 요청과 다릅니다" }
            val jobId = parsed.attributes["job-id"]?.firstOrNull() as? Int
            val jobUri = parsed.attributes["job-uri"]?.firstOrNull()?.toString()
            if (isSuccessful(parsed.status) && parsed.requestId == id) {
                require(jobId != null || !jobUri.isNullOrBlank()) { "접수 응답에 작업 식별자가 없습니다" }
                PrintResult(true, raw.first, parsed.status, jobId, null, "Print-Job", jobUri, warning = responseWarning(parsed))
            } else {
                ippFailureResult(endpoint, raw.first, parsed.status, "Print-Job", jobId, jobUri)
            }
        } catch (e: Exception) {
            exceptionPrintResult(e, "Print-Job")
        }
    }

    private fun createJobAndSendDocument(
        endpoint: PrinterEndpoint,
        document: DocumentData,
        mime: String,
        jobName: String,
        options: IppJobOptions,
    ): PrintResult {
        val createId = requestIds.incrementAndGet()
        var jobId: Int? = null
        var jobUri: String? = null
        var documentSubmitted = false
        try {
            val create = ByteArrayOutputStream()
            writeCommonOperationAttributes(create, endpoint, jobName)
            create.write(attr(0x22, "ipp-attribute-fidelity", byteArrayOf(1)))
            writeJobAttributes(create, options)
            create.write(0x03)
            val createRaw = send(endpoint, withHeader(CREATE_JOB, createId, endpoint, create), 10_000)
            if (createRaw.first != 200) return PrintResult(false, createRaw.first, null, null, "Create-Job HTTP ${createRaw.first}", "Create-Job + Send-Document")
            val created = parse(createRaw.second)
            require(created.requestId == createId) { "작업 생성 응답 번호가 요청과 다릅니다" }
            if (!isSuccessful(created.status)) {
                return ippFailureResult(endpoint, createRaw.first, created.status, "Create-Job + Send-Document")
            }
            jobId = created.attributes["job-id"]?.firstOrNull() as? Int
            jobUri = created.attributes["job-uri"]?.firstOrNull()?.toString()
            if (responseWarning(created) != null) {
                cancelJob(endpoint, jobId, jobUri)
                return PrintResult(false, createRaw.first, created.status, jobId,
                    "문서를 보내지 않았습니다. ${responseWarning(created)}", "Create-Job", jobUri)
            }
            if (jobId == null && jobUri.isNullOrBlank()) {
                return PrintResult(false, createRaw.first, created.status, null, "Create-Job 응답에 job-id/job-uri가 없습니다", "Create-Job + Send-Document")
            }

            val sendId = requestIds.incrementAndGet()
            val sendDocument = ByteArrayOutputStream()
            sendDocument.write(0x01)
            sendDocument.write(text(0x47, "attributes-charset", "utf-8"))
            sendDocument.write(text(0x48, "attributes-natural-language", "ko"))
            writeJobTarget(sendDocument, endpoint, jobId, jobUri)
            sendDocument.write(text(0x42, "requesting-user-name", requestingUserName(endpoint)))
            sendDocument.write(text(0x42, "document-name", jobName.take(120)))
            sendDocument.write(text(0x49, "document-format", mime))
            sendDocument.write(attr(0x22, "last-document", byteArrayOf(1)))
            sendDocument.write(0x03)
            documentSubmitted = true
            val sendRaw = send(endpoint, IppRequestBody(withHeader(SEND_DOCUMENT, sendId, endpoint, sendDocument), document), 20_000)
            if (sendRaw.first != 200) {
                cancelJob(endpoint, jobId, jobUri)
                return PrintResult(false, sendRaw.first, null, jobId, "Send-Document HTTP ${sendRaw.first}", "Create-Job + Send-Document", jobUri)
            }
            val sent = parse(sendRaw.second)
            require(sent.requestId == sendId) { "문서 전송 응답 번호가 요청과 다릅니다" }
            if (sent.requestId != sendId || !isSuccessful(sent.status)) cancelJob(endpoint, jobId, jobUri)
            return if (isSuccessful(sent.status) && sent.requestId == sendId) {
                PrintResult(true, sendRaw.first, sent.status, jobId, null, "Create-Job + Send-Document", jobUri, warning = responseWarning(sent))
            } else {
                ippFailureResult(endpoint, sendRaw.first, sent.status, "Create-Job + Send-Document", jobId, jobUri)
            }
        } catch (e: Exception) {
            if (!documentSubmitted && (jobId != null || !jobUri.isNullOrBlank())) cancelJob(endpoint, jobId, jobUri)
            return exceptionPrintResult(e, "Create-Job + Send-Document", jobId, jobUri)
        }
    }

    internal fun getJobStatus(endpoint: PrinterEndpoint, jobId: Int?, jobUri: String?): IppJobStatus {
        require(jobId != null || !jobUri.isNullOrBlank()) { "조회할 작업 번호가 없습니다" }
        val id = requestIds.incrementAndGet()
        val out = ByteArrayOutputStream().apply {
            write(0x01)
            write(text(0x47, "attributes-charset", "utf-8"))
            write(text(0x48, "attributes-natural-language", "ko"))
            // Keep printer-uri and job-id adjacent; both identify the operation target.
            writeJobTarget(this, endpoint, jobId, jobUri.takeIf { jobId == null })
            write(text(0x42, "requesting-user-name", requestingUserName(endpoint)))
            // Some devices reject a list containing an optional attribute they do not implement.
            write(text(0x44, "requested-attributes", "all"))
            write(0x03)
        }
        val raw = send(endpoint, withHeader(0x0009, id, endpoint, out), 5_000)
        require(raw.first == 200) { "작업 상태 조회 HTTP ${raw.first}" }
        val parsed = parse(raw.second)
        require(parsed.requestId == id) { "작업 상태 응답 번호가 요청과 다릅니다" }
        require(isSuccessful(parsed.status)) {
            "작업 상태를 조회할 수 없습니다 (IPP 0x${parsed.status.toString(16).padStart(4, '0')})"
        }
        return IppJobStatus.from(parsed.attributes)
    }

    private fun cancelJob(endpoint: PrinterEndpoint, jobId: Int?, jobUri: String?) = runCatching {
        val id = requestIds.incrementAndGet()
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(text(0x47, "attributes-charset", "utf-8"))
        out.write(text(0x48, "attributes-natural-language", "ko"))
        writeJobTarget(out, endpoint, jobId, jobUri)
        out.write(text(0x42, "requesting-user-name", requestingUserName(endpoint)))
        out.write(0x03)
        send(endpoint, withHeader(CANCEL_JOB, id, endpoint, out), 5_000)
    }

    private fun writeCommonOperationAttributes(out: ByteArrayOutputStream, endpoint: PrinterEndpoint, jobName: String) {
        out.write(0x01)
        out.write(text(0x47, "attributes-charset", "utf-8"))
        out.write(text(0x48, "attributes-natural-language", "ko"))
        out.write(text(0x45, "printer-uri", endpoint.uri))
        out.write(text(0x42, "requesting-user-name", requestingUserName(endpoint)))
        out.write(text(0x42, "job-name", jobName.take(120)))
    }

    private fun writeJobAttributes(out: ByteArrayOutputStream, options: IppJobOptions) {
        out.write(0x02)
        out.write(integer("copies", options.copies))
        options.pageRanges.forEachIndexed { index, value ->
            out.write(range(if (index == 0) "page-ranges" else "", value))
        }
        options.colorMode?.let { out.write(text(0x44, "print-color-mode", it)) }
        options.media?.let { out.write(text(0x44, "media", it)) }
        options.printScaling?.let { out.write(text(0x44, "print-scaling", it)) }
        options.sides?.let { out.write(text(0x44, "sides", it)) }
        options.orientation?.let { out.write(enumValue("orientation-requested", it)) }
        options.printQuality?.let { out.write(enumValue("print-quality", it)) }
        options.resolution?.let { out.write(resolution("printer-resolution", it)) }
        options.numberUp?.let { out.write(integer("number-up", it)) }
        options.xImagePosition?.let { out.write(text(0x44, "x-image-position", it)) }
        options.yImagePosition?.let { out.write(text(0x44, "y-image-position", it)) }
    }

    private fun requestingUserName(endpoint: PrinterEndpoint): String =
        credentials[endpoint.key]?.username?.take(255) ?: USER_NAME

    private fun writeJobTarget(out: ByteArrayOutputStream, endpoint: PrinterEndpoint, jobId: Int?, jobUri: String?) {
        if (!jobUri.isNullOrBlank()) out.write(text(0x45, "job-uri", jobUri))
        else {
            out.write(text(0x45, "printer-uri", endpoint.uri))
            out.write(integer("job-id", requireNotNull(jobId)))
        }
    }

    private fun withHeader(operation: Int, id: Int, endpoint: PrinterEndpoint, body: ByteArrayOutputStream): ByteArray =
        ByteArrayOutputStream().apply {
            write(header(operation, id, endpoint.ippMajor, endpoint.ippMinor))
            write(body.toByteArray())
        }.toByteArray()

    private fun responseWarning(response: IppResponse): String? {
        val changed = response.groups.filter { it.tag == 5 }.flatMap { it.attributes.keys }.distinct()
        if (response.status == 0 && changed.isEmpty()) return null
        return "프린터가 설정을 무시·대체했거나 추가 상태를 반환했습니다 (IPP 0x${response.status.toString(16).padStart(4, '0')})." +
            if (changed.isEmpty()) " 변경 항목은 응답에 없습니다." else " 확인할 항목: ${changed.joinToString()}"
    }

    private fun isSuccessful(status: Int) = status in 0x0000..0x00ff

    private fun ippFailureResult(
        endpoint: PrinterEndpoint,
        httpStatus: Int,
        ippStatus: Int,
        method: String,
        jobId: Int? = null,
        jobUri: String? = null,
    ): PrintResult {
        val authRequired = ippStatus == IPP_NOT_AUTHENTICATED
        val challenge = if (authRequired) {
            authChallenge(endpoint) ?: IppAuthChallenge(emptyList())
        } else {
            null
        }
        return PrintResult(
            accepted = false,
            httpStatus = httpStatus,
            ippStatus = ippStatus,
            jobId = jobId,
            error = statusLabel(ippStatus),
            method = method,
            jobUri = jobUri,
            authRequired = authRequired,
            authChallenge = challenge,
        )
    }

    private fun exceptionPrintResult(
        error: Exception,
        method: String,
        jobId: Int? = null,
        jobUri: String? = null,
    ): PrintResult =
        if (error is IppAuthenticationRequiredException) {
            PrintResult(
                accepted = false,
                httpStatus = HttpURLConnection.HTTP_UNAUTHORIZED,
                ippStatus = null,
                jobId = jobId,
                error = error.message,
                method = method,
                jobUri = jobUri,
                authRequired = true,
                authChallenge = error.challenge,
            )
        } else {
            PrintResult(
                false,
                null,
                null,
                jobId,
                if (method == "Validate-Job") "사전 검사 실패: ${error.message}" else
                    "접수 여부를 확인할 수 없습니다: ${error.message}. 중복 출력을 피하려면 프린터에서 확인한 뒤 다시 인쇄하세요.",
                method,
                jobUri,
                outcomeUnknown = method != "Validate-Job",
            )
        }

    private fun statusLabel(status: Int): String = when (status) {
        IPP_NOT_AUTHENTICATED -> "IPP 인증 필요 (0x0402)"
        IPP_NOT_AUTHORIZED -> "IPP 권한 없음 (0x0403)"
        else -> "IPP 0x${status.toString(16).padStart(4, '0')}"
    }

    private fun header(operation: Int, id: Int, major: Int, minor: Int): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        .put(major.toByte()).put(minor.toByte()).putShort(operation.toShort()).putInt(id).array()

    private fun versionCandidates(endpoint: PrinterEndpoint): List<Pair<Int, Int>> =
        (listOf(endpoint.ippMajor to endpoint.ippMinor) + STANDARD_VERSIONS).distinct()

    private data class IppHttpResponse(
        val status: Int,
        val body: ByteArray,
        val authenticateHeaders: List<String> = emptyList(),
    ) {
        val first: Int get() = status
        val second: ByteArray get() = body
    }

    private class IppAuthenticationRequiredException(
        val challenge: IppAuthChallenge,
    ) : IOException("프린터 인증 필요: ${challenge.label}")

    private fun send(endpoint: PrinterEndpoint, payload: ByteArray, timeoutMs: Int): IppHttpResponse =
        send(endpoint, IppRequestBody(payload), timeoutMs)

    private fun send(endpoint: PrinterEndpoint, payload: DocumentData, timeoutMs: Int): IppHttpResponse {
        val transport = if (endpoint.secure) "https" else "http"
        val url = URL("$transport://${endpoint.host}:${endpoint.port}${endpoint.path}")
        val savedCredentials = credentials[endpoint.key]
        val cachedChallenges = httpChallenges[endpoint.key].orEmpty()
        var authorization = cachedChallenges.firstNotNullOfOrNull { challenge ->
            savedCredentials?.let {
                IppHttpAuthentication.authorization(challenge, it, "POST", url, payload)
            }
        }
        if (authorization == null && savedCredentials?.preemptiveBasic == true) {
            authorization = IppHttpAuthentication.basic(savedCredentials)
        }
        var response = sendOnce(url, payload, timeoutMs, authorization)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val parsed = IppHttpAuthentication.parseChallenges(response.authenticateHeaders)
            val public = IppHttpAuthentication.publicChallenge(parsed)
            challenges[endpoint.key] = public
            httpChallenges[endpoint.key] = parsed
            if (savedCredentials != null) {
                val retryAuthorization = parsed.firstNotNullOfOrNull { challenge ->
                    IppHttpAuthentication.authorization(
                        challenge,
                        savedCredentials,
                        "POST",
                        url,
                        payload,
                    )
                } ?: if (savedCredentials.preemptiveBasic) {
                    IppHttpAuthentication.basic(savedCredentials)
                } else {
                    null
                }
                if (retryAuthorization != null) {
                    response = sendOnce(url, payload, timeoutMs, retryAuthorization)
                } else if (parsed.isNotEmpty()) {
                    throw IOException("프린터의 HTTP 인증 방식을 지원하지 않습니다: ${public.label}")
                }
            }
        }
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val parsed = IppHttpAuthentication.parseChallenges(response.authenticateHeaders)
            val public = if (parsed.isNotEmpty()) {
                IppHttpAuthentication.publicChallenge(parsed)
            } else {
                challenges[endpoint.key] ?: IppAuthChallenge(emptyList())
            }
            challenges[endpoint.key] = public
            if (parsed.isNotEmpty()) httpChallenges[endpoint.key] = parsed
            throw IppAuthenticationRequiredException(public)
        }
        return response
    }

    private fun sendOnce(
        url: URL,
        payload: DocumentData,
        timeoutMs: Int,
        authorization: String?,
    ): IppHttpResponse {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = timeoutMs; connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.doOutput = true; connection.setFixedLengthStreamingMode(payload.length)
            connection.setRequestProperty("Content-Type", "application/ipp")
            connection.setRequestProperty("Accept", "application/ipp")
            if (!authorization.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", authorization)
            }
            try {
                connection.outputStream.use { payload.writeTo(it) }
            } catch (writeError: IOException) {
                if (writeError is DocumentDataException) throw writeError
                // A printer can reject the headers before consuming a large body.
                // Only an explicit 401 permits an authentication retry; an uncertain
                // network failure must never resubmit a possibly accepted print job.
                if (runCatching { connection.responseCode }.getOrNull() != HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw writeError
                }
            }
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("전송 확인이 취소되었습니다")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= IppResponseParser.MAX_RESPONSE_BYTES) { "프린터 응답이 처리 가능한 크기를 초과했습니다" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val authenticateHeaders = connection.headerFields
                .filterKeys { it?.equals("WWW-Authenticate", ignoreCase = true) == true }
                .values
                .flatten()
            return IppHttpResponse(status, body, authenticateHeaders)
        } finally { connection.disconnect() }
    }

    fun parse(data: ByteArray): IppResponse = IppResponseParser.parse(data)

    private val STANDARD_VERSIONS = listOf(2 to 2, 2 to 1, 2 to 0, 1 to 1, 1 to 0)
    private const val USER_NAME = "ipp-printer-connect-android"
    private const val PRINT_JOB = 0x0002
    private const val PRINT_URI = 0x0003
    private const val VALIDATE_JOB = 0x0004
    private const val CREATE_JOB = 0x0005
    private const val SEND_DOCUMENT = 0x0006
    private const val SEND_URI = 0x0007
    private const val CANCEL_JOB = 0x0008
    private const val IPP_NOT_AUTHENTICATED = 0x0402
    private const val IPP_NOT_AUTHORIZED = 0x0403
    private const val IPP_VERSION_NOT_SUPPORTED = 0x0503
}
