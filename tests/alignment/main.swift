import CoreGraphics

func assertPoint(_ point: CGPoint, x: CGFloat, y: CGFloat) {
  precondition(abs(point.x - x) < 0.001 && abs(point.y - y) < 0.001)
}

let firstEye = CGPoint(x: 100, y: 120)
let secondEye = CGPoint(x: 140, y: 130)
let transform = FaceEyeAlignment.transform(firstEye: secondEye, secondEye: firstEye)!
assertPoint(firstEye.applying(transform), x: 20, y: 40)
assertPoint(secondEye.applying(transform), x: 44, y: 40)
assertPoint(CGPoint(x: 125, y: 105).applying(transform), x: 32, y: 28)

let closerFirstEye = CGPoint(x: 70, y: 90)
let closerSecondEye = CGPoint(x: 150, y: 110)
let closerTransform = FaceEyeAlignment.transform(firstEye: closerFirstEye, secondEye: closerSecondEye)!
assertPoint(CGPoint(x: 120, y: 60).applying(closerTransform), x: 32, y: 28)

precondition(FaceEyeAlignment.transform(firstEye: firstEye, secondEye: firstEye) == nil)
precondition(FaceEyeAlignment.transform(firstEye: .zero, secondEye: CGPoint(x: 15, y: 20)) == nil)
print("Eye alignment tests passed")
