package kr.ac.hit.networkprinterstudio

object PrintLayoutSupport {
    fun contentRotationDegrees(ippOrientation: Int?): Float = when (ippOrientation) {
        4 -> 90f
        5 -> 270f
        6 -> 180f
        else -> 0f
    }
}
