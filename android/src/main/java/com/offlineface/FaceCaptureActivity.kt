package com.offlineface

import android.Manifest
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen, privacy-preserving face capture UI.
 *
 * The activity owns every camera frame in memory, extracts only histogram
 * descriptors, saves an encrypted template when registering, and returns a
 * small result map. No bitmap is written to disk or sent anywhere.
 */
class FaceCaptureActivity : ComponentActivity() {
  private val handler = Handler(Looper.getMainLooper())
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val processingFrame = AtomicBoolean(false)
  private val probes = mutableListOf<FloatArray>()

  private lateinit var previewView: PreviewView
  private lateinit var overlayView: FaceOverlayView
  private lateinit var instructionText: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var bottomPanel: LinearLayout
  private lateinit var glassesWarning: TextView
  private lateinit var topPanel: LinearLayout

  private var mode: String = MODE_VERIFY
  private var captureOptions = FaceCaptureOptions()
  private var enrolled: FaceTemplateRecord? = null
  private var store: FaceTemplateStore? = null
  private var provider: ProcessCameraProvider? = null
  private var detector: FaceDetector? = null
  private var labeler: ImageLabeler? = null
  private var liveness: LivenessTracker? = null
  private var lastLivenessState: LivenessTracker.State? = null
  private var registrationPhoto: String? = null
  private val approachTracker = FaceApproachTracker()

  @Volatile private var finished = false
  private var released = false
  private var noFaceSince = 0L
  private var stableSince = 0L
  private var sawAnyFace = false
  private var sawUsableFace = false
  private var lastProbeAt = 0L
  private var lastLivenessResetAt = 0L
  private var lastGlassesCheck = 0L
  private var glassesCheckInFlight = false
  private var glassesDetected = false
  private var blockedByGlasses = false

  private val timeoutRunnable = Runnable {
    val error =
      when {
        !sawAnyFace -> "no_face"
        !sawUsableFace -> "face_too_small"
        else -> "timeout"
      }
    finishFailure(error)
  }

