package kr.ac.hit.networkprinterstudio

import kotlin.math.ceil
import kotlin.math.floor

/** HP PCL 5 Quick Reference, page/job control and raster graphics commands.
 * https://h10032.www1.hp.com/ctg/Manual/bpl13205.pdf
 * These commands use the printer's logical page origin; they cannot remove hardware clipping.
 */
internal object PclPageSetup {
    private data class Paper(val code: Int, val shortPoints: Double, val longPoints: Double)
    private fun mm(value: Double) = value * 72.0 / 25.4
    private val papers = listOf(
        Paper(1, 522.0, 756.0),       // Executive 7.25 x 10.5 inches
        Paper(2, 612.0, 792.0),       // Letter
        Paper(3, 612.0, 1008.0),      // Legal
        Paper(6, 792.0, 1224.0),      // Ledger 11 x 17 inches
        Paper(25, mm(148.0), mm(210.0)),
        Paper(26, mm(210.0), mm(297.0)),
        Paper(27, mm(297.0), mm(420.0)),
    )

    fun pageSizeCode(widthPoints: Int, heightPoints: Int): Int {
        require(widthPoints > 0 && heightPoints > 0) { "PCL 용지 크기는 0보다 커야 합니다" }
        val short = minOf(widthPoints, heightPoints); val long = maxOf(widthPoints, heightPoints)
        // PdfRenderer exposes integer points. Accept either integer rounding of a metric paper edge,
        // but never treat an arbitrary custom page as the nearest standard sheet.
        return papers.firstOrNull { matches(short, it.shortPoints) && matches(long, it.longPoints) }?.code
            ?: error("전송 실패: 현재 PCL 변환은 ${widthPoints}×${heightPoints} pt 용지 크기를 설정할 수 없습니다. 사용자 지정 용지를 다른 크기로 자동 변경하지 않았습니다. A3·A4·A5·Letter·Legal·Ledger·Executive 용지를 선택하거나 다른 출력 형식을 사용해 주세요.")
    }

    fun commands(widthPoints: Int, heightPoints: Int, dpi: Int, rasterWidth: Int, rasterHeight: Int): String {
        RenderQuality.requirePclDpi(dpi)
        require(rasterWidth > 0 && rasterHeight > 0) { "PCL 이미지 크기는 0보다 커야 합니다" }
        val paper = pageSizeCode(widthPoints, heightPoints)
        val orientation = if (widthPoints > heightPoints) 1 else 0
        return "\u001b&l${paper}A\u001b&l${orientation}O" +
            // Orientation and perforation changes restore defaults, so clear margins afterwards.
            "\u001b&l0L\u001b&l0E\u001b9\u001b&a0P\u001b*p0x0Y" +
            // Default presentation=3 ignores logical landscape; 0 follows the selected orientation.
            "\u001b*r0F\u001b*t${dpi}R\u001b*r${rasterWidth}S\u001b*r${rasterHeight}T\u001b*r1A\u001b*b0M"
    }

    private fun matches(value: Int, points: Double): Boolean =
        value in floor(points).toInt()..ceil(points).toInt()
}
