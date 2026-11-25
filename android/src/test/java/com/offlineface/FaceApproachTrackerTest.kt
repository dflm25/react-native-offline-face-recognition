package com.offlineface

import org.junit.Assert.*
import org.junit.Test

class FaceApproachTrackerTest {
  @Test fun requiresCloserMovementAndResets() {
    val tracker = FaceApproachTracker()
    assertFalse(tracker.update(0.6f))
    assertTrue(tracker.needsStartingDistance)
    assertFalse(tracker.update(0.3f))
    assertFalse(tracker.update(0.34f))
    assertTrue(tracker.update(0.37f))
    tracker.reset()
    assertFalse(tracker.completed)
    assertTrue(tracker.needsStartingDistance)
  }
}
