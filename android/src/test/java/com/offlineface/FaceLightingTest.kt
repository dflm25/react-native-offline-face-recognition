package com.offlineface

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLightingTest {
  private val size = 64

  @Test
  fun reducesDifferenceFromSideLightWhileKeepingTexture() {
    val mask = BooleanArray(size * size) { index ->
      val x = index % size - size / 2
      val y = index / size - size / 2
      x * x + y * y < 29 * 29
    }
    val even = IntArray(mask.size) { 120 + ((it % size * 11 + it / size * 7) % 29) - 14 }
    val shadowed = IntArray(mask.size) { even[it] + (it % size - size / 2) * 2 }
    val normalizedEven = even.copyOf()
    val normalizedShadow = shadowed.copyOf()
    FaceLighting.normalize(normalizedEven, mask, size)
    FaceLighting.normalize(normalizedShadow, mask, size)
    val inside = mask.indices.filter { mask[it] }
    val rawDifference = inside.sumOf { abs(even[it] - shadowed[it]) }
    val normalizedDifference = inside.sumOf { abs(normalizedEven[it] - normalizedShadow[it]) }
    assertTrue(normalizedDifference * 2 < rawDifference)
    assertTrue(inside.map { normalizedEven[it] }.toSet().size > 10)
    assertEquals(0, normalizedShadow[0])
    assertTrue(FaceExposure.acceptable(shadowed, size, checkSides = true))
  }

  @Test
  fun detectsClippingOnOneSideWithoutChangingLegacyCheck() {
    val clippedSide = IntArray(size * size) { if (it % size < size / 2) 0 else 128 }
    assertTrue(FaceExposure.acceptable(clippedSide, size))
    assertFalse(FaceExposure.acceptable(clippedSide, size, checkSides = true))
  }
}
