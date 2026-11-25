package com.offlineface

/** Measures relative face width only; it does not measure depth or prove liveness. */
internal class FaceApproachTracker {
  private var baseline: Float? = null
  var completed = false
    private set
  val needsStartingDistance get() = baseline == null

  fun reset() { baseline = null; completed = false }

  fun update(width: Float): Boolean {
    if (!width.isFinite() || width <= 0f || completed) return completed
    val start = baseline
    if (start == null) {
      if (width <= 0.50f) baseline = width
      return false
    }
    if (width >= start * 1.20f) completed = true
    return completed
  }
}
