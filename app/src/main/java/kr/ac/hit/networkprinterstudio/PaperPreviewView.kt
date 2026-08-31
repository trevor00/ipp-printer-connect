package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

data class PrintableAreaInsets(
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
)

class PaperPreviewView(
    context: Context,
    private val pageBitmap: Bitmap,
    private val printableInsets: PrintableAreaInsets? = null,
) : View(context) {
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(10f), 0f, dp(4f), 0x66000000)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 78, 90)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clippedAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 220, 38, 38)
        style = Paint.Style.FILL
    }
    private val printableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 18, 60)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.rgb(218, 222, 228))
        contentDescription = "흰색 테두리는 용지 경계이고 붉은 영역은 프린터가 출력하지 못할 수 있는 가장자리입니다"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageBitmap.isRecycled || width <= 0 || height <= 0) return
        val inset = dp(24f)
        val availableWidth = (width - inset * 2f).coerceAtLeast(1f)
        val availableHeight = (height - inset * 2f).coerceAtLeast(1f)
        val scale = minOf(availableWidth / pageBitmap.width, availableHeight / pageBitmap.height)
        val paperWidth = pageBitmap.width * scale
        val paperHeight = pageBitmap.height * scale
        val left = (width - paperWidth) / 2f
        val top = (height - paperHeight) / 2f
        val paper = RectF(left, top, left + paperWidth, top + paperHeight)
        canvas.drawRect(paper, paperPaint)
        canvas.drawBitmap(pageBitmap, null, paper, bitmapPaint)
        printableInsets?.let { insets ->
            val printable = RectF(
                paper.left + paper.width() * insets.leftRatio.coerceIn(0f, 0.45f),
                paper.top + paper.height() * insets.topRatio.coerceIn(0f, 0.45f),
                paper.right - paper.width() * insets.rightRatio.coerceIn(0f, 0.45f),
                paper.bottom - paper.height() * insets.bottomRatio.coerceIn(0f, 0.45f),
            )
            canvas.drawRect(paper.left, paper.top, paper.right, printable.top, clippedAreaPaint)
            canvas.drawRect(paper.left, printable.bottom, paper.right, paper.bottom, clippedAreaPaint)
            canvas.drawRect(paper.left, printable.top, printable.left, printable.bottom, clippedAreaPaint)
            canvas.drawRect(printable.right, printable.top, paper.right, printable.bottom, clippedAreaPaint)
            canvas.drawRect(printable, printableBorderPaint)
        }
        canvas.drawRect(paper, borderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
