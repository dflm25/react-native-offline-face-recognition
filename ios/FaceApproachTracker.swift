import Foundation

/// Tracks a relative change in detected face width, not physical depth or liveness.
final class FaceApproachTracker {
  private var baseline: CGFloat?
  private(set) var completed = false

  func reset() { baseline = nil; completed = false }

  func update(width: CGFloat) -> Bool {
    guard width.isFinite, width > 0, !completed else { return completed }
    if baseline == nil {
      guard width <= 0.50 else { return false }
      baseline = width
      return false
    }
    if let baseline, width >= baseline * 1.20 { completed = true }
    return completed
  }

  var needsStartingDistance: Bool { baseline == nil }
}
