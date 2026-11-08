package com.example.agemetr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.method.LinkMovementMethod
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val LIVE_SAMPLE_COUNT = 3
    }

    private lateinit var cameraPreview: PreviewView
    private lateinit var photoPreview: ImageView
    private lateinit var photoPlaceholder: TextView
    private lateinit var faceGuide: FaceGuideView
    private lateinit var faceSearchLabel: TextView
    private lateinit var faceSelectionOverlay: FaceSelectionOverlayView
    private lateinit var resultText: TextView
    private lateinit var resultCard: MaterialCardView
    private lateinit var progressBar: ProgressBar
    private lateinit var cameraButton: Button
    private lateinit var photoButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var detector: FaceDetector
    private lateinit var photoDetector: FaceDetector
    private lateinit var models: AgeModels

    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameInferenceRunning = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var selectedPhotoBitmap: Bitmap? = null
    @Volatile private var captureInProgress = false
    private var previousStableFace: Rect? = null
    private var stableFaceFrames = 0
    // Оценки с нескольких кадров: итог — медиана возраста и большинство по полу
    private val liveSamples = java.util.Collections.synchronizedList(mutableListOf<AgeGenderEstimate>())
    private var useFrontCamera = false
    private var liveMode = true
    @Volatile private var activePhotoRequest = 0
    private var lastFrameStartedAt = 0L

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startLiveCamera()
        } else {
            cameraPreview.visibility = View.VISIBLE
            faceGuide.visibility = View.GONE
            photoPlaceholder.text = "Разрешите доступ к камере"
            photoPlaceholder.visibility = View.VISIBLE
            cameraButton.visibility = View.VISIBLE
            showResult("Камера недоступна")
        }
    }

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            enterPhotoMode()
            analyzePhoto(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        photoPreview = findViewById(R.id.photoPreview)
        photoPlaceholder = findViewById(R.id.photoPlaceholder)
        faceGuide = findViewById(R.id.faceGuide)
        faceSearchLabel = findViewById(R.id.faceSearchLabel)
        faceSelectionOverlay = findViewById(R.id.faceSelectionOverlay)
        resultText = findViewById(R.id.resultText)
        resultCard = findViewById(R.id.resultCard)
        progressBar = findViewById(R.id.progressBar)
        cameraButton = findViewById(R.id.cameraButton)
        photoButton = findViewById(R.id.photoButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)

        models = AgeModels(applicationContext)
        inferenceExecutor.execute { runCatching { models.warmUp() } }
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build(),
        )
        // Для фото скорость не важна: точный режим находит мелкие лица на групповых снимках
        photoDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.03f)
                .build(),
        )

        cameraButton.setOnClickListener { returnToLiveCamera() }
        switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startLiveCamera()
        }
        photoButton.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        findViewById<View>(R.id.aboutButton).setOnClickListener { showAboutDialog() }

        cameraPreview.post {
            if (hasCameraPermission()) startLiveCamera()
            else requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Авторы модели и лицензии: CC BY-NC 4.0 требует указать авторство в приложении. */
    private fun showAboutDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(HtmlCompat.fromHtml(getString(R.string.about_text).trim(), HtmlCompat.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.about_close, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startLiveCamera() {
        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        liveMode = true
        activePhotoRequest++
        captureInProgress = false
        previousStableFace = null
        stableFaceFrames = 0
        resetLiveSamples()
        cameraPreview.visibility = View.VISIBLE
        photoPreview.visibility = View.GONE
        clearPhotoPreview()
        photoPlaceholder.visibility = View.GONE
        faceGuide.visibility = View.VISIBLE
        faceSearchLabel.text = "Поиск лица…"
        faceSearchLabel.visibility = View.VISIBLE
        faceSelectionOverlay.clear()
        cameraButton.visibility = View.GONE
        switchCameraButton.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        clearResult()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!liveMode || isFinishing) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(cameraPreview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeCameraFrame)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                faceSearchLabel.visibility = View.GONE
                photoPlaceholder.text = "Камера недоступна"
                photoPlaceholder.visibility = View.VISIBLE
                faceGuide.visibility = View.GONE
                cameraButton.visibility = View.VISIBLE
                showResult("Камера недоступна")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeCameraFrame(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!liveMode || captureInProgress || now - lastFrameStartedAt < 300L ||
            !frameInferenceRunning.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }
        lastFrameStartedAt = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            frameInferenceRunning.set(false)
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        try {
            detector.process(InputImage.fromMediaImage(mediaImage, rotation))
                .addOnSuccessListener(cameraExecutor) { faces ->
                    var frameClosed = false
                    try {
                        if (!liveMode || faces.isEmpty()) {
                            previousStableFace = null
                            stableFaceFrames = 0
                            resetLiveSamples()
                            imageProxy.close()
                            frameClosed = true
                            frameInferenceRunning.set(false)
                            return@addOnSuccessListener
                        }

                        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
                        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
                        val centeredFaces = faces.filter {
                            isFaceCentered(it.boundingBox, uprightWidth, uprightHeight)
                        }
                        if (centeredFaces.isEmpty()) {
                            previousStableFace = null
                            stableFaceFrames = 0
                            resetLiveSamples()
                            imageProxy.close()
                            frameClosed = true
                            frameInferenceRunning.set(false)
                            return@addOnSuccessListener
                        }
                        val largestFace = centeredFaces.maxBy {
                            it.boundingBox.width().toLong() * it.boundingBox.height()
                        }
                        val faceBounds = Rect(largestFace.boundingBox)
                        if (faceBounds.width() < 72) {
                            previousStableFace = null
                            stableFaceFrames = 0
                            resetLiveSamples()
                            imageProxy.close()
                            frameClosed = true
                            frameInferenceRunning.set(false)
                            return@addOnSuccessListener
                        }

                        val stableCapture = isStableFace(faceBounds)
                        if (!stableCapture) {
                            imageProxy.close()
                            frameClosed = true
                            frameInferenceRunning.set(false)
                            return@addOnSuccessListener
                        }
                        captureInProgress = true
                        val requestId = activePhotoRequest
                        val cameraBitmap = imageProxy.toBitmap()
                        val uprightBitmap = rotateBitmap(cameraBitmap, rotation)
                        val ageFace = cropFace(uprightBitmap, faceBounds, 1.7f)
                        val genderFace = cropFace(uprightBitmap, faceBounds, 1.15f)
                        val displaySource = if (useFrontCamera) mirrorBitmap(uprightBitmap) else uprightBitmap

                        runOnUiThread {
                            // Ход анализа показывается в надписи сверху, без ProgressBar — экран не дёргается
                            if (liveMode && requestId == activePhotoRequest && liveSamples.isEmpty()) faceSearchLabel.text = "Анализ… не двигайтесь"
                        }

                        imageProxy.close()
                        frameClosed = true
                        inferenceExecutor.execute {
                            var capturedFrame: Bitmap? = null
                            try {
                                val prediction = models.predict(ageFace, genderFace)
                                capturedFrame = displaySource.copy(Bitmap.Config.ARGB_8888, false)
                                val frameForUi = capturedFrame
                                capturedFrame = null
                                runOnUiThread {
                                    if (liveMode && requestId == activePhotoRequest && !isFinishing) {
                                        liveSamples.add(prediction)
                                        val collected = liveSamples.size
                                        if (collected < LIVE_SAMPLE_COUNT) {
                                            // Ещё не все кадры: продолжаем смотреть
                                            frameForUi?.takeIf { !it.isRecycled }?.recycle()
                                            progressBar.visibility = View.INVISIBLE
                                            faceSearchLabel.text = "Анализ $collected/$LIVE_SAMPLE_COUNT… не двигайтесь"
                                            captureInProgress = false
                                        } else {
                                            val result = combineSamples(liveSamples.toList())
                                            liveSamples.clear()
                                            showFrozenCameraResult(frameForUi!!, result)
                                        }
                                    } else {
                                        frameForUi?.takeIf { !it.isRecycled }?.recycle()
                                    }
                                }
                            } catch (_: Exception) {
                                runOnUiThread {
                                    if (liveMode && requestId == activePhotoRequest && !isFinishing) {
                                        captureInProgress = false
                                        previousStableFace = null
                                        stableFaceFrames = 0
                                        resetLiveSamples()
                                        progressBar.visibility = View.INVISIBLE
                                    }
                                }
                            } finally {
                                capturedFrame?.takeIf { !it.isRecycled }?.recycle()
                                ageFace.recycle()
                                genderFace.recycle()
                                if (uprightBitmap !== cameraBitmap) uprightBitmap.recycle()
                                if (displaySource !== uprightBitmap) displaySource.recycle()
                                cameraBitmap.recycle()
                                frameInferenceRunning.set(false)
                            }
                        }
                    } catch (_: Exception) {
                        if (!frameClosed) imageProxy.close()
                        captureInProgress = false
                        frameInferenceRunning.set(false)
                    }
                }
                .addOnFailureListener(cameraExecutor) {
                    imageProxy.close()
                    captureInProgress = false
                    frameInferenceRunning.set(false)
                }
        } catch (_: Exception) {
            imageProxy.close()
            captureInProgress = false
            frameInferenceRunning.set(false)
        }
    }

    private fun resetLiveSamples() {
        if (liveSamples.isEmpty()) return
        liveSamples.clear()
        runOnUiThread { if (liveMode) faceSearchLabel.text = "Поиск лица…" }
    }

    private fun combineSamples(samples: List<AgeGenderEstimate>): AgeGenderEstimate {
        val ages = samples.map { it.ageYears }.sorted()
        val medianAge = ages[ages.size / 2]
        val gender = samples.groupingBy { it.gender }.eachCount().maxBy { it.value }.key
        val confidence = samples.filter { it.gender == gender }.map { it.genderConfidence }.average().toFloat()
        return AgeGenderEstimate(medianAge, gender, confidence)
    }

    private fun isStableFace(bounds: Rect): Boolean {
        val previous = previousStableFace
        val centerTolerance = max(12, (bounds.width() * 0.12f).toInt())
        val stable = previous != null &&
            abs(bounds.centerX() - previous.centerX()) <= centerTolerance &&
            abs(bounds.centerY() - previous.centerY()) <= centerTolerance &&
            abs(bounds.width() - previous.width()) <= max(12, (previous.width() * 0.18f).toInt()) &&
            abs(bounds.height() - previous.height()) <= max(12, (previous.height() * 0.18f).toInt())
        if (!stable) resetLiveSamples()
        stableFaceFrames = if (stable) stableFaceFrames + 1 else 1
        previousStableFace = Rect(bounds)
        return stableFaceFrames >= 2
    }

    private fun isFaceCentered(bounds: Rect, imageWidth: Int, imageHeight: Int): Boolean =
        abs(bounds.centerX() - imageWidth / 2) <= imageWidth * 0.20f &&
            abs(bounds.centerY() - imageHeight / 2) <= imageHeight * 0.20f

    private fun showFrozenCameraResult(frame: Bitmap, prediction: AgeGenderEstimate) {
        liveMode = false
        cameraProvider?.unbindAll()
        cameraPreview.visibility = View.GONE
        faceSearchLabel.visibility = View.GONE
        faceGuide.visibility = View.VISIBLE
        faceSelectionOverlay.clear()
        photoPreview.visibility = View.VISIBLE
        clearPhotoPreview()
        photoPreview.scaleType = ImageView.ScaleType.CENTER_CROP
        selectedPhotoBitmap = frame
        photoPreview.setImageBitmap(frame)
        photoPlaceholder.visibility = View.GONE
        switchCameraButton.visibility = View.GONE
        cameraButton.text = "Переснять"
        cameraButton.visibility = View.VISIBLE
        captureInProgress = false
        showResult(singleResultText(prediction))
    }

    private fun mirrorBitmap(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun enterPhotoMode() {
        liveMode = false
        activePhotoRequest++
        captureInProgress = false
        cameraProvider?.unbindAll()
        cameraPreview.visibility = View.GONE
        faceGuide.visibility = View.GONE
        faceSearchLabel.visibility = View.GONE
        faceSelectionOverlay.clear()
        photoPreview.visibility = View.VISIBLE
        clearPhotoPreview()
        photoPlaceholder.text = "Загружаю фото…"
        photoPlaceholder.visibility = View.VISIBLE
        cameraButton.visibility = View.VISIBLE
        cameraButton.text = "К камере"
        switchCameraButton.visibility = View.GONE
        clearResult()
    }

    private fun returnToLiveCamera() {
        if (hasCameraPermission()) startLiveCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun analyzePhoto(uri: Uri) {
        val requestId = ++activePhotoRequest
        clearResult()
        photoPlaceholder.text = "Загружаю фото…"
        photoPlaceholder.visibility = View.VISIBLE
        setPhotoBusy(true)
        photoExecutor.execute {
            try {
                val bitmap = decodeBitmap(uri)
                runOnUiThread {
                    if (requestId == activePhotoRequest && !liveMode) {
                        showPhotoPreview(bitmap)
                    }
                }
                photoDetector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { faces ->
                        if (requestId == activePhotoRequest && !liveMode) {
                            analyzeAllFaces(bitmap, faces, requestId)
                        }
                    }
                    .addOnFailureListener {
                        if (requestId == activePhotoRequest && !liveMode) showResult("Не удалось распознать лицо")
                    }
            } catch (_: Exception) {
                if (requestId == activePhotoRequest && !liveMode) {
                    runOnUiThread {
                        if (requestId == activePhotoRequest && !liveMode) {
                            photoPlaceholder.text = "Не удалось загрузить фото"
                            photoPlaceholder.visibility = View.VISIBLE
                            showResult("Не удалось обработать фото")
                        }
                    }
                }
            }
        }
    }

    private fun analyzeAllFaces(bitmap: Bitmap, faces: List<Face>, requestId: Int) {
        if (faces.isEmpty()) {
            showResult("Лицо не найдено")
            return
        }
        // Нумеруем лица слева направо
        val boxes = faces.map { Rect(it.boundingBox) }.sortedBy { it.left }
        runOnUiThread {
            if (requestId == activePhotoRequest && !liveMode) {
                // -1: все рамки рисуются одинаково, выделенного лица нет
                faceSelectionOverlay.setFaces(boxes, -1, bitmap.width, bitmap.height)
            }
        }
        val crops = boxes.map { box -> cropFace(bitmap, box, 1.7f) to cropFace(bitmap, box, 1.15f) }
        inferenceExecutor.execute {
            try {
                val predictions = crops.map { (ageFace, genderFace) ->
                    try {
                        models.predict(ageFace, genderFace)
                    } catch (_: Exception) {
                        null
                    }
                }
                val labels = predictions.mapIndexed { i, p ->
                    if (p == null) "${i + 1}: ?"
                    else "${i + 1}: ${if (p.gender == "мужской") "М" else "Ж"}, ${p.ageYears}"
                }
                val text = if (boxes.size == 1) {
                    predictions[0]?.let { singleResultText(it) }
                        ?: "Не удалось определить возраст и пол"
                } else {
                    "Найдено лиц: ${boxes.size}\n" + predictions.mapIndexed { i, p ->
                        if (p == null) "${i + 1}. не удалось определить"
                        else "${i + 1}.  ${p.gender}, ${ageText(p.ageYears)}"
                    }.joinToString("\n")
                }
                runOnUiThread {
                    if (requestId == activePhotoRequest && !liveMode) {
                        faceSelectionOverlay.setLabels(labels)
                        showResult(text)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (requestId == activePhotoRequest && !liveMode) showResult("Не удалось определить возраст и пол")
                }
            } finally {
                crops.forEach { (ageFace, genderFace) ->
                    ageFace.recycle()
                    genderFace.recycle()
                }
                setPhotoBusy(false)
            }
        }
    }

    private fun cropFace(bitmap: Bitmap, bounds: Rect, expansion: Float): Bitmap {
        val requestedSide = max(bounds.width(), bounds.height()).times(expansion).toInt().coerceAtLeast(1)
        val side = min(requestedSide, min(bitmap.width, bitmap.height)).coerceAtLeast(1)
        val left = (bounds.centerX() - side / 2).coerceIn(0, bitmap.width - side)
        val top = (bounds.centerY() - side / 2).coerceIn(0, bitmap.height - side)
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestSide = max(info.size.width, info.size.height)
            if (longestSide > 1800) {
                val scale = 1800f / longestSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun showPhotoPreview(bitmap: Bitmap) {
        cameraPreview.visibility = View.GONE
        faceGuide.visibility = View.GONE
        photoPreview.visibility = View.VISIBLE
        photoPreview.scaleType = ImageView.ScaleType.FIT_CENTER
        photoPreview.setImageDrawable(null)
        selectedPhotoBitmap?.takeIf { !it.isRecycled }?.recycle()
        selectedPhotoBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        photoPreview.setImageBitmap(selectedPhotoBitmap)
        photoPlaceholder.visibility = View.GONE
    }

    private fun clearPhotoPreview() {
        photoPreview.setImageDrawable(null)
        selectedPhotoBitmap?.takeIf { !it.isRecycled }?.recycle()
        selectedPhotoBitmap = null
    }

    private fun setPhotoBusy(busy: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            cameraButton.isEnabled = !busy
            photoButton.isEnabled = !busy
        }
    }

    /** «34 года», «21 год», «25 лет» */
    private fun ageText(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "лет"
            n % 10 == 1 -> "год"
            n % 10 in 2..4 -> "года"
            else -> "лет"
        }
        return "$n $word"
    }

    private fun singleResultText(p: AgeGenderEstimate): String =
        "${ageText(p.ageYears)}\n${p.gender} пол · ${(p.genderConfidence * 100).toInt()} %"

    private fun showResult(text: String) {
        resultText.text = text
        resultCard.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        cameraButton.isEnabled = true
        photoButton.isEnabled = true
    }

    private fun clearResult() {
        resultText.text = ""
        resultCard.visibility = View.GONE
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        clearPhotoPreview()
        switchCameraButton.visibility = View.GONE
        detector.close()
        photoDetector.close()
        photoExecutor.shutdown()
        cameraExecutor.shutdown()
        inferenceExecutor.shutdown()
        super.onDestroy()
    }
}
