package com.offlineface

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Active liveness state machine.
 *
 * Challenge: look straight -> turn head to one side -> return -> turn to the
 * other side -> open the mouth -> close it. A static photo, a still screen image
 * and a simple looped clip cannot complete the coherent sequence within the
 * timing window. The mouth action is easier to detect than a fast blink or a
 * small nod, especially with glasses or in low light.
 */
internal class LivenessTracker(
  private val timeoutMs: Long = 20_000L,
) {
  enum class Stage {
    CENTER,
    TURN_ONE,
    RETURN_CENTER,
    TURN_TWO,
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
  private var firstTurnDirection = 0
  private var mouthBaseline = 0.10f
  private var lastMouthRatio: Float? = null
  private var sawOpenMouth = false

  fun restart(now: Long) {
    startedAt = now
    stage = Stage.CENTER
    centeredSince = 0L
    firstTurnDirection = 0
    mouthBaseline = 0.10f
    lastMouthRatio = null
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
    if (mouth != null) {
      lastMouthRatio = mouth
    }

    when (stage) {
      Stage.CENTER -> {
        val centered = yaw != null && abs(yaw) <= 9f
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
          mouthBaseline = (lastMouthRatio ?: 0.08f).coerceIn(0.02f, 0.30f)
          stage = Stage.OPEN_MOUTH
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
        Stage.TURN_ONE -> 0.30f
        Stage.RETURN_CENTER -> 0.48f
        Stage.TURN_TWO -> 0.66f
        Stage.OPEN_MOUTH -> 0.82f
        Stage.CLOSE_MOUTH -> 0.92f
        Stage.PASSED -> 1f
        Stage.FAILED -> 1f
      }
    val prompt =
      when (stage) {
        Stage.CENTER -> "Look straight ahead"
        Stage.TURN_ONE -> "Slowly turn your head to one side"
        Stage.RETURN_CENTER -> "Look straight again"
        Stage.TURN_TWO -> "Now turn to the other side"
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
