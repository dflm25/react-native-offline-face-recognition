import Foundation

enum FaceLighting {
  /// Removes gradual illumination changes with a masked local mean and contrast adjustment.
  static func normalize(_ gray: inout [Int], mask: [Bool], size: Int) {
    let radius = max(1, size / 10)
    let stride = size + 1
    var sums = [Int](repeating: 0, count: stride * stride)
    var squaredSums = sums
    var counts = sums
    for y in 0..<size {
      var rowSum = 0
      var rowSquaredSum = 0
      var rowCount = 0
      for x in 0..<size {
        let index = y * size + x
        if mask[index] {
          let value = gray[index]
          rowSum += value
          rowSquaredSum += value * value
          rowCount += 1
        }
        let integralIndex = (y + 1) * stride + x + 1
        sums[integralIndex] = sums[integralIndex - stride] + rowSum
        squaredSums[integralIndex] = squaredSums[integralIndex - stride] + rowSquaredSum
        counts[integralIndex] = counts[integralIndex - stride] + rowCount
      }
    }

    for y in 0..<size {
      let top = max(0, y - radius) * stride
      let bottom = min(size, y + radius + 1) * stride
      for x in 0..<size {
        let index = y * size + x
        guard mask[index] else {
          gray[index] = 0
          continue
        }
        let left = max(0, x - radius)
        let right = min(size, x + radius + 1)
        let count = Double(windowSum(counts, top, bottom, left, right))
        let mean = Double(windowSum(sums, top, bottom, left, right)) / count
        let variance = Double(windowSum(squaredSums, top, bottom, left, right)) / count - mean * mean
        // Floor the variance so that nearly flat regions do not amplify sensor noise.
        let contrast = sqrt(max(144, variance))
        let value = 128 + 32 * (Double(gray[index]) - mean) / contrast
        gray[index] = Int(min(255, max(0, value)).rounded())
      }
    }
  }

  private static func windowSum(_ integral: [Int], _ top: Int, _ bottom: Int, _ left: Int, _ right: Int) -> Int {
    integral[bottom + right] - integral[top + right] - integral[bottom + left] + integral[top + left]
  }
}
