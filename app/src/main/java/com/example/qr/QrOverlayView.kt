package com.example.qr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class QrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8F000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val frame = RectF()
    private val dimPath = Path()
    private val radius = dp(20f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w * 0.68f, h * 0.56f)
        val cx = w / 2f
        val cy = h * 0.44f
        frame.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return

        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRoundRect(frame, radius, radius, Path.Direction.CW)
        canvas.drawPath(dimPath, dimPaint)
        canvas.drawRoundRect(frame, radius, radius, borderPaint)

        val inset = dp(2f)
        val len = dp(26f)
        val l = frame.left + inset
        val t = frame.top + inset
        val r = frame.right - inset
        val b = frame.bottom - inset

        canvas.drawLine(l, t + len, l, t + radius, cornerPaint)
        canvas.drawLine(l, t, l + len, t, cornerPaint)

        canvas.drawLine(r - len, t, r, t, cornerPaint)
        canvas.drawLine(r, t + radius, r, t + len, cornerPaint)

        canvas.drawLine(l, b - len, l, b - radius, cornerPaint)
        canvas.drawLine(l, b, l + len, b, cornerPaint)

        canvas.drawLine(r - len, b, r, b, cornerPaint)
        canvas.drawLine(r, b - radius, r, b - len, cornerPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
