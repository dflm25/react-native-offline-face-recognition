import Foundation

/// Block-wise histogram intersection matcher, mirroring the Android matcher.
enum FaceMatcher {
  static let descriptorSize = 1_520
  private static let lbpGrid = 4
  private static let lbpBins = 59
  private static let hogGrid = 8
  private static let hogBins = 9
  private static let lbpBlocks = lbpGrid * lbpGrid
  private static let hogBlocks = hogGrid * hogGrid
  private static let lbpWeight = 0.65
  private static let hogWeight = 0.35
  private static let topMatches = 3

  static func score(template: [[Float]], probes: [[Float]]) -> Double {
    guard !template.isEmpty, !probes.isEmpty else {
      return 0.0
    }

    var bestPerProbe: [Double] = []
    for probe in probes {
      var best = 0.0
      for enrolled in template {
        best = max(best, similarity(probe, enrolled))
      }
      bestPerProbe.append(best)
    }

    let top = bestPerProbe.sorted(by: >).prefix(topMatches)
    guard !top.isEmpty else {
      return 0.0
    }
    let value = top.reduce(0.0, +) / Double(top.count)
    return (value * 10_000.0).rounded() / 10_000.0
  }

  static func similarity(_ a: [Float], _ b: [Float]) -> Double {
    guard a.count == descriptorSize, b.count == a.count else {
      return 0.0
    }

    var lbp = 0.0
    for block in 0..<lbpBlocks {
      lbp += histogramIntersection(a, b, block * lbpBins, (block + 1) * lbpBins)
    }
    lbp /= Double(lbpBlocks)

    let hogOffset = lbpBlocks * lbpBins
    var hog = 0.0
    for block in 0..<hogBlocks {
      let start = hogOffset + block * hogBins
      hog += histogramIntersection(a, b, start, start + hogBins)
    }
    hog /= Double(hogBlocks)

    return min(1.0, max(0.0, lbpWeight * lbp + hogWeight * hog))
  }

  private static func histogramIntersection(
    _ a: [Float],
    _ b: [Float],
    _ start: Int,
    _ end: Int
  ) -> Double {
    var sumA = 0.0
    var sumB = 0.0
    for index in start..<end {
      sumA += Double(a[index])
      sumB += Double(b[index])
    }
    if sumA <= 1e-9, sumB <= 1e-9 {
      return 1.0
    }
    if sumA <= 1e-9 || sumB <= 1e-9 {
      return 0.0
    }

    var intersection = 0.0
    for index in start..<end {
      intersection += Double(min(a[index], b[index]))
    }
    return max(0.0, intersection)
  }
}
