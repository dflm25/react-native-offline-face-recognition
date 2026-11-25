import CoreGraphics

enum FaceEyeAlignment {
  /// Core Image uses an upward Y axis. The target eye line is 24 px from the top.
  static func transform(firstEye: CGPoint, secondEye: CGPoint) -> CGAffineTransform? {
    guard firstEye.x.isFinite, firstEye.y.isFinite, secondEye.x.isFinite, secondEye.y.isFinite
    else { return nil }
    let (left, right) = firstEye.x < secondEye.x ? (firstEye, secondEye) : (secondEye, firstEye)
    let dx = right.x - left.x
    let dy = right.y - left.y
    let distanceSquared = dx * dx + dy * dy
    guard distanceSquared >= 12 * 12, abs(dy) <= dx * 0.57735 else { return nil }

    let a = 24 * dx / distanceSquared
    let b = 24 * dy / distanceSquared
    return CGAffineTransform(
      a: a, b: -b, c: b, d: a,
      tx: 20 - (a * left.x + b * left.y),
      ty: 40 - (-b * left.x + a * left.y)
    )
  }
}
