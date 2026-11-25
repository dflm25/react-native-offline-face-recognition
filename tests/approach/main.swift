import Foundation

let tracker = FaceApproachTracker()
precondition(!tracker.update(width: 0.6) && tracker.needsStartingDistance)
precondition(!tracker.update(width: 0.3))
precondition(!tracker.update(width: 0.34))
precondition(tracker.update(width: 0.37))
tracker.reset()
precondition(!tracker.completed && tracker.needsStartingDistance)
print("Approach tests passed")
