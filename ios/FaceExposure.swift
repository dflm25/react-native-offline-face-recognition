import Foundation

/// Reject only frames whose central region is overwhelmingly clipped. This is
/// a capture heuristic, not a measure of skin tone or recognition accuracy.
enum FaceExposure {
  static func acceptable(_ pixels: [UInt8], size: Int) -> Bool {
    guard size >= 4, pixels.count == size * size else { return false }
    var dark = 0, bright = 0, count = 0
    for y in (size / 4)..<(size * 3 / 4) {
      for x in (size / 4)..<(size * 3 / 4) {
        let value = pixels[y * size + x]
        if value <= 10 { dark += 1 }
        if value >= 245 { bright += 1 }
        count += 1
      }
    }
    return dark * 100 < count * 85 && bright * 100 < count * 85
  }
}
