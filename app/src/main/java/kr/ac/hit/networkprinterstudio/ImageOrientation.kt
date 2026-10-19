package kr.ac.hit.networkprinterstudio

internal object ImageOrientation {
    // EXIF uses the stored pixel axes; translations keep the transformed image at (0, 0).
    fun matrixValues(orientation: Int, width: Int, height: Int): FloatArray? {
        require(width > 0 && height > 0) { "사진 크기가 올바르지 않습니다" }
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> null
        }
    }
}
