package kr.ac.hit.networkprinterstudio

data class DocumentOutputPlan(val mime: String, val renderPages: Boolean)

object DocumentOutputPlanner {
    private val appOutputFormats = listOf(
        PrintFormatSupport.PDF,
        PrintFormatSupport.POSTSCRIPT,
        PrintFormatSupport.PWG_RASTER,
        PrintFormatSupport.URF,
        PrintFormatSupport.PCL,
        "image/jpeg",
        "image/png",
    )

    fun conversionTargets(formats: Collection<String>, colorMode: String? = null): List<String> {
        val preserveColor = requiresColorPath(colorMode)
        return appOutputFormats
            .filterNot { preserveColor && it == PrintFormatSupport.PCL }
            .mapNotNull { mime -> PrintFormatSupport.advertisedMime(formats, mime) }
            .distinct()
    }

    /**
     * Prefer page-oriented/vector-capable formats, then the raster formats the
     * printer explicitly advertises. JPEG precedes PNG only to avoid needlessly
     * large photo jobs when a printer accepts both single-page image formats.
     */
    fun automaticOutputMime(formats: Collection<String>, colorMode: String? = null): String? =
        conversionTargets(formats, colorMode).firstOrNull()

    fun conversionTargets(printer: PrinterRecord, colorMode: String?, sidesMode: String?): List<String> =
        conversionTargets(printer.formats, colorMode).filter { mime ->
            PrinterOutputPolicy.rasterProblem(
                printer, mime, AutomaticRasterPolicy.outputDpi(printer, mime), sidesMode ?: printer.defaultSides,
            ) == null
        }

    fun automaticOutputMime(printer: PrinterRecord, colorMode: String?, sidesMode: String?): String? =
        conversionTargets(printer, colorMode, sidesMode).firstOrNull()

    fun automaticOutputProblem(printer: PrinterRecord, colorMode: String?, sidesMode: String?): String? {
        val candidates = conversionTargets(printer.formats, colorMode)
        if (candidates.isEmpty()) {
            return if (requiresColorPath(colorMode) && PrintFormatSupport.supports(printer.formats, PrintFormatSupport.PCL)) {
                "앱에서 생성하는 PCL은 흑백만 지원합니다. 컬러를 유지하려면 프린터가 PDF, PostScript, PWG Raster, URF, JPEG 또는 PNG를 지원해야 합니다."
            } else null
        }
        return candidates.firstNotNullOfOrNull { mime ->
            PrinterOutputPolicy.rasterProblem(
                printer, mime, AutomaticRasterPolicy.outputDpi(printer, mime), sidesMode ?: printer.defaultSides,
            )
        }
    }

    fun plan(
        formats: Collection<String>,
        sourceMime: String,
        applyPageLayout: Boolean,
        forcePostScript: Boolean,
        colorMode: String? = null,
        requestedOutputMime: String? = null,
        requireExplicitOutput: Boolean = false,
        printer: PrinterRecord? = null,
        sidesMode: String? = null,
    ): DocumentOutputPlan {
        val source = PrintFormatSupport.normalize(sourceMime)
        val original = PrintFormatSupport.directSubmissionMime(formats, source)
        val postScript = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.POSTSCRIPT)
        if (forcePostScript && postScript == null) error("선택한 프린터가 PostScript 출력을 광고하지 않습니다")
        val preserveColor = requiresColorPath(colorMode)
        val layoutRequired = applyPageLayout || PrintCapabilityPolicy.isPhoto(source) || source == "text/plain"
        if (!layoutRequired && original != null && requestedOutputMime == null &&
            (!forcePostScript || source == PrintFormatSupport.POSTSCRIPT)) {
            // Preserve source bytes unless preview-matching page generation is needed
            // (photos/TXT) or the user explicitly requests layout/conversion.
            return DocumentOutputPlan(original, renderPages = false)
        }

        require(PrintCapabilityPolicy.appCanLayout(source)) {
            PrintFormatSupport.unsupportedFileMessage("선택한 파일", sourceMime, formats)
        }

        val candidates = appOutputFormats.filterNot { preserveColor && it == PrintFormatSupport.PCL }
        val output = when {
            forcePostScript -> postScript
            requestedOutputMime != null -> {
                val requested = PrintFormatSupport.normalize(requestedOutputMime)
                require(requested in appOutputFormats) { "앱에서 변환할 수 없는 출력 형식입니다: $requestedOutputMime" }
                require(!(preserveColor && requested == PrintFormatSupport.PCL)) {
                    "PCL 변환은 흑백 전용입니다. 컬러를 유지하려면 프린터가 지원하는 PDF, PostScript, PWG Raster, URF, PNG 또는 JPEG를 선택하세요."
                }
                val advertised = PrintFormatSupport.advertisedMime(formats, requested)
                    ?: error("선택한 변환 형식($requestedOutputMime)을 프린터가 광고하지 않습니다")
                printer?.let { device ->
                    PrinterOutputPolicy.rasterProblem(
                        device, advertised, AutomaticRasterPolicy.outputDpi(device, advertised), sidesMode ?: device.defaultSides,
                    )?.let { error(it) }
                }
                advertised
            }
            requireExplicitOutput -> null
            else -> candidates.firstNotNullOfOrNull { mime ->
                val advertised = PrintFormatSupport.advertisedMime(formats, mime) ?: return@firstNotNullOfOrNull null
                val problem = printer?.let { device ->
                    PrinterOutputPolicy.rasterProblem(
                        device, advertised, AutomaticRasterPolicy.outputDpi(device, advertised), sidesMode ?: device.defaultSides,
                    )
                }
                advertised.takeIf { problem == null }
            }
        }
        require(output != null) {
            if (requestedOutputMime == null && requireExplicitOutput) {
                "전송할 변환 형식을 선택하세요. 변환은 인쇄 또는 미리보기를 누른 뒤 시작합니다."
            } else if (printer != null && automaticOutputProblem(printer, colorMode, sidesMode) != null) {
                "전송 실패: ${automaticOutputProblem(printer, colorMode, sidesMode)}"
            } else if (preserveColor) {
                "전송 실패: 컬러를 유지해서 변환할 수 있는 출력 형식이 없습니다. 앱의 PCL 변환은 흑백 전용이므로 컬러 인쇄에 사용하지 않습니다. 프린터가 PDF, PostScript, PWG Raster, URF, PNG 또는 JPEG 중 하나를 지원해야 합니다."
            } else {
                "전송 실패: 배치를 적용한 문서를 보낼 수 있는 출력 형식이 없습니다. 프린터가 PDF, PostScript, PWG Raster, URF, PCL, PNG 또는 JPEG 중 하나를 지원해야 합니다."
            }
        }
        return DocumentOutputPlan(output, renderPages = true)
    }

    private fun requiresColorPath(colorMode: String?): Boolean = colorMode?.trim()?.lowercase(java.util.Locale.ROOT) in setOf(
        "color", "auto-color", "process-color", "full-color", "highlight", "auto",
    )
}
