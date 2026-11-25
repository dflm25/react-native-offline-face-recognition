package com.offlineface

import kotlin.math.abs

internal object FaceEyeAlignment {
  /** Matrix values for a 64 px patch with the eyes at (20, 24) and (44, 24). */
  fun matrixValues(firstX: Float, firstY: Float, secondX: Float, secondY: Float): FloatArray? {
    if (!firstX.isFinite() || !firstY.isFinite() || !secondX.isFinite() || !secondY.isFinite()) return null
    val firstIsLeft = firstX < secondX
    val leftX = if (firstIsLeft) firstX else secondX
    val leftY = if (firstIsLeft) firstY else secondY
    val rightX = if (firstIsLeft) secondX else firstX
    val rightY = if (firstIsLeft) secondY else firstY
    val dx = rightX - leftX
    val dy = rightY - leftY
    val distanceSquared = dx * dx + dy * dy
    if (distanceSquared < 12f * 12f || abs(dy) > dx * 0.57735f) return null

    val a = 24f * dx / distanceSquared
    val b = 24f * dy / distanceSquared
    return floatArrayOf(
      a, b, 20f - (a * leftX + b * leftY),
      -b, a, 24f - (-b * leftX + a * leftY),
      0f, 0f, 1f,
    )
  }
}
