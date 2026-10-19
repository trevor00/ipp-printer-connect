package kr.ac.hit.networkprinterstudio

import java.util.Locale

object PrintFormatSupport {
    const val PDF = "application/pdf"
    const val POSTSCRIPT = "application/postscript"
    const val PCL = "application/vnd.hp-pcl"
    const val PCL_XL = "application/vnd.hp-pclxl"
    const val PWG_RASTER = "image/pwg-raster"
    const val URF = "image/urf"
    const val OCTET_STREAM = "application/octet-stream"

    private val genericMimes = setOf(
        OCTET_STREAM, "application/x-octet-stream", "binary/octet-stream", "application/binary",
        "application/unknown", "unknown/unknown", "application/force-download",
        "application/download", "application/x-download",
    )
    private val mimePattern = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

    fun normalize(mime: String): String = when (val value = mime.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "application/ps", "application/x-postscript" -> POSTSCRIPT
        "application/pcl", "application/x-pcl", PCL -> PCL
        "application/pclxl", "application/x-pclxl", PCL_XL -> PCL_XL
        "image/x-pwg-raster" -> PWG_RASTER
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        "image/x-tiff" -> "image/tiff"
        "image/x-bmp", "image/x-ms-bmp" -> "image/bmp"
        "application/x-pdf" -> PDF
        else -> value
    }

    /** 자동 형식 감지와 와일드카드는 특정 문서 형식을 해석한다는 광고가 아니다. */
    fun isGenericMime(mime: String): Boolean {
        val canonical = normalize(mime)
        return canonical in genericMimes || !mimePattern.matches(canonical)
    }

    fun normalized(formats: Collection<String>): Set<String> = formats.mapTo(linkedSetOf(), ::normalize)

    fun advertisedMime(formats: Collection<String>, canonicalMime: String): String? {
        val canonical = normalize(canonicalMime)
        if (isGenericMime(canonical)) return null
        return formats.firstOrNull { normalize(it) == canonical }
    }

    fun supports(formats: Collection<String>, mime: String): Boolean =
        advertisedMime(formats, mime) != null

    /**
     * 원본 MIME을 프린터가 광고하면 변환보다 원본 전송을 우선한다.
     * 사용자가 PostScript 강제 전송을 선택한 경우에만 직접 전송을 건너뛴다.
     */
    fun directSubmissionMime(
        formats: Collection<String>,
        sourceMime: String,
        forcePostScript: Boolean = false,
    ): String? = if (forcePostScript) null else advertisedMime(formats, sourceMime)

    fun conversionLabels(formats: Collection<String>): List<String> = buildList {
        if (supports(formats, PDF)) add("사진·텍스트→PDF")
        if (supports(formats, POSTSCRIPT)) add("PDF·사진·텍스트→PostScript")
        if (supports(formats, PWG_RASTER)) add("PDF·사진·텍스트→PWG Raster")
        if (supports(formats, URF)) add("PDF·사진·텍스트→Apple Raster(URF)")
        if (supports(formats, PCL)) add("PDF·사진·텍스트→흑백 PCL")
        if (supports(formats, "image/png")) add("PDF·사진·텍스트→PNG (한 페이지)")
        if (supports(formats, "image/jpeg")) add("PDF·사진·텍스트→JPEG (한 페이지)")
    }

    fun unsupportedFileMessage(fileName: String, sourceMime: String, formats: Collection<String>): String {
        val canonicalSource = normalize(sourceMime)
        val pdfPaths = buildList {
            if (supports(formats, PDF)) add("PDF")
            if (supports(formats, POSTSCRIPT)) add("PostScript")
            if (supports(formats, PWG_RASTER)) add("PWG Raster")
            if (supports(formats, URF)) add("URF")
            if (supports(formats, PCL)) add("PCL")
            if (supports(formats, "image/png")) add("PNG (한 페이지)")
            if (supports(formats, "image/jpeg")) add("JPEG (한 페이지)")
        }
        val reason = if (isGenericMime(canonicalSource)) {
            "'$fileName'의 파일 형식을 판별할 수 없고, 앱에서 이 파일을 인쇄 언어로 변환할 수 없습니다. " +
                "프린터의 자동 형식 감지 광고만으로는 이 파일의 인쇄 지원 여부를 확인할 수 없습니다."
        } else {
            "'$fileName' 형식($sourceMime)을 프린터가 지원하지 않으며, 앱에도 이 형식의 변환 기능이 없습니다."
        }
        val suggestion = when {
            canonicalSource == PDF && pdfPaths.isEmpty() ->
                "또한 이 프린터가 앱이 생성할 수 있는 PDF, PostScript, PWG Raster, URF 또는 PCL 형식을 광고하지 않습니다."
            canonicalSource == PDF ->
                "이 프린터에는 ${pdfPaths.joinToString(", ")} 출력 경로가 있습니다. PDF 파일과 출력 설정을 확인해 주세요."
            pdfPaths.isNotEmpty() ->
                "문서를 PDF로 변환한 뒤 다시 선택하세요. PDF를 선택하면 ${pdfPaths.joinToString(", ")} 출력 경로로 전송을 시도할 수 있습니다."
            else ->
                "PDF로 바꾸더라도 현재 프린터가 앱이 생성할 수 있는 출력 형식을 광고하지 않아 인쇄가 보장되지 않습니다."
        }
        return "전송 실패: $reason $suggestion"
    }
}
