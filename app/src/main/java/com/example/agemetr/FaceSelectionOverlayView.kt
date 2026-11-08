package com.example.agemetr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Draws every detected photo face and marks the face used for the displayed result. */
class FaceSelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var boxes: List<Rect> = emptyList()
    private var chosenIndex = -1
    private var labels: List<String> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val otherFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        setShadowLayer(4f * resources.displayMetrics.density, 0f, 1f, 0x99000000.toInt())
    }
    private val chosenFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        setShadowLayer(5f * resources.displayMetrics.density, 0f, 1f, 0xCC000000.toInt())
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 8, 145, 178)
    }

    /** Подписи над рамками (по индексу лица). Пустая строка — без подписи. */
    fun setLabels(newLabels: List<String>) {
        labels = newLabels
        invalidate()
    }

    fun setFaces(faces: List<Rect>, selected: Int, imageWidth: Int, imageHeight: Int) {
        boxes = faces.map(::Rect)
        labels = emptyList()
        chosenIndex = selected
        sourceWidth = imageWidth
        sourceHeight = imageHeight
        visibility = if (boxes.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        labels = emptyList()
        chosenIndex = -1
        sourceWidth = 0
        sourceHeight = 0
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // Match ImageView.ScaleType.FIT_CENTER, including the blank margins.
        val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        boxes.forEachIndexed { index, box ->
            val left = offsetX + box.left * scale
            val top = offsetY + box.top * scale
            val right = offsetX + box.right * scale
            val bottom = offsetY + box.bottom * scale
            canvas.drawRect(left, top, right, bottom, if (index == chosenIndex) chosenFacePaint else otherFacePaint)

            val label = labels.getOrNull(index).orEmpty()
            if (label.isNotEmpty()) {
                val pad = 4f * resources.displayMetrics.density
                val textWidth = labelTextPaint.measureText(label)
                val textHeight = labelTextPaint.textSize
                // Подпись над рамкой; если места сверху нет — внутри рамки.
                val labelBottom = if (top - textHeight - 2 * pad >= 0f) top else top + textHeight + 2 * pad
                canvas.drawRoundRect(
                    left, labelBottom - textHeight - 2 * pad, left + textWidth + 2 * pad, labelBottom,
                    pad, pad, labelBackgroundPaint,
                )
                canvas.drawText(label, left + pad, labelBottom - pad - labelTextPaint.descent(), labelTextPaint)
            }
        }
    }
}
