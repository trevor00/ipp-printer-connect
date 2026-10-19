package kr.ac.hit.networkprinterstudio

data class PageLayoutControls(
    val appCanLayout: Boolean,
    val pageRangesAllowed: Boolean,
    val numberUpChoices: List<Int>,
    val pageRangesHint: String,
)

object PrintCapabilityPolicy {
    private val appNumberUps = listOf(1, 2, 4, 6, 9, 16)
    // Android 이미지 디코더로 여는 사진 형식만 포함한다. PWG Raster·URF는 사진이 아니다.
    private val photoMimes = setOf(
        "image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp",
        "image/heic", "image/heif", "image/avif",
    )

    fun appCanLayout(sourceMime: String?, isRemoteUri: Boolean = false): Boolean {
        if (isRemoteUri || sourceMime == null) return false
        val mime = PrintFormatSupport.normalize(sourceMime)
        return mime == PrintFormatSupport.PDF || mime == "text/plain" || mime in photoMimes
    }

    fun layoutControls(
        printer: PrinterRecord?,
        sourceMime: String?,
        isRemoteUri: Boolean = false,
    ): PageLayoutControls {
        val appLayout = appCanLayout(sourceMime, isRemoteUri)
        val printerRanges = printer?.pageRangesSupported == true
        val advertisedNumberUps = printer?.numberUps.orEmpty().filter { it >= 1 }
        val numberUps = if (appLayout) appNumberUps + advertisedNumberUps else advertisedNumberUps
        return PageLayoutControls(
            appCanLayout = appLayout,
            pageRangesAllowed = appLayout || printerRanges,
            numberUpChoices = numberUps.distinct().sorted(),
            pageRangesHint = when {
                appLayout -> "예: 1-3,5 · 앱에서 적용"
                printerRanges -> "예: 1-3,5 · 프린터에서 적용"
                sourceMime == null && !isRemoteUri -> "문서를 선택하세요"
                else -> "프린터 미지원"
            },
        )
    }

    fun supportsSubmission(printer: PrinterRecord, isRemoteUri: Boolean): Boolean =
        if (isRemoteUri) printer.supportsPrintUri || printer.supportsCreateSendUri
        else printer.supportsPrintJob || printer.supportsCreateSendDocument
}
