import Foundation

/// Checks for clipped pixels in the central face region and, optionally, in each half.
enum FaceExposure {
  static func acceptable(_ pixels: [UInt8], size: Int, checkSides: Bool = false) -> Bool {
    guard size >= 4, pixels.count == size * size else { return false }
    let start = size / 4
    let middle = size / 2
    let end = size * 3 / 4
    guard regionIsUsable(pixels, size: size, columns: start..<end, limit: 85) else { return false }
    return !checkSides || (
      regionIsUsable(pixels, size: size, columns: start..<middle, limit: 60) &&
      regionIsUsable(pixels, size: size, columns: middle..<end, limit: 60)
    )
  }

  private static func regionIsUsable(_ pixels: [UInt8], size: Int, columns: Range<Int>, limit: Int) -> Bool {
    var dark = 0, bright = 0, count = 0
    for y in (size / 4)..<(size * 3 / 4) {
      for x in columns {
        let value = pixels[y * size + x]
        if value <= 10 { dark += 1 }
        if value >= 245 { bright += 1 }
        count += 1
      }
    }
    return dark * 100 < count * limit && bright * 100 < count * limit
  }
}
