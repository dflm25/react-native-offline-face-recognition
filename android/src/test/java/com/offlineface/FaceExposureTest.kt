package com.offlineface

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceExposureTest {
  @Test fun detectsClippedFrames() {
    assertFalse(FaceExposure.acceptable(IntArray(64) { 0 }, 8))
    assertFalse(FaceExposure.acceptable(IntArray(64) { 255 }, 8))
    assertTrue(FaceExposure.acceptable(IntArray(64) { 80 }, 8))
    assertFalse(FaceExposure.acceptable(IntArray(1), 8))
  }
}
