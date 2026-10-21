package kr.ac.hit.networkprinterstudio

import kotlin.math.roundToLong

/** Exact conversion dimensions: reject impossible requests instead of silently lowering quality. */
object RenderQuality {
    fun pixelDimensions(widthPoints: Int, heightPoints: Int, scale: Float): Pair<Int, Int> {
        require(widthPoints > 0 && heightPoints > 0 && scale.isFinite() && scale > 0f) {
            "용지 크기와 변환 해상도는 0보다 커야 합니다"
        }
        val width = (widthPoints.toDouble() * scale).roundToLong().coerceAtLeast(1)
        val height = (heightPoints.toDouble() * scale).roundToLong().coerceAtLeast(1)
        require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE &&
            width * height <= Int.MAX_VALUE / 4L) {
            "선택한 크기의 이미지는 Android 비트맵 한도를 초과합니다. 해상도·배율을 직접 변경해 주세요."
        }
        return width.toInt() to height.toInt()
    }

    fun requirePclDpi(dpi: Int) {
        require(dpi in setOf(75, 100, 150, 200, 300, 600, 1200)) {
            "PCL 변환 해상도는 75·100·150·200·300·600·1200 dpi 중에서 선택해 주세요."
        }
    }
}
