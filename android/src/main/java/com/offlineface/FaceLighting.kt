package com.offlineface

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object FaceLighting {
  /** Removes gradual illumination changes with a masked local mean and contrast adjustment. */
  fun normalize(gray: IntArray, mask: BooleanArray, size: Int) {
    val radius = (size / 10).coerceAtLeast(1)
    val stride = size + 1
    val sums = IntArray(stride * stride)
    val squaredSums = IntArray(stride * stride)
    val counts = IntArray(stride * stride)
    for (y in 0 until size) {
      var rowSum = 0
      var rowSquaredSum = 0
      var rowCount = 0
      for (x in 0 until size) {
        val index = y * size + x
        if (mask[index]) {
          val value = gray[index]
          rowSum += value
          rowSquaredSum += value * value
          rowCount++
        }
        val integralIndex = (y + 1) * stride + x + 1
        sums[integralIndex] = sums[integralIndex - stride] + rowSum
        squaredSums[integralIndex] = squaredSums[integralIndex - stride] + rowSquaredSum
        counts[integralIndex] = counts[integralIndex - stride] + rowCount
      }
    }

    for (y in 0 until size) {
      val top = (y - radius).coerceAtLeast(0) * stride
      val bottom = (y + radius + 1).coerceAtMost(size) * stride
      for (x in 0 until size) {
        val index = y * size + x
        if (!mask[index]) {
          gray[index] = 0
          continue
        }
        val left = (x - radius).coerceAtLeast(0)
        val right = (x + radius + 1).coerceAtMost(size)
        val count = windowSum(counts, top, bottom, left, right).toDouble()
        val mean = windowSum(sums, top, bottom, left, right) / count
        val variance = windowSum(squaredSums, top, bottom, left, right) / count - mean * mean
        // Floor the variance so that nearly flat regions do not amplify sensor noise.
        val contrast = sqrt(variance.coerceAtLeast(144.0))
        val value = 128 + 32 * (gray[index] - mean) / contrast
        gray[index] = value.coerceIn(0.0, 255.0).roundToInt()
      }
    }
  }

  private fun windowSum(integral: IntArray, top: Int, bottom: Int, left: Int, right: Int): Int =
    integral[bottom + right] - integral[top + right] - integral[bottom + left] + integral[top + left]
}
