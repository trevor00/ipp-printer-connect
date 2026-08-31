package kr.ac.hit.networkprinterstudio

object PrintFormatSupport {
    const val PDF = "application/pdf"
    const val POSTSCRIPT = "application/postscript"
    const val PCL = "application/vnd.hp-pcl"
    const val PCL_XL = "application/vnd.hp-pclxl"
    const val PWG_RASTER = "image/pwg-raster"
    const val URF = "image/urf"
    const val OCTET_STREAM = "application/octet-stream"

    fun normalize(mime: String): String = when (mime.trim().lowercase()) {
        "application/ps", "application/x-postscript" -> POSTSCRIPT
        "application/pcl", "application/x-pcl", PCL -> PCL
        "application/pclxl", "application/x-pclxl", PCL_XL -> PCL_XL
        "image/x-pwg-raster" -> "image/pwg-raster"
        else -> mime.trim().lowercase()
    }

    fun normalized(formats: Collection<String>): Set<String> = formats.mapTo(linkedSetOf(), ::normalize)

    fun advertisedMime(formats: Collection<String>, canonicalMime: String): String? {
        val canonical = normalize(canonicalMime)
        return formats.firstOrNull { normalize(it) == canonical }
    }

    fun supports(formats: Collection<String>, mime: String): Boolean =
        advertisedMime(formats, mime) != null

    fun conversionLabels(formats: Collection<String>): List<String> = buildList {
        if (supports(formats, PDF)) add("사진·텍스트→PDF")
        if (supports(formats, POSTSCRIPT)) add("PDF·사진·텍스트→PostScript")
        if (supports(formats, PWG_RASTER)) add("PDF·사진·텍스트→PWG Raster")
        if (supports(formats, URF)) add("PDF·사진·텍스트→Apple Raster(URF)")
        if (supports(formats, PCL)) add("PDF·사진·텍스트→흑백 PCL")
    }

    fun unsupportedFileMessage(fileName: String, sourceMime: String, formats: Collection<String>): String {
        val canonicalSource = normalize(sourceMime)
        val pdfPaths = buildList {
            if (supports(formats, PDF)) add("PDF")
            if (supports(formats, POSTSCRIPT)) add("PostScript")
            if (supports(formats, PWG_RASTER)) add("PWG Raster")
            if (supports(formats, URF)) add("URF")
            if (supports(formats, PCL)) add("PCL")
        }
        val reason = if (canonicalSource == OCTET_STREAM) {
            "'$fileName'의 파일 형식을 판별할 수 없고, 앱에서 이 파일을 인쇄 언어로 변환할 수 없습니다."
        } else {
            "'$fileName' 형식($sourceMime)을 프린터가 지원하지 않으며, 앱에도 이 형식의 변환 기능이 없습니다."
        }
        val suggestion = when {
            canonicalSource == PDF ->
                "또한 이 프린터가 앱이 생성할 수 있는 PDF, PostScript, PWG Raster, URF 또는 PCL 형식을 광고하지 않습니다."
            pdfPaths.isNotEmpty() ->
                "문서를 PDF로 변환한 뒤 다시 선택하세요. PDF는 ${pdfPaths.joinToString(", ")} 경로를 이용해 이 프린터로 출력할 수 있습니다."
            else ->
                "PDF로 바꾸더라도 현재 프린터가 앱이 생성할 수 있는 출력 형식을 광고하지 않아 인쇄가 보장되지 않습니다."
        }
        return "전송 실패: $reason $suggestion"
    }
}
