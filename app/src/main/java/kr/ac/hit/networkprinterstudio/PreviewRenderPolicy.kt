package kr.ac.hit.networkprinterstudio

import kotlin.math.floor

/** Screen-only limits; these values must not be used to reduce printed output quality. */
internal object PreviewRenderPolicy {
    const val MAX_WIDTH = 900
    const val MAX_HEIGHT = 1600
    const val MAX_IMAGE_EDGE = 2048
    const val MAX_IMAGE_PIXELS = 2_000_000L

    fun pagePixelSize(
        width: Int,
        height: Int,
        maxWidth: Int = MAX_WIDTH,
        maxHeight: Int = MAX_HEIGHT,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "미리보기 페이지 크기는 양수여야 합니다" }
        require(maxWidth > 0 && maxHeight > 0) { "미리보기 최대 크기는 양수여야 합니다" }

        // Keep an exact rational scale. Products of two positive Int values fit in Long.
        var numerator = 2L
        var denominator = 1L
        if (maxWidth.toLong() * denominator < width.toLong() * numerator) {
            numerator = maxWidth.toLong()
            denominator = width.toLong()
        }
        if (maxHeight.toLong() * denominator < height.toLong() * numerator) {
            numerator = maxHeight.toLong()
            denominator = height.toLong()
        }
        val pixelWidth = (width.toLong() * numerator / denominator).coerceIn(1L, maxWidth.toLong())
        val pixelHeight = (height.toLong() * numerator / denominator).coerceIn(1L, maxHeight.toLong())
        return pixelWidth.toInt() to pixelHeight.toInt()
    }

    fun imageSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "미리보기 이미지 크기는 양수여야 합니다" }
        var sample = 1
        while (true) {
            // Bitmap decoders can round sampled dimensions up, so use ceiling division.
            val sampledWidth = (width.toLong() + sample - 1L) / sample
            val sampledHeight = (height.toLong() + sample - 1L) / sample
            if (sampledWidth <= MAX_IMAGE_EDGE && sampledHeight <= MAX_IMAGE_EDGE &&
                sampledWidth * sampledHeight <= MAX_IMAGE_PIXELS) return sample
            check(sample <= Int.MAX_VALUE / 2) { "미리보기 이미지 크기를 계산할 수 없습니다" }
            sample *= 2
        }
    }

    fun rasterDpi(pageWidth: Int, pageHeight: Int): Int {
        require(pageWidth > 0 && pageHeight > 0) { "미리보기 페이지 크기는 양수여야 합니다" }
        val scale = minOf(MAX_WIDTH.toDouble() / pageWidth, MAX_HEIGHT.toDouble() / pageHeight)
        return floor(72.0 * scale).toInt().coerceIn(1, 150)
    }

    fun optionsForDisplay(options: ContentLayoutOptions): ContentLayoutOptions = options.copy(
        rasterDpi = minOf(options.rasterDpi, rasterDpi(options.pageWidth, options.pageHeight)),
    )
}
