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

    fun advertisedMime(formats: Collection<String>, canonicalMime: String): String? {
        val canonical = normalize(canonicalMime)
        if (isGenericMime(canonical)) return null
        return formats.firstOrNull { normalize(it) == canonical }
    }

    fun supports(formats: Collection<String>, mime: String): Boolean =
        advertisedMime(formats, mime) != null

    /** A directly submitted source is currently retained only for native PDF. */
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
        val reason = when {
            isGenericMime(canonicalSource) -> "'$fileName'의 파일 형식을 확인할 수 없습니다."
            else -> "앱에서 '$fileName' 형식($sourceMime)의 내용을 미리보기와 같은 페이지로 구성할 수 없습니다."
        }
        val route = if (pdfPaths.isEmpty()) {
            " 다만 현재 프린터가 앱에서 만들 수 있는 전송 형식을 광고하지 않아, PDF로 바꾸더라도 출력할 수 없습니다."
        } else {
            " 이 프린터는 ${pdfPaths.joinToString(", ")} 형식으로 전송할 수 있습니다."
        }
        return "전송 실패: $reason PDF로 변환 후 다시 시도해 주세요.$route"
    }
}
