package net.meinook.labelscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ClinicalGaugeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentValue: Float = 0f
    private var yellowThreshold: Float = 200f
    private var redThreshold: Float = 400f
    private var maxScale: Float = 600f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F1714")
        style = Paint.Style.FILL
    }

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#81C784")
        style = Paint.Style.FILL
    }

    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.FILL
    }

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B6B")
        style = Paint.Style.FILL
    }

    private val clipPath = Path()
    private val boundsRect = RectF()

    fun setGaugeData(value: Double, yellowLimit: Double, redLimit: Double, scaleMax: Double) {
        this.currentValue = value.toFloat().coerceAtLeast(0f)
        this.yellowThreshold = yellowLimit.toFloat().coerceAtLeast(1f)
        this.redThreshold = redLimit.toFloat().coerceAtLeast(this.yellowThreshold)
        this.maxScale = scaleMax.toFloat().coerceAtLeast(this.redThreshold * 1.1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = h / 2f
        boundsRect.set(0f, 0f, w, h)

        // 1. Draw Background Capsule Track
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, trackPaint)

        val safeScale = if (maxScale > 0f) maxScale else 1f
        val greenEndX = (yellowThreshold / safeScale).coerceIn(0f, 1f) * w
        val yellowEndX = (redThreshold / safeScale).coerceIn(0f, 1f) * w
        val valueEndX = (currentValue / safeScale).coerceIn(0f, 1f) * w

        if (valueEndX <= 0f) return

        // 2. Clip Fill Area to the Capsule Track
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 3. Draw Green Safe Zone
        val gEnd = minOf(valueEndX, greenEndX)
        if (gEnd > 0f) {
            canvas.drawRect(0f, 0f, gEnd, h, greenPaint)
        }

        // 4. Draw Yellow Caution Zone (if value crossed caution threshold)
        if (valueEndX > greenEndX) {
            val yEnd = minOf(valueEndX, yellowEndX)
            canvas.drawRect(greenEndX, 0f, yEnd, h, yellowPaint)
        }

        // 5. Draw Red Danger Zone (if value blew past redline)
        if (valueEndX > yellowEndX) {
            canvas.drawRect(yellowEndX, 0f, valueEndX, h, redPaint)
        }

        canvas.restore()
    }
}