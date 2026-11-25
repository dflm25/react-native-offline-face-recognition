import Foundation

/// Basic movement challenge: center with mouth closed, then open and close it.
final class LivenessTracker {
  enum Stage {
    case center
    case openMouth
    case closeMouth
    case passed
    case failed
  }

  struct State {
    let stage: Stage
    let prompt: String
    let progress: Float
    var passed: Bool { stage == .passed }
    var failed: Bool { stage == .failed }
  }

  private let timeoutMs: Int
  private var startedAt: TimeInterval = 0
  private var stage: Stage = .center
  private var centeredSince: TimeInterval = 0
  private var mouthBaseline: Float = 0.10
  private var sawOpenMouth = false

  init(timeoutMs: Int = 20_000) {
    self.timeoutMs = timeoutMs
  }

  func restart(now: TimeInterval) {
    startedAt = now
    stage = .center
    centeredSince = 0
    mouthBaseline = 0.10
    sawOpenMouth = false
  }

  func finishNow() -> State {
    stage = .passed
    return state()
  }

  /// - Parameters:
  ///   - yaw: face yaw in radians.
  ///   - mouthOpenRatio: inner-lip height divided by mouth width, or nil.
  func update(now: TimeInterval, yaw: Float?, mouthOpenRatio: Float?) -> State {
    if stage == .passed || stage == .failed {
      return state()
    }
    if startedAt == 0 {
      restart(now: now)
    }
    if (now - startedAt) * 1000.0 > Double(timeoutMs) {
      stage = .failed
      return state()
    }

    switch stage {
    case .center:
      let centered = yaw != nil && yaw!.isFinite && abs(yaw!) <= 0.16 &&
        mouthOpenRatio != nil && mouthOpenRatio!.isFinite && mouthOpenRatio! <= 0.13
      if centered {
        if centeredSince == 0 {
          centeredSince = now
        }
        if (now - centeredSince) * 1000.0 >= 550 {
          mouthBaseline = min(0.13, max(0.02, mouthOpenRatio!))
          stage = .openMouth
        }
      } else {
        centeredSince = 0
      }
    case .openMouth:
      let openThreshold = max(0.14, mouthBaseline + 0.06)
      if let mouthOpenRatio, mouthOpenRatio >= openThreshold {
        sawOpenMouth = true
        stage = .closeMouth
      }
    case .closeMouth:
      let closeThreshold = max(0.08, mouthBaseline + 0.025)
      if sawOpenMouth, let mouthOpenRatio, mouthOpenRatio <= closeThreshold {
        stage = .passed
      }
    case .passed, .failed:
      break
    }

    return state()
  }

  private func state() -> State {
    let progress: Float
    let prompt: String

    switch stage {
    case .center:
      progress = 0.10
      prompt = "Look straight and close your mouth"
    case .openMouth:
      progress = 0.45
      prompt = "Open your mouth"
    case .closeMouth:
      progress = 0.75
      prompt = "Now close your mouth"
    case .passed:
      progress = 1.0
      prompt = "Liveness confirmed"
    case .failed:
      progress = 1.0
      prompt = "Liveness check failed"
    }
    return State(stage: stage, prompt: prompt, progress: progress)
  }
}
