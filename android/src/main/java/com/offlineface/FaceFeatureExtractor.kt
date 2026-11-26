package com.offlineface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extracts an illumination-normalized, landmark-aligned texture descriptor.
 *
 * The descriptor combines uniform Local Binary Patterns (LBP) with Histogram of
 * Oriented Gradients (HOG) over an elliptical face mask. Descriptors are
 * intentionally not reverse-image-able at any useful resolution: only a 64x64,
 * masked, equalized grayscale patch is used and only histograms are retained.
 *
 * This is a purely native, fully offline descriptor. It is not a deep neural
 * face embedding and its accuracy is lower than a trained face-recognition
 * network; see SECURITY.md for the threat model.
 */
internal object FaceFeatureExtractor {
  sealed interface CaptureResult {
    data class Sample(val descriptor: FloatArray) : CaptureResult
    data object InvalidFrame : CaptureResult
    data object PoorLighting : CaptureResult
  }

  const val IMAGE_SIZE = 64

  private const val LBP_GRID = 4
  private const val LBP_BINS = 59
  private const val LBP_BLOCKS = LBP_GRID * LBP_GRID
  private const val HOG_GRID = 8
  private const val HOG_BINS = 9
  private const val HOG_BLOCKS = HOG_GRID * HOG_GRID
  private const val LBP_OFFSET = 0
  private const val HOG_OFFSET = LBP_BLOCKS * LBP_BINS
  const val DESCRIPTOR_SIZE = LBP_BLOCKS * LBP_BINS + HOG_BLOCKS * HOG_BINS

  private val uniformLbpBins: IntArray by lazy {
    val bins = IntArray(256)
    var next = 0
    for (code in 0..255) {
      if (isUniform(code)) {
        bins[code] = next++
      }
    }
    for (code in 0..255) {
      if (!isUniform(code)) {
        bins[code] = 58
      }
    }
    bins
  }

  fun extract(source: Bitmap, face: Face, format: FaceDescriptorFormat): CaptureResult {
    if (source.isRecycled || source.width < 48 || source.height < 48) {
      return CaptureResult.InvalidFrame
    }

    val box = face.boundingBox
    if (box.width() < 24 || box.height() < 24) {
      return CaptureResult.InvalidFrame
    }

    return when (format) {
      FaceDescriptorFormat.LEGACY -> extractLegacy(source, face)
      FaceDescriptorFormat.EYE_ALIGNED -> extractEyeAligned(source, face, normalizeLighting = false)
      FaceDescriptorFormat.LIGHTING_NORMALIZED -> extractEyeAligned(source, face, normalizeLighting = true)
    }
  }

