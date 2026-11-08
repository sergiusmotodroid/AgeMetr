package com.example.agemetr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

data class AgeGenderEstimate(
    val ageYears: Int,
    val gender: String,
    /** Уверенность модели в поле, 0.5…1.0 */
    val genderConfidence: Float = 1f,
)

/**
 * Возраст и пол на устройстве: модель MiVOLO v2 (только лицо), ONNX int8.
 * Вход: лицо 384×384, RGB, нормализация ImageNet. Выход: [p_мужской, p_женский, возраст].
 */
class AgeModels(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy {
        val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(bytes, options)
    }

    /** Первый вызов загружает модель (около секунды) — можно вызвать заранее в фоне. */
    fun warmUp() {
        session
    }

    /** [ageFace] не используется (оставлен для совместимости), лицо берётся из [genderFace]. */
    @Suppress("UNUSED_PARAMETER")
    fun predict(ageFace: Bitmap, genderFace: Bitmap): AgeGenderEstimate = predict(genderFace)

    @Synchronized
    fun predict(face: Bitmap): AgeGenderEstimate {
        val input = toInput(face)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<FloatArray>)[0]
                val male = out[0]
                val female = out[1]
                val age = out[2].roundToInt().coerceIn(1, 100)
                return AgeGenderEstimate(
                    ageYears = age,
                    gender = if (male >= female) "мужской" else "женский",
                    genderConfidence = max(male, female),
                )
            }
        }
    }

    /** Вписывает лицо в квадрат 384×384 с чёрными полями (как при обучении модели). */
    private fun toInput(face: Bitmap): FloatBuffer {
        val scale = SIZE.toFloat() / max(face.width, face.height)
        val w = (face.width * scale).roundToInt().coerceIn(1, SIZE)
        val h = (face.height * scale).roundToInt().coerceIn(1, SIZE)
        val boxed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.BLACK)
            val left = (SIZE - w) / 2
            val top = (SIZE - h) / 2
            drawBitmap(face, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(SIZE * SIZE)
        boxed.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        boxed.recycle()

        val plane = SIZE * SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            data[plane + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            data[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return FloatBuffer.wrap(data)
    }

    companion object {
        private const val MODEL_FILE = "mivolo_face_int8.onnx"
        private const val SIZE = 384
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
