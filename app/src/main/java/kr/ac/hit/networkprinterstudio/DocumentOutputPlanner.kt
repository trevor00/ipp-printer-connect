package kr.ac.hit.networkprinterstudio

data class DocumentOutputPlan(val mime: String, val renderPages: Boolean)

object DocumentOutputPlanner {
    private val appOutputFormats = listOf(
        PrintFormatSupport.PDF,
        PrintFormatSupport.POSTSCRIPT,
        PrintFormatSupport.PWG_RASTER,
        PrintFormatSupport.URF,
        PrintFormatSupport.PCL,
        "image/png",
        "image/jpeg",
    )

    fun conversionTargets(formats: Collection<String>, colorMode: String? = null): List<String> {
        val preserveColor = requiresColorPath(colorMode)
        return appOutputFormats
            .filterNot { preserveColor && it == PrintFormatSupport.PCL }
            .mapNotNull { mime -> PrintFormatSupport.advertisedMime(formats, mime) }
            .distinct()
    }

    fun plan(
        formats: Collection<String>,
        sourceMime: String,
        applyPageLayout: Boolean,
        forcePostScript: Boolean,
        colorMode: String? = null,
        requestedOutputMime: String? = null,
        requireExplicitOutput: Boolean = false,
    ): DocumentOutputPlan {
        val source = PrintFormatSupport.normalize(sourceMime)
        require(!PrintFormatSupport.isGenericMime(source)) {
            PrintFormatSupport.unsupportedFileMessage("선택한 파일", sourceMime, formats)
        }
        val original = PrintFormatSupport.directSubmissionMime(formats, source)
        val postScript = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.POSTSCRIPT)
        if (forcePostScript && postScript == null) error("선택한 프린터가 PostScript 출력을 광고하지 않습니다")
        val preserveColor = requiresColorPath(colorMode)
        if (!applyPageLayout && original != null && (!forcePostScript || source == PrintFormatSupport.POSTSCRIPT)) {
            // A supplied printer-language file may already contain color. Only the app's
            // own monochrome PCL encoder is excluded from color conversion below.
            return DocumentOutputPlan(original, renderPages = false)
        }

        val renderable = PrintCapabilityPolicy.appCanLayout(source)
        require(renderable) {
            if (applyPageLayout) "전송 실패: 이 파일 형식($sourceMime)은 앱에서 페이지 배치를 적용할 수 없습니다. PDF나 사진, 일반 텍스트 파일로 선택해 주세요."
            else "전송 실패: 이 파일 형식($sourceMime)을 앱에서 변환할 수 없습니다."
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
                PrintFormatSupport.advertisedMime(formats, requested)
                    ?: error("선택한 변환 형식($requestedOutputMime)을 프린터가 광고하지 않습니다")
            }
            requireExplicitOutput -> null
            else -> candidates.firstNotNullOfOrNull { PrintFormatSupport.advertisedMime(formats, it) }
        }
        require(output != null) {
            if (requestedOutputMime == null && requireExplicitOutput) {
                "전송할 변환 형식을 선택하세요. 변환은 인쇄 또는 미리보기를 누른 뒤 시작합니다."
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
