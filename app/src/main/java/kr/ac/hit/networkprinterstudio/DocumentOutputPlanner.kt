package kr.ac.hit.networkprinterstudio

data class DocumentOutputPlan(val mime: String, val renderPages: Boolean)

object DocumentOutputPlanner {
    fun plan(
        formats: Collection<String>,
        sourceMime: String,
        applyPageLayout: Boolean,
        forcePostScript: Boolean,
    ): DocumentOutputPlan {
        val source = PrintFormatSupport.normalize(sourceMime)
        require(!PrintFormatSupport.isGenericMime(source)) {
            PrintFormatSupport.unsupportedFileMessage("선택한 파일", sourceMime, formats)
        }
        val original = PrintFormatSupport.directSubmissionMime(formats, source)
        val postScript = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.POSTSCRIPT)
        if (forcePostScript && postScript == null) error("선택한 프린터가 PostScript 출력을 광고하지 않습니다")
        if (!applyPageLayout && original != null && (!forcePostScript || source == PrintFormatSupport.POSTSCRIPT)) {
            return DocumentOutputPlan(original, renderPages = false)
        }

        val renderable = source == PrintFormatSupport.PDF || source == "text/plain" || source.startsWith("image/")
        require(renderable) {
            if (applyPageLayout) "전송 실패: 이 파일 형식($sourceMime)은 앱에서 페이지 배치를 적용할 수 없습니다. PDF나 사진, 일반 텍스트 파일로 선택해 주세요."
            else "전송 실패: 이 파일 형식($sourceMime)을 앱에서 변환할 수 없습니다."
        }
        val output = if (forcePostScript) postScript else listOf(
            PrintFormatSupport.PDF, PrintFormatSupport.POSTSCRIPT,
            PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF, PrintFormatSupport.PCL,
            "image/png", "image/jpeg",
        ).firstNotNullOfOrNull { PrintFormatSupport.advertisedMime(formats, it) }
        require(output != null) {
            "전송 실패: 배치를 적용한 문서를 보낼 수 있는 출력 형식이 없습니다. 프린터가 PDF, PostScript, PWG Raster, URF, PCL, PNG 또는 JPEG 중 하나를 지원해야 합니다."
        }
        return DocumentOutputPlan(output, renderPages = true)
    }
}
