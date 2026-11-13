import Foundation

/// Same active challenge as Android: center -> turn -> center -> other turn -> close/open eyes.
final class LivenessTracker {
  enum Stage {
    case center
    case turnOne
    case returnCenter
    case turnTwo
    case blink
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
  private var firstTurnDirection = 0
  private var sawClosedEyes = false
  private var blinkOpenBaseline: Float = 0.35
  private var lastEyeOpen: Float?
  private var missingEyeFrames = 0

  init(timeoutMs: Int = 20_000) {
    self.timeoutMs = timeoutMs
  }

  func restart(now: TimeInterval) {
    startedAt = now
    stage = .center
    centeredSince = 0
    firstTurnDirection = 0
    sawClosedEyes = false
    blinkOpenBaseline = 0.35
    lastEyeOpen = nil
    missingEyeFrames = 0
  }

  func finishNow() -> State {
    stage = .passed
    return state()
  }

  /// - Parameters:
  ///   - yaw: face yaw in radians.
  ///   - eyeOpenRatio: approximate eye height/width, or nil when unavailable.
  func update(now: TimeInterval, yaw: Float?, eyeOpenRatio: Float?) -> State {
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

    if let eyeOpenRatio {
      lastEyeOpen = eyeOpenRatio
    }

    switch stage {
    case .center:
      let centered = (yaw != nil && abs(yaw!) <= 0.16) && (eyeOpenRatio == nil || eyeOpenRatio! >= 0.18)
      if centered {
        if centeredSince == 0 {
          centeredSince = now
        }
        if (now - centeredSince) * 1000.0 >= 550 {
          stage = .turnOne
        }
      } else {
        centeredSince = 0
      }
    case .turnOne:
      if let yaw, abs(yaw) >= 0.32 {
        firstTurnDirection = yaw >= 0 ? 1 : -1
        stage = .returnCenter
      }
    case .returnCenter:
      if let yaw, abs(yaw) <= 0.16 {
        stage = .turnTwo
      }
    case .turnTwo:
      if let yaw, abs(yaw) >= 0.32 {
        let direction = yaw >= 0 ? 1 : -1
        if direction == -firstTurnDirection {
          blinkOpenBaseline = max(0.20, lastEyeOpen ?? 0.35)
          stage = .blink
        }
      }
    case .blink:
      let closedThreshold = max(0.14, blinkOpenBaseline * 0.60)
      let openThreshold = max(0.19, blinkOpenBaseline * 0.80)
      if let eyeOpenRatio {
        missingEyeFrames = 0
        if eyeOpenRatio <= closedThreshold {
          sawClosedEyes = true
        } else if sawClosedEyes && eyeOpenRatio >= openThreshold {
          stage = .passed
        }
      } else {
        // Vision can omit eye landmarks while the eyelids are closed.
        missingEyeFrames += 1
        if missingEyeFrames >= 2 {
          sawClosedEyes = true
        }
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
      prompt = "Look straight ahead"
    case .turnOne:
      progress = 0.30
      prompt = "Slowly turn your head to one side"
    case .returnCenter:
      progress = 0.48
      prompt = "Look straight again"
    case .turnTwo:
      progress = 0.66
      prompt = "Now turn to the other side"
    case .blink:
      progress = 0.85
      prompt = "Close your eyes briefly, then open them"
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
