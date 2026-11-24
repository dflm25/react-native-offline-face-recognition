package com.offlineface

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Active liveness state machine.
 *
 * Challenge: look straight -> turn head to one side -> return -> turn to the
 * other side -> briefly close and open the eyes. A static photo, a still screen
 * image and a simple looped clip cannot complete a coherent two-sided turn plus
 * an eye close/open within the timing window. This is a deterrent, not a certified PAD implementation.
 */
internal class LivenessTracker(
  private val timeoutMs: Long = 20_000L,
) {
  enum class Stage {
    CENTER,
    TURN_ONE,
    RETURN_CENTER,
    TURN_TWO,
    BLINK,
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
  private var firstTurnDirection = 0
  private var sawClosedEyes = false
  private var blinkOpenBaseline = 0.35f
  private var lastEyeOpen: Float? = null
  private var lastPitch: Float? = null
  private var blinkPitchBaseline = 0f
  private var sawNod = false
  private var missingEyeFrames = 0

  fun restart(now: Long) {
    startedAt = now
    stage = Stage.CENTER
    centeredSince = 0L
    firstTurnDirection = 0
    sawClosedEyes = false
    blinkOpenBaseline = 0.35f
    lastEyeOpen = null
    lastPitch = null
    blinkPitchBaseline = 0f
    sawNod = false
    missingEyeFrames = 0
  }

  fun finishNow(): State {
    stage = Stage.PASSED
    return state()
  }

  /**
   * @param yawDegrees Face yaw in degrees, or null when not available.
   * @param eyeOpenAverage Average ML Kit eye-open probability, or null.
   */
  fun update(
    now: Long,
    yawDegrees: Float?,
    pitchDegrees: Float?,
    eyeOpenAverage: Float?,
  ): State {
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
    val pitch = pitchDegrees?.takeIf { it.isFinite() }
    val eyesOpen = eyeOpenAverage?.takeIf { it.isFinite() }
    if (eyesOpen != null) {
      lastEyeOpen = eyesOpen
    }
    if (pitch != null) {
      lastPitch = pitch
    }

    when (stage) {
      Stage.CENTER -> {
        val centered = yaw != null && abs(yaw) <= 9f && (eyesOpen == null || eyesOpen >= 0.45f)
        if (centered) {
          if (centeredSince == 0L) centeredSince = now
          if (now - centeredSince >= 550L) {
            stage = Stage.TURN_ONE
          }
        } else {
          centeredSince = 0L
        }
      }
      Stage.TURN_ONE -> {
        if (yaw != null && abs(yaw) >= 18f) {
          firstTurnDirection = yaw.sign.toInt()
          stage = Stage.RETURN_CENTER
        }
      }
      Stage.RETURN_CENTER -> {
        if (yaw != null && abs(yaw) <= 9f) {
          stage = Stage.TURN_TWO
        }
      }
      Stage.TURN_TWO -> {
        if (yaw != null && abs(yaw) >= 18f && yaw.sign.toInt() == -firstTurnDirection) {
          blinkOpenBaseline = max(0.20f, lastEyeOpen ?: 0.35f)
          blinkPitchBaseline = lastPitch ?: 0f
          stage = Stage.BLINK
        }
      }
      Stage.BLINK -> {
        // Nodding is an alternative that is much more reliable than a fast
        // blink, especially with glasses or in low light.
        if (pitch != null) {
          val pitchDelta = abs(pitch - blinkPitchBaseline)
          if (pitchDelta >= 14f) {
            sawNod = true
          } else if (sawNod && pitchDelta <= 5f) {
            stage = Stage.PASSED
          }
        }

        if (stage != Stage.PASSED) {
          val closedThreshold = max(0.18f, blinkOpenBaseline * 0.72f)
          val openThreshold = max(0.22f, blinkOpenBaseline * 0.88f)
          if (eyesOpen != null) {
            missingEyeFrames = 0
            if (eyesOpen <= closedThreshold) {
              sawClosedEyes = true
            } else if (sawClosedEyes && eyesOpen >= openThreshold) {
              stage = Stage.PASSED
            }
          } else {
            // Vision/ML Kit can omit eye landmarks while the eyelids are closed.
            missingEyeFrames++
            if (missingEyeFrames >= 1) {
              sawClosedEyes = true
            }
          }
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
        Stage.TURN_ONE -> 0.30f
        Stage.RETURN_CENTER -> 0.48f
        Stage.TURN_TWO -> 0.66f
        Stage.BLINK -> 0.85f
        Stage.PASSED -> 1f
        Stage.FAILED -> 1f
      }
    val prompt =
      when (stage) {
        Stage.CENTER -> "Look straight ahead"
        Stage.TURN_ONE -> "Slowly turn your head to one side"
        Stage.RETURN_CENTER -> "Look straight again"
        Stage.TURN_TWO -> "Now turn to the other side"
        Stage.BLINK -> "Blink once or nod your head"
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
