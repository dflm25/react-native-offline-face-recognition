package com.offlineface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceEyeAlignmentTest {
  @Test
  fun alignsEyesAndFaceAcrossDistanceAndRoll() {
    val matrix = requireNotNull(FaceEyeAlignment.matrixValues(140f, 110f, 100f, 120f))
    assertPoint(matrix, 100f, 120f, 20f, 24f)
    assertPoint(matrix, 140f, 110f, 44f, 24f)
    assertPoint(matrix, 125f, 135f, 32f, 36f)

    val closerMatrix = requireNotNull(FaceEyeAlignment.matrixValues(70f, 150f, 150f, 130f))
    assertPoint(closerMatrix, 120f, 180f, 32f, 36f)
  }

  @Test
  fun rejectsInvalidAndSteepEyePositions() {
    assertNull(FaceEyeAlignment.matrixValues(100f, 120f, 100f, 120f))
    assertNull(FaceEyeAlignment.matrixValues(0f, 0f, 15f, 20f))
  }

  private fun assertPoint(matrix: FloatArray, x: Float, y: Float, expectedX: Float, expectedY: Float) {
    assertEquals(expectedX, matrix[0] * x + matrix[1] * y + matrix[2], 0.001f)
    assertEquals(expectedY, matrix[3] * x + matrix[4] * y + matrix[5], 0.001f)
  }
}
