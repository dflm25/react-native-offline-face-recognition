import Foundation

let tracker = LivenessTracker(timeoutMs: 10_000)
_ = tracker.update(now: 1, yaw: 0, mouthOpenRatio: 0.08)
precondition(tracker.update(now: 1.6, yaw: 0, mouthOpenRatio: 0.08).stage == .openMouth)
precondition(tracker.update(now: 1.7, yaw: 0, mouthOpenRatio: 0.08).stage == .openMouth)
precondition(tracker.update(now: 1.8, yaw: 0, mouthOpenRatio: 0.2).stage == .closeMouth)
precondition(tracker.update(now: 1.9, yaw: 0, mouthOpenRatio: 0.08).passed)
let timed = LivenessTracker(timeoutMs: 1000)
_ = timed.update(now: 1, yaw: 0, mouthOpenRatio: 0.2)
precondition(timed.update(now: 1.6, yaw: 0, mouthOpenRatio: 0.2).stage == .center)
precondition(timed.update(now: 2.01, yaw: 0, mouthOpenRatio: nil).failed)
print("Movement challenge tests passed")
