package kr.ac.hit.networkprinterstudio

object PrintLayoutSupport {
    fun fitScale(sourceWidth: Float, sourceHeight: Float, availableWidth: Float, availableHeight: Float, shrinkOnly: Boolean = false): Float {
        val scale = minOf(availableWidth / sourceWidth, availableHeight / sourceHeight)
        return if (shrinkOnly) minOf(1f, scale) else scale
    }

    fun optionsForLaidOutPages(printer: PrinterRecord, options: IppJobOptions): IppJobOptions = options.copy(
        pageRanges = emptyList(),
        numberUp = neutralInt(printer.numberUps, printer.defaultNumberUp, 1, "모아찍기", "1쪽씩", { it > 1 }),
        orientation = neutralInt(printer.orientations, printer.defaultOrientation, 3, "문서 방향", "세로", { it in 4..6 }),
        printScaling = neutralKeyword(printer.printScalings, printer.defaultPrintScaling, "none", "내용 크기 조절", setOf("auto", "auto-fit", "fit", "fill", "shrink-to-fit")),
        xImagePosition = neutralKeyword(printer.xImagePositions, printer.defaultXImagePosition, "center", "가로 위치", setOf("left", "right")),
        yImagePosition = neutralKeyword(printer.yImagePositions, printer.defaultYImagePosition, "center", "세로 위치", setOf("top", "bottom")),
    )

    private fun neutralInt(
        supported: List<Int>, default: Int?, neutral: Int, label: String, neutralLabel: String,
        changesLayout: (Int) -> Boolean,
    ): Int? {
        supported.firstOrNull { it == neutral }?.let { return it }
        if (default == neutral) return neutral.takeIf { supported.isEmpty() }
        require(default == null || !changesLayout(default)) {
            conflictingDefaultMessage(label, default.toString(), neutralLabel)
        }
        return null
    }

    private fun neutralKeyword(
        supported: List<String>, default: String?, neutral: String, label: String, changesLayout: Set<String>,
    ): String? {
        supported.firstOrNull { it.equals(neutral, true) }?.let { return it }
        val normalizedDefault = default?.trim()?.lowercase(java.util.Locale.ROOT)
        if (normalizedDefault == neutral) return neutral.takeIf { supported.isEmpty() }
        require(normalizedDefault !in changesLayout) {
            conflictingDefaultMessage(label, default.orEmpty(), neutral)
        }
        return null
    }

    private fun conflictingDefaultMessage(label: String, default: String, neutral: String): String =
        "전송 실패: 프린터의 기본 $label 설정은 '$default'이지만 '$neutral' 설정 지원을 확인할 수 없습니다. 앱에서 완성한 페이지에 배치가 다시 적용될 수 있어 전송하지 않았습니다."

    fun contentRotationDegrees(ippOrientation: Int?): Float = when (ippOrientation) {
        4 -> 90f
        5 -> 270f
        6 -> 180f
        else -> 0f
    }
}
