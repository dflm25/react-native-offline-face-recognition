import Foundation

enum FaceApiMatcher {
  /// Reference starting point from face-api.js. This threshold needs device measurements.
  static let maximumDistance = 0.60

  static func meanDistance(template: [[Float]], probes: [[Float]]) -> Double? {
    guard !template.isEmpty, !probes.isEmpty else { return nil }
    var total = 0.0
    for probe in probes {
      for sample in template {
        guard sample.count == 128, probe.count == sample.count else { return nil }
        let squared = zip(sample, probe).reduce(0.0) { total, pair in
          let difference = Double(pair.0) - Double(pair.1)
          return total + difference * difference
        }
        total += sqrt(squared)
      }
    }
    let mean = total / Double(template.count * probes.count)
    return mean.isFinite ? mean : nil
  }
}
