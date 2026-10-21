package kr.ac.hit.networkprinterstudio

import kotlin.math.roundToInt

internal object PrinterOutputPolicy {
    fun collection(printer: PrinterRecord?, media: String?): IppCollection? {
        printer ?: return null
        fun IppCollection.matches() = listOf("media-key", "media-size-name").any {
            attributes[it]?.firstOrNull()?.toString()?.equals(media, true) == true
        }
        val matching = printer.mediaCollections.filter { it.matches() }
        // Different trays/media types can have different margins for the same paper.
        if (matching.size == 1) return matching.single()
        if (matching.size > 1 && matching.map { it.attributes }.distinct().size == 1) return matching.first()
        return printer.defaultMediaCollection?.takeIf { media == printer.defaultMedia || it.matches() }
    }

    fun pageSize(printer: PrinterRecord?, media: String?): Pair<Int, Int>? {
        val size = collection(printer, media)?.attributes?.get("media-size")?.firstOrNull() as? IppCollection
        val x = size?.attributes?.get("x-dimension")?.firstOrNull() as? Int
        val y = size?.attributes?.get("y-dimension")?.firstOrNull() as? Int
        if (x != null && y != null && x > 0 && y > 0) return (x * 72.0 / 2540).roundToInt() to (y * 72.0 / 2540).roundToInt()
        val match = Regex(".*_(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)(mm|in)$").matchEntire(media.orEmpty().lowercase()) ?: return null
        val multiplier = if (match.groupValues[3] == "in") 72.0 else 72.0 / 25.4
        val width = match.groupValues[1].toDouble() * multiplier
        val height = match.groupValues[2].toDouble() * multiplier
        require(width.isFinite() && height.isFinite() && width in 1.0..100_000.0 && height in 1.0..100_000.0) { "용지 크기를 처리할 수 없습니다" }
        return width.roundToInt() to height.roundToInt()
    }

    fun marginsMm(printer: PrinterRecord?, media: String?): List<Float>? {
        printer ?: return null
        val attrs = collection(printer, media)?.attributes
        val names = listOf("media-left-margin", "media-top-margin", "media-right-margin", "media-bottom-margin")
        val exact = names.map { attrs?.get(it)?.firstOrNull() as? Int }
        if (exact.all { it != null && it >= 0 }) return exact.map { it!! / 100f }
        val lists = listOf(printer.mediaLeftMargins, printer.mediaTopMargins, printer.mediaRightMargins, printer.mediaBottomMargins)
        return if (lists.all { it.distinct().size == 1 && it.first() >= 0 }) lists.map { it.first() / 100f } else null
    }

    fun rasterProblem(printer: PrinterRecord, mime: String, dpi: Int?, sides: String?): String? {
        val format = PrintFormatSupport.normalize(mime)
        if (format == PrintFormatSupport.PWG_RASTER) {
            if (printer.pwgTypes.none { it.equals("srgb_8", true) }) return "PWG Raster의 8비트 RGB 지원을 확인할 수 없습니다. 다른 변환 형식을 선택하세요."
            if (printer.pwgResolutions.isEmpty()) return "PWG Raster의 문서 해상도 지원 정보가 없습니다. 다른 변환 형식을 선택하세요."
            if (dpi != null && printer.pwgResolutions.none { it.units == 3 && it.x == dpi && it.y == dpi }) return "프린터가 PWG Raster ${dpi}dpi를 지원하지 않습니다. 지원 해상도: ${printer.pwgResolutions.joinToString { it.label }}"
            if (sides?.startsWith("two-sided") == true && printer.pwgSheetBack != "normal") return "이 프린터의 PWG Raster 양면 뒷면 처리는 지원하지 않습니다. 다른 형식 또는 단면을 선택하세요."
        }
        if (format == PrintFormatSupport.URF) {
            val tokens = printer.urfSupported.flatMap { it.split(',') }.map { it.trim().uppercase() }
            if ("SRGB24" !in tokens) return "URF의 24비트 RGB 지원을 확인할 수 없습니다. 다른 변환 형식을 선택하세요."
            val resolutions = tokens.filter { it.startsWith("RS") }.flatMap { it.drop(2).split('-') }.mapNotNull(String::toIntOrNull)
            if (resolutions.isEmpty()) return "URF의 문서 해상도 지원 정보가 없습니다. 다른 변환 형식을 선택하세요."
            if (dpi != null && dpi !in resolutions) return "프린터가 URF ${dpi}dpi를 지원하지 않습니다. 지원 해상도: ${resolutions.joinToString()}dpi"
            if (sides?.startsWith("two-sided") == true) return "현재 URF 변환은 단면만 지원합니다. 양면 인쇄에는 다른 변환 형식을 선택하세요."
        }
        return null
    }
}
