package com.example.agemetr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Fixed face-shaped guide drawn above the live camera preview. */
class FaceGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(5f * resources.displayMetrics.density, 0f, 1f, 0x66000000)
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 37, 33, 42)
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Keep a natural head silhouette (about 0.72 width-to-height), independent
        // of the tall portrait preview. The previous percentage-based oval looked
        // like a long neck on a phone screen.
        val guideWidth = minOf(width * 0.72f, height * 0.38f)
        val guideHeight = guideWidth / 0.72f
        val left = (width - guideWidth) / 2f
        val top = (height - guideHeight) / 2f
        val right = left + guideWidth
        val bottom = top + guideHeight
        val centerX = width / 2f

        val outline = Path().apply {
            moveTo(centerX, top)
            // Rounded forehead and temples.
            cubicTo(centerX - guideWidth * 0.34f, top,
                left + guideWidth * 0.02f, top + guideHeight * 0.12f,
                left + guideWidth * 0.03f, top + guideHeight * 0.38f)
            // Cheek and jaw taper into a rounded, not pointed, chin.
            cubicTo(left + guideWidth * 0.04f, top + guideHeight * 0.62f,
                centerX - guideWidth * 0.22f, bottom,
                centerX, bottom)
            cubicTo(centerX + guideWidth * 0.22f, bottom,
                right - guideWidth * 0.04f, top + guideHeight * 0.62f,
                right - guideWidth * 0.03f, top + guideHeight * 0.38f)
            cubicTo(right - guideWidth * 0.02f, top + guideHeight * 0.12f,
                centerX + guideWidth * 0.34f, top,
                centerX, top)
        }

        // Keep the mask fixed to this guide, independent of the detector's box.
        val outsideMask = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addPath(outline)
        }
        canvas.drawPath(outsideMask, maskPaint)
        canvas.drawPath(outline, paint)

        val oldColor = paint.color
        val oldStrokeWidth = paint.strokeWidth
        paint.clearShadowLayer()
        paint.color = 0x99FFFFFF.toInt()
        paint.strokeWidth = 1.5f * resources.displayMetrics.density
        val eyeY = top + guideHeight * 0.40f
        val eyeInnerGap = guideWidth * 0.08f
        val eyeLength = guideWidth * 0.15f
        canvas.drawLine(centerX - eyeInnerGap - eyeLength, eyeY,
            centerX - eyeInnerGap, eyeY, paint)
        canvas.drawLine(centerX + eyeInnerGap, eyeY,
            centerX + eyeInnerGap + eyeLength, eyeY, paint)
        canvas.drawLine(centerX, eyeY + guideHeight * 0.04f,
            centerX, eyeY + guideHeight * 0.17f, paint)
        paint.color = oldColor
        paint.strokeWidth = oldStrokeWidth
        paint.setShadowLayer(5f * resources.displayMetrics.density, 0f, 1f, 0x66000000)
    }
}
