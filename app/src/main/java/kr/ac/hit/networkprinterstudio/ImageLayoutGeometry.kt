package kr.ac.hit.networkprinterstudio

internal data class ImagePageBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Placement uses logical source dimensions, independently of decoded bitmap resolution. */
internal object ImageLayoutGeometry {
    fun insets(options: ContentLayoutOptions): FloatArray = when {
        options.scale == ContentScale.FULL_BLEED_FILL -> FloatArray(4)
        options.scale == ContentScale.PRINTABLE_FIT || options.usePrinterMargins -> floatArrayOf(
            options.printableLeft.toFloat(), options.printableTop.toFloat(),
            options.printableRight.toFloat(), options.printableBottom.toFloat(),
        )
        else -> FloatArray(4) { options.margin.toFloat() }
    }
    fun place(sourceWidth: Float, sourceHeight: Float, options: ContentLayoutOptions): ImagePageBounds {
        require(sourceWidth.isFinite() && sourceHeight.isFinite() && sourceWidth > 0f && sourceHeight > 0f) {
            "이미지의 원본 크기는 0보다 커야 합니다"
        }
        val insets = insets(options)
        val availableWidth = options.pageWidth - insets[0] - insets[2]
        val availableHeight = options.pageHeight - insets[1] - insets[3]
        require(availableWidth > 0 && availableHeight > 0) { "용지보다 여백이 큽니다" }
        val baseScale = when (options.scale) {
            ContentScale.FILL, ContentScale.FULL_BLEED_FILL ->
                maxOf(availableWidth / sourceWidth, availableHeight / sourceHeight)
            ContentScale.ACTUAL -> 1f
            ContentScale.SHRINK_ONLY -> PrintLayoutSupport.fitScale(
                sourceWidth, sourceHeight, availableWidth, availableHeight, shrinkOnly = true,
            )
            ContentScale.FIT, ContentScale.PRINTABLE_FIT ->
                minOf(availableWidth / sourceWidth, availableHeight / sourceHeight)
        }
        val scale = baseScale * options.scalePercent / 100f
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = when (options.horizontal) {
            ContentHorizontal.LEFT -> insets[0]
            ContentHorizontal.CENTER -> insets[0] + (availableWidth - width) / 2f
            ContentHorizontal.RIGHT -> options.pageWidth - insets[2] - width
        } + options.offsetX
        val top = when (options.vertical) {
            ContentVertical.TOP -> insets[1]
            ContentVertical.CENTER -> insets[1] + (availableHeight - height) / 2f
            ContentVertical.BOTTOM -> options.pageHeight - insets[3] - height
        } + options.offsetY
        return ImagePageBounds(left, top, left + width, top + height)
    }
}
