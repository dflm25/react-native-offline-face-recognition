package com.offlineface

import kotlin.math.abs
import kotlin.math.max

/**
 * Active liveness state machine.
 *
 * Basic movement challenge: look straight with the mouth closed, then open and close it.
 * This is not certified presentation-attack detection.
 */
internal class LivenessTracker(
  private val timeoutMs: Long = 20_000L,
) {
  enum class Stage {
    CENTER,
    OPEN_MOUTH,
    CLOSE_MOUTH,
    PASSED,
    FAILED,
  }

  data class State(
    val stage: Stage,
    val prompt: String,
    val progress: Float,
    val passed: Boolean,
    val failed: Boolean,
  )

  private var startedAt = 0L
  private var stage = Stage.CENTER
  private var centeredSince = 0L
  private var mouthBaseline = 0.10f
  private var sawOpenMouth = false

  fun restart(now: Long) {
    startedAt = now
    stage = Stage.CENTER
    centeredSince = 0L
    mouthBaseline = 0.10f
    sawOpenMouth = false
  }

  fun finishNow(): State {
    stage = Stage.PASSED
    return state()
  }

  /**
   * @param yawDegrees Face yaw in degrees, or null when not available.
   * @param mouthOpenRatio Inner-lip height divided by mouth width, or null.
   */
  fun update(now: Long, yawDegrees: Float?, mouthOpenRatio: Float?): State {
    if (stage == Stage.PASSED || stage == Stage.FAILED) {
      return state()
    }
    if (startedAt == 0L) {
      restart(now)
    }
    if (now - startedAt > timeoutMs) {
      stage = Stage.FAILED
      return state()
    }

    val yaw = yawDegrees?.takeIf { it.isFinite() }
    val mouth = mouthOpenRatio?.takeIf { it.isFinite() }
    when (stage) {
      Stage.CENTER -> {
        val centered = yaw != null && abs(yaw) <= 9f && mouth != null && mouth <= 0.13f
        if (centered) {
          if (centeredSince == 0L) centeredSince = now
          if (now - centeredSince >= 550L) {
            mouthBaseline = mouth!!.coerceIn(0.02f, 0.13f)
            stage = Stage.OPEN_MOUTH
          }
        } else {
          centeredSince = 0L
        }
      }
      Stage.OPEN_MOUTH -> {
        val openThreshold = max(0.14f, mouthBaseline + 0.06f)
        if (mouth != null && mouth >= openThreshold) {
          sawOpenMouth = true
          stage = Stage.CLOSE_MOUTH
        }
      }
      Stage.CLOSE_MOUTH -> {
        val closeThreshold = max(0.08f, mouthBaseline + 0.025f)
        if (sawOpenMouth && mouth != null && mouth <= closeThreshold) {
          stage = Stage.PASSED
        }
      }
      Stage.PASSED, Stage.FAILED -> Unit
    }

    return state()
  }

  private fun state(): State {
    val progress =
      when (stage) {
        Stage.CENTER -> 0.10f
        Stage.OPEN_MOUTH -> 0.45f
        Stage.CLOSE_MOUTH -> 0.75f
        Stage.PASSED -> 1f
        Stage.FAILED -> 1f
      }
    val prompt =
      when (stage) {
        Stage.CENTER -> "Look straight and close your mouth"
        Stage.OPEN_MOUTH -> "Open your mouth"
        Stage.CLOSE_MOUTH -> "Now close your mouth"
        Stage.PASSED -> "Liveness confirmed"
        Stage.FAILED -> "Liveness check failed"
      }
    return State(
      stage = stage,
      prompt = prompt,
      progress = progress,
      passed = stage == Stage.PASSED,
      failed = stage == Stage.FAILED,
    )
  }
}
