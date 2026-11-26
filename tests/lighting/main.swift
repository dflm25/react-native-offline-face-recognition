import Foundation

let size = 64
let mask = (0..<(size * size)).map { index in
  let x = index % size - size / 2
  let y = index / size - size / 2
  return x * x + y * y < 29 * 29
}
let even = mask.indices.map { 120 + (($0 % size * 11 + $0 / size * 7) % 29) - 14 }
let shadowed = mask.indices.map { even[$0] + ($0 % size - size / 2) * 2 }
var normalizedEven = even
var normalizedShadow = shadowed
FaceLighting.normalize(&normalizedEven, mask: mask, size: size)
FaceLighting.normalize(&normalizedShadow, mask: mask, size: size)
let inside = mask.indices.filter { mask[$0] }
let rawDifference = inside.reduce(0) { $0 + abs(even[$1] - shadowed[$1]) }
let normalizedDifference = inside.reduce(0) { $0 + abs(normalizedEven[$1] - normalizedShadow[$1]) }
precondition(normalizedDifference * 2 < rawDifference)
precondition(Set(inside.map { normalizedEven[$0] }).count > 10)
precondition(normalizedShadow[0] == 0)

let clippedSide = mask.indices.map { UInt8($0 % size < size / 2 ? 0 : 128) }
precondition(FaceExposure.acceptable(clippedSide, size: size))
precondition(!FaceExposure.acceptable(clippedSide, size: size, checkSides: true))
precondition(FaceExposure.acceptable(shadowed.map(UInt8.init), size: size, checkSides: true))
print("Lighting compensation tests passed")