  private fun extractEyeAligned(source: Bitmap, face: Face, normalizeLighting: Boolean): CaptureResult {
    val firstEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return CaptureResult.InvalidFrame
    val secondEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return CaptureResult.InvalidFrame
    val values = FaceEyeAlignment.matrixValues(firstEye.x, firstEye.y, secondEye.x, secondEye.y)
      ?: return CaptureResult.InvalidFrame
    val matrix = Matrix().apply { setValues(values) }
    val patch = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(patch)
      canvas.drawColor(Color.BLACK)
      canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
      return descriptorFromBitmap(patch, normalizeLighting)
    } finally {
      patch.recycle()
    }
  }

  private fun extractLegacy(source: Bitmap, face: Face): CaptureResult {
    val box = face.boundingBox
    val centerX = box.exactCenterX()
    val centerY = box.exactCenterY()
    var side = (max(box.width(), box.height()) * 1.5f).roundToInt()
    side = min(side, min(source.width, source.height))
    if (side < 48) {
      return CaptureResult.InvalidFrame
    }

    val left = (centerX - side / 2f).roundToInt().coerceIn(0, source.width - side)
    val top = (centerY - side / 2f).roundToInt().coerceIn(0, source.height - side)

    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    var rollDegrees = face.headEulerAngleZ
    if (leftEye != null && rightEye != null) {
      rollDegrees = Math.toDegrees(atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble())).toFloat()
    }
    if (rollDegrees.isNaN() || abs(rollDegrees) > 45f) {
      return CaptureResult.InvalidFrame
    }

    val cropped = Bitmap.createBitmap(source, left, top, side, side)
    val aligned = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val scaled = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(aligned)
      canvas.drawColor(Color.BLACK)
      val matrix = Matrix().apply { postRotate(-rollDegrees, side / 2f, side / 2f) }
      canvas.drawBitmap(cropped, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

      val scale = IMAGE_SIZE.toFloat() / side
      val scaleMatrix = Matrix().apply { postScale(scale, scale) }
      val scaleCanvas = Canvas(scaled)
      scaleCanvas.drawColor(Color.BLACK)
      scaleCanvas.drawBitmap(aligned, scaleMatrix, Paint(Paint.FILTER_BITMAP_FLAG))

      return descriptorFromBitmap(scaled, normalizeLighting = false)
    } finally {
      if (cropped !== source) cropped.recycle()
      aligned.recycle()
      scaled.recycle()
    }
  }

  private fun descriptorFromBitmap(bitmap: Bitmap, normalizeLighting: Boolean): CaptureResult {
    val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

    val gray = IntArray(pixels.size)
    val mask = BooleanArray(pixels.size)
    var inside = 0
    var minGray = 255
    var maxGray = 0
    for (y in 0 until IMAGE_SIZE) {
      for (x in 0 until IMAGE_SIZE) {
        val index = y * IMAGE_SIZE + x
        val color = pixels[index]
        val value =
          ((color ushr 16 and 0xff) * 77 +
            (color ushr 8 and 0xff) * 151 +
            (color and 0xff) * 28) ushr 8
        gray[index] = value

        val nx = (x + 0.5f) / IMAGE_SIZE
        val ny = (y + 0.5f) / IMAGE_SIZE
        val dx = (nx - 0.5f) / 0.46f
        val dy = (ny - 0.51f) / 0.50f
        if (dx * dx + dy * dy <= 1f) {
          mask[index] = true
          inside++
          if (value < minGray) minGray = value
          if (value > maxGray) maxGray = value
        }
      }
    }
    if (!FaceExposure.acceptable(gray, IMAGE_SIZE, checkSides = normalizeLighting)) {
      return CaptureResult.PoorLighting
    }
    if (inside < IMAGE_SIZE * IMAGE_SIZE / 4 || maxGray - minGray < 12) {
      return CaptureResult.InvalidFrame
    }

    if (normalizeLighting) {
      FaceLighting.normalize(gray, mask, IMAGE_SIZE)
    } else {
      equalize(gray, mask)
    }
    val descriptor = FloatArray(DESCRIPTOR_SIZE)
    computeLbp(gray, mask, descriptor)
    computeHog(gray, mask, descriptor)

    for (index in descriptor.indices) {
      if (!descriptor[index].isFinite()) {
        return CaptureResult.InvalidFrame
      }
    }
    return CaptureResult.Sample(descriptor)
  }

  private fun equalize(gray: IntArray, mask: BooleanArray) {
    val histogram = IntArray(256)
    var count = 0
    for (index in gray.indices) {
      if (mask[index]) {
        histogram[gray[index]]++
        count++
      }
    }
    if (count == 0) {
      return
    }

    val lookup = IntArray(256)
    var cumulative = 0
    for (value in 0..255) {
      cumulative += histogram[value]
      lookup[value] = (cumulative * 255 / count).coerceIn(0, 255)
    }
    for (index in gray.indices) {
      gray[index] = if (mask[index]) lookup[gray[index]] else 0
    }
  }

  private fun computeLbp(gray: IntArray, mask: BooleanArray, descriptor: FloatArray) {
    val counts = IntArray(LBP_BLOCKS)
    for (y in 1 until IMAGE_SIZE - 1) {
      for (x in 1 until IMAGE_SIZE - 1) {
        val index = y * IMAGE_SIZE + x
        if (!mask[index]) {
          continue
        }
        val center = gray[index]
        var code = 0
        if (gray[index - IMAGE_SIZE - 1] >= center) code = code or (1 shl 0)
        if (gray[index - IMAGE_SIZE] >= center) code = code or (1 shl 1)
        if (gray[index - IMAGE_SIZE + 1] >= center) code = code or (1 shl 2)
        if (gray[index + 1] >= center) code = code or (1 shl 3)
        if (gray[index + IMAGE_SIZE + 1] >= center) code = code or (1 shl 4)
        if (gray[index + IMAGE_SIZE] >= center) code = code or (1 shl 5)
        if (gray[index + IMAGE_SIZE - 1] >= center) code = code or (1 shl 6)
        if (gray[index - 1] >= center) code = code or (1 shl 7)

        val cellX = (x * LBP_GRID / IMAGE_SIZE).coerceIn(0, LBP_GRID - 1)
        val cellY = (y * LBP_GRID / IMAGE_SIZE).coerceIn(0, LBP_GRID - 1)
        val cell = cellY * LBP_GRID + cellX
        val bin = uniformLbpBins[code]
        descriptor[LBP_OFFSET + cell * LBP_BINS + bin] += 1f
        counts[cell]++
      }
    }

    for (cell in 0 until LBP_BLOCKS) {
      val count = counts[cell]
      if (count == 0) {
        continue
      }
      val offset = LBP_OFFSET + cell * LBP_BINS
      for (bin in 0 until LBP_BINS) {
        descriptor[offset + bin] /= count.toFloat()
      }
    }
  }

  private fun computeHog(gray: IntArray, mask: BooleanArray, descriptor: FloatArray) {
    val blockEnergy = FloatArray(HOG_BLOCKS)
    for (y in 1 until IMAGE_SIZE - 1) {
      for (x in 1 until IMAGE_SIZE - 1) {
        val index = y * IMAGE_SIZE + x
        if (!mask[index]) {
          continue
        }
        val gx = gray[index + 1] - gray[index - 1]
        val gy = gray[index + IMAGE_SIZE] - gray[index - IMAGE_SIZE]
        val magnitude = sqrt((gx * gx + gy * gy).toFloat())
        if (magnitude < 1e-3f) {
          continue
        }
        var angle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()
        if (angle < 0f) angle += 180f
        if (angle >= 180f) angle -= 180f
        val bin = ((angle / 180f) * HOG_BINS).toInt().coerceIn(0, HOG_BINS - 1)
        val cellX = (x * HOG_GRID / IMAGE_SIZE).coerceIn(0, HOG_GRID - 1)
        val cellY = (y * HOG_GRID / IMAGE_SIZE).coerceIn(0, HOG_GRID - 1)
        val cell = cellY * HOG_GRID + cellX
        descriptor[HOG_OFFSET + cell * HOG_BINS + bin] += magnitude
        blockEnergy[cell] += magnitude
      }
    }

    for (cell in 0 until HOG_BLOCKS) {
      val energy = blockEnergy[cell]
      if (energy <= 1e-6f) {
        continue
      }
      val offset = HOG_OFFSET + cell * HOG_BINS
      for (bin in 0 until HOG_BINS) {
        descriptor[offset + bin] /= energy
      }
    }
  }

  private fun isUniform(code: Int): Boolean {
    var transitions = 0
    for (bit in 0 until 8) {
      val current = (code ushr bit) and 1
      val next = (code ushr ((bit + 1) and 7)) and 1
      if (current != next) transitions++
    }
    return transitions <= 2
  }
}
