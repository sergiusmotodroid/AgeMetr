package com.example.agemetr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Рисует рамки найденных лиц и подписи к ним. Подписи не налезают друг на друга:
 * если полные подписи («2: М, 44») не помещаются, остаются только номера лиц,
 * а подробности показывает карточка результата.
 * Учитывает padding так же, как ImageView со scaleType FIT_CENTER.
 */
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

    private val density = resources.displayMetrics.density
    private val pad = 4f * density
    private val gap = 3f * density

    private val otherFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        setShadowLayer(4f * density, 0f, 1f, 0x99000000.toInt())
    }
    private val chosenFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        setShadowLayer(5f * density, 0f, 1f, 0xCC000000.toInt())
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
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

        // Как у ImageView.ScaleType.FIT_CENTER внутри padding, включая пустые поля.
        val areaWidth = (width - paddingLeft - paddingRight).toFloat()
        val areaHeight = (height - paddingTop - paddingBottom).toFloat()
        if (areaWidth <= 0f || areaHeight <= 0f) return
        val scale = min(areaWidth / sourceWidth, areaHeight / sourceHeight)
        val offsetX = paddingLeft + (areaWidth - sourceWidth * scale) / 2f
        val offsetY = paddingTop + (areaHeight - sourceHeight * scale) / 2f

        val screenBoxes = boxes.map { box ->
            RectF(
                offsetX + box.left * scale,
                offsetY + box.top * scale,
                offsetX + box.right * scale,
                offsetY + box.bottom * scale,
            )
        }
        screenBoxes.forEachIndexed { index, r ->
            canvas.drawRect(r, if (index == chosenIndex) chosenFacePaint else otherFacePaint)
        }

        if (labels.isEmpty()) return
        // Сначала пробуем полные подписи, при наложении — только номера лиц.
        val placed = layoutLabels(screenBoxes, labels)
            ?: layoutLabels(screenBoxes, labels.indices.map { i -> if (labels[i].isEmpty()) "" else "${i + 1}" })
            ?: layoutLabels(screenBoxes, labels.indices.map { i -> if (labels[i].isEmpty()) "" else "${i + 1}" }, force = true)
            ?: return
        placed.forEach { (text, rect) ->
            canvas.drawRoundRect(rect, pad, pad, labelBackgroundPaint)
            val baseline = rect.centerY() - (labelTextPaint.ascent() + labelTextPaint.descent()) / 2f
            canvas.drawText(text, rect.centerX(), baseline, labelTextPaint)
        }
    }

    /**
     * Ставит подпись по центру над рамкой, при нехватке места — под рамкой.
     * Возвращает null, если какие-то подписи налезают друг на друга (кроме [force]).
     */
    private fun layoutLabels(screenBoxes: List<RectF>, texts: List<String>, force: Boolean = false): List<Pair<String, RectF>>? {
        val textHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        val labelHeight = textHeight + 2 * pad
        val result = mutableListOf<Pair<String, RectF>>()
        texts.forEachIndexed { index, text ->
            if (text.isEmpty()) return@forEachIndexed
            val box = screenBoxes.getOrNull(index) ?: return@forEachIndexed
            val labelWidth = labelTextPaint.measureText(text) + 2 * pad
            val left = (box.centerX() - labelWidth / 2f).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            val above = RectF(left, box.top - gap - labelHeight, left + labelWidth, box.top - gap)
            val below = RectF(left, box.bottom + gap, left + labelWidth, box.bottom + gap + labelHeight)
            val candidates = listOf(above, below).filter { it.top >= 0f && it.bottom <= height }
            val free = candidates.firstOrNull { c -> result.none { RectF.intersects(it.second, c) } }
            when {
                free != null -> result += text to free
                force -> result += text to (candidates.firstOrNull() ?: above)
                else -> return null
            }
        }
        return result
    }
}
