package kr.ac.hit.networkprinterstudio

object PrintLayoutSupport {
    fun fitScale(sourceWidth: Float, sourceHeight: Float, availableWidth: Float, availableHeight: Float, shrinkOnly: Boolean = false): Float {
        val scale = minOf(availableWidth / sourceWidth, availableHeight / sourceHeight)
        return if (shrinkOnly) minOf(1f, scale) else scale
    }

    fun optionsForLaidOutPages(printer: PrinterRecord, options: IppJobOptions): IppJobOptions = options.copy(
        pageRanges = emptyList(),
        numberUp = printer.numberUps.firstOrNull { it == 1 },
        orientation = printer.orientations.firstOrNull { it == 3 },
        printScaling = printer.printScalings.firstOrNull { it.equals("none", true) },
        xImagePosition = printer.xImagePositions.firstOrNull { it.equals("center", true) },
        yImagePosition = printer.yImagePositions.firstOrNull { it.equals("center", true) },
    )

    fun contentRotationDegrees(ippOrientation: Int?): Float = when (ippOrientation) {
        4 -> 90f
        5 -> 270f
        6 -> 180f
        else -> 0f
    }
}