  private val cameraPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        startCamera()
      } else {
        finishFailure("camera_permission_denied")
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
    captureOptions =
      FaceCaptureOptions(
        timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT, DEFAULT_TIMEOUT_MS),
        requireLiveness = intent.getBooleanExtra(EXTRA_REQUIRE_LIVENESS, true),
        threshold = intent.getDoubleExtra(EXTRA_THRESHOLD, DEFAULT_THRESHOLD),
        allowGlasses = intent.getBooleanExtra(EXTRA_ALLOW_GLASSES, true),
        glassesSensitivity = intent.getStringExtra(EXTRA_GLASSES_SENSITIVITY) ?: "medium",
        returnPhoto = intent.getBooleanExtra(EXTRA_RETURN_PHOTO, false),
      )

    if (
      captureOptions.timeoutMs < MIN_TIMEOUT_MS ||
        captureOptions.timeoutMs > MAX_TIMEOUT_MS ||
        captureOptions.threshold < 0.0 ||
        captureOptions.threshold > 1.0
    ) {
      finishFailure("invalid_options")
      return
    }

    store = FaceTemplateStore(this)
    if (mode == MODE_VERIFY) {
      enrolled = store?.load()
      if (enrolled == null) {
        finishFailure("no_template")
        return
      }
    }

    if (captureOptions.requireLiveness) {
      liveness =
        LivenessTracker(
          timeoutMs = min(35_000L, max(10_000L, captureOptions.timeoutMs - 5_000L))
        )
    }

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      isAppearanceLightStatusBars = false
      isAppearanceLightNavigationBars = false
    }

    buildUi()
    onBackPressedDispatcher.addCallback(this) { finishFailure("cancelled") }

    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      startCamera()
    } else {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  override fun onDestroy() {
    if (!finished) {
      finished = true
      handler.removeCallbacks(timeoutRunnable)
      releaseCamera()
    }
    super.onDestroy()
  }

  private fun buildUi() {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    previewView =
      PreviewView(this).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        scaleType = PreviewView.ScaleType.FILL_CENTER
      }
    overlayView = FaceOverlayView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }

    topPanel =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(16), dp(24), dp(8))
      }
    val title =
      TextView(this).apply {
        text = if (mode == MODE_REGISTER) "Register your face" else "Verify your face"
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
      }
    val privacy =
      TextView(this).apply {
        text = "On-device only  •  Nothing is uploaded"
        setTextColor(Color.parseColor("#9BB8AA"))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
      }
    topPanel.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
    topPanel.addView(privacy, LinearLayout.LayoutParams(MATCH, WRAP))

    bottomPanel =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(18), dp(24), dp(20))
        background =
          android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#E60B1210"))
            cornerRadius = dp(22).toFloat()
          }
      }
    instructionText =
      TextView(this).apply {
        text = "Center your face in the oval"
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
          setColor(Color.parseColor("#B8000000"))
          cornerRadius = dp(18).toFloat()
        }
      }
    topPanel.addView(instructionText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    glassesWarning =
      TextView(this).apply {
        text = "Remove your glasses for a better scan"
        setTextColor(Color.parseColor("#FFB020"))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(6))
        visibility = View.GONE
      }
    progressBar =
      ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(Color.parseColor("#34C759"))
        progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
      }
    val cancel =
      TextView(this).apply {
        text = "Cancel"
        setTextColor(Color.parseColor("#B9CFC4"))
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        contentDescription = "Cancel face capture"
        setOnClickListener { finishFailure("cancelled") }
      }

    bottomPanel.addView(glassesWarning, LinearLayout.LayoutParams(MATCH, WRAP))
    bottomPanel.addView(
      progressBar,
      LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(12) },
    )
    bottomPanel.addView(cancel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })

    val bottomParams =
      FrameLayout.LayoutParams(MATCH, WRAP).apply {
        gravity = Gravity.BOTTOM
        marginStart = dp(16)
        marginEnd = dp(16)
        bottomMargin = dp(12)
      }
    val topParams =
      FrameLayout.LayoutParams(MATCH, WRAP).apply {
        gravity = Gravity.TOP
      }

    root.addView(previewView)
    root.addView(overlayView)
    root.addView(topPanel, topParams)
    root.addView(bottomPanel, bottomParams)
    setContentView(root)

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      topPanel.setPadding(dp(24), bars.top + dp(16), dp(24), dp(8))
      bottomPanel.setPadding(dp(24), dp(18), dp(24), bars.bottom + dp(16))
      insets
    }
  }

  private fun startCamera() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
      {
        if (finished || isFinishing) {
          return@addListener
        }
        try {
          provider = future.get()
          val detectorOptions =
            FaceDetectorOptions.Builder()
              .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
              .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
              .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
              .setMinFaceSize(0.2f)
              .build()
          detector = FaceDetection.getClient(detectorOptions)

          val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
          val analysis =
            ImageAnalysis.Builder()
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
              .setTargetResolution(Size(640, 480))
              .build()
          analysis.setAnalyzer(cameraExecutor, FaceAnalyzer())

          provider?.unbindAll()
          provider?.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            analysis,
          )

          handler.postDelayed(timeoutRunnable, captureOptions.timeoutMs)
          updateUi("Center your face in the oval", 0f)
        } catch (_: Throwable) {
          finishFailure("camera_unavailable")
        }
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
    private var lastAnalyzedAt = 0L

    override fun analyze(image: ImageProxy) {
      val now = SystemClock.elapsedRealtime()
      if (finished || now - lastAnalyzedAt < 70L || !processingFrame.compareAndSet(false, true)) {
        image.close()
        return
      }
      lastAnalyzedAt = now

      var rotated: android.graphics.Bitmap? = null
      try {
        val bitmap = image.toBitmap()
        rotated = rotate(bitmap, image.imageInfo.rotationDegrees)
        if (rotated !== bitmap) {
          bitmap.recycle()
        }
        val frame = rotated
        val activeDetector = detector
        if (activeDetector == null) {
          if (!frame.isRecycled) frame.recycle()
          image.close()
          processingFrame.set(false)
          return
        }
        activeDetector
          .process(InputImage.fromBitmap(frame, 0))
          .addOnSuccessListener { faces -> handleFaces(frame, faces) }
          .addOnFailureListener { updateUi("Camera is warming up…", 0f) }
          .addOnCompleteListener {
            if (!frame.isRecycled) frame.recycle()
            image.close()
            processingFrame.set(false)
          }
      } catch (_: Throwable) {
        rotated?.let { if (!it.isRecycled) it.recycle() }
        image.close()
        processingFrame.set(false)
      }
    }
  }

  private fun handleFaces(bitmap: android.graphics.Bitmap, faces: List<Face>) {
    if (finished || isFinishing) {
      return
    }
    val now = SystemClock.elapsedRealtime()

    if (faces.isEmpty()) {
      stableSince = 0L
      glassesDetected = false
      setGlassesWarning(false, blocking = !captureOptions.allowGlasses)
      if (noFaceSince == 0L) {
        noFaceSince = now
      }
      if (now - noFaceSince > 1_200L && now - lastLivenessResetAt > 800L) {
        resetLiveness(now)
        lastLivenessResetAt = now
      }
      if (now - noFaceSince > 600L) {
        updateUi("Center your face in the oval", liveness?.update(now, null, null)?.progress ?: 0f)
      }
      return
    }

    sawAnyFace = true
    if (faces.size > 1) {
      glassesDetected = false
      setGlassesWarning(false, blocking = !captureOptions.allowGlasses)
      noFaceSince = 0L
      if (now - lastLivenessResetAt > 800L) {
        resetLiveness(now)
        lastLivenessResetAt = now
      }
      updateUi("Only one person should be in frame", 0f)
      return
    }

    noFaceSince = 0L
    val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() } ?: return
    val box = face.boundingBox
    val widthRatio = box.width().toFloat() / bitmap.width
    val heightRatio = box.height().toFloat() / bitmap.height
    if (widthRatio < 0.20f || heightRatio < 0.20f) {
      stableSince = 0L
      updateUi("Move a little closer", liveness?.update(now, null, null)?.progress ?: 0f)
      return
    }
    if (widthRatio > 0.75f || heightRatio > 0.85f) {
      stableSince = 0L
      updateUi("Move a little farther away", lastLivenessState?.progress ?: 0f)
      return
    }

    val centerX = box.exactCenterX() / bitmap.width
    val centerY = box.exactCenterY() / bitmap.height
    if (centerX < 0.18f || centerX > 0.82f || centerY < 0.12f || centerY > 0.88f) {
      stableSince = 0L
      updateUi("Center your face in the oval", liveness?.update(now, null, null)?.progress ?: 0f)
      return
    }
    sawUsableFace = true
    if (!captureOptions.allowGlasses) {
      checkGlasses(bitmap, face, now)
    }

    if (!captureOptions.allowGlasses && glassesDetected) {
      if (!blockedByGlasses) {
        blockedByGlasses = true
        probes.clear()
        stableSince = 0L
      }
      updateUi("Remove your glasses to continue", 0f)
      setGlassesWarning(true, blocking = true)
      return
    }
    if (blockedByGlasses) {
      blockedByGlasses = false
      resetLiveness(now)
    }

    if (!captureOptions.requireLiveness) {
      if (!face.headEulerAngleY.isFinite() || abs(face.headEulerAngleY) > 10f) {
        stableSince = 0L
        updateUi("Look straight ahead", 0f)
        return
      }
      if (stableSince == 0L) {
        stableSince = now
      }
      if (now - stableSince < 300L) {
        updateUi("Hold still…", 0f)
        return
      }
      captureProbe(bitmap, face, now)
      return
    }

    val tracker = liveness ?: return
    if (!approachTracker.completed) {
      if (!approachTracker.update(widthRatio)) {
        updateUi(if (approachTracker.needsStartingDistance) "Move a little farther away" else "Slowly move closer", 0.2f)
        return
      }
      tracker.restart(now)
    }
    val state = tracker.update(now, face.headEulerAngleY, mouthOpenRatio(face))
    lastLivenessState = state
    if (state.failed) {
      finishFailure("liveness_failed", livenessPassed = false)
      return
    }
    updateUi(state.prompt, state.progress)
    if (state.passed) {
      if (!face.headEulerAngleY.isFinite() || abs(face.headEulerAngleY) > 10f) {
        stableSince = 0L
        updateUi("Look straight ahead", 1f)
        return
      }
      if (stableSince == 0L) {
        stableSince = now
      }
      if (now - stableSince < 250L) {
        updateUi("Hold still…", 1f)
        return
      }
      captureProbe(bitmap, face, now)
    }
  }

  private fun checkGlasses(bitmap: android.graphics.Bitmap, face: Face, now: Long) {
    if (glassesCheckInFlight || now - lastGlassesCheck < 900L) {
      return
    }
    val activeLabeler = labeler ?: return
    val box = face.boundingBox
    val padding = (max(box.width(), box.height()) * 0.15f).roundToInt()
    val left = (box.left - padding).coerceIn(0, bitmap.width - 1)
    val top = (box.top - padding).coerceIn(0, bitmap.height - 1)
    val right = (box.right + padding).coerceIn(left + 1, bitmap.width)
    val bottom = (box.bottom + padding).coerceIn(top + 1, bitmap.height)
    if (right - left < 48 || bottom - top < 48) {
      return
    }

    val crop =
      try {
        android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
      } catch (_: Throwable) {
        return
      }

    lastGlassesCheck = now
    glassesCheckInFlight = true
    activeLabeler
      .process(InputImage.fromBitmap(crop, 0))
      .addOnSuccessListener { labels ->
        val detected =
          labels.any {
            it.confidence >= glassesConfidenceThreshold() && it.text.lowercase(Locale.US) in GLASSES_LABELS
          }
        if (labels.isNotEmpty()) {
          glassesDetected = detected
          setGlassesWarning(detected, blocking = !captureOptions.allowGlasses)
        }
        crop.recycle()
        glassesCheckInFlight = false
      }
      .addOnFailureListener {
        crop.recycle()
        glassesCheckInFlight = false
      }
  }

  private fun setGlassesWarning(detected: Boolean, blocking: Boolean) {
    if (!::glassesWarning.isInitialized) {
      return
    }
    glassesWarning.text =
      if (blocking) {
        "Remove your glasses to continue"
      } else {
        "Remove your glasses for a better scan"
      }
    glassesWarning.visibility = if (detected) View.VISIBLE else View.GONE
  }

  private fun glassesConfidenceThreshold(): Float =
    when (captureOptions.glassesSensitivity) {
      "low" -> 0.65f
      "high" -> 0.35f
      else -> 0.50f
    }

  private fun captureProbe(bitmap: android.graphics.Bitmap, face: Face, now: Long) {
    if (now - lastProbeAt < 150L) {
      return
    }
    val format = enrolled?.format ?: FaceDescriptorFormat.EYE_ALIGNED
    val descriptor = FaceFeatureExtractor.extract(bitmap, face, format) ?: run {
      val prompt = if (format == FaceDescriptorFormat.EYE_ALIGNED) {
        "Keep both eyes visible in even light"
      } else {
        "Improve lighting and hold still…"
      }
      updateUi(prompt, lastLivenessState?.progress ?: 0f)
      return
    }
    lastProbeAt = now
    probes.add(descriptor)
    val progress = 0.9f + 0.1f * (probes.size.toFloat() / PROBE_COUNT)
    updateUi("Capturing ${probes.size}/$PROBE_COUNT", progress)

    if (probes.size >= PROBE_COUNT) {
      if (mode == MODE_REGISTER && captureOptions.returnPhoto) {
        registrationPhoto = makePhoto(bitmap, face)
        if (registrationPhoto == null) {
          finishFailure("photo_capture_failed")
          return
        }
      }
      completeCapture()
    }
  }

  private fun makePhoto(source: android.graphics.Bitmap, face: Face): String? {
    val box = face.boundingBox
    val side = min((max(box.width(), box.height()) * 1.35f).roundToInt(), min(source.width, source.height))
    if (side < 24) return null
    val left = (box.exactCenterX() - side / 2f).roundToInt().coerceIn(0, source.width - side)
    val top = (box.exactCenterY() - side / 2f).roundToInt().coerceIn(0, source.height - side)
    var crop: android.graphics.Bitmap? = null
    var scaled: android.graphics.Bitmap? = null
    return try {
      crop = android.graphics.Bitmap.createBitmap(source, left, top, side, side)
      scaled = android.graphics.Bitmap.createScaledBitmap(crop, 256, 256, true)
      val output = java.io.ByteArrayOutputStream()
      if (!scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, output) || output.size() > 96_000) null
      else android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Exception) {
      null
    } finally {
      if (scaled !== crop && scaled !== source) scaled?.recycle()
      if (crop !== source) crop?.recycle()
    }
  }

  private fun completeCapture() {
    if (finished) {
      return
    }

    if (mode == MODE_REGISTER) {
      val record = store?.save(probes)
      if (record == null) {
        finishFailure("storage_error")
      } else {
        finishSuccess(templateId = record.templateId, photoBase64 = registrationPhoto, livenessPassed = lastLivenessState?.passed)
      }
      return
    }

    val current = enrolled ?: run {
      finishFailure("no_template")
      return
    }
    val score = FaceMatcher.score(current.descriptors, probes)
    val livenessPassed = if (captureOptions.requireLiveness) lastLivenessState?.passed == true else null
    if (score >= captureOptions.threshold) {
      finishSuccess(score = score, livenessPassed = livenessPassed)
    } else {
      finishFailure("face_mismatch", score = score, livenessPassed = livenessPassed)
    }
  }

  private fun resetLiveness(now: Long) {
    registrationPhoto = null
    approachTracker.reset()
    probes.clear()
    stableSince = 0L
    lastLivenessState = null
    liveness?.restart(now)
  }

  private fun updateUi(prompt: String, progress: Float) {
    if (finished) {
      return
    }
    instructionText.text = prompt
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    progressBar.progress = percent
    overlayView.progress = progress
  }

  private fun finishSuccess(
    templateId: String? = null,
    photoBase64: String? = null,
    score: Double? = null,
    livenessPassed: Boolean? = null,
  ) {
    finishWithOutcome(
      CaptureOutcome(
        success = true,
        templateId = templateId,
        photoBase64 = photoBase64,
        score = score,
        livenessPassed = livenessPassed,
      )
    )
  }

  private fun finishFailure(
    error: String,
    score: Double? = null,
    livenessPassed: Boolean? = null,
  ) {
    finishWithOutcome(
      CaptureOutcome(
        success = false,
        score = score,
        livenessPassed = livenessPassed,
        error = error,
      )
    )
  }

  private fun finishWithOutcome(outcome: CaptureOutcome) {
    if (finished) {
      return
    }
    finished = true
    handler.removeCallbacks(timeoutRunnable)
    releaseCamera()

    val result =
      Intent().apply {
        putExtra(EXTRA_SUCCESS, outcome.success)
        putExtra(EXTRA_ERROR, outcome.error)
        outcome.templateId?.let { putExtra(EXTRA_TEMPLATE_ID, it) }
        outcome.photoBase64?.let { putExtra(EXTRA_PHOTO, it) }
        outcome.score?.let { putExtra(EXTRA_SCORE, it) }
        outcome.livenessPassed?.let { putExtra(EXTRA_LIVENESS, it) }
      }
    setResult(RESULT_OK, result)
    finish()
  }

  private fun releaseCamera() {
    if (released) {
      return
    }
    released = true
    try {
      provider?.unbindAll()
    } catch (_: Throwable) {
      // Ignore teardown errors.
    }
    try {
      detector?.close()
    } catch (_: Throwable) {
      // Ignore teardown errors.
    }
    try {
      labeler?.close()
    } catch (_: Throwable) {
      // Ignore teardown errors.
    }
    cameraExecutor.shutdown()
  }

  private fun mouthOpenRatio(face: Face): Float? {
    val upperInner = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
    val lowerInner = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
    val upper =
      if (!upperInner.isNullOrEmpty() && !lowerInner.isNullOrEmpty()) {
        upperInner
      } else {
        face.getContour(FaceContour.UPPER_LIP_TOP)?.points?.takeIf { it.isNotEmpty() }
          ?: return null
      }
    val lower =
      if (!upperInner.isNullOrEmpty() && !lowerInner.isNullOrEmpty()) {
        lowerInner
      } else {
        face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points?.takeIf { it.isNotEmpty() }
          ?: return null
      }

    val upperY = upper.maxOf { it.y }
    val lowerY = lower.minOf { it.y }
    val minX = minOf(upper.minOf { it.x }, lower.minOf { it.x })
    val maxX = maxOf(upper.maxOf { it.x }, lower.maxOf { it.x })
    val width = maxX - minX
    if (width <= 0f) {
      return null
    }
    return ((lowerY - upperY) / width).coerceAtLeast(0f)
  }

  private fun rotate(source: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) {
      return source
    }
    val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
    return android.graphics.Bitmap.createBitmap(
      source,
      0,
      0,
      source.width,
      source.height,
      matrix,
      true,
    )
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

  private class FaceOverlayView(context: android.content.Context) : View(context) {
    var progress: Float = 0f
      set(value) {
        field = value.coerceIn(0f, 1f)
        invalidate()
      }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val density = resources.displayMetrics.density
      val ovalWidth = min(width * 0.74f, height * 0.44f)
      val ovalHeight = ovalWidth * 1.28f
      val cx = width / 2f
      val cy = height * 0.43f
      oval.set(cx - ovalWidth / 2f, cy - ovalHeight / 2f, cx + ovalWidth / 2f, cy + ovalHeight / 2f)

      scrimPaint.color = Color.argb(150, 0, 0, 0)
      canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
      canvas.drawOval(oval, clearPaint)
      canvas.restore()

      strokePaint.color = Color.argb(143, 255, 255, 255)
      strokePaint.strokeWidth = 1.5f * density
      canvas.drawOval(oval, strokePaint)

      val ring = RectF(oval)
      ring.inset(-6f * density, -6f * density)

      trackPaint.color = Color.argb(41, 255, 255, 255)
      trackPaint.strokeWidth = 3.5f * density
      trackPaint.strokeCap = Paint.Cap.ROUND
      canvas.drawOval(ring, trackPaint)


      arcPaint.color = Color.parseColor("#34C759")
      arcPaint.strokeWidth = 3.5f * density
      arcPaint.strokeCap = Paint.Cap.ROUND
      canvas.drawArc(ring, -90f, 360f * progress, false, arcPaint)
    }
  }

  private companion object {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    const val PROBE_COUNT = 6
    val GLASSES_LABELS =
      setOf("glasses", "sunglasses", "eyeglasses", "goggles", "gafas", "anteojos", "anteojos de sol")
  }
}
