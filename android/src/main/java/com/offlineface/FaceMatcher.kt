package com.offlineface

import kotlin.math.max
import kotlin.math.min

/**
 * Compares histogram descriptors using block-wise histogram intersection.
 *
 * Each LBP/HOG cell histogram sums to 1, so intersection lies in [0, 1] and is
 * comparable across devices, lighting conditions and template versions.
 * For a probe sequence, the best match per probe is averaged to tolerate
 * transient blinks/head movement while still requiring several good frames.
 */
internal object FaceMatcher {
  private const val LBP_GRID = 4
  private const val LBP_BINS = 59
  private const val HOG_GRID = 8
  private const val HOG_BINS = 9
  private const val LBP_BLOCKS = LBP_GRID * LBP_GRID
  private const val HOG_BLOCKS = HOG_GRID * HOG_GRID
  private const val LBP_WEIGHT = 0.65
  private const val HOG_WEIGHT = 0.35
  private const val TOP_MATCHES = 3

  fun score(template: List<FloatArray>, probes: List<FloatArray>): Double {
    if (template.isEmpty() || probes.isEmpty()) {
      return 0.0
    }

    val bestPerProbe =
      probes.map { probe ->
        template.maxOf { enrolled -> similarity(probe, enrolled) }
      }
    val top = bestPerProbe.sortedDescending().take(min(TOP_MATCHES, bestPerProbe.size))
    val value = top.average()
    return (value * 10_000.0).toInt().coerceIn(0, 10_000) / 10_000.0
  }

  fun similarity(a: FloatArray, b: FloatArray): Double {
    if (a.size != FaceFeatureExtractor.DESCRIPTOR_SIZE || b.size != a.size) {
      return 0.0
    }

    var lbp = 0.0
    for (block in 0 until LBP_BLOCKS) {
      lbp += histogramIntersection(a, b, block * LBP_BINS, (block + 1) * LBP_BINS)
    }
    lbp /= LBP_BLOCKS

    val hogOffset = LBP_BLOCKS * LBP_BINS
    var hog = 0.0
    for (block in 0 until HOG_BLOCKS) {
      val start = hogOffset + block * HOG_BINS
      hog += histogramIntersection(a, b, start, start + HOG_BINS)
    }
    hog /= HOG_BLOCKS

    return (LBP_WEIGHT * lbp + HOG_WEIGHT * hog).coerceIn(0.0, 1.0)
  }

  private fun histogramIntersection(a: FloatArray, b: FloatArray, start: Int, end: Int): Double {
    var sumA = 0.0
    var sumB = 0.0
    for (index in start until end) {
      sumA += a[index]
      sumB += b[index]
    }
    if (sumA <= 1e-9 && sumB <= 1e-9) {
      return 1.0
    }
    if (sumA <= 1e-9 || sumB <= 1e-9) {
      return 0.0
    }

    var intersection = 0.0
    for (index in start until end) {
      intersection += min(a[index], b[index])
    }
    return max(0.0, intersection)
  }
}
