import Foundation

precondition(!FaceExposure.acceptable(Array(repeating: 0, count: 64), size: 8))
precondition(!FaceExposure.acceptable(Array(repeating: 255, count: 64), size: 8))
precondition(FaceExposure.acceptable(Array(repeating: 80, count: 64), size: 8))
precondition(!FaceExposure.acceptable([1], size: 8))
print("Exposure tests passed")
