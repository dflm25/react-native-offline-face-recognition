package com.offlineface

import org.junit.Assert.assertEquals
import org.junit.Test

class LivenessTrackerTest {
  @Test fun mouthMustOpenAndCloseAfterCentering() {
    val tracker = LivenessTracker(10_000)
    tracker.update(1000, 0f, 0.08f)
    assertEquals(LivenessTracker.Stage.OPEN_MOUTH, tracker.update(1600, 0f, 0.08f).stage)
    assertEquals(LivenessTracker.Stage.OPEN_MOUTH, tracker.update(1700, 0f, 0.08f).stage)
    assertEquals(LivenessTracker.Stage.CLOSE_MOUTH, tracker.update(1800, 0f, 0.2f).stage)
    assertEquals(LivenessTracker.Stage.PASSED, tracker.update(1900, 0f, 0.08f).stage)
  }

  @Test fun requiresClosedMouthAndTimesOut() {
    val tracker = LivenessTracker(1000)
    tracker.update(1000, 0f, 0.2f)
    assertEquals(LivenessTracker.Stage.CENTER, tracker.update(1600, 0f, 0.2f).stage)
    assertEquals(LivenessTracker.Stage.FAILED, tracker.update(2001, 0f, null).stage)
  }
}
